/*
 * Copyright 2016 Nu-book Inc.
 * Copyright 2016 ZXing authors
 * Copyright 2026 Kamil Krzywański
 */
// SPDX-License-Identifier: Apache-2.0
package io.github.kamilkrzywanski.barleader.decode;

import io.github.kamilkrzywanski.barleader.BarcodeHit;
import io.github.kamilkrzywanski.barleader.ImagePoint;
import io.github.kamilkrzywanski.barleader.Symbology;
import io.github.kamilkrzywanski.barleader.image.GrayU8;

import java.util.ArrayList;
import java.util.List;

/**
 * Drops empty payloads and keeps one hit when the same payload sits on the same spot. A UPC-E
 * that is the left half of an EAN-13 or UPC-A also read is dropped, whichever came first.
 */
public final class HitCollector {
    private static final double MERGE_DISTANCE_SQ = 80.0 * 80.0;
    /** Rows between two scan lines are compared this many pixels apart. */
    private static final double BAR_STEP = 4.0;
    /** Each row along the way must correlate this well with the one before it. */
    private static final double BAR_CORRELATION = 0.6;

    private final List<BarcodeHit> hits = new ArrayList<>();
    /** The image the corners are in, or {@code null} when only the geometry is known. */
    private final GrayU8 image;

    public HitCollector() {
        this(null);
    }

    public HitCollector(GrayU8 image) {
        this.image = image;
    }

    public boolean add(BarcodeHit hit) {
        if (hit.text().isBlank()) {
            return false;
        }
        if (hits.stream().anyMatch(existing -> leftHalf(hit, existing))) {
            return false;
        }
        hits.removeIf(existing -> leftHalf(existing, hit));
        for (int i = 0; i < hits.size(); i++) {
            BarcodeHit existing = hits.get(i);
            if (existing.symbology() == hit.symbology()
                    && existing.text().equals(hit.text())
                    && (close(existing, hit) || sameBars(existing.corners(), hit.corners()))) {
                if (hit.confidence() > existing.confidence()) {
                    hits.set(i, hit);
                }
                return false;
            }
        }
        hits.add(hit);
        return true;
    }

    /**
     * The six L- and G-coded digits on the left of an EAN-13 are a UPC-E between its guards: a
     * crop that cuts the symbol in the middle reads {@code 10004924} from {@code 4000492512476}.
     * BarBeR's Muenster photos gave ten such texts in {@code findAll} once fast mode searched
     * band crops. The six digits of the UPC-E are then digits 2 to 7 of the EAN-13.
     */
    private static boolean leftHalf(BarcodeHit upcE, BarcodeHit full) {
        if (upcE.symbology() != Symbology.UPC_E || upcE.text().length() != 8) {
            return false;
        }
        String ean;
        if (full.symbology() == Symbology.EAN_13 && full.text().length() == 13) {
            ean = full.text();
        } else if (full.symbology() == Symbology.UPC_A && full.text().length() == 12) {
            ean = "0" + full.text();
        } else {
            return false;
        }
        return ean.regionMatches(1, upcE.text(), 1, 6);
    }

    public boolean isEmpty() {
        return hits.isEmpty();
    }

    public List<BarcodeHit> list() {
        return List.copyOf(hits);
    }

    private static boolean close(BarcodeHit left, BarcodeHit right) {
        ImagePoint a = center(left);
        ImagePoint b = center(right);
        if (a == null || b == null) {
            return true;
        }
        double dx = a.x() - b.x();
        double dy = a.y() - b.y();
        return dx * dx + dy * dy <= MERGE_DISTANCE_SQ
                || sameLinearSymbol(left.corners(), right.corners())
                || crossingLines(left.corners(), right.corners())
                || sameMatrixSymbol(left.corners(), right.corners());
    }

    /**
     * One 2D symbol found twice, as {@code Barcode::operator==} in zxing-cpp has it for matrix codes:
     * the centre of one hit lies inside the other. A partial detection, such as the left row
     * indicator of a PDF417 that the erasures still correct, sits on the outline rather than in it,
     * so the outline here is the box around the corners grown by a twentieth of its larger side.
     */
    private static boolean sameMatrixSymbol(List<ImagePoint> left, List<ImagePoint> right) {
        if (left.size() < 3 && right.size() < 3) {
            return false;
        }
        return insideGrownBox(center(left), right) || insideGrownBox(center(right), left);
    }

