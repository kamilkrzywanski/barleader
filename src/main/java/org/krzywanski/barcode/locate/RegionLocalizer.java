package org.krzywanski.barcode.locate;

import org.krzywanski.barcode.BarcodeHit;
import org.krzywanski.barcode.ImagePoint;
import org.krzywanski.barcode.Symbology;
import org.krzywanski.barcode.image.Geometry;
import org.krzywanski.barcode.image.GrayU8;
import org.krzywanski.barcode.image.PixelMap;
import org.krzywanski.barcode.image.Transformed;
import org.krzywanski.barcode.preprocess.Thresholds;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Quadrilateral candidates for symbols whose outline is not one clean polygon.
 * A Data Matrix clock track is dotted, so six dilations merge the dark modules into one blob.
 * The four diagonal extremes of that blob are deskewed as {@code blob-quad}.
 * QR, Micro QR, Aztec, and Data Matrix finders run on the scan ladder, not here.
 */
public final class RegionLocalizer {
    private static final int MAX_SIDE = 1800;
    private static final int MAX_QUADS = 5;
    private static final int MIN_CONTOUR = 80;

    public interface Sink {
        boolean open();

        void decoded(BarcodeHit hit);

        void candidate(GrayU8 luma, PixelMap toSource, List<ImagePoint> corners, String attempt, Set<Symbology> formats);
    }

    public void localize(
            GrayU8 gray,
            PixelMap toSource,
            Set<Symbology> wanted,
            boolean fiducials,
            boolean quadrilaterals,
            Sink sink
    ) {
        GrayU8 work = gray;
        PixelMap workToSource = toSource;
        int maxSide = Math.max(gray.width, gray.height);
        if (maxSide > MAX_SIDE) {
            double scale = MAX_SIDE / (double) maxSide;
            if (Geometry.withinPixelBudget(gray.width, gray.height, scale)) {
                Transformed smaller = Geometry.scale(gray, scale);
                work = smaller.image();
                workToSource = smaller.toSource().before(toSource);
            }
        }
        if (sink.open() && quadrilaterals) {
            detectQuads(work, workToSource, wanted, sink);
        }
    }

    private static void detectQuads(GrayU8 gray, PixelMap toSource, Set<Symbology> wanted, Sink sink) {
        EnumSet<Symbology> formats = EnumSet.noneOf(Symbology.class);
        if (wanted.contains(Symbology.DATA_MATRIX)) {
            formats.add(Symbology.DATA_MATRIX);
        }
        if (wanted.contains(Symbology.QR_CODE)) {
            formats.add(Symbology.QR_CODE);
        }
        if (wanted.contains(Symbology.AZTEC)) {
            formats.add(Symbology.AZTEC);
        }
        if (formats.isEmpty()) {
            return;
        }
        GrayU8 binary = Thresholds.sauvola(gray).get(0);
        detectClosedBlobs(gray, binary, toSource, wanted, formats, sink);
    }

    /**
     * Dilating the dark modules merges a dotted clock track. The four extreme points of that blob
     * are a perspective square.
     */
    private static void detectClosedBlobs(
            GrayU8 gray,
            GrayU8 binary,
            PixelMap toSource,
            Set<Symbology> wanted,
            EnumSet<Symbology> formats,
            Sink sink
    ) {
        if (!sink.open() || formats.isEmpty()) {
            return;
        }
        GrayU8 dilated = binary;
        for (int i = 0; i < 6; i++) {
            dilated = Thresholds.dilate8(dilated);
        }
        List<ImagePoint[]> blobs = BlobContours.external(dilated, MIN_CONTOUR);
        int kept = 0;
        double imageArea = gray.width * (double) gray.height;
        for (ImagePoint[] points : blobs) {
            if (!sink.open() || kept >= MAX_QUADS) {
                break;
            }
            ImagePoint[] quad = extremeQuad(points);
            if (quad == null || !convex(quad)) {
                continue;
            }
            double area = Math.abs(area(quad));
            if (area < 48.0 * 48.0 || area > imageArea * 0.9) {
                continue;
            }
            double aspect = aspect(quad);
            if (aspect < 0.2 || aspect > 5.0) {
                continue;
            }
            EnumSet<Symbology> forQuad = EnumSet.copyOf(formats);
            if (wanted.contains(Symbology.PDF_417) && (aspect > 1.6 || aspect < 1.0 / 1.6)) {
                forQuad.add(Symbology.PDF_417);
            }
            kept++;
            offerRectified(gray, quad, toSource, "blob-quad", forQuad, sink);
        }
    }

