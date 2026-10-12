package org.krzywanski.barcode;

import com.google.zxing.LuminanceSource;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import org.krzywanski.barcode.decode.HitCollector;
import org.krzywanski.barcode.decode.ZxingDecoder;
import org.krzywanski.barcode.image.Geometry;
import org.krzywanski.barcode.image.GrayImages;
import org.krzywanski.barcode.image.GrayU8;
import org.krzywanski.barcode.image.PixelMap;
import org.krzywanski.barcode.image.Transformed;
import org.krzywanski.barcode.locate.RegionLocalizer;
import org.krzywanski.barcode.locate.dm.AztecDetector;
import org.krzywanski.barcode.locate.dm.DataMatrixDetector;
import org.krzywanski.barcode.locate.dm.MicroQrDetector;
import org.krzywanski.barcode.locate.dm.QrDetector;
import org.krzywanski.barcode.preprocess.Thresholds;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Ordered attempts from the spec. Cheap ZXing calls run first so a clean symbol
 * does not pay for thresholds, rotations, or detectors.
 */
final class Scan {
    private final ReadOptions options;
    private final Set<Symbology> symbologies;
    private final HitCollector hits = new HitCollector();
    private final RegionLocalizer localizer = new RegionLocalizer();
    private final long deadlineNanos;
    private int attempts;

    Scan(ReadOptions options) {
        this.options = options;
        this.symbologies = options.symbologies();
        this.deadlineNanos = System.nanoTime() + options.timeBudgetMillis() * 1_000_000L;
    }

    List<BarcodeHit> run(BufferedImage image) {
        if (image.getWidth() < 2 || image.getHeight() < 2) {
            return List.of();
        }
        GrayU8 gray = GrayImages.from(image);

        tryBuffered(image, false, "original-hybrid", 0.92, options.findAll());
        tryBuffered(image, true, "original-global", 0.84, false);
        tryGray(GrayImages.invert(gray), PixelMap.IDENTITY, "inverted-luma", 0, false, false, 0.86, false);
        Transformed shifted = Geometry.pad(gray, 4);
        tryGray(shifted.image(), shifted.toSource(), "pad-4", 0, false, false, 0.82, false);
        GrayU8 stretched = GrayImages.stretch(gray);
        if (stretched != gray) {
            tryGray(stretched, PixelMap.IDENTITY, "contrast-stretch", 0, false, false, 0.74, false);
        }
        tryGray(GrayImages.flattenIllumination(gray), PixelMap.IDENTITY, "illumination", 0, false, false, 0.74, false);
        for (int angle : new int[]{90, 180, 270}) {
            Transformed rotated = Geometry.rotateOrthogonal(gray, angle);
            tryGray(rotated.image(), rotated.toSource(), "rotate-" + angle, angle, false, false, 0.80, options.findAll());
        }
        tryBinary(Thresholds.mean(gray), "adaptive-mean", false, 0.68);
        tryBinary(Thresholds.otsu(gray), "otsu", false, 0.68);
        if (Math.min(gray.width, gray.height) <= 900 && Geometry.withinPixelBudget(gray.width, gray.height, 2.0)) {
            Transformed scaled = Geometry.scale(gray, 2.0);
            tryGray(scaled.image(), scaled.toSource(), "scale-2", 0, false, false, 0.80, false);
        }
        traceQr(gray);
        traceMicroQr(gray);
        traceAztec(gray);
        traceDataMatrix(gray);
        if (options.mode() == DecodeMode.FAST || stop()) {
            return hits.list();
        }

        // Angle recovery before thresholds: a 30°–50° linear symbol is one rotation away,
        // and the Sauvola / quad work below is much more expensive.
        for (double angle : new double[]{-15.0, 15.0, -30.0, 30.0}) {
            if (!Geometry.rotationFits(gray.width, gray.height, angle)) {
                continue;
            }
            Transformed rotated = Geometry.rotate(gray, angle);
            tryGray(rotated.image(), rotated.toSource(), "rotate-" + angle, rotated.degrees(), false, false, 0.72, false);
        }
        // A strip that is still tilted after ±15/±30. It sits here, not after Sauvola:
        // the ninth crop of a miss needs attempt budget the rest of this ladder would spend.
        // FAST has already returned. A hit on the angle loop stops before this runs.
        if ((hits.isEmpty() || options.findAll()) && !stop()) {
            tryLinearBands(gray);
        }
        // zxing-cpp LumImagePyramid, after the angles so a rotated linear code still wins there.
        // FAST returns above. The full-resolution layer is the cheap ladder, not a second pass.
        tryDownscale(gray);
        tryTiles(gray);
        tryBinary(Thresholds.gaussian(gray), "adaptive-gaussian", false, 0.68);
        List<GrayU8> sauvola = Thresholds.sauvola(gray);
        for (int i = 0; i < sauvola.size(); i++) {
            tryBinary(sauvola.get(i), "sauvola-" + i, false, 0.68);
        }
        if (!sauvola.isEmpty()) {
            int index = 0;
            for (GrayU8 variant : Thresholds.morphology(sauvola.get(0))) {
                tryBinary(variant, "morph-" + index, true, 0.64);
                index++;
            }
        }
        if (Geometry.withinPixelBudget(gray.width, gray.height, 1.5)) {
            Transformed scaled = Geometry.scale(gray, 1.5);
            tryGray(scaled.image(), scaled.toSource(), "scale-1.5", 0, false, false, 0.80, false);
        }
        if (gray.width * (long) gray.height > 800L * 800L && Geometry.withinPixelBudget(gray.width, gray.height, 0.5)) {
            Transformed scaled = Geometry.scale(gray, 0.5);
            tryGray(scaled.image(), scaled.toSource(), "scale-0.5", 0, false, false, 0.80, false);
        }
        locate(gray, false, true);
        if (hits.isEmpty() || options.findAll()) {
            GrayU8 inverted = GrayImages.invert(gray);
            traceQr(inverted);
            traceMicroQr(inverted);
            traceAztec(inverted);
            traceDataMatrix(inverted);
        }
        return hits.list();
    }

