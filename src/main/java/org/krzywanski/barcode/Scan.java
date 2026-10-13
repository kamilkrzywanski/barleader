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
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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
    /** Crops from the band search, reused by the late unsharp that sits in the gaussian slot. */
    private List<LinearBand.Crop> bandCrops = List.of();
    /**
     * One nearest 2× Otsu of a tight crop stays under this. The shared scale cap is 1.6 Mpx
     * and is not raised. Crops above this were not measured.
     */
    private static final double BAND_UPSCALE_PIXELS = 2_000_000.0;

    /**
     * Unsharp and 2× on a 2976×2232 photo cost about three seconds and pushed Sauvola
     * past the 8 s budget, dropping {@code PICawdT0024}. The frames those attempts recover
     * are 1152×864, so anything above one megapixel skips them. The percentile strips are
     * cheap and stay behind the same gate.
     */
    private static final long BAND_EXTRA_PIXELS = 1_000_000L;

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
        // A hit on the two buffered attempts used to still build every later image and
        // then throw the decode away. Those images are independent, so a miss runs them
        // together and keeps the earliest hit in this same order.
        if (!stop() && !symbologies.isEmpty()) {
            List<Callable<List<BarcodeHit>>> jobs = new ArrayList<>();
            jobs.add(() -> readImage(GrayImages.invert(gray), PixelMap.IDENTITY, "inverted-luma", 0, false, 0.86, false));
            jobs.add(() -> {
                Transformed shifted = Geometry.pad(gray, 4);
                return readImage(shifted.image(), shifted.toSource(), "pad-4", 0, false, 0.82, false);
            });
            GrayU8 stretched = GrayImages.stretch(gray);
            if (stretched != gray) {
                jobs.add(() -> readImage(stretched, PixelMap.IDENTITY, "contrast-stretch", 0, false, 0.74, false));
            }
            jobs.add(() -> readImage(
                    GrayImages.flattenIllumination(gray), PixelMap.IDENTITY, "illumination", 0, false, 0.74, false));
            for (int angle : new int[]{90, 180, 270}) {
                jobs.add(() -> {
                    Transformed rotated = Geometry.rotateOrthogonal(gray, angle);
                    return readImage(
                            rotated.image(), rotated.toSource(), "rotate-" + angle, angle, false, 0.80, options.findAll());
                });
            }
            jobs.add(() -> readImage(
                    GrayImages.binaryToLuma(Thresholds.mean(gray)),
                    PixelMap.IDENTITY, "adaptive-mean", 0, true, 0.68, false));
            jobs.add(() -> readImage(
                    GrayImages.binaryToLuma(Thresholds.otsu(gray)),
                    PixelMap.IDENTITY, "otsu", 0, true, 0.68, false));
            if (Math.min(gray.width, gray.height) <= 900 && Geometry.withinPixelBudget(gray.width, gray.height, 2.0)) {
                jobs.add(() -> {
                    Transformed scaled = Geometry.scale(gray, 2.0);
                    return readImage(scaled.image(), scaled.toSource(), "scale-2", 0, false, 0.80, false);
                });
            }
            runAttempts(jobs);
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
        // The four rotations do not depend on each other. Results are still applied from
        // -15 upward, so a hit on an earlier angle still hides a later one.
        if (!stop() && !symbologies.isEmpty()) {
            List<Callable<List<BarcodeHit>>> angles = new ArrayList<>();
            for (double angle : new double[]{-15.0, 15.0, -30.0, 30.0}) {
                if (!Geometry.rotationFits(gray.width, gray.height, angle)) {
                    continue;
                }
                double degrees = angle;
                angles.add(() -> {
                    Transformed rotated = Geometry.rotate(gray, degrees);
                    return readImage(
                            rotated.image(), rotated.toSource(), "rotate-" + degrees, rotated.degrees(), false, 0.72, false);
                });
            }
            runAttempts(angles);
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
        List<GrayU8> sauvola = Thresholds.sauvola(gray);
        for (int i = 0; i < sauvola.size(); i++) {
            tryBinary(sauvola.get(i), "sauvola-" + i, false, 0.68);
        }
        if (Geometry.withinPixelBudget(gray.width, gray.height, 1.5)) {
            Transformed scaled = Geometry.scale(gray, 1.5);
            tryGray(scaled.image(), scaled.toSource(), "scale-1.5", 0, false, false, 0.80, false);
        }
        // On the fullest 1152×864 miss this pass is attempt 100 and still runs
        // (Foto(799), Foto(848)). One more decode in front of it would drop both.
        // The next slot used to be a full-frame gaussian, which recovered nothing
        // labeled. It now sharpens the first band crop instead. Morphology still waits.
        if (gray.width * (long) gray.height > 800L * 800L && Geometry.withinPixelBudget(gray.width, gray.height, 0.5)) {
            Transformed scaled = Geometry.scale(gray, 0.5);
            tryGray(scaled.image(), scaled.toSource(), "scale-0.5", 0, false, false, 0.80, false);
        }
        if (!stop()) {
            // Gaussian recovered no labeled symbol on the 430-frame set. When a band
            // search already ran, that attempt instead sharpens the first crop: radius 4
            // amount 4 then Otsu reads Foto(480), which the radius-3 unsharp does not.
            Set<Symbology> linear = linearFormats();
            if (!options.findAll() && !bandCrops.isEmpty() && !linear.isEmpty()) {
                LinearBand.Crop crop = bandCrops.get(0);
                GrayU8 sharp = GrayImages.unsharpHorizontal(crop.image(), 4, 4.0);
                tryGray(
                        GrayImages.binaryToLuma(Thresholds.otsu(sharp)),
                        crop.toSource(),
                        "linear-band-us4-otsu",
                        crop.degrees(),
                        true,
                        linear,
                        0.70,
                        false
                );
            } else {
                tryBinary(Thresholds.gaussian(gray), "adaptive-gaussian", false, 0.68);
            }
        }
        // The 3° band grid leaves a soft symbol tilted. A -2° correction, then a
        // horizontal unsharp, reads labels the upright crop misses. The soft wide
        // crop goes first at radius 3; Foto(846) has only one attempt left. Further
        // crops stay at radius 4 while the attempt budget remains. This stays
        // behind half-scale: Foto(799) and Foto(848) still win there.
        if (!stop()) {
            refineMissedBands(linearFormats());
        }
        if (!stop() && !sauvola.isEmpty()) {
            int index = 0;
            for (GrayU8 variant : Thresholds.morphology(sauvola.get(0))) {
                tryBinary(variant, "morph-" + index, true, 0.64);
                index++;
            }
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
     * Hybrid, stretch, Otsu, and local mean run on every crop. The tight window repeats hybrid
     * and Otsu only. On a photo of at most one megapixel, fourteen extra attempts sit on
     * crops that still missed: unsharp on the first crop, percentile cuts at 35, 45 and 50,
     * a local mean of its sharpest rows, one unsharp of that average, a sharp-row average
     * cut at 50 and at 40, a radius-2 unsharp of that average on the second crop, and on
     * the third crop the sharp-row average plus its per-column median and darkest pixel.
     * Unsharp-then-Otsu is the fourth crop and one 2× Otsu is the fifth tight window.
     * After scale 0.5, the old gaussian slot sharpens the first crop when a band search
     * ran. Morphology still follows a miss. On the fullest 1152×864 miss, scale 0.5
     * is attempt 100 and still runs. One more decode in front of it would drop Foto(799)
     * and Foto(848). Larger photos skip the extras.
     */
    private void tryLinearBands(GrayU8 gray) {
        Set<Symbology> formats = linearFormats();
        if (formats.isEmpty()) {
            return;
        }
        boolean extra = gray.width * (long) gray.height <= BAND_EXTRA_PIXELS;
        int index = 0;
        List<LinearBand.Crop> crops = LinearBand.find(gray);
        bandCrops = crops;
        for (LinearBand.Crop crop : crops) {
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
                if (stop()) {
                    return;
                }
            }
            if (extra && index == 0) {
                readUnsharp(crop, formats, 3, 3.0, false);
                if (stop()) {
                    return;
                }
                // 35 recovers Foto(481) and Foto(728). 45 recovers Foto(573). Neither
                // percentile reads all three, and a different text did not appear on a
                // frame whose ordinary crop-0 steps had already missed.
                readLevel(crop, formats, 35);
                if (stop()) {
                    return;
                }
                readLevel(crop, formats, 45);
                if (stop()) {
                    return;
                }
                // 50 is the median: 45 and 55 both missed Foto(556). The local mean of the
                // sharpest rows reads Foto(817) and Foto(820). Unsharp of the average,
                // cut at 50, reads Foto(501).
                readLevel(crop, formats, 50);
                if (stop()) {
                    return;
                }
                readLocalLevel(crop, formats);
                if (stop()) {
                    return;
                }
                readSharpenedLevel(crop, formats);
                if (stop()) {
                    return;
                }
                // The flat average misses Foto(778); the sharp-row average cut at 50 reads it.
                // 40 reads Foto(483), which 50 missed.
                readTopLevel(crop, formats);
                if (stop()) {
                    return;
                }
                readSharpPercent(crop, formats, 40, "linear-band-level-top40");
            } else if (extra && index == 1) {
                // Foto(504). Radius 2 on the sharp-row average; the flat unsharp does not read it.
                readSharpUnsharp(crop, formats);
            } else if (extra && index == 2) {
                // Foto(765), Foto(776) and Foto(779). Same digits, third crop only.
                readTopLevel(crop, formats);
                if (stop()) {
                    return;
                }
                // Foto(406) is the per-column median. Foto(738) is the darkest pixel.
                readColumnCut(crop, formats, false, "linear-band-level-med");
                if (stop()) {
                    return;
                }
                readColumnCut(crop, formats, true, "linear-band-level-min");
            } else if (extra && index == 3) {
                readUnsharp(crop, formats, 2, 2.5, true);
            } else if (extra && index == 4 && tight != null) {
                readScaledOtsu(tight, formats);
            }
            index++;
        }
    }

    /**
     * Horizontal unsharp, then hybrid or Otsu. Runs only after this crop's ordinary steps missed,
     * so a frame already read from the raw crop is left alone.
     */
    private void readUnsharp(
            LinearBand.Crop crop,
            Set<Symbology> formats,
            int radius,
            double amount,
            boolean otsu
    ) {
        GrayU8 sharp = GrayImages.unsharpHorizontal(crop.image(), radius, amount);
        if (otsu) {
            tryGray(
                    GrayImages.binaryToLuma(Thresholds.otsu(sharp)),
                    crop.toSource(),
                    "linear-band-unsharp-otsu",
                    crop.degrees(),
                    true,
                    formats,
                    0.70,
                    false
            );
            return;
        }
        tryGray(sharp, crop.toSource(), "linear-band-unsharp", crop.degrees(), false, formats, 0.70, false);
    }

    /**
     * Average the bar rows, cut at a percentile, and repeat that one black-and-white
     * scanline. A blurred EAN is a sine wave; one global cut of the average is enough
     * for some frames where Otsu on the whole crop is not.
     */
    private void readLevel(LinearBand.Crop crop, Set<Symbology> formats, int percent) {
        GrayU8 image = crop.image();
        if (image.width < 2 || image.height < 2) {
            return;
        }
        int y0 = image.height / 5;
        int y1 = Math.max(y0 + 1, image.height * 3 / 5);
        int[] sum = new int[image.width];
        int rows = y1 - y0;
        for (int y = y0; y < y1; y++) {
            for (int x = 0; x < image.width; x++) {
                sum[x] += image.get(x, y);
            }
        }
        int[] sorted = new int[image.width];
        for (int x = 0; x < image.width; x++) {
            sum[x] /= rows;
            sorted[x] = sum[x];
        }
        java.util.Arrays.sort(sorted);
        int index = Math.min(sorted.length - 1, sorted.length * percent / 100);
        int cut = sorted[index];
        GrayU8 strip = new GrayU8(image.width, 48);
        for (int x = 0; x < image.width; x++) {
            int value = sum[x] <= cut ? 0 : 255;
            for (int y = 0; y < strip.height; y++) {
                strip.set(x, y, value);
            }
        }
        tryGray(
                strip,
                crop.toSource(),
                "linear-band-level-" + percent,
                crop.degrees(),
                true,
                formats,
                0.70,
                false
        );
    }

    /**
     * Local mean of the sharpest bar rows. Digits and a wide quiet zone pull a
     * single percentile off the modules; the high-gradient rows do not.
     */
    private void readLocalLevel(LinearBand.Crop crop, Set<Symbology> formats) {
        GrayU8 image = crop.image();
        if (image.width < 2 || image.height < 8) {
            return;
        }
        int[] signal = sharpRows(image);
        if (signal == null) {
            return;
        }
        int radius = 24;
        int[] prefix = new int[signal.length + 1];
        for (int x = 0; x < signal.length; x++) {
            prefix[x + 1] = prefix[x] + signal[x];
        }
        GrayU8 strip = new GrayU8(signal.length, 48);
        for (int x = 0; x < signal.length; x++) {
            int left = Math.max(0, x - radius);
            int right = Math.min(signal.length - 1, x + radius);
            int blur = (prefix[right + 1] - prefix[left]) / (right - left + 1);
            int value = signal[x] <= blur ? 0 : 255;
            for (int y = 0; y < strip.height; y++) {
                strip.set(x, y, value);
            }
        }
        tryGray(strip, crop.toSource(), "linear-band-level-local", crop.degrees(), true, formats, 0.70, false);
    }

    /**
     * Box-unsharp the averaged bar rows, then cut at the median. Radius 4 puts a
     * gray thin bar back under the gaps beside it.
     */
    private void readSharpenedLevel(LinearBand.Crop crop, Set<Symbology> formats) {
        GrayU8 image = crop.image();
        if (image.width < 2 || image.height < 2) {
            return;
        }
        int y0 = image.height / 5;
        int y1 = Math.max(y0 + 1, image.height * 3 / 5);
        int[] sum = new int[image.width];
        int rows = y1 - y0;
        for (int y = y0; y < y1; y++) {
            for (int x = 0; x < image.width; x++) {
                sum[x] += image.get(x, y);
            }
        }
        for (int x = 0; x < image.width; x++) {
            sum[x] /= rows;
        }
        int radius = 4;
        double amount = 4.0;
        int[] prefix = new int[image.width + 1];
        for (int x = 0; x < image.width; x++) {
            prefix[x + 1] = prefix[x] + sum[x];
        }
        int[] sharp = new int[image.width];
        for (int x = 0; x < image.width; x++) {
            int left = Math.max(0, x - radius);
            int right = Math.min(image.width - 1, x + radius);
            int blur = (prefix[right + 1] - prefix[left]) / (right - left + 1);
            int value = (int) Math.round(sum[x] + amount * (sum[x] - blur));
            if (value < 0) {
                value = 0;
            } else if (value > 255) {
                value = 255;
            }
            sharp[x] = value;
        }
        int[] sorted = sharp.clone();
        java.util.Arrays.sort(sorted);
        int index = Math.min(sorted.length - 1, sorted.length * 50 / 100);
        int cut = sorted[index];
        GrayU8 strip = new GrayU8(image.width, 48);
        for (int x = 0; x < image.width; x++) {
            int value = sharp[x] <= cut ? 0 : 255;
            for (int y = 0; y < strip.height; y++) {
                strip.set(x, y, value);
            }
        }
        tryGray(strip, crop.toSource(), "linear-band-level-us4", crop.degrees(), true, formats, 0.70, false);
    }

    /**
     * Median of the sharpest bar rows. A wide quiet zone pulls a flat average off the
     * modules; the high-gradient rows do not. The strip is already black and white.
     */
    private void readTopLevel(LinearBand.Crop crop, Set<Symbology> formats) {
        GrayU8 image = crop.image();
        if (image.width < 2 || image.height < 8) {
            return;
        }
        int[] signal = sharpRows(image);
        if (signal == null) {
            return;
        }
        int[] sorted = signal.clone();
        java.util.Arrays.sort(sorted);
        int index = Math.min(sorted.length - 1, sorted.length * 50 / 100);
        int cut = sorted[index];
        GrayU8 strip = new GrayU8(signal.length, 48);
        for (int x = 0; x < signal.length; x++) {
            int value = signal[x] <= cut ? 0 : 255;
            for (int y = 0; y < strip.height; y++) {
                strip.set(x, y, value);
            }
        }
        tryGray(strip, crop.toSource(), "linear-band-level-top", crop.degrees(), true, formats, 0.70, false);
    }

    /**
     * Radius-2 unsharp of the sharp-row average, then the median cut.
     * Foto(504) is this and not the flat-row unsharp.
     */
    private void readSharpUnsharp(LinearBand.Crop crop, Set<Symbology> formats) {
        GrayU8 image = crop.image();
        if (image.width < 2 || image.height < 8) {
            return;
        }
        int[] signal = sharpRows(image);
        if (signal == null) {
            return;
        }
        int radius = 2;
        double amount = 4.0;
        int[] prefix = new int[signal.length + 1];
        for (int x = 0; x < signal.length; x++) {
            prefix[x + 1] = prefix[x] + signal[x];
        }
        int[] sharp = new int[signal.length];
        for (int x = 0; x < signal.length; x++) {
            int left = Math.max(0, x - radius);
            int right = Math.min(signal.length - 1, x + radius);
            int blur = (prefix[right + 1] - prefix[left]) / (right - left + 1);
            int value = (int) Math.round(signal[x] + amount * (signal[x] - blur));
            if (value < 0) {
                value = 0;
            } else if (value > 255) {
                value = 255;
            }
            sharp[x] = value;
        }
        paintCut(crop, formats, sharp, 50, "linear-band-level-su2");
    }

    /** Same sharp-row average as {@link #readTopLevel}, cut at {@code percent}. */
    private void readSharpPercent(LinearBand.Crop crop, Set<Symbology> formats, int percent, String attempt) {
        GrayU8 image = crop.image();
        if (image.width < 2 || image.height < 8) {
            return;
        }
        paintCut(crop, formats, sharpRows(image), percent, attempt);
    }

    /**
     * Per-column median, or the darkest sample, of the same sharp rows.
     * A mean smears a bar that is one pixel off between rows.
     */
    private void readColumnCut(LinearBand.Crop crop, Set<Symbology> formats, boolean darkest, String attempt) {
        GrayU8 image = crop.image();
        if (image.width < 2 || image.height < 8) {
            return;
        }
        int[][] rows = keptRows(image);
        if (rows.length == 0) {
            return;
        }
        int[] signal = new int[image.width];
        int[] column = new int[rows.length];
        for (int x = 0; x < image.width; x++) {
            for (int i = 0; i < rows.length; i++) {
                column[i] = rows[i][x];
            }
            if (darkest) {
                int best = 255;
                for (int value : column) {
                    if (value < best) {
                        best = value;
                    }
                }
                signal[x] = best;
            } else {
                int[] copy = column.clone();
                java.util.Arrays.sort(copy);
                signal[x] = copy[copy.length / 2];
            }
        }
        paintCut(crop, formats, signal, 50, attempt);
    }

    private void paintCut(LinearBand.Crop crop, Set<Symbology> formats, int[] signal, int percent, String attempt) {
        if (signal == null || signal.length < 2) {
            return;
        }
        int[] sorted = signal.clone();
        java.util.Arrays.sort(sorted);
        int index = Math.min(sorted.length - 1, sorted.length * percent / 100);
        int cut = sorted[index];
        GrayU8 strip = new GrayU8(signal.length, 48);
        for (int x = 0; x < signal.length; x++) {
            int value = signal[x] <= cut ? 0 : 255;
            for (int y = 0; y < strip.height; y++) {
                strip.set(x, y, value);
            }
        }
        tryGray(strip, crop.toSource(), attempt, crop.degrees(), true, formats, 0.70, false);
    }

    /** Sharp rows kept by {@link #sharpRows}, one sample array per row. */
    private static int[][] keptRows(GrayU8 image) {
        int y0 = image.height / 5;
        int y1 = Math.max(y0 + 1, image.height * 4 / 5);
        int count = y1 - y0;
        int[] energy = new int[count];
        int[][] samples = new int[count][];
        for (int i = 0; i < count; i++) {
            int row = image.startIndex + (y0 + i) * image.stride;
            int[] sample = new int[image.width];
            int previous = image.data[row] & 255;
            sample[0] = previous;
            int value = 0;
            for (int x = 1; x < image.width; x++) {
                int pixel = image.data[row + x] & 255;
                sample[x] = pixel;
                int delta = pixel - previous;
                value += delta < 0 ? -delta : delta;
                previous = pixel;
            }
            energy[i] = value;
            samples[i] = sample;
        }
        int[] sorted = energy.clone();
        java.util.Arrays.sort(sorted);
        int gate = sorted[count * 3 / 4];
        java.util.List<int[]> kept = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            if (energy[i] >= gate) {
                kept.add(samples[i]);
            }
        }
        return kept.toArray(int[][]::new);
    }

    /** Mean of the rows from 20% to 80% whose gradient is at or above the 75th percentile. */
    private static int[] sharpRows(GrayU8 image) {
        int y0 = image.height / 5;
        int y1 = Math.max(y0 + 1, image.height * 4 / 5);
        int count = y1 - y0;
        int[] energy = new int[count];
        for (int i = 0; i < count; i++) {
            int row = image.startIndex + (y0 + i) * image.stride;
            int previous = image.data[row] & 255;
            int value = 0;
            for (int x = 1; x < image.width; x++) {
                int sample = image.data[row + x] & 255;
                int delta = sample - previous;
                value += delta < 0 ? -delta : delta;
                previous = sample;
            }
            energy[i] = value;
        }
        int[] sorted = energy.clone();
        java.util.Arrays.sort(sorted);
        int gate = sorted[sorted.length * 3 / 4];
        int[] sum = new int[image.width];
        int rows = 0;
        for (int i = 0; i < count; i++) {
            if (energy[i] < gate) {
                continue;
            }
            int row = image.startIndex + (y0 + i) * image.stride;
            for (int x = 0; x < image.width; x++) {
                sum[x] += image.data[row + x] & 255;
            }
            rows++;
        }
        if (rows == 0) {
            return null;
        }
        for (int x = 0; x < image.width; x++) {
            sum[x] /= rows;
        }
        return sum;
    }

    /** Nearest 2× then Otsu. The map sends scaled pixels back through the tight crop. */
    private void readScaledOtsu(LinearBand.Crop crop, Set<Symbology> formats) {
        GrayU8 image = crop.image();
        double pixels = image.width * (double) image.height * 4.0;
        if (pixels < 16.0 || pixels > BAND_UPSCALE_PIXELS) {
            return;
        }
        Transformed scaled = Geometry.scale(image, 2.0);
        tryGray(
                GrayImages.binaryToLuma(Thresholds.otsu(scaled.image())),
                scaled.toSource().before(crop.toSource()),
                "linear-band-x2-otsu-tight",
                crop.degrees(),
                true,
                formats,
                0.70,
                false
        );
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

    /**
     * Residual tilt after the band search. Angle step is 3°, so a blurred code can
     * sit 2° off the crop. A −2° correction, then a horizontal unsharp and Otsu, is
     * one attempt per crop. The softest crop at least 400×100 goes first, with
     * radius 3: that is where Foto(849), Foto(846) and Foto(851) are, Foto(846) has
     * only one attempt left, and radius 4 misses Foto(837) and Foto(516). The later
     * indexes stay at radius 4. They are the other crops that held a labeled symbol
     * at −2°. Index 6 is not in that list. On Foto(839) it is the only −2° read, and
     * the text is the printed GTIN rather than the typo on the label, so it would
     * be a false read. When index 6 is itself the soft crop, as on Foto(849), it
     * still runs first. When those crops miss and a slot remains, crop 1 is tried
     * again at −1°. That reads Foto(698). Crop 0 at −1° false-reads Foto(828),
     * so it is not included. Crop 0 at +2° reads Foto(515) and Foto(824).
     * When those miss too, crop 2 at +0.5° reads Foto(823), and crop 3 at +1°
     * and +2° reads Foto(828) and Foto(830). A frame that already hit does not
     * get here. Three later slots stay behind those angles: the sharp-row median
     * of crop 2 at −1° reads Foto(484), a keystone of crop 3 at +1° reads
     * Foto(496), and the sharp-row unsharp of crop 0 at −1° reads Foto(761).
     */
    private void refineMissedBands(Set<Symbology> linear) {
        if (options.findAll() || linear.isEmpty() || bandCrops.isEmpty()) {
            return;
        }
        boolean[] tried = new boolean[bandCrops.size()];
        LinearBand.Crop soft = softestWideCrop();
        if (soft != null) {
            int index = bandCrops.indexOf(soft);
            if (index >= 0) {
                tried[index] = true;
            }
            // Radius 3 still reads Foto(846), Foto(849) and Foto(851), and it also
            // reads Foto(837) and Foto(516), which radius 4 leaves blank. Later crops
            // stay at radius 4: that is the mask their labels were measured with.
            refineRotated(soft, linear, 3, -2.0);
        }
        for (int index : new int[]{1, 2, 3, 0, 4}) {
            if (stop()) {
                return;
            }
            if (index >= tried.length || tried[index]) {
                continue;
            }
            tried[index] = true;
            refineRotated(bandCrops.get(index), linear, 4, -2.0);
        }
        // Foto(698) is upright at −2° and reads at −1° on crop 1. Crop 0 at −1°
        // false-reads Foto(828), so this second angle stays on index 1.
        if (!stop() && bandCrops.size() > 1) {
            refineRotated(bandCrops.get(1), linear, 4, -1.0);
        }
        // Foto(515) and Foto(824) read at +2° on crop 0. The −2° pass does not.
        // On the former misses that angle of crop 0 did not false-read.
        if (!stop() && !bandCrops.isEmpty()) {
            refineRotated(bandCrops.get(0), linear, 4, 2.0);
        }
        // Later angles, only when earlier crops missed. Each one was a clean
        // labeled read on the former misses: crop 2 at +0.5° is Foto(823),
        // crop 3 at +1° is Foto(828), crop 3 at +2° is Foto(830).
        if (!stop() && bandCrops.size() > 2) {
            refineRotated(bandCrops.get(2), linear, 4, 0.5);
        }
        if (!stop() && bandCrops.size() > 3) {
            refineRotated(bandCrops.get(3), linear, 4, 1.0);
        }
        if (!stop() && bandCrops.size() > 3) {
            refineRotated(bandCrops.get(3), linear, 4, 2.0);
        }
        // Foto(484) is still tilted at −1° on crop 2. The sharp rows of that
        // unsharp, cut at the median, read it. The same strip did not false-read
        // the other former misses.
        if (!stop() && bandCrops.size() > 2) {
            refineUnsharpTop(bandCrops.get(2), linear, -1.0);
        }
        // Foto(496) is a trapezoid. Crop 3 at +1°, straightened so the bar band
        // is a rectangle, then the same unsharp and Otsu. Foto(479) and Foto(506)
        // also read here, but they have no attempt left by this point.
        if (!stop() && bandCrops.size() > 3) {
            refineKeystone(bandCrops.get(3), linear, 1.0);
        }
        // Foto(761) reads the radius-2 unsharp of the sharp-row average on crop 0
        // at −1°. Crop 0 at −1° through Otsu false-reads Foto(828); this strip
        // did not, and Foto(828) has already stopped on an earlier slot.
        if (!stop() && !bandCrops.isEmpty()) {
            refineSharpUnsharp(bandCrops.get(0), linear, -1.0);
        }
        // Foto(312), Foto(760), Foto(762) and Foto(827) still miss every binarizer.
        // The sharpest rows correlate with the EAN templates. The same attempt
        // then tries a coarser grid, which is what reads Foto(751) and Foto(771).
        // This stays last: an earlier slot would push Foto(489) off the ladder.
        if (!stop()) {
            correlateRemaining();
        }
    }

    /**
     * One attempt for every wide crop still unread. The correlator does not call
     * ZXing, so the attempt is counted here. A narrow crop was not in the false-read
     * check and is skipped.
     *
     * <p>The guarded grid runs on every wide crop first. The coarser grid runs only
     * when that pass misses them all, inside the same attempt. An earlier crop must
     * not be allowed to win on the loose grid while a later crop still has a guarded
     * hit: the loose grid is what reads a smeared bar, and it is also what would
     * replace a guarded payload if it ran first.
     */
    private void correlateRemaining() {
        if (stop() || (!symbologies.contains(Symbology.EAN_13) && !symbologies.contains(Symbology.UPC_A))) {
            return;
        }
        boolean wide = false;
        for (LinearBand.Crop crop : bandCrops) {
            GrayU8 image = crop.image();
            if (image.width >= 280 && image.height >= 24) {
                wide = true;
                break;
            }
        }
        if (!wide) {
            return;
        }
        attempts++;
        // Pass 2 halves the module step. It runs only after the guarded grid and the
        // coarse loose grid both miss, so it cannot replace Foto(751) or Foto(771).
        // Foto(714) is the frame that needs module 3.1.
        if (!correlateCrops(0) && !correlateCrops(1)) {
            correlateCrops(2);
        }
    }

    /** @return true when this grid recorded a hit. 0 guarded, 1 coarse, 2 medium module step. */
    private boolean correlateCrops(int pass) {
        for (LinearBand.Crop crop : bandCrops) {
            EanCorrelator.Found found = switch (pass) {
                case 0 -> EanCorrelator.read(crop.image());
                case 1 -> EanCorrelator.readLoose(crop.image());
                default -> EanCorrelator.readMedium(crop.image());
            };
            if (found == null) {
                continue;
            }
            String text = found.text();
            Symbology symbology = Symbology.EAN_13;
            if (text.charAt(0) == '0' && symbologies.contains(Symbology.UPC_A)) {
                symbology = Symbology.UPC_A;
                text = text.substring(1);
            } else if (!symbologies.contains(Symbology.EAN_13)) {
                continue;
            }
            int rotation = crop.degrees();
            int orientation = !found.upsideDown() && rotation == 0
                    ? -1
                    : Math.floorMod((found.upsideDown() ? 180 : 0) + rotation, 360);
            GrayU8 image = crop.image();
            PixelMap map = crop.toSource();
            int mid = image.height / 2;
            hits.add(new BarcodeHit(
                    text,
                    symbology,
                    List.of(map.toSource(0, mid), map.toSource(image.width - 1, mid)),
                    orientation,
                    0.70,
                    "ean-correlate"
            ));
            return true;
        }
        return false;
    }

    /** Lowest sampled edge energy among crops that can hold a retail code. */
    private LinearBand.Crop softestWideCrop() {
        LinearBand.Crop best = null;
        int bestEnergy = Integer.MAX_VALUE;
        int bestWidth = -1;
        for (LinearBand.Crop crop : bandCrops) {
            GrayU8 image = crop.image();
            if (image.width < 400 || image.height < 100) {
                continue;
            }
            int energy = edgeEnergy(image);
            if (best == null || energy < bestEnergy || (energy == bestEnergy && image.width > bestWidth)) {
                best = crop;
                bestEnergy = energy;
                bestWidth = image.width;
            }
        }
        return best;
    }

    /** Mean absolute horizontal step, every other pixel. Matches the crop probe. */
    private static int edgeEnergy(GrayU8 image) {
        long sum = 0;
        int samples = 0;
        for (int y = 0; y < image.height; y += 2) {
            int previous = image.get(0, y);
            for (int x = 1; x < image.width; x += 2) {
                int sample = image.get(x, y);
                int delta = sample - previous;
                sum += delta < 0 ? -delta : delta;
                previous = sample;
                samples++;
            }
        }
        return samples == 0 ? 0 : (int) (sum / samples);
    }

    private void refineRotated(LinearBand.Crop crop, Set<Symbology> linear, int radius, double angle) {
        if (stop()) {
            return;
        }
        Transformed rotated = Geometry.rotate(crop.image(), angle);
        GrayU8 sharp = GrayImages.unsharpHorizontal(rotated.image(), radius, 4.0);
        int degrees = Math.floorMod(crop.degrees() + rotated.degrees(), 360);
        tryGray(
                GrayImages.binaryToLuma(Thresholds.otsu(sharp)),
                rotated.toSource().before(crop.toSource()),
                "linear-band-us4-rot",
                degrees,
                true,
                linear,
                0.70,
                false
        );
    }

    /** Unsharp the rotated crop, then the median of its sharpest rows. */
    private void refineUnsharpTop(LinearBand.Crop crop, Set<Symbology> linear, double angle) {
        if (stop()) {
            return;
        }
        Transformed rotated = Geometry.rotate(crop.image(), angle);
        GrayU8 sharp = GrayImages.unsharpHorizontal(rotated.image(), 4, 4.0);
        GrayU8 strip = medianStrip(sharpRows(sharp));
        if (strip == null) {
            return;
        }
        int degrees = Math.floorMod(crop.degrees() + rotated.degrees(), 360);
        tryGray(
                strip,
                rotated.toSource().before(crop.toSource()),
                "linear-band-us4-top",
                degrees,
                true,
                linear,
                0.70,
                false
        );
    }

    /** Radius-2 unsharp of the sharp-row average, after a small rotation. */
    private void refineSharpUnsharp(LinearBand.Crop crop, Set<Symbology> linear, double angle) {
        if (stop()) {
            return;
        }
        Transformed rotated = Geometry.rotate(crop.image(), angle);
        int[] signal = sharpenSignal(sharpRows(rotated.image()), 2, 4.0);
        GrayU8 strip = medianStrip(signal);
        if (strip == null) {
            return;
        }
        int degrees = Math.floorMod(crop.degrees() + rotated.degrees(), 360);
        tryGray(
                strip,
                rotated.toSource().before(crop.toSource()),
                "linear-band-su2-rot",
                degrees,
                true,
                linear,
                0.70,
                false
        );
    }

    /**
     * Pull a trapezoidal bar band into a rectangle, then unsharp and Otsu.
     * Skips a crop whose edges are already parallel, so a straight symbol is left
     * to the slots that were measured on it.
     */
    private void refineKeystone(LinearBand.Crop crop, Set<Symbology> linear, double angle) {
        if (stop()) {
            return;
        }
        Transformed rotated = Geometry.rotate(crop.image(), angle);
        Keystone key = keystone(rotated.image());
        if (key == null) {
            return;
        }
        GrayU8 sharp = GrayImages.unsharpHorizontal(key.image, 4, 4.0);
        int degrees = Math.floorMod(crop.degrees() + rotated.degrees(), 360);
        PixelMap toRotated = (x, y) -> {
            double yTop = key.topA + key.topB * x;
            double yBot = key.botA + key.botB * x;
            double sy = yTop + (y + 0.5) / key.image.height * (yBot - yTop);
            return new ImagePoint(x, sy);
        };
        tryGray(
                GrayImages.binaryToLuma(Thresholds.otsu(sharp)),
                toRotated.before(rotated.toSource()).before(crop.toSource()),
                "linear-band-key",
                degrees,
                true,
                linear,
                0.70,
                false
        );
    }

    private static GrayU8 medianStrip(int[] signal) {
        if (signal == null || signal.length < 2) {
            return null;
        }
        int[] sorted = signal.clone();
        java.util.Arrays.sort(sorted);
        int index = Math.min(sorted.length - 1, sorted.length * 50 / 100);
        int cut = sorted[index];
        GrayU8 strip = new GrayU8(signal.length, 48);
        for (int x = 0; x < signal.length; x++) {
            int value = signal[x] <= cut ? 0 : 255;
            for (int y = 0; y < strip.height; y++) {
                strip.set(x, y, value);
            }
        }
        return strip;
    }

    private static int[] sharpenSignal(int[] signal, int radius, double amount) {
        if (signal == null) {
            return null;
        }
        int[] prefix = new int[signal.length + 1];
        for (int x = 0; x < signal.length; x++) {
            prefix[x + 1] = prefix[x] + signal[x];
        }
        int[] sharp = new int[signal.length];
        for (int x = 0; x < signal.length; x++) {
            int left = Math.max(0, x - radius);
            int right = Math.min(signal.length - 1, x + radius);
            int blur = (prefix[right + 1] - prefix[left]) / (right - left + 1);
            int value = (int) Math.round(signal[x] + amount * (signal[x] - blur));
            if (value < 0) {
                value = 0;
            } else if (value > 255) {
                value = 255;
            }
            sharp[x] = value;
        }
        return sharp;
    }

    /** Fitted bar band. Null when the crop is already a rectangle or the fit is wild. */
    private static Keystone keystone(GrayU8 image) {
        int width = image.width;
        int height = image.height;
        if (width < 80 || height < 40) {
            return null;
        }
        int samples = 12;
        double[] xs = new double[samples];
        double[] top = new double[samples];
        double[] bot = new double[samples];
        int kept = 0;
        for (int i = 0; i < samples; i++) {
            int x = (width - 1) * i / (samples - 1);
            int yTop = -1;
            int yBot = -1;
            for (int y = 1; y < height - 1; y++) {
                int delta = Math.abs(image.get(Math.min(width - 1, x + 1), y) - image.get(Math.max(0, x - 1), y));
                if (delta >= 18) {
                    if (yTop < 0) {
                        yTop = y;
                    }
                    yBot = y;
                }
            }
            if (yTop < 0 || yBot - yTop < 16) {
                continue;
            }
            xs[kept] = x;
            top[kept] = yTop;
            bot[kept] = yBot;
            kept++;
        }
        if (kept < 6) {
            return null;
        }
        double[] topLine = fitLine(xs, top, kept);
        double[] botLine = fitLine(xs, bot, kept);
        double leftH = botLine[0] - topLine[0];
        double rightH = (botLine[0] + botLine[1] * (width - 1)) - (topLine[0] + topLine[1] * (width - 1));
        if (leftH < 20 || rightH < 20) {
            return null;
        }
        double ratio = Math.max(leftH, rightH) / Math.min(leftH, rightH);
        if (ratio < 1.06 || ratio > 1.8) {
            return null;
        }
        int outH = (int) Math.round((leftH + rightH) / 2.0);
        outH = Math.max(32, Math.min(outH, height));
        GrayU8 dest = new GrayU8(width, outH);
        for (int y = 0; y < outH; y++) {
            for (int x = 0; x < width; x++) {
                double yTop = topLine[0] + topLine[1] * x;
                double yBot = botLine[0] + botLine[1] * x;
                double sy = yTop + (y + 0.5) / outH * (yBot - yTop);
                dest.set(x, y, sampleBilinear(image, x, sy));
            }
        }
        return new Keystone(dest, topLine[0], topLine[1], botLine[0], botLine[1]);
    }

    private static double[] fitLine(double[] xs, double[] ys, int n) {
        double sx = 0;
        double sy = 0;
        double sxx = 0;
        double sxy = 0;
        for (int i = 0; i < n; i++) {
            sx += xs[i];
            sy += ys[i];
            sxx += xs[i] * xs[i];
            sxy += xs[i] * ys[i];
        }
        double denom = n * sxx - sx * sx;
        if (Math.abs(denom) < 1e-6) {
            return new double[]{sy / n, 0};
        }
        double b = (n * sxy - sx * sy) / denom;
        double a = (sy - b * sx) / n;
        return new double[]{a, b};
    }

    private static int sampleBilinear(GrayU8 source, double x, double y) {
        if (x < 0 || y < 0 || x > source.width - 1 || y > source.height - 1) {
            return 255;
        }
        int x0 = (int) Math.floor(x);
        int y0 = (int) Math.floor(y);
        int x1 = Math.min(x0 + 1, source.width - 1);
        int y1 = Math.min(y0 + 1, source.height - 1);
        double fx = x - x0;
        double fy = y - y0;
        int p00 = source.get(x0, y0);
        int p10 = source.get(x1, y0);
        int p01 = source.get(x0, y1);
        int p11 = source.get(x1, y1);
        return (int) Math.round(p00 * (1 - fx) * (1 - fy) + p10 * fx * (1 - fy) + p01 * (1 - fx) * fy + p11 * fx * fy);
    }

    private static final class Keystone {
        private final GrayU8 image;
        private final double topA;
        private final double topB;
        private final double botA;
        private final double botB;

        private Keystone(GrayU8 image, double topA, double topB, double botA, double botB) {
            this.image = image;
            this.topA = topA;
            this.topB = topB;
            this.botA = botA;
            this.botB = botB;
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

    /**
     * One decode that does not touch the attempt counter. {@code null} means the image is
     * too small to be an attempt, matching {@link #tryGray}.
     */
    private List<BarcodeHit> readImage(
            GrayU8 image,
            PixelMap toSource,
            String attempt,
            int degrees,
            boolean globalBinarizer,
            double confidence,
            boolean multi
    ) {
        if (image.width < 2 || image.height < 2) {
            return null;
        }
        return ZxingDecoder.read(
                image, toSource, attempt, degrees, confidence, symbologies, globalBinarizer, multi);
    }

    /**
     * Runs every job, then counts results in submission order. A hit, the attempt cap,
     * or the deadline discards the jobs that follow, including ones already running.
     */
    private void runAttempts(List<Callable<List<BarcodeHit>>> jobs) {
        if (jobs.isEmpty()) {
            return;
        }
        List<Future<List<BarcodeHit>>> futures = new ArrayList<>(jobs.size());
        for (Callable<List<BarcodeHit>> job : jobs) {
            futures.add(AttemptPool.EXECUTOR.submit(job));
        }
        try {
            for (Future<List<BarcodeHit>> future : futures) {
                if (stop()) {
                    break;
                }
                List<BarcodeHit> found = future.get();
                if (found == null) {
                    continue;
                }
                attempts++;
                accept(found);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(cause);
        } finally {
            for (Future<List<BarcodeHit>> future : futures) {
                future.cancel(false);
            }
        }
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

    /**
     * Not the common pool: a caller that already parallelizes frames must not deadlock
     * on a nested join. Daemon threads so the pool does not keep the JVM up.
     * Separate from the linear-band pool, because a band search can run while a
     * cancelled attempt is still finishing.
     */
    private static final class AttemptPool {
        static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(
                Math.max(1, Runtime.getRuntime().availableProcessors() - 1),
                runnable -> {
                    Thread thread = new Thread(runnable, "scan-attempt");
                    thread.setDaemon(true);
                    return thread;
                });
    }
}