    /** Top-left, top-right, bottom-right, bottom-left by diagonal extremes. */
    private static ImagePoint[] extremeQuad(ImagePoint[] points) {
        ImagePoint topLeft = points[0];
        ImagePoint topRight = points[0];
        ImagePoint bottomRight = points[0];
        ImagePoint bottomLeft = points[0];
        for (int i = 1; i < points.length; i++) {
            ImagePoint point = points[i];
            if (point.x() + point.y() < topLeft.x() + topLeft.y()) {
                topLeft = point;
            }
            if (point.x() - point.y() > topRight.x() - topRight.y()) {
                topRight = point;
            }
            if (point.x() + point.y() > bottomRight.x() + bottomRight.y()) {
                bottomRight = point;
            }
            if (point.y() - point.x() > bottomLeft.y() - bottomLeft.x()) {
                bottomLeft = point;
            }
        }
        if (!separated(topLeft, topRight) || !separated(topRight, bottomRight)
                || !separated(bottomRight, bottomLeft) || !separated(bottomLeft, topLeft)
                || !separated(topLeft, bottomRight) || !separated(topRight, bottomLeft)) {
            return null;
        }
        return new ImagePoint[]{topLeft, topRight, bottomRight, bottomLeft};
    }

    private static boolean separated(ImagePoint a, ImagePoint b) {
        double dx = a.x() - b.x();
        double dy = a.y() - b.y();
        return dx * dx + dy * dy >= 12.0 * 12.0;
    }

    private static boolean convex(ImagePoint[] quad) {
        boolean positive = false;
        boolean negative = false;
        for (int i = 0; i < quad.length; i++) {
            ImagePoint a = quad[i];
            ImagePoint b = quad[(i + 1) % quad.length];
            ImagePoint c = quad[(i + 2) % quad.length];
            double cross = (b.x() - a.x()) * (c.y() - b.y()) - (b.y() - a.y()) * (c.x() - b.x());
            if (cross > 0.0) {
                positive = true;
            } else if (cross < 0.0) {
                negative = true;
            }
            if (positive && negative) {
                return false;
            }
        }
        return positive || negative;
    }

    private static double area(ImagePoint[] quad) {
        double sum = 0.0;
        for (int i = 0; i < quad.length; i++) {
            ImagePoint a = quad[i];
            ImagePoint b = quad[(i + 1) % quad.length];
            sum += a.x() * b.y() - b.x() * a.y();
        }
        return 0.5 * sum;
    }

    private static double aspect(ImagePoint[] quad) {
        double width = (distance(quad[0], quad[1]) + distance(quad[2], quad[3])) / 2.0;
        double height = (distance(quad[1], quad[2]) + distance(quad[3], quad[0])) / 2.0;
        if (height < 1e-6) {
            return 0.0;
        }
        return width / height;
    }

    private static double distance(ImagePoint a, ImagePoint b) {
        return Math.hypot(a.x() - b.x(), a.y() - b.y());
    }

    private static void offerRectified(
            GrayU8 gray,
            ImagePoint[] bounds,
            PixelMap toSource,
            String attempt,
            Set<Symbology> formats,
            Sink sink
    ) {
        Transformed rectified = Geometry.rectify(gray, bounds, toSource, 1.4);
        if (rectified == null) {
            return;
        }
        List<ImagePoint> corners = List.of(Geometry.cornersOf(bounds, toSource));
        sink.candidate(rectified.image(), rectified.toSource(), corners, attempt, formats);
    }
}