    /**
     * Deskewed linear strips. Matrix and PDF417 stay off so a crop cannot invent a stacked code.
     * Hybrid, stretch, Otsu, and local mean are the steps that won a labeled frame and still
     * leave a miss enough attempts for Sauvola and scale 0.5. Otsu at 2× won two frames and is
     * not run: nine crops of it pass attempt 100 before those later rungs. The tight window
     * repeats hybrid and Otsu only.
     */
    private void tryLinearBands(GrayU8 gray) {
        Set<Symbology> formats = linearFormats();
        if (formats.isEmpty()) {
            return;
        }
        for (LinearBand.Crop crop : LinearBand.find(gray)) {
            if (stop()) {
                return;
            }
            readBand(crop, formats, "");
            if (stop()) {
                return;
            }
            LinearBand.Crop tight = LinearBand.tighten(crop);
            if (tight != null) {
                readBand(tight, formats, "-tight");
            }
        }
    }

    private void readBand(LinearBand.Crop crop, Set<Symbology> formats, String suffix) {
        GrayU8 image = crop.image();
        PixelMap map = crop.toSource();
        int degrees = crop.degrees();
        tryGray(image, map, "linear-band" + suffix, degrees, false, formats, 0.70, false);
        if (stop()) {
            return;
        }
        if (suffix.isEmpty()) {
            GrayU8 stretched = GrayImages.stretch(image);
            if (stretched != image) {
                tryGray(stretched, map, "linear-band-stretch", degrees, false, formats, 0.70, false);
                if (stop()) {
                    return;
                }
            }
        }
        tryGray(
                GrayImages.binaryToLuma(Thresholds.otsu(image)),
                map,
                "linear-band-otsu" + suffix,
                degrees,
                true,
                formats,
                0.70,
                false
        );
        if (stop()) {
            return;
        }
        if (suffix.isEmpty()) {
            tryGray(
                    GrayImages.binaryToLuma(Thresholds.mean(image)),
                    map,
                    "linear-band-mean",
                    degrees,
                    true,
                    formats,
                    0.70,
                    false
            );
            if (stop()) {
                return;
            }
        }
    }

    private Set<Symbology> linearFormats() {
        EnumSet<Symbology> formats = EnumSet.noneOf(Symbology.class);
        for (Symbology symbology : symbologies) {
            if (symbology.linear()) {
                formats.add(symbology);
            }
        }
        return formats;
    }