    private static boolean insideGrownBox(ImagePoint point, List<ImagePoint> corners) {
        if (corners.size() < 3) {
            return false;
        }
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (ImagePoint corner : corners) {
            minX = Math.min(minX, corner.x());
            minY = Math.min(minY, corner.y());
            maxX = Math.max(maxX, corner.x());
            maxY = Math.max(maxY, corner.y());
        }
        double margin = Math.max(maxX - minX, maxY - minY) / 20;
        return point.x() >= minX - margin && point.x() <= maxX + margin && point.y() >= minY - margin && point.y() <= maxY + margin;
    }

    /**
     * Two scan lines of one linear symbol, as {@code Barcode::operator==} in zxing-cpp has it: one
     * starts less than half the length of the other away from it, and their lengths differ by less
     * than a fifth. A tall symbol read on rows far apart is then still one hit.
     */
    private static boolean sameLinearSymbol(List<ImagePoint> left, List<ImagePoint> right) {
        if (left.size() != 2 || right.size() != 2) {
            return false;
        }
        double length = maxAbs(left.get(0), left.get(1));
        double start = Math.min(maxAbs(right.get(0), left.get(0)), maxAbs(right.get(1), left.get(0)));
        return start < length / 2 && Math.abs(length - maxAbs(right.get(0), right.get(1))) < length / 5;
    }

    /**
     * Two scan lines that cross, or nearly touch, read the same bars: a band crop reads a level EAN
     * along a slant through it, and its line crosses the row the ladder read, as on 13 Arte-Lab
     * photos in {@code findAll}. "Nearly" is a twentieth of the shorter line, less than the quiet
     * zones between two symbols of the same text placed end to end.
     */
    private static boolean crossingLines(List<ImagePoint> left, List<ImagePoint> right) {
        if (left.size() != 2 || right.size() != 2) {
            return false;
        }
        ImagePoint p0 = left.get(0);
        ImagePoint p1 = left.get(1);
        ImagePoint q0 = right.get(0);
        ImagePoint q1 = right.get(1);
        double shorter = Math.min(Math.hypot(p1.x() - p0.x(), p1.y() - p0.y()), Math.hypot(q1.x() - q0.x(), q1.y() - q0.y()));
        if (side(p0, p1, q0) * side(p0, p1, q1) < 0 && side(q0, q1, p0) * side(q0, q1, p1) < 0) {
            return true;
        }
        double gap = Math.min(Math.min(toSegment(q0, p0, p1), toSegment(q1, p0, p1)),
                Math.min(toSegment(p0, q0, q1), toSegment(p1, q0, q1)));
        return gap <= shorter / 20;
    }

    /** Which side of the line a–b the point p is on: the sign of the cross product. */
    private static double side(ImagePoint a, ImagePoint b, ImagePoint p) {
        return (b.x() - a.x()) * (p.y() - a.y()) - (b.y() - a.y()) * (p.x() - a.x());
    }

    private static double toSegment(ImagePoint p, ImagePoint a, ImagePoint b) {
        double dx = b.x() - a.x();
        double dy = b.y() - a.y();
        double lengthSq = dx * dx + dy * dy;
        double t = lengthSq == 0 ? 0 : Math.max(0, Math.min(1, ((p.x() - a.x()) * dx + (p.y() - a.y()) * dy) / lengthSq));
        return Math.hypot(p.x() - a.x() - t * dx, p.y() - a.y() - t * dy);
    }