    /**
     * One ZXing attempt per smaller pyramid layer. Factor 3 and threshold 500 match
     * zxing-cpp {@code ReaderOptions}. Confidence stays under the angle attempts.
     * The attempt name carries the integer width ratio used to map corners back.
     */
    private void tryDownscale(GrayU8 gray) {
        if (stop()) {
            return;
        }
        for (Transformed layer : Geometry.boxPyramid(gray, 500, 3)) {
            int factor = gray.width / layer.image().width;
            tryGray(layer.image(), layer.toSource(), "downscale-" + factor, 0, false, false, 0.70, false);
        }
    }

    /**
     * Overlapping tiles for a small matrix symbol on a large label.
     * A dotted clock track is rarely one quadrilateral, so the symbol is read from a
     * tile that contains it. Linear formats stay off: a tile that slices
     * a long bar code checksums as a short retail code.
     */
    private void tryTiles(GrayU8 gray) {
        if (stop() || gray.width < 240 || gray.height < 240) {
            return;
        }
        Set<Symbology> matrix = twoDimensional();
        if (matrix.isEmpty()) {
            return;
        }
        int tileWidth = Math.max(32, (int) Math.round(gray.width * 0.55));
        int tileHeight = Math.max(32, (int) Math.round(gray.height * 0.55));
        if (tileWidth >= gray.width - 8 && tileHeight >= gray.height - 8) {
            return;
        }
        int columns = 3;
        int rows = 3;
        for (int row = 0; row < rows; row++) {
            int y = row * (gray.height - tileHeight) / (rows - 1);
            for (int column = 0; column < columns; column++) {
                if (stop()) {
                    return;
                }
                int x = column * (gray.width - tileWidth) / (columns - 1);
                Transformed tile = Geometry.crop(gray, x, y, tileWidth, tileHeight);
                tryGray(tile.image(), tile.toSource(), "tile-" + column + "-" + row, 0, false, matrix, 0.70, false);
            }
        }
    }

    /**
     * One QR attempt on the original gray. A clean symbol still stops on {@code original-*}.
     * Confidence stays under that hit.
     */
    private void traceQr(GrayU8 gray) {
        if (stop() || !symbologies.contains(Symbology.QR_CODE)) {
            return;
        }
        attempts++;
        List<QrDetector.Found> found = QrDetector.read(gray);
        // Raw local-average misses salt-and-pepper the same way zxing-cpp 3.1.1 does.
        // The median is a second look inside this attempt, not a looser finder threshold.
        if (found.isEmpty()) {
            found = QrDetector.read(GrayImages.median3(gray));
        }
        for (QrDetector.Found hit : found) {
            List<ImagePoint> corners = new ArrayList<>(hit.corners().size());
            for (ImagePoint corner : hit.corners()) {
                corners.add(PixelMap.IDENTITY.toSource(corner.x(), corner.y()));
            }
            hits.add(new BarcodeHit(
                    hit.text(),
                    Symbology.QR_CODE,
                    corners,
                    hit.orientationDegrees(),
                    0.85,
                    "qr-trace"
            ));
        }
    }

    /**
     * One Micro QR attempt on the original gray, after the QR finder and before Aztec.
     * A clean symbol still stops on {@code original-*}, and a QR hit stops on
     * {@code qr-trace}. Confidence stays under those hits.
     */
    private void traceMicroQr(GrayU8 gray) {
        if (stop() || !symbologies.contains(Symbology.MICRO_QR)) {
            return;
        }
        attempts++;
        for (MicroQrDetector.Found found : MicroQrDetector.read(gray)) {
            List<ImagePoint> corners = new ArrayList<>(found.corners().size());
            for (ImagePoint corner : found.corners()) {
                corners.add(PixelMap.IDENTITY.toSource(corner.x(), corner.y()));
            }
            hits.add(new BarcodeHit(
                    found.text(),
                    Symbology.MICRO_QR,
                    corners,
                    found.orientationDegrees(),
                    0.85,
                    "mqr-trace"
            ));
        }
    }

    /**
     * One Aztec attempt on the original gray. A clean symbol still stops on {@code original-*}.
     * Confidence stays under that hit. A symbol of dimension 35 or larger is sampled from
     * {@code LocalGrid} timing crosses.
     */
    private void traceAztec(GrayU8 gray) {
        if (stop() || !symbologies.contains(Symbology.AZTEC)) {
            return;
        }
        attempts++;
        for (AztecDetector.Found found : AztecDetector.read(gray)) {
            List<ImagePoint> corners = new ArrayList<>(found.corners().size());
            for (ImagePoint corner : found.corners()) {
                corners.add(PixelMap.IDENTITY.toSource(corner.x(), corner.y()));
            }
            hits.add(new BarcodeHit(
                    found.text(),
                    Symbology.AZTEC,
                    corners,
                    found.orientationDegrees(),
                    0.85,
                    "aztec-trace"
            ));
        }
    }