    /**
     * Two scan lines of one tall linear symbol too far apart for {@link #sameLinearSymbol}: the
     * ladder reads a Code 93 at its bottom row and the band search at its top, 173 px apart on a
     * 272 px symbol, where two symbols of the same text stacked that close are also possible. The
     * lines must run within 35° of each other with about the same span, and the bars must run unbroken from one to
     * the other: every row between them, {@link #BAR_STEP} px apart, correlates with the row before.
     * The gap between two stacked symbols, blank or with their text, breaks that.
     */
    private boolean sameBars(List<ImagePoint> left, List<ImagePoint> right) {
        if (image == null || left.size() != 2 || right.size() != 2) {
            return false;
        }
        ImagePoint a0 = left.get(0);
        ImagePoint a1 = left.get(1);
        boolean swap = Math.hypot(right.get(1).x() - a0.x(), right.get(1).y() - a0.y())
                < Math.hypot(right.get(0).x() - a0.x(), right.get(0).y() - a0.y());
        ImagePoint b0 = right.get(swap ? 1 : 0);
        ImagePoint b1 = right.get(swap ? 0 : 1);
        double ax = a1.x() - a0.x();
        double ay = a1.y() - a0.y();
        double length = Math.hypot(ax, ay);
        double bx = b1.x() - b0.x();
        double by = b1.y() - b0.y();
        if (length < 8 || Math.abs(length - Math.hypot(bx, by)) > length / 5
                || (ax * bx + ay * by) / (length * Math.hypot(bx, by)) < 0.82) {
            return false;
        }
        // the start of one line must lie across from the start of the other, not shifted along it
        double along = ((b0.x() - a0.x()) * ax + (b0.y() - a0.y()) * ay) / length;
        if (Math.abs(along) > length / 5) {
            return false;
        }
        double gap = Math.max(Math.hypot(b0.x() - a0.x(), b0.y() - a0.y()), Math.hypot(b1.x() - a1.x(), b1.y() - a1.y()));
        int steps = Math.max(1, (int) Math.ceil(gap / BAR_STEP));
        int samples = (int) Math.ceil(length);
        double[] previous = row(a0, a1, b0, b1, 0.0, samples);
        for (int s = 1; s <= steps; s++) {
            double[] next = row(a0, a1, b0, b1, (double) s / steps, samples);
            if (correlation(previous, next) < BAR_CORRELATION) {
                return false;
            }
            previous = next;
        }
        return true;
    }

    /** Gray values along the line a fraction {@code t} of the way from a0–a1 to b0–b1. */
    private double[] row(ImagePoint a0, ImagePoint a1, ImagePoint b0, ImagePoint b1, double t, int samples) {
        double x0 = a0.x() + t * (b0.x() - a0.x());
        double y0 = a0.y() + t * (b0.y() - a0.y());
        double x1 = a1.x() + t * (b1.x() - a1.x());
        double y1 = a1.y() + t * (b1.y() - a1.y());
        double[] values = new double[samples];
        for (int i = 0; i < samples; i++) {
            double f = samples == 1 ? 0.0 : (double) i / (samples - 1);
            int x = Math.min(image.width - 1, Math.max(0, (int) Math.round(x0 + f * (x1 - x0))));
            int y = Math.min(image.height - 1, Math.max(0, (int) Math.round(y0 + f * (y1 - y0))));
            values[i] = image.get(x, y);
        }
        return values;
    }

    /** Pearson correlation; a flat row, such as a blank gap, correlates with nothing. */
    private static double correlation(double[] p, double[] q) {
        double mp = 0.0;
        double mq = 0.0;
        for (int i = 0; i < p.length; i++) {
            mp += p[i];
            mq += q[i];
        }
        mp /= p.length;
        mq /= q.length;
        double pq = 0.0;
        double pp = 0.0;
        double qq = 0.0;
        for (int i = 0; i < p.length; i++) {
            pq += (p[i] - mp) * (q[i] - mq);
            pp += (p[i] - mp) * (p[i] - mp);
            qq += (q[i] - mq) * (q[i] - mq);
        }
        return pp < 1e-9 || qq < 1e-9 ? 0.0 : pq / Math.sqrt(pp * qq);
    }

    private static double maxAbs(ImagePoint a, ImagePoint b) {
        return Math.max(Math.abs(a.x() - b.x()), Math.abs(a.y() - b.y()));
    }

    private static ImagePoint center(BarcodeHit hit) {
        return hit.corners().isEmpty() ? null : center(hit.corners());
    }

    private static ImagePoint center(List<ImagePoint> corners) {
        double x = 0.0;
        double y = 0.0;
        for (ImagePoint corner : corners) {
            x += corner.x();
            y += corner.y();
        }
        return new ImagePoint(x / corners.size(), y / corners.size());
    }
}