    /**
     * One Data Matrix attempt on the original gray. It sits after the cheap ladder,
     * so a clean symbol still stops on {@code original-*}. Confidence stays under
     * {@code inverted-luma} when both hits are kept.
     */
    private void traceDataMatrix(GrayU8 gray) {
        if (stop() || !symbologies.contains(Symbology.DATA_MATRIX)) {
            return;
        }
        attempts++;
        for (DataMatrixDetector.Found found : DataMatrixDetector.read(gray)) {
            List<ImagePoint> corners = new ArrayList<>(found.corners().size());
            for (ImagePoint corner : found.corners()) {
                corners.add(PixelMap.IDENTITY.toSource(corner.x(), corner.y()));
            }
            hits.add(new BarcodeHit(
                    found.text(),
                    Symbology.DATA_MATRIX,
                    corners,
                    found.orientationDegrees(),
                    0.85,
                    "dm-trace"
            ));
        }
    }

    private void locate(GrayU8 gray, boolean fiducials, boolean quadrilaterals) {
        if (stop()) {
            return;
        }
        localizer.localize(gray, PixelMap.IDENTITY, symbologies, fiducials, quadrilaterals, new RegionLocalizer.Sink() {
            @Override
            public boolean open() {
                return !stop();
            }

            @Override
            public void decoded(BarcodeHit hit) {
                if (stop()) {
                    return;
                }
                attempts++;
                hits.add(hit);
            }

            @Override
            public void candidate(
                    GrayU8 luma,
                    PixelMap toSource,
                    List<ImagePoint> corners,
                    String attempt,
                    Set<Symbology> formats
            ) {
                tryGray(luma, toSource, attempt, 0, false, formats, 0.76, false);
            }
        });
    }

    private void tryBuffered(BufferedImage image, boolean global, String attempt, double confidence, boolean multi) {
        if (stop()) {
            return;
        }
        attempts++;
        LuminanceSource source = new BufferedImageLuminanceSource(image);
        accept(ZxingDecoder.read(source, PixelMap.IDENTITY, attempt, 0, confidence, symbologies, global, multi));
    }

    private void tryBinary(GrayU8 binary, String attempt, boolean matrixOnly, double confidence) {
        tryGray(GrayImages.binaryToLuma(binary), PixelMap.IDENTITY, attempt, 0, true, matrixOnly, confidence, false);
    }

    private void tryGray(
            GrayU8 image,
            PixelMap toSource,
            String attempt,
            int degrees,
            boolean globalBinarizer,
            boolean matrixOnly,
            double confidence,
            boolean multi
    ) {
        tryGray(image, toSource, attempt, degrees, globalBinarizer, matrixOnly ? twoDimensional() : symbologies, confidence, multi);
    }

    private void tryGray(
            GrayU8 image,
            PixelMap toSource,
            String attempt,
            int degrees,
            boolean globalBinarizer,
            Set<Symbology> formats,
            double confidence,
            boolean multi
    ) {
        if (formats.isEmpty() || stop() || image.width < 2 || image.height < 2) {
            return;
        }
        attempts++;
        accept(ZxingDecoder.read(image, toSource, attempt, degrees, confidence, formats, globalBinarizer, multi));
    }

    private void accept(List<BarcodeHit> found) {
        for (BarcodeHit hit : found) {
            hits.add(hit);
        }
    }

    private Set<Symbology> twoDimensional() {
        EnumSet<Symbology> formats = EnumSet.noneOf(Symbology.class);
        for (Symbology symbology : symbologies) {
            if (symbology.twoDimensional()) {
                formats.add(symbology);
            }
        }
        return formats;
    }

    private boolean stop() {
        if (Thread.currentThread().isInterrupted()) {
            return true;
        }
        if (!options.findAll() && !hits.isEmpty()) {
            return true;
        }
        if (attempts >= options.maxAttempts()) {
            return true;
        }
        return System.nanoTime() >= deadlineNanos;
    }
}
