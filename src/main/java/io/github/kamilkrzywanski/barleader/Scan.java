/*
 * Copyright 2026 Kamil Krzywański
 */
// SPDX-License-Identifier: Apache-2.0
package io.github.kamilkrzywanski.barleader;

import io.github.kamilkrzywanski.barleader.decode.HitCollector;
import io.github.kamilkrzywanski.barleader.decode.BitmapDecoder;
import io.github.kamilkrzywanski.barleader.decode.Code128Fit;
import io.github.kamilkrzywanski.barleader.decode.EanCrossCheck;
import io.github.kamilkrzywanski.barleader.decode.EanFit;
import io.github.kamilkrzywanski.barleader.decode.ProfileLines;
import io.github.kamilkrzywanski.barleader.decode.SourceCheck;
import io.github.kamilkrzywanski.barleader.retail.RetailEngine;
import io.github.kamilkrzywanski.barleader.decode.StripBands;
import io.github.kamilkrzywanski.barleader.decode.EanHalves;
import io.github.kamilkrzywanski.barleader.image.BinaryBitmap;
import io.github.kamilkrzywanski.barleader.image.BlackMatrix;
import io.github.kamilkrzywanski.barleader.image.ContentBounds;
import io.github.kamilkrzywanski.barleader.image.Geometry;
import io.github.kamilkrzywanski.barleader.image.GrayImages;
import io.github.kamilkrzywanski.barleader.image.GrayLuminanceSource;
import io.github.kamilkrzywanski.barleader.image.GrayU8;
import io.github.kamilkrzywanski.barleader.image.PixelMap;
import io.github.kamilkrzywanski.barleader.image.Transformed;
import io.github.kamilkrzywanski.barleader.locate.RegionLocalizer;
import io.github.kamilkrzywanski.barleader.locate.dm.AztecDetector;
import io.github.kamilkrzywanski.barleader.locate.dm.DataMatrixDetector;
import io.github.kamilkrzywanski.barleader.locate.dm.MicroQrDetector;
import io.github.kamilkrzywanski.barleader.locate.dm.QrDetector;
import io.github.kamilkrzywanski.barleader.locate.dm.TraceMatrix;
import io.github.kamilkrzywanski.barleader.preprocess.Thresholds;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Ordered attempts from the spec. Cheap ZXing calls run first so a clean symbol
 * does not pay for thresholds, rotations, or detectors.
 */
final class Scan {
    private final ReadOptions options;
    private final Set<Symbology> symbologies;
    /** Set by {@link #run(GrayU8, GrayU8)}, which knows the image the corners are in. */
    private HitCollector hits = new HitCollector();
    private final RegionLocalizer localizer = new RegionLocalizer();
    private final Fanout fanout;
    private final long startNanos;
    /** {@code Long.MAX_VALUE} when the read has no time budget. */
    private final long budgetNanos;
    private int attempts;
    /**
     * Attempts that fast mode's steps after the variants took in aggressive mode, which the
     * aggressive steps after them get on top of their own budget.
     */
    private int lent;
    /** A read of the candidates first that waits for the variants of the whole frame ({@link #confirmedEarly}). */
    private HitCollector held;
    /** Crops from the band search, reused by the late unsharp that sits in the gaussian slot. */
    private List<LinearBand.Crop> bandCrops = List.of();
    /** The colour image read, whose brightest and darkest channels fast mode reads last ({@link #channelReads}). */
    private BufferedImage colour;
    /** Whether {@link #bandCrops} are the crops of {@link BarSegments}, which {@link Deblur} sharpens. */
    private boolean segmentCrops;
    /** Frames of at least this many pixels are read where they hold anything ({@link ContentBounds}). */
    private static final long CROP_PIXELS = 20_000_000L;
    /** Fast mode reads frames of at least this many pixels on a reduced copy first. */
    private static final long REDUCE_PIXELS = 20_000_000L;
    /** About the pixels of that reduced copy. */
    private static final long REDUCED_PIXELS = 8_000_000L;
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
    /** Strips of {@link BarStrips} that {@link Code128Fit} reads, the most energy first. */
    private static final int MAX_FIT_STRIPS = 8;
    /**
     * Strips of {@link BarStrips} that {@link EanFit} reads, without findAll and with it. Each of the
     * 69 snapshot photos {@code ean-fit} read in fast mode read on the first or the second strip;
     * with findAll, where a photo holds several symbols, two more read on the third and the fourth.
     */
    private static final int MAX_EAN_FIT_STRIPS = 2;
    private static final int MAX_EAN_FIT_STRIPS_ALL = 4;
    /**
     * Strips of {@link BarStrips} that the linear readers read, the most energy first. Each is an
     * attempt: with eight, the thresholded band crops after them lost 20 of BarBeR's codes to the
     * forty attempts of fast mode, with four 5, and the eight read one code more.
     */
    private static final int MAX_READ_STRIPS = 4;
    /** White pixels around a crop of {@link BarSegments} read again by {@link #rescueJobs}. */
    private static final int CROP_MARGIN = 8;
    /** A crop at least this long on one side is located again by {@link #rescueJobs}. */
    private static final int RELOCATE_SIDE = 360;
    /** Crops {@link BarSegments} finds inside such a crop. */
    private static final int RELOCATE_CROPS = 3;
    /** How many times as long {@link #rescueJobs} samples a crop again from the frame. */
    private static final double LENGTHEN = 1.8;
    /** Symbologies whose readers read the rows of a bitmap and never its black matrix. */
    private static final Set<Symbology> ROW_READERS = EnumSet.of(Symbology.EAN_13, Symbology.EAN_8, Symbology.UPC_A,
            Symbology.UPC_E, Symbology.CODE_39, Symbology.CODE_93, Symbology.CODE_128, Symbology.ITF, Symbology.CODABAR);
    /** {@link BarStrips} of the image, found once for the readers and for {@link Code128Fit}. */
    private List<LinearBand.Crop> barStrips;
    /** The profiles of {@link #barStrips} the fits share ({@link #stripBands}). */
    private StripBands[] stripBands;
    /** The crops of {@link BarSegments}, found once ({@link #segments}). */
    private List<LinearBand.Crop> segments;
    /** The matrix of the 2D traces ({@link #trace}), and the image it is of. */
    private TraceMatrix trace;
    private GrayU8 traced;
    /** Whether {@link #matrixEvidence} ran, and what it found. */
    private boolean evidence;
    private boolean qrEvidence;
    private boolean aztecEvidence;
    /** The PDF417 start and stop patterns of the frame, as x and y ({@link #pdf417Shown}). */
    private List<int[]> pdf417Guards = List.of();
    /** Where the linear readers of the whole-frame variants read ({@link #linearRegions}). */
    private volatile List<double[]> linearRegions;
    /** The attempts of the step this thread runs as a job of aggressive mode's own steps ({@link #readTailSideBySide}), or null. */
    private final ThreadLocal<StepLog> logged = new ThreadLocal<>();
    /** Set once those steps are taken no further: a step still running then makes no more attempts. */
    private volatile boolean tailStopped;
    /** How many attempts each of those steps logged once it finished, -1 before; and how many were left when they started. */
    private java.util.concurrent.atomic.AtomicIntegerArray tailCounts;
    private int tailBudget;
    private int segmentLimit;
    /** The frame the regions are in, and how far from its edges a row runs along them ({@link #alongBorder}). */
    private int frameWidth;
    private int frameHeight;
    private double border;
    /** The band along the edges of the frame, as a share of its shorter side, and at least in pixels. */
    private static final double BORDER_SHARE = 0.05;
    private static final double BORDER_MIN = 32;
    /** Rows filled on each side of a row a turned variant reads ({@link #filledRows}). */
    private static final int FILL_MARGIN = 16;
    /** How far, as a share of its longer side, a region of the locators is widened. */
    private static final double REGION_MARGIN = 0.1;

    Scan(ReadOptions options, Fanout fanout) {
        this.options = options;
        this.symbologies = options.symbologies();
        this.fanout = fanout;
        this.startNanos = System.nanoTime();
        long millis = options.timeBudgetMillis();
        this.budgetNanos = millis >= Long.MAX_VALUE / 1_000_000L ? Long.MAX_VALUE : millis * 1_000_000L;
    }

    List<BarcodeHit> run(BufferedImage image) {
        if (image.getWidth() < 2 || image.getHeight() < 2) {
            return List.of();
        }
        GrayImages.Converted converted = GrayImages.convert(image, true, fanout::runAll);
        colour = image;
        return run(converted.gray(), converted.zxing());
    }

    /**
     * @param gray     what the ladder reads
     * @param original what the first two attempts read: ZXing's own luminance of a colour image,
     *                 or {@code gray} itself for luminance input. Nothing writes to either.
     */
    List<BarcodeHit> run(GrayU8 gray, GrayU8 original) {
        if (gray.width < 2 || gray.height < 2) {
            return List.of();
        }
        // A photo pasted on a blank 100-megapixel canvas was read whole: every variant built and
        // thresholded the blank too, fast mode ran out of time before reading it, and the parallel
        // variants of aggressive mode held gigabytes. A very large frame is read where it holds
        // anything, and the corners moved back.
        int[] box = (long) gray.width * gray.height >= CROP_PIXELS ? ContentBounds.find(gray) : null;
        GrayU8 work = gray;
        GrayU8 workOriginal = original;
        if (box != null || pixels(gray) >= REDUCE_PIXELS) {
            // The colour channels are in the coordinates of the whole frame.
            colour = null;
        }
        if (box != null) {
            work = ContentBounds.crop(gray, box);
            workOriginal = original == gray ? work : ContentBounds.crop(original, box);
        }
        List<BarcodeHit> found = null;
        long pixels = (long) work.width * work.height;
        // A frame still this large holds symbols whose modules span many pixels, past the windows
        // of the thresholds: an EAN-8 upsampled to 48 megapixels read on every reduced copy and on
        // none of the full frame. Fast mode first reads a box-averaged copy of about eight
        // megapixels, then the full frame when that read nothing. With findAll the full frame is
        // read, as a reduced copy can lose a small second symbol.
        if (pixels >= REDUCE_PIXELS && options.mode() != DecodeMode.AGGRESSIVE && !options.findAll()) {
            int factor = (int) Math.ceil(Math.sqrt(pixels / (double) REDUCED_PIXELS));
            Transformed small = Geometry.boxDownscaled(work, factor);
            List<BarcodeHit> reduced = new ArrayList<>(new Scan(options.timeBudgetMillis(remainingMillis()), fanout)
                    .runWhole(small.image(), small.image()));
            // The four-state bars of a Japan Post code, halved and halved again, read as the
            // EAN-13 6111111111111; such a read sends the frame back to full resolution.
            reduced.removeIf(Scan::repetitiveRetail);
            if (!reduced.isEmpty()) {
                found = moved(reduced, small.toSource(), 0, 0);
            }
        }
        if (found == null) {
            found = runWhole(work, workOriginal);
        }
        return box == null ? found : moved(found, PixelMap.IDENTITY, box[0], box[1]);
    }

    /** {@code hits} with their corners mapped by {@code map} and shifted by ({@code dx}, {@code dy}). */
    private static List<BarcodeHit> moved(List<BarcodeHit> hits, PixelMap map, int dx, int dy) {
        List<BarcodeHit> out = new ArrayList<>(hits.size());
        for (BarcodeHit hit : hits) {
            List<ImagePoint> corners = new ArrayList<>(hit.corners().size());
            for (ImagePoint point : hit.corners()) {
                ImagePoint p = map.toSource(point.x(), point.y());
                corners.add(new ImagePoint(p.x() + dx, p.y() + dy));
            }
            out.add(new BarcodeHit(hit.text(), hit.symbology(), corners, hit.orientationDegrees(),
                    hit.confidence(), hit.attempt(), hit.bytes(), hit.addOn()));
        }
        return out;
    }

    /** An EAN-13 or UPC-A with one digit five times or more in either half. */
    private static boolean repetitiveRetail(BarcodeHit hit) {
        if (hit.symbology() != Symbology.EAN_13 && hit.symbology() != Symbology.UPC_A) {
            return false;
        }
        String text = hit.text().length() == 12 ? "0" + hit.text() : hit.text();
        for (String half : new String[]{text.substring(1, 7), text.substring(7)}) {
            int[] count = new int[10];
            for (int i = 0; i < half.length(); i++) {
                if (++count[half.charAt(i) - '0'] >= 5) {
                    return true;
                }
            }
        }
        return false;
    }

    private static long pixels(GrayU8 gray) {
        return (long) gray.width * gray.height;
    }

    /**
     * The brightest and the darkest channel of the colour image read, built the first time a
     * step asks for them, or null for a gray image or a frame read in parts.
     */
    private long remainingMillis() {
        if (budgetNanos == Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        return Math.max(1, (budgetNanos - (System.nanoTime() - startNanos)) / 1_000_000L);
    }

    private List<BarcodeHit> runWhole(GrayU8 gray, GrayU8 original) {
        hits = new HitCollector(gray);
        tryOriginals(new GrayLuminanceSource(original));
        return ladder(gray);
    }

    private void tryOriginals(GrayLuminanceSource original) {
        tryOriginal(original, false, "original-hybrid", 0.92, options.findAll(), symbologies);
        // The global binarizer changes only the black matrix. The other linear readers threshold
        // each row on its own histogram whichever binarizer an attempt has, so on the same pixels
        // they read again what the hybrid attempt, which ran them all, read or missed.
        Set<Symbology> matrix = EnumSet.copyOf(symbologies);
        matrix.removeAll(ROW_READERS);
        tryOriginal(original, true, "original-global", 0.84, false, matrix);
    }

    private List<BarcodeHit> ladder(GrayU8 gray) {
        // A hit on the two buffered attempts used to still build every later image and
        // then throw the decode away. Those images are independent, so a miss runs them
        // together and keeps the earliest hit in this same order.
        Set<Symbology> variants = variantFormats();
        // Without findAll the symbols the linear locators found are read first, as tapirscan
        // reads its candidates before anything else: the patches of BarStrips, the crops of
        // BarSegments and their rescue. Only a miss there builds the variants of the whole frame,
        // and these reads lend the variants the attempts they took. A read the frame does not
        // confirm waits: the variants and the traces come first, as before, and it counts only
        // when they read nothing.
        boolean early = !options.findAll() && !stop() && !variants.isEmpty();
        if (early) {
            linearRegions(gray);
            int spent = attempts;
            readCandidates(gray);
            lent += attempts - spent;
            if (!hits.isEmpty() && !confirmedEarly(hits.list().get(0), gray)) {
                held = hits;
                hits = new HitCollector(gray);
            }
        }
        if (options.mode() == DecodeMode.QUICK) {
            // The shortest ladder ends with the candidates. With findAll they have not been read yet.
            if (!early && !stop() && !variants.isEmpty()) {
                linearRegions(gray);
                readCandidates(gray);
            }
            // A read that waited for the variants has nothing left to wait for.
            if (held != null && hits.isEmpty()) {
                hits = held;
            }
            return hits.list();
        }
        if (!stop() && !variants.isEmpty()) {
            // The linear readers of the variants read only the rows across a symbol the linear
            // locators found, and the matrix readers only on a frame that shows their finders:
            // both are found side by side, before the variants run in parallel.
            // The turned variants below read four of these images again: each is built once.
            AtomicReferenceArray<Object> built = new AtomicReferenceArray<>(4);
            fanout.runAll(List.of(() -> linearRegions(gray), () -> matrixEvidence(gray, built)));
            Set<Symbology> read = gated(variants);
            boolean readVariants = !read.isEmpty();
            List<Callable<List<BarcodeHit>>> jobs = new ArrayList<>();
            // With findAll every variant returns all it reads, as the original does: on a label
            // with a Data Matrix and a PDF417 one symbol a variant would always be the Data Matrix.
            boolean all = options.findAll();
            if (readVariants) {
                jobs.add(() -> readVariant(GrayImages.invert(gray), PixelMap.IDENTITY, "inverted-luma", 0, false, read, 0.86, all));
                jobs.add(() -> {
                    Transformed shifted = Geometry.pad(gray, 4);
                    return readVariant(shifted.image(), shifted.toSource(), "pad-4", 0, false, read, 0.82, all);
                });
            }
            GrayU8 stretched = readVariants ? GrayImages.stretch(gray) : gray;
            if (stretched != gray) {
                jobs.add(() -> readVariant(stretched, PixelMap.IDENTITY, "contrast-stretch", 0, false, read, 0.74, all));
            }
            if (readVariants) {
                jobs.add(() -> readVariant(
                        built(built, 0, () -> GrayImages.flattenIllumination(gray)), PixelMap.IDENTITY, "illumination", 0, false, read, 0.74, all));
            }
            Set<Symbology> turned = gated(rotationFormats());
            for (int angle : turned.isEmpty() ? new int[0] : new int[]{90, 180, 270}) {
                jobs.add(() -> {
                    Transformed rotated = Geometry.rotateOrthogonal(gray, angle,
                            filledRows(turned, Geometry.orthogonalMap(gray.width, gray.height, angle), gray, angle));
                    return readVariant(rotated.image(), rotated.toSource(), "rotate-" + angle, angle, false,
                            turned, 0.80, options.findAll());
                });
            }
            if (readVariants) {
                jobs.add(() -> readVariant(
                        built(built, 1, () -> GrayImages.binaryToLuma(Thresholds.mean(gray))),
                        PixelMap.IDENTITY, "adaptive-mean", 0, true, read, 0.68, all));
                jobs.add(() -> readVariant(
                        built(built, 2, () -> GrayImages.binaryToLuma(Thresholds.otsu(gray))),
                        PixelMap.IDENTITY, "otsu", 0, true, read, 0.68, all));
            }
            if (readVariants && Math.min(gray.width, gray.height) <= 900 && Geometry.withinPixelBudget(gray.width, gray.height, 2.0)) {
                jobs.add(() -> {
                    Transformed scaled = Geometry.scale(gray, 2.0);
                    return readVariant(scaled.image(), scaled.toSource(), "scale-2", 0, false, read, 0.80, all);
                });
            }
            // The other way round for a large photo: a symbol blurred over several pixels reads
            // once a box average has shrunk the blur. On BarBeR this reads 47 more labelled codes
            // in fast mode, EAN-13 on Muenster and ZVZ, ten ZVZ PDF417 and a few QR codes; the
            // pyramid of aggressive mode, by threes, reads few of them. Halving starts at a shorter
            // side of 800, so the 1152×864 Arte-Lab frames get it too: 24 more labelled EAN-13 in
            // fast mode, and nothing that read before is lost.
            int halving = 800;
            for (int factor : new int[]{2, 4}) {
                if (readVariants && Math.min(gray.width, gray.height) >= (factor == 2 ? halving : 1400)) {
                    jobs.add(() -> {
                        Transformed smaller = factor == 2
                                ? built(built, 3, () -> Geometry.boxDownscaled(gray, 2))
                                : Geometry.boxDownscaled(gray, factor);
                        return readVariant(smaller.image(), smaller.toSource(), "downscale-" + factor, 0, false,
                                read, 0.78, all);
                    });
                }
            }
            runAttempts(jobs);
            // The variants scan rows only, and rotate-90 turns only the original: a vertical symbol
            // that needs a threshold, the illumination or the halved frame was lost. Turned by 90
            // degrees as well, for the linear formats, they read 57 more snapshot images in fast
            // mode, and of 930 photos turned by 90 degrees 827 read instead of 805. They come last,
            // and like the fits they take no attempt slot, so the steps after them keep theirs.
            Set<Symbology> across = gated(rotationFormats());
            if (!across.isEmpty() && (hits.isEmpty() || all) && !outOfTime()) {
                List<Callable<List<BarcodeHit>>> crossed = new ArrayList<>();
                if (stretched != gray) {
                    crossed.add(() -> readTurned(stretched, PixelMap.IDENTITY, "contrast-stretch-90", false, across, 0.72, all));
                }
                crossed.add(() -> readTurned(built(built, 0, () -> GrayImages.flattenIllumination(gray)), PixelMap.IDENTITY, "illumination-90", false, across, 0.72, all));
                crossed.add(() -> readTurned(built(built, 1, () -> GrayImages.binaryToLuma(Thresholds.mean(gray))), PixelMap.IDENTITY, "adaptive-mean-90", true, across, 0.66, all));
                crossed.add(() -> readTurned(built(built, 2, () -> GrayImages.binaryToLuma(Thresholds.otsu(gray))), PixelMap.IDENTITY, "otsu-90", true, across, 0.66, all));
                if (Math.min(gray.width, gray.height) >= halving) {
                    crossed.add(() -> {
                        Transformed smaller = built(built, 3, () -> Geometry.boxDownscaled(gray, 2));
                        return readTurned(smaller.image(), smaller.toSource(), "downscale-2-90", false, across, 0.76, all);
                    });
                }
                fanout.inOrder(crossed, () -> outOfTime() || !all && !hits.isEmpty(), found -> {
                    if (found != null) {
                        accept(found);
                    }
                });
            }
        }
        // A miss of the variants still looks for tilted linear strips: first the patches of bars
        // of BarStrips at their own angle, then the crops of BarSegments, then the fits to the
        // gray profile, EAN halves and the colour channels. Aggressive mode takes these steps too,
        // before its own, and its own get the attempts these took on top of their budget: on the
        // snapshot images it read fewer KAIST and BarBeR photos than fast mode, and on the public
        // tapirscan benchmark it missed 48 turned and 28 vertical photos fast mode read.
        // Read first, the candidates leave these steps independent of each other: they then run
        // side by side (readMissed).
        boolean together = early && hits.isEmpty();
        if (together) {
            readMissed(gray);
        } else {
            runTraces(gray);
            if (held != null && hits.isEmpty()) {
                hits = held;
            }
        }
        int before = attempts;
        if (!together) {
            if ((hits.isEmpty() || options.findAll()) && !stop()) {
                if (!early) {
                    readCandidates(gray);
                }
                tryDeblurredBands(gray);
            }
            tryCode128Fit(gray);
            tryEanFit(gray);
            tryEanHalves(gray);
            tryColourChannels();
        }
        if (options.mode() == DecodeMode.FAST) {
            return hits.list();
        }
        lent += attempts - before;
        if (stop()) {
            return hits.list();
        }

        // Angle recovery before thresholds: a 30°–50° linear symbol is one rotation away,
        // and the Sauvola / quad work below is much more expensive.
        // The four rotations do not depend on each other. Results are still applied from
        // -15 upward, so a hit on an earlier angle still hides a later one.
        Set<Symbology> tilted = rotationFormats();
        if (!stop() && !tilted.isEmpty()) {
            List<Callable<List<BarcodeHit>>> angles = new ArrayList<>();
            for (double angle : new double[]{-15.0, 15.0, -30.0, 30.0}) {
                if (!Geometry.rotationFits(gray.width, gray.height, angle)) {
                    continue;
                }
                double degrees = angle;
                angles.add(() -> {
                    Transformed rotated = Geometry.rotate(gray, degrees);
                    return readImage(rotated.image(), rotated.toSource(), "rotate-" + degrees, rotated.degrees(), false,
                            tilted, 0.72, false);
                });
            }
            runAttempts(angles);
        }
        if (fanout.parallel()) {
            readTailSideBySide(gray);
        } else {
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
            // The images below are built only while the budget lasts: each read stops on an empty
            // budget anyway, and on a large frame building them took seconds past it.
            GrayU8 sauvola = null;
            for (int i = 0; i < 2 && !stop(); i++) {
                GrayU8 image = Thresholds.sauvola(gray, i);
                if (image == null) {
                    break;
                }
                sauvola = i == 0 ? image : sauvola;
                tryBinary(image, "sauvola-" + i, false, 0.68);
            }
            if (!stop() && Geometry.withinPixelBudget(gray.width, gray.height, 1.5)) {
                Transformed scaled = Geometry.scale(gray, 1.5);
                tryGray(scaled.image(), scaled.toSource(), "scale-1.5", 0, false, false, 0.80, false);
            }
            // On the fullest 1152×864 miss this pass is attempt 100 and still runs
            // (Foto(799), Foto(848)). One more decode in front of it would drop both.
            // The next slot used to be a full-frame gaussian, which recovered nothing
            // labeled. It now sharpens the first band crop instead. Morphology still waits.
            if (!stop() && gray.width * (long) gray.height > 800L * 800L && Geometry.withinPixelBudget(gray.width, gray.height, 0.5)) {
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
            if (!stop() && sauvola != null) {
                int index = 0;
                for (GrayU8 variant : Thresholds.morphology(sauvola)) {
                    tryBinary(variant, "morph-" + index, true, 0.64);
                    index++;
                }
            }
            locate(gray, false, true);
        }
        if ((hits.isEmpty() || options.findAll()) && !stop()) {
            GrayU8 inverted = GrayImages.invert(gray);
            runTraces(inverted);
        }
        return hits.list();
    }

    /**
     * The steps of aggressive mode from the linear bands to the localizer, side by side: each step
     * runs as a job that logs its attempts instead of counting them, as if nothing stopped it, and
     * the calling thread replays the logs in the order of one after another, counting each attempt
     * and taking its reads while the ladder goes on. A step's choices depend on nothing but whether
     * the ladder stopped, and once stopped it stays so, so the same attempts count with the same
     * reads. The bands are found first, as later steps read their crops.
     */
    private void readTailSideBySide(GrayU8 gray) {
        loggedSteps = 0;
        tailStopped = false;
        List<Callable<List<Logged>>> steps = new ArrayList<>();
        Set<Symbology> bandFormats = linearFormats();
        if ((hits.isEmpty() || options.findAll()) && !stop() && !bandFormats.isEmpty()) {
            boolean extra = gray.width * (long) gray.height <= BAND_EXTRA_PIXELS;
            List<LinearBand.Crop> crops = LinearBand.find(gray, fanout);
            bandCrops = crops;
            segmentCrops = false;
            for (int index = 0; index < crops.size(); index++) {
                LinearBand.Crop crop = crops.get(index);
                int at = index;
                steps.add(logging(() -> readBandCrop(crop, at, bandFormats, extra)));
            }
        }
        steps.add(logging(() -> tryDownscale(gray)));
        steps.add(logging(() -> tryTiles(gray)));
        Once<GrayU8> firstSauvola = new Once<>(() -> Thresholds.sauvola(gray, 0));
        steps.add(logging(() -> {
            for (int i = 0; i < 2 && !stop(); i++) {
                GrayU8 image = i == 0 ? firstSauvola.get() : Thresholds.sauvola(gray, i);
                if (image == null) {
                    break;
                }
                tryBinary(image, "sauvola-" + i, false, 0.68);
            }
        }));
        steps.add(logging(() -> {
            if (!stop() && Geometry.withinPixelBudget(gray.width, gray.height, 1.5)) {
                Transformed scaled = Geometry.scale(gray, 1.5);
                tryGray(scaled.image(), scaled.toSource(), "scale-1.5", 0, false, false, 0.80, false);
            }
        }));
        steps.add(logging(() -> {
            if (!stop() && gray.width * (long) gray.height > 800L * 800L && Geometry.withinPixelBudget(gray.width, gray.height, 0.5)) {
                Transformed scaled = Geometry.scale(gray, 0.5);
                tryGray(scaled.image(), scaled.toSource(), "scale-0.5", 0, false, false, 0.80, false);
            }
        }));
        steps.add(logging(() -> {
            if (!stop()) {
                Set<Symbology> linear = linearFormats();
                if (!options.findAll() && !bandCrops.isEmpty() && !linear.isEmpty()) {
                    LinearBand.Crop crop = bandCrops.get(0);
                    GrayU8 sharp = GrayImages.unsharpHorizontal(crop.image(), 4, 4.0);
                    tryGray(GrayImages.binaryToLuma(Thresholds.otsu(sharp)), crop.toSource(), "linear-band-us4-otsu", crop.degrees(),
                            true, linear, 0.70, false);
                } else {
                    tryBinary(Thresholds.gaussian(gray), "adaptive-gaussian", false, 0.68);
                }
            }
        }));
        steps.add(logging(() -> {
            if (!stop()) {
                refineMissedBands(linearFormats());
            }
        }));
        steps.add(logging(() -> {
            GrayU8 sauvola = firstSauvola.get();
            if (!stop() && sauvola != null) {
                int index = 0;
                for (GrayU8 variant : Thresholds.morphology(sauvola)) {
                    tryBinary(variant, "morph-" + index, true, 0.64);
                    index++;
                }
            }
        }));
        steps.add(logging(() -> locate(gray, false, true)));
        tailCounts = new java.util.concurrent.atomic.AtomicIntegerArray(steps.size());
        for (int i = 0; i < steps.size(); i++) {
            tailCounts.set(i, -1);
        }
        tailBudget = options.maxAttempts() + lent - attempts;
        fanout.inOrder(steps, () -> {
            boolean stopped = stop();
            tailStopped |= stopped;
            return stopped;
        }, this::replay);
        tailStopped = true;
    }

    /** {@code step} as a job that logs its attempts on its own thread; the jobs are numbered in the order they are made. */
    private Callable<List<Logged>> logging(Runnable step) {
        int index = loggedSteps++;
        return () -> {
            StepLog log = new StepLog(index);
            logged.set(log);
            try {
                step.run();
            } finally {
                logged.remove();
            }
            tailCounts.set(index, log.entries.size());
            return log.entries;
        };
    }

    private int loggedSteps;

    /**
     * Whether no further attempt of {@code log}'s step can count: the attempts the steps before it
     * that finished logged, and its own, already use up the attempts left. A step before it still
     * running only adds to them.
     */
    private boolean beyondBudget(StepLog log) {
        int before = 0;
        for (int i = 0; i < log.step; i++) {
            before += Math.max(0, tailCounts.get(i));
        }
        return before + log.entries.size() >= tailBudget;
    }

    /** The attempts one step of {@link #readTailSideBySide} logged. */
    private static final class StepLog {
        final int step;
        final List<Logged> entries = new ArrayList<>();

        StepLog(int step) {
            this.step = step;
        }
    }

    /** The attempts of a logged step, counted and taken as they were made, until the ladder stops. */
    private void replay(List<Logged> log) {
        for (Logged entry : log) {
            if (stop()) {
                tailStopped = true;
                return;
            }
            attempts++;
            switch (entry.kind()) {
                case Logged.READ -> accept(entry.read());
                case Logged.BAND -> acceptBand(entry.read());
                default -> {
                    if (entry.hit() != null) {
                        hits.add(entry.hit());
                    }
                }
            }
        }
    }

    /**
     * One attempt a logged step made: what {@link #tryGray} read, what {@link #tryBand} read, or a
     * hit the localizer decoded or the correlator found, null when it found none.
     */
    private record Logged(int kind, List<BarcodeHit> read, BarcodeHit hit) {
        static final int READ = 0;
        static final int BAND = 1;
        static final int HIT = 2;
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
        List<LinearBand.Crop> crops = LinearBand.find(gray, fanout);
        bandCrops = crops;
        segmentCrops = false;
        for (int index = 0; index < crops.size(); index++) {
            if (stop()) {
                return;
            }
            readBandCrop(crops.get(index), index, formats, extra);
        }
    }

    /** The reads of {@link #tryLinearBands} on crop {@code index}, until one stops the ladder. */
    private void readBandCrop(LinearBand.Crop crop, int index, Set<Symbology> formats, boolean extra) {
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
            tryBand(
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
        tryBand(sharp, crop.toSource(), "linear-band-unsharp", crop.degrees(), false, formats, 0.70, false);
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
        tryBand(
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

    /**
     * The band crops of {@link LinearBand#find(GrayU8, Fanout, boolean)} at its coarse grid, each read
     * as it is and stretched. On all of BarBeR's labelled codes this reads 3,909 instead of 3,787 in
     * fast mode, and Arte-Lab 287 instead of 248 of 430, with no new text that matches no label. A
     * photo without a code then takes about twice as long, still under a third of aggressive mode.
     *
     * <p>When every crop misses, each is read again through Otsu and a local mean, for the longer
     * symbologies only: 48 more of BarBeR's labels, one wrong EAN-13. With UPC-E and EAN-8 these
     * reads had returned six wrong texts in 69, mostly a short code inside a longer one, so those
     * two stay with aggressive mode.
     */
    /**
     * Code 128 with modules of about a pixel, fitted on the strips of {@link BarStrips}, as the last
     * step of a read that found nothing, or with findAll also beside what it found, such as a
     * parcel's order number. It decodes no variant of the image, so the attempt budget
     * does not stop it and it takes no attempt from the ladder: on BarBeR's ParcelBar photos the
     * band reads of fast mode use up all forty. The time budget still stops it.
     */
    private void tryCode128Fit(GrayU8 gray) {
        if (!hits.isEmpty() && !options.findAll() || !symbologies.contains(Symbology.CODE_128) || outOfTime()) {
            return;
        }
        fanout.inOrder(code128Fits(gray, List.copyOf(hits.list())), () -> outOfTime() || !options.findAll() && !hits.isEmpty(),
                this::added);
    }

    /** {@link #tryCode128Fit} on each strip, side by side, a fit beside {@code before} only as {@link #besideHits} lets it. */
    private List<Callable<BarcodeHit>> code128Fits(GrayU8 gray, List<BarcodeHit> before) {
        if (noCrops(gray)) {
            return List.of();
        }
        List<LinearBand.Crop> strips = barStrips(gray);
        List<Callable<BarcodeHit>> fits = new ArrayList<>(strips.size());
        for (int i = 0; i < strips.size(); i++) {
            int index = i;
            fits.add(() -> {
                Code128Fit.Found found = Code128Fit.read(stripBands(index), within());
                return found == null || !before.isEmpty() && !besideHits(found.text(), before)
                        ? null : code128Hit(strips.get(index), found);
            });
        }
        return fits;
    }

    /** What runs the pieces of one job side by side on the reader's pool, or null for one after another. */
    private java.util.function.Consumer<List<Runnable>> within() {
        return fanout.parallel() ? fanout::runAll : null;
    }

    private static BarcodeHit code128Hit(LinearBand.Crop strip, Code128Fit.Found found) {
        PixelMap map = strip.toSource();
        return new BarcodeHit(
                found.text(),
                Symbology.CODE_128,
                List.of(map.toSource(found.left(), found.y()), map.toSource(found.right(), found.y())),
                Math.floorMod(strip.degrees() + (found.upsideDown() ? 180 : 0), 360),
                0.60,
                "code128-fit"
        );
    }

    /**
     * A fit beside codes already read counts only with six characters or more and when it is not
     * part of one of them: there the strips also cross the codes read, and textures beside them
     * fitted two or three characters.
     */
    private static boolean besideHits(String text, List<BarcodeHit> before) {
        if (text.length() < 6) {
            return false;
        }
        for (BarcodeHit hit : before) {
            if (hit.text().contains(text)) {
                return false;
            }
        }
        return true;
    }

    /**
     * EAN-13 and UPC-A with modules of about a pixel, fitted on the strips of {@link BarStrips} as
     * {@link #tryCode128Fit} fits Code 128, as the last step of a read that found nothing.
     */
    private void tryEanFit(GrayU8 gray) {
        boolean ean13 = symbologies.contains(Symbology.EAN_13);
        boolean upcA = symbologies.contains(Symbology.UPC_A);
        // Unlike Code 128 not beside other hits with findAll: on shelf photos with many EAN-13
        // the strips beside the codes already read fitted random digits.
        if (!hits.isEmpty() || !ean13 && !upcA || outOfTime()) {
            return;
        }
        fanout.inOrder(eanFits(gray), () -> outOfTime() || !options.findAll() && !hits.isEmpty(), this::added);
    }

    /** {@link #tryEanFit} on each of the first strips ({@link #MAX_EAN_FIT_STRIPS}), side by side. */
    private List<Callable<BarcodeHit>> eanFits(GrayU8 gray) {
        if (noCrops(gray)) {
            return List.of();
        }
        List<LinearBand.Crop> strips = barStrips(gray);
        int limit = Math.min(strips.size(), options.findAll() ? MAX_EAN_FIT_STRIPS_ALL : MAX_EAN_FIT_STRIPS);
        List<Callable<BarcodeHit>> fits = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            int index = i;
            fits.add(() -> eanFitHit(strips.get(index), EanFit.read(stripBands(index), within())));
        }
        return fits;
    }

    /** {@code found} on {@code strip} as an EAN-13 or a UPC-A asked for, or null. */
    private BarcodeHit eanFitHit(LinearBand.Crop strip, EanFit.Found found) {
        if (found == null) {
            return null;
        }
        Symbology symbology = Symbology.EAN_13;
        String text = found.text();
        if (text.charAt(0) == '0' && symbologies.contains(Symbology.UPC_A)) {
            symbology = Symbology.UPC_A;
            text = text.substring(1);
        } else if (!symbologies.contains(Symbology.EAN_13)) {
            return null;
        }
        PixelMap map = strip.toSource();
        return new BarcodeHit(
                text,
                symbology,
                List.of(map.toSource(found.left(), found.y()), map.toSource(found.right(), found.y())),
                Math.floorMod(strip.degrees() + (found.upsideDown() ? 180 : 0), 360),
                0.60,
                "ean-fit"
        );
    }

    /**
     * Whether {@link BarSegments} found no crop on the frame: the fits and the EAN halves then do
     * not run. Every one of the 448 snapshot photos these steps read in fast mode had a crop, and of
     * KAIST's 788 photos without a barcode 300 have none. Not with the postal codes, which the
     * segment voting does not find.
     */
    private boolean noCrops(GrayU8 gray) {
        return !postal() && segments(gray, options.findAll() ? 16 : 8).isEmpty();
    }

    private List<LinearBand.Crop> barStrips(GrayU8 gray) {
        if (barStrips == null) {
            List<LinearBand.Crop> strips = BarStrips.find(gray, MAX_FIT_STRIPS, fanout);
            StripBands[] bands = new StripBands[strips.size()];
            for (int i = 0; i < bands.length; i++) {
                bands[i] = new StripBands(strips.get(i).image());
            }
            stripBands = bands;
            barStrips = strips;
        }
        return barStrips;
    }

    /** The profiles of strip {@code index} of {@link #barStrips}, which both fits read. */
    private StripBands stripBands(int index) {
        return stripBands[index];
    }

    /** How far, as a share of its length, a band read may lie from a read code's line to be that code. */
    private static final double ON_LINE = 0.1;

    /**
     * Whether the middle of a linear band read lies on the scan line of a linear code already read
     * with other content: a second, wrong decode of that same symbol from a crop across it.
     */
    private boolean onReadLine(BarcodeHit hit) {
        if (hit.symbology().twoDimensional() || hit.corners().isEmpty()) {
            return false;
        }
        double cx = 0;
        double cy = 0;
        for (ImagePoint point : hit.corners()) {
            cx += point.x();
            cy += point.y();
        }
        cx /= hit.corners().size();
        cy /= hit.corners().size();
        for (BarcodeHit other : hits.list()) {
            List<ImagePoint> line = other.corners();
            if (other.symbology().twoDimensional() || line.size() < 2 || other.text().equals(hit.text())) {
                continue;
            }
            ImagePoint p = line.get(0);
            ImagePoint q = line.size() > 2 ? line.get(line.size() / 2) : line.get(1);
            double dx = q.x() - p.x();
            double dy = q.y() - p.y();
            double length2 = dx * dx + dy * dy;
            if (length2 < 1) {
                continue;
            }
            double u = Math.max(0, Math.min(1, ((cx - p.x()) * dx + (cy - p.y()) * dy) / length2));
            double ex = cx - p.x() - u * dx;
            double ey = cy - p.y() - u * dy;
            if (ex * ex + ey * ey < ON_LINE * ON_LINE * length2) {
                return true;
            }
        }
        return false;
    }

    private boolean sameRightHalf(BarcodeHit hit) {
        if (hit.symbology() != Symbology.EAN_13 && hit.symbology() != Symbology.UPC_A) {
            return false;
        }
        String right = hit.text().substring(hit.text().length() - 6);
        for (BarcodeHit other : hits.list()) {
            if ((other.symbology() == Symbology.EAN_13 || other.symbology() == Symbology.UPC_A)
                    && !other.text().equals(hit.text()) && other.text().endsWith(right)) {
                return true;
            }
        }
        return false;
    }

    /**
     * EAN-13 and UPC-A in blurred crops of {@link BarSegments}, read on the three sharpened copies
     * of {@link Deblur} when nothing else was read, late in both modes. A text counts when no copy
     * of the crop reads another one, no other text reads on any row of a copy that read it, and either all three
     * copies read it or it is confirmed: by {@link EanFit} on the crop or a copy, by two copies,
     * or on four rows of one. A copy alone also reads a left half that its ghost turned into other
     * digits, beside the right half of the real code. Like the fits after it, this takes no
     * attempt slot: only the time budget stops it, as the crops before it often spend the attempts.
     */
    private void tryDeblurredBands(GrayU8 gray) {
        if (!hits.isEmpty()) {
            return;
        }
        fanout.inOrder(deblurredCrops(gray), () -> outOfTime() || !hits.isEmpty(), this::added);
    }

    /** {@link #tryDeblurredBands} on each crop of {@link #bandCrops}, side by side; none without EAN-13 and UPC-A or without crops of {@link BarSegments}. */
    private List<Callable<BarcodeHit>> deblurredCrops(GrayU8 gray) {
        Set<Symbology> retail = EnumSet.noneOf(Symbology.class);
        for (Symbology symbology : new Symbology[]{Symbology.EAN_13, Symbology.UPC_A}) {
            if (symbologies.contains(symbology)) {
                retail.add(symbology);
            }
        }
        List<Callable<BarcodeHit>> crops = new ArrayList<>();
        if (!retail.isEmpty() && segmentCrops) {
            for (LinearBand.Crop crop : bandCrops) {
                crops.add(() -> deblurred(crop, retail, gray));
            }
        }
        return crops;
    }

    /** What {@link #tryDeblurredBands} reads on {@code crop}, or null. */
    private BarcodeHit deblurred(LinearBand.Crop crop, Set<Symbology> retail, GrayU8 gray) {
        Deblur deblur = Deblur.blurred(crop);
        if (deblur == null) {
            return null;
        }
        String[] names = {"linear-band-deghost", "linear-band-deghost-back", "linear-band-defocus"};
        // Side by side the three copies are built and read at once, and taken in this order.
        java.util.function.Consumer<List<Runnable>> within = within();
        GrayU8[] copies = within == null ? deblur.variants() : deblur.variants(within);
        @SuppressWarnings("unchecked")
        List<BarcodeHit>[] reads = new List[copies.length];
        if (within != null) {
            List<Runnable> reading = new ArrayList<>(copies.length);
            for (int i = 0; i < copies.length; i++) {
                int index = i;
                reading.add(() -> reads[index] = BitmapDecoder.read(copies[index], deblur.toSource(), names[index], deblur.degrees(), 0.60,
                        retail, false, false, options.eanAddOn()));
            }
            within.accept(reading);
        }
        BarcodeHit first = null;
        String key = null;
        int reading = 0;
        int own = 0;
        int rival = 0;
        boolean conflict = false;
        for (int i = 0; i < copies.length && !conflict; i++) {
            List<BarcodeHit> read = reads[i] != null ? reads[i] : BitmapDecoder.read(copies[i], deblur.toSource(), names[i], deblur.degrees(), 0.60,
                    retail, false, false, options.eanAddOn());
            if (read.isEmpty()) {
                continue;
            }
            BarcodeHit hit = read.get(0);
            String text = BitmapDecoder.eanSupportKey(hit.text());
            if (first == null) {
                first = hit;
                key = text;
            } else if (!text.equals(key)) {
                conflict = true;
                continue;
            }
            reading++;
            for (var entry : BitmapDecoder.eanRowSupport(copies[i]).entrySet()) {
                if (entry.getKey().equals(key)) {
                    own = Math.max(own, entry.getValue());
                } else {
                    rival = Math.max(rival, entry.getValue());
                }
            }
        }
        // The rows read the copies of the strongest box only, as tapirscan deblurs one box a frame: on a frame without a symbol they cost the most.
        if (first == null && !conflict) {
            return crop == bandCrops.get(0) ? deblurredRows(deblur, copies, names, retail, gray) : null;
        }
        if (conflict) {
            return null;
        }
        if (reading == copies.length || rival == 0 && (reading >= 2 || own >= 4 || fitted(deblur, copies, key, within))) {
            return first;
        }
        return null;
    }

    /**
     * What the rows of {@link RetailEngine} read on the sharpened copies of a crop whose own rows
     * read nothing there, as tapirscan reads its deblurred crops ({@code recover_deblurred_crops}
     * of {@code bindings/rust/src/pipeline/deblur.rs}): a text on four rows of a copy. A copy is
     * a restored image, and its rows can agree on a wrong text as clearly as on a right one, so
     * beyond tapirscan the text counts only when the pixels of the frame follow its modules, when
     * no lines of the frame across it read another text, and when no copy reads another text on
     * four rows. The rows read no supplement.
     */
    private BarcodeHit deblurredRows(Deblur deblur, GrayU8[] copies, String[] names, Set<Symbology> retail, GrayU8 gray) {
        if (options.eanAddOn() != EanAddOn.IGNORE) {
            return null;
        }
        String text = null;
        BarcodeHit hit = null;
        for (int i = 0; i < copies.length; i++) {
            double w = copies[i].width - 1;
            double h = copies[i].height - 1;
            for (RetailEngine.Found found : RetailEngine.read(copies[i], List.of(new ProfileLines.Quad(0, 0, w, 0, w, h, 0, h)))) {
                if (found.support() < 4) {
                    continue;
                }
                if (text != null && !text.equals(found.text())) {
                    return null;
                }
                text = found.text();
                double[] polygon = new double[8];
                for (int k = 0; k < 8; k += 2) {
                    ImagePoint point = deblur.toSource().toSource(found.polygon()[k], found.polygon()[k + 1]);
                    polygon[k] = point.x();
                    polygon[k + 1] = point.y();
                }
                boolean upcA = text.charAt(0) == '0' && retail.contains(Symbology.UPC_A);
                if (hit != null || !upcA && !retail.contains(Symbology.EAN_13)
                        || !SourceCheck.agrees(gray, polygon, text) || SourceCheck.contradicted(gray, polygon, text)) {
                    continue;
                }
                ImagePoint left = new ImagePoint((polygon[0] + polygon[6]) / 2, (polygon[1] + polygon[7]) / 2);
                ImagePoint right = new ImagePoint((polygon[2] + polygon[4]) / 2, (polygon[3] + polygon[5]) / 2);
                if (Math.hypot(right.x() - left.x(), right.y() - left.y()) < 30) {
                    continue;
                }
                int degrees = !found.upsideDown() && deblur.degrees() == 0 ? -1 : Math.floorMod(deblur.degrees() + (found.upsideDown() ? 180 : 0), 360);
                hit = new BarcodeHit(upcA ? text.substring(1) : text, upcA ? Symbology.UPC_A : Symbology.EAN_13, List.of(left, right), degrees, 0.60,
                        names[i] + "-rows");
            }
        }
        return hit;
    }

    /**
     * EAN-13 and UPC-A joined from halves on different rows by {@link EanHalves}, on the image, its
     * halved frame and the image turned by 90 degrees, at whole and at quarter pixels, when fast
     * mode read nothing else, after the fits: with findAll a fit beside it would not run. On the
     * snapshot images fast mode missed it read 63 more and no wrong text. Like the fits it takes no
     * attempt slot.
     */
    private void tryEanHalves(GrayU8 gray) {
        boolean ean13 = symbologies.contains(Symbology.EAN_13);
        boolean upcA = symbologies.contains(Symbology.UPC_A);
        // Halves read no supplement, so they cannot give a symbol that has to carry one.
        if (!hits.isEmpty() || !ean13 && !upcA || options.eanAddOn() == EanAddOn.REQUIRE || outOfTime()) {
            return;
        }
        fanout.inOrder(halves(gray), () -> outOfTime() || !hits.isEmpty(), this::added);
    }

    /**
     * {@link #tryEanHalves} on the frame, its halved copy and the frame turned by 90 degrees, side
     * by side, each at whole and then at quarter pixels.
     */
    private List<Callable<BarcodeHit>> halves(GrayU8 gray) {
        if (noCrops(gray)) {
            return List.of();
        }
        linearRegions(gray);
        List<java.util.function.Supplier<Transformed>> sources = new ArrayList<>();
        sources.add(() -> Geometry.rotateOrthogonal(gray, 0));
        if (Math.min(gray.width, gray.height) >= 800) {
            sources.add(() -> Geometry.boxDownscaled(gray, 2));
        }
        sources.add(() -> Geometry.rotateOrthogonal(gray, 90,
                filledRows(EnumSet.of(Symbology.EAN_13), Geometry.orthogonalMap(gray.width, gray.height, 90), gray, 90)));
        List<Callable<BarcodeHit>> reads = new ArrayList<>(sources.size());
        for (java.util.function.Supplier<Transformed> made : sources) {
            reads.add(() -> {
                Transformed source = made.get();
                BinaryBitmap bitmap = BinaryBitmap.hybrid(new GrayLuminanceSource(source.image()))
                        .restrictRows(linearRows(source.image().width, source.image().height, source.toSource()));
                BarcodeHit hit = halvesHit(source, EanHalves.read(bitmap, 1));
                return hit != null || outOfTime() ? hit : halvesHit(source, EanHalves.read(bitmap, 4));
            });
        }
        return reads;
    }

    /** {@code found} on {@code source} as an EAN-13 or a UPC-A asked for, or null. */
    private BarcodeHit halvesHit(Transformed source, EanHalves.Found found) {
        if (found == null) {
            return null;
        }
        Symbology symbology = Symbology.EAN_13;
        String text = found.text();
        if (text.charAt(0) == '0' && symbologies.contains(Symbology.UPC_A)) {
            symbology = Symbology.UPC_A;
            text = text.substring(1);
        } else if (!symbologies.contains(Symbology.EAN_13)) {
            return null;
        }
        PixelMap map = source.toSource();
        return new BarcodeHit(
                text,
                symbology,
                List.of(map.toSource(found.left(), found.row()), map.toSource(found.right(), found.row())),
                Math.floorMod(source.degrees() + (found.upsideDown() ? 180 : 0), 360),
                0.60,
                "ean-halves"
        );
    }

    /**
     * Luma turns a red background into a dark gray under black bars, and blue bars into a middling
     * gray on white. The brightest channel of each pixel keeps the first contrast, the darkest the
     * second. Fast mode reads both, with the formats of the variants, when nothing else read a colour
     * image, as its very last step: earlier, a short Code 128 on a parcel read there before the
     * band crops and the fits reached the labelled one. No attempt slot.
     */
    private void tryColourChannels() {
        if (!hits.isEmpty() || outOfTime()) {
            return;
        }
        fanout.inOrder(channelReads(), () -> outOfTime() || !options.findAll() && !hits.isEmpty(), this::accept);
    }

    /** {@link #tryColourChannels} on the brightest and on the darkest channel, side by side; none for a gray image. */
    private List<Callable<List<BarcodeHit>>> channelReads() {
        BufferedImage image = colour;
        Set<Symbology> formats = gated(variantFormats());
        if (image == null || formats.isEmpty()) {
            return List.of();
        }
        boolean all = options.findAll();
        Once<GrayU8[]> planes = new Once<>(() -> GrayImages.channels(image));
        List<Callable<List<BarcodeHit>>> reads = new ArrayList<>(2);
        for (int plane = 0; plane < 2; plane++) {
            int index = plane;
            String attempt = plane == 0 ? "channel-max" : "channel-min";
            reads.add(() -> {
                GrayU8[] made = planes.get();
                return made == null ? List.of() : readVariant(made[index], PixelMap.IDENTITY, attempt, 0, false, formats, 0.72, all);
            });
        }
        return reads;
    }

    /**
     * Whether {@link EanFit} reads {@code key} on the resampled crop or on one of its copies; with
     * {@code within} the four fits run side by side and are taken in this order.
     */
    private static boolean fitted(Deblur deblur, GrayU8[] copies, String key, java.util.function.Consumer<List<Runnable>> within) {
        if (within != null) {
            GrayU8[] images = new GrayU8[copies.length + 1];
            images[0] = deblur.original();
            System.arraycopy(copies, 0, images, 1, copies.length);
            EanFit.Found[] fits = new EanFit.Found[images.length];
            List<Runnable> fitting = new ArrayList<>(images.length);
            for (int i = 0; i < images.length; i++) {
                int index = i;
                fitting.add(() -> fits[index] = EanFit.read(new StripBands(images[index]), within));
            }
            within.accept(fitting);
            for (EanFit.Found found : fits) {
                if (found != null && BitmapDecoder.eanSupportKey(found.text()).equals(key)) {
                    return true;
                }
            }
            return false;
        }
        EanFit.Found found = EanFit.read(deblur.original());
        if (found != null && BitmapDecoder.eanSupportKey(found.text()).equals(key)) {
            return true;
        }
        for (GrayU8 copy : copies) {
            found = EanFit.read(copy);
            if (found != null && BitmapDecoder.eanSupportKey(found.text()).equals(key)) {
                return true;
            }
        }
        return false;
    }

    private BarcodeHit firstRead(GrayU8 image, LinearBand.Crop crop, String attempt, Set<Symbology> formats) {
        List<BarcodeHit> read = readImage(image, crop.toSource(), attempt, crop.degrees(), true, formats, 0.64, false);
        return read == null || read.isEmpty() ? null : read.get(0);
    }

    /** Whether the top and the bottom half of {@code image} each read {@code text} on their own. */
    private boolean halvesRead(GrayU8 image, String text, Set<Symbology> formats, boolean globalBinarizer) {
        int half = image.height / 2;
        if (half < 4) {
            return false;
        }
        for (int[] box : new int[][]{{0, 0, image.width, half}, {0, half, image.width, image.height}}) {
            List<BarcodeHit> part = readImage(ContentBounds.crop(image, box), PixelMap.IDENTITY, "half", 0,
                    globalBinarizer, formats, 0.64, false);
            if (part == null || part.isEmpty() || !part.get(0).text().equals(text)) {
                return false;
            }
        }
        return true;
    }

    private boolean rescueDone(boolean all) {
        return outOfTime() || !all && !hits.isEmpty();
    }

    /**
     * The reads of a crop of {@link #rescueJobs} as {@link #acceptBand} takes them, without a
     * UPC-E the frame shows bars just past: a crop that ends, or is padded white, at the middle guard
     * of an EAN-13 reads its left half as a UPC-E, as two BarBeR photos did, one of them in place of
     * its EAN-13. An EAN-8 has no such counterpart, and the real ones of the public tapirscan
     * benchmark can have the dark edge of their label or collage 5 modules past their ends.
     */
    private void acceptRescued(GrayU8 gray, List<BarcodeHit> found) {
        if (found == null) {
            return;
        }
        List<BarcodeHit> kept = new ArrayList<>(found.size());
        for (BarcodeHit hit : found) {
            if (hit.symbology() != Symbology.UPC_E || hit.corners().size() != 2 || !hit.addOn().isEmpty()) {
                kept.add(hit);
                continue;
            }
            ImagePoint a = hit.corners().get(0);
            ImagePoint b = hit.corners().get(1);
            if (QuietZone.clear(gray, a.x(), a.y(), b.x(), b.y(), 51)) {
                kept.add(hit);
            }
        }
        acceptBand(kept);
    }

    /** The reads of a band crop, without a wrong left half beside a code read ({@link #sameRightHalf}) or, with findAll, a read across one ({@link #onReadLine}). */
    private void acceptBand(List<BarcodeHit> found) {
        if (found == null) {
            return;
        }
        for (BarcodeHit hit : found) {
            if (!sameRightHalf(hit) && !(options.findAll() && onReadLine(hit))) {
                hits.add(hit);
            }
        }
    }

    private void readBand(LinearBand.Crop crop, Set<Symbology> formats, String suffix) {
        GrayU8 image = crop.image();
        PixelMap map = crop.toSource();
        int degrees = crop.degrees();
        tryBand(image, map, "linear-band" + suffix, degrees, false, formats, 0.70, false);
        if (stop()) {
            return;
        }
        if (suffix.isEmpty()) {
            GrayU8 stretched = GrayImages.stretch(image);
            if (stretched != image) {
                tryBand(stretched, map, "linear-band-stretch", degrees, false, formats, 0.70, false);
                if (stop()) {
                    return;
                }
            }
        }
        tryBand(
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
            tryBand(
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
        // The correlator reads the thirteen digits only, never a supplement.
        if (stop() || options.eanAddOn() == EanAddOn.REQUIRE
                || (!symbologies.contains(Symbology.EAN_13) && !symbologies.contains(Symbology.UPC_A))) {
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
        // Pass 2 halves the module step. It runs only after the guarded grid and the
        // coarse loose grid both miss, so it cannot replace Foto(751) or Foto(771).
        // Foto(714) is the frame that needs module 3.1.
        BarcodeHit hit = correlateCrops(0);
        if (hit == null) {
            hit = correlateCrops(1);
        }
        if (hit == null) {
            hit = correlateCrops(2);
        }
        StepLog log = logged.get();
        if (log != null) {
            log.entries.add(new Logged(Logged.HIT, null, hit));
            return;
        }
        attempts++;
        if (hit != null) {
            hits.add(hit);
        }
    }

    /** The first crop this grid reads, as a hit, or null. 0 guarded, 1 coarse, 2 medium module step. */
    private BarcodeHit correlateCrops(int pass) {
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
            return new BarcodeHit(
                    text,
                    symbology,
                    List.of(map.toSource(0, mid), map.toSource(image.width - 1, mid)),
                    orientation,
                    0.70,
                    "ean-correlate"
            );
        }
        return null;
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
     * The traces of {@code gray}, one attempt each, in this order: QR Code, Micro QR, Aztec and Data
     * Matrix. They sit after the cheap ladder, so a clean symbol still stops on {@code original-*},
     * and their confidence stays under those hits and under {@code inverted-luma}. They share one
     * matrix and run side by side; a trace counts while an attempt is left, as one after another.
     */
    private void runTraces(GrayU8 gray) {
        List<Callable<Runnable>> jobs = new ArrayList<>();
        Once<TraceMatrix> matrix = traceJobs(jobs, gray);
        missedOff = 0;
        fanout.inOrder(jobs, this::stop, this::take);
        keepTrace(gray, matrix);
    }

    /** The traces of {@code gray} asked for, added to {@code jobs}, and the matrix they share. */
    private Once<TraceMatrix> traceJobs(List<Callable<Runnable>> jobs, GrayU8 gray) {
        TraceMatrix cached = traced == gray ? trace : null;
        Once<TraceMatrix> matrix = new Once<>(() -> cached != null ? cached : TraceMatrix.of(gray));
        // The look at the median of the frame is for finder rings that noise broke: it runs when the
        // frame shows a ring somewhere, or when nothing has looked for one. Of the snapshot photos
        // that look read, 5 without findAll and 44 with it, all show a ring but one pathological
        // sample, which then reads from its outline; of 300 KAIST photos without a barcode 21 show one.
        boolean median = !evidence || qrEvidence;
        traceJob(jobs, Symbology.QR_CODE, () -> qrTrace(gray, matrix.get(), within(), median));
        traceJob(jobs, Symbology.MICRO_QR, () -> microQrTrace(matrix.get()));
        traceJob(jobs, Symbology.AZTEC, () -> aztecTrace(matrix.get()));
        traceJob(jobs, Symbology.DATA_MATRIX, () -> dataMatrixTrace(matrix.get(), within()));
        return matrix;
    }

    /** A trace of {@code symbology}, when asked for, that counts only while an attempt is left, as one. */
    private void traceJob(List<Callable<Runnable>> jobs, Symbology symbology, Callable<List<BarcodeHit>> read) {
        if (!symbologies.contains(symbology)) {
            return;
        }
        jobs.add(() -> {
            List<BarcodeHit> found = (missedOff & OFF_TRACES) != 0 ? null : read.call();
            return () -> {
                if (!stop()) {
                    attempts++;
                    accept(found);
                }
            };
        });
    }

    /** The matrix of {@code gray}'s traces, if one of them built it, kept for the next trace of {@code gray}. */
    private void keepTrace(GrayU8 gray, Once<TraceMatrix> matrix) {
        TraceMatrix made = matrix.made();
        if (made != null && traced != gray) {
            trace = made;
            traced = gray;
        }
    }

    /** One result of the jobs of {@link #runTraces} or {@link #readMissed}, taken in order on the calling thread. */
    private void take(Runnable result) {
        result.run();
        missedOff |= stop() ? OFF_TRACES : 0;
    }

    /** What the QR trace reads on {@code gray}, whose trace matrix is {@code matrix}; its second look, at the median of the frame, only with {@code median}. */
    private static List<BarcodeHit> qrTrace(GrayU8 gray, TraceMatrix matrix, java.util.function.Consumer<List<Runnable>> within,
            boolean median) {
        List<QrDetector.Found> found;
        if (within == null) {
            found = matrix.qr();
            // Raw local-average misses salt-and-pepper the same way zxing-cpp 3.1.1 does.
            // The median is a second look inside this attempt, not a looser finder threshold.
            if (found.isEmpty() && median) {
                found = QrDetector.read(GrayImages.median3(gray));
            }
            // An upright symbol that fills the image reads without its finders, which BoofCV's
            // pathological samples cut, dot and pierce (QrPure).
            if (found.isEmpty()) {
                QrDetector.Found pure = matrix.qrPure(gray);
                if (pure != null) {
                    found = List.of(pure);
                }
            }
        } else {
            // The three looks side by side, the first that finds a symbol counting, as one after another.
            Object[] looks = new Object[3];
            within.accept(List.of(
                    () -> looks[0] = matrix.qr(),
                    () -> looks[1] = median ? QrDetector.read(GrayImages.median3(gray)) : List.of(),
                    () -> looks[2] = matrix.qrPure(gray)));
            @SuppressWarnings("unchecked")
            List<QrDetector.Found> asItIs = (List<QrDetector.Found>) looks[0];
            @SuppressWarnings("unchecked")
            List<QrDetector.Found> smoothed = (List<QrDetector.Found>) looks[1];
            QrDetector.Found pure = (QrDetector.Found) looks[2];
            found = !asItIs.isEmpty() ? asItIs : !smoothed.isEmpty() ? smoothed : pure != null ? List.of(pure) : List.of();
        }
        List<BarcodeHit> read = new ArrayList<>(found.size());
        for (QrDetector.Found hit : found) {
            read.add(new BarcodeHit(hit.text(), Symbology.QR_CODE, frameCorners(hit.corners()), hit.orientationDegrees(), 0.85,
                    "qr-trace", hit.bytes(), ""));
        }
        return read;
    }

    private static List<BarcodeHit> microQrTrace(TraceMatrix matrix) {
        List<BarcodeHit> read = new ArrayList<>();
        for (MicroQrDetector.Found found : matrix.microQr()) {
            read.add(new BarcodeHit(found.text(), Symbology.MICRO_QR, frameCorners(found.corners()), found.orientationDegrees(),
                    0.85, "mqr-trace", found.bytes(), ""));
        }
        return read;
    }

    private static List<BarcodeHit> aztecTrace(TraceMatrix matrix) {
        List<BarcodeHit> read = new ArrayList<>();
        for (AztecDetector.Found found : matrix.aztec()) {
            read.add(new BarcodeHit(found.text(), Symbology.AZTEC, frameCorners(found.corners()), found.orientationDegrees(),
                    0.85, "aztec-trace", found.bytes(), ""));
        }
        return read;
    }

    private static List<BarcodeHit> dataMatrixTrace(TraceMatrix matrix, java.util.function.Consumer<List<Runnable>> within) {
        List<BarcodeHit> read = new ArrayList<>();
        for (DataMatrixDetector.Found found : matrix.dataMatrix(within)) {
            read.add(new BarcodeHit(found.text(), Symbology.DATA_MATRIX, frameCorners(found.corners()), found.orientationDegrees(),
                    0.85, "dm-trace", found.bytes(), ""));
        }
        return read;
    }

    /** The corners of a trace, which reads the frame itself. */
    private static List<ImagePoint> frameCorners(List<ImagePoint> corners) {
        List<ImagePoint> mapped = new ArrayList<>(corners.size());
        for (ImagePoint corner : corners) {
            mapped.add(PixelMap.IDENTITY.toSource(corner.x(), corner.y()));
        }
        return mapped;
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
                StepLog log = logged.get();
                if (log != null) {
                    log.entries.add(new Logged(Logged.HIT, null, hit));
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
     * The image in {@code slot}, built by {@code make} the first time it is asked for. Two jobs
     * asking at once may both build it; either copy is the same image.
     */
    @SuppressWarnings("unchecked")
    private static <T> T built(AtomicReferenceArray<Object> slots, int slot, java.util.function.Supplier<T> make) {
        Object image = slots.get(slot);
        if (image == null) {
            image = make.get();
            slots.compareAndSet(slot, null, image);
        }
        return (T) image;
    }

    /** {@code image} turned by 90 degrees and read, its corners mapped back through {@code toSource}. */
    private List<BarcodeHit> readTurned(GrayU8 image, PixelMap toSource, String attempt, boolean global,
            Set<Symbology> formats, double confidence, boolean multi) {
        Transformed turned = Geometry.rotateOrthogonal(image, 90,
                filledRows(formats, Geometry.orthogonalMap(image.width, image.height, 90).before(toSource), image, 90));
        return readVariant(turned.image(), turned.toSource().before(toSource), attempt, 90, global, formats, confidence, multi);
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
        return readImage(image, toSource, attempt, degrees, globalBinarizer, symbologies, confidence, multi);
    }

    private List<BarcodeHit> readImage(
            GrayU8 image,
            PixelMap toSource,
            String attempt,
            int degrees,
            boolean globalBinarizer,
            Set<Symbology> formats,
            double confidence,
            boolean multi
    ) {
        if (image.width < 2 || image.height < 2) {
            return null;
        }
        if (formats.isEmpty()) {
            return List.of();
        }
        return BitmapDecoder.read(image, toSource, attempt, degrees, confidence, formats, globalBinarizer, multi, options.eanAddOn());
    }

    /**
     * Whether a read of the candidates first counts before the variants of the whole frame. A
     * crop is read on its own pixels, without the rows around it: read before the variants, on
     * the snapshot photos crops returned a wrong EAN-13 or UPC-A on 6 photos a variant read right,
     * 5 of them with a wrong left half, and a UPC-E cut from an EAN-13 on 2. An EAN-13 or UPC-A
     * counts only when one of the lines of the frame beside it repeats it ({@link EanCrossCheck}):
     * 5 of those 6 had none, and of a quarter of the photos a variant read the same, 549 of 643
     * had one. A UPC-E always waits.
     */
    private static boolean confirmedEarly(BarcodeHit hit, GrayU8 gray) {
        // A read of the lines across a box counts at once when three lines of the frame's own
        // pixels agree on it; one on two lines only ({@link #PAIR}) is checked as a crop's read is.
        if (hit.attempt().startsWith("scan-line") && !hit.attempt().equals(PAIR) || hit.attempt().equals(ROWS)) {
            return true;
        }
        if (hit.symbology() == Symbology.UPC_E) {
            return false;
        }
        if ((hit.symbology() == Symbology.EAN_13 || hit.symbology() == Symbology.UPC_A) && hit.corners().size() == 2) {
            ImagePoint a = hit.corners().get(0);
            ImagePoint b = hit.corners().get(1);
            return EanCrossCheck.repeated(gray, a.x(), a.y(), b.x(), b.y(), hit.text());
        }
        return true;
    }

    /**
     * The patches of {@link BarStrips}, then the crops of {@link BarSegments} and their rescue,
     * each read a job of its own: a parallel reader runs them side by side, and every read is
     * taken here in this order with the checks it had when they ran one after another, so the hits
     * are the same. A job no longer counted does not run ({@link #candidatesOff}).
     * <ul>
     * <li>The patches of parallel bars of {@link BarStrips}, sampled at the angle of their own
     * gradients. The band search tries a few angles and ranks bands of edges, so a symbol between
     * its angles, or beside a block of printed text, is cut badly or not at all.</li>
     * <li>Each crop as it is and stretched, an attempt each, then on an image nothing has read the
     * first eight thresholded by Otsu and by the local mean, for the longer codes: in find-all
     * mode, beside a symbol already read, the mean threshold returned 60 EAN-13 with the right half
     * of that symbol and a wrong left. With findAll the crops past the eighth are read last: read
     * first, they spent the attempts the thresholds of the first eight need on an image nothing has
     * read yet.</li>
     * <li>The rescue of the crops ({@link #rescueJobs}).</li>
     * </ul>
     */
    private void readCandidates(GrayU8 gray) {
        Set<Symbology> formats = linearFormats();
        if (formats.isEmpty() || stop()) {
            return;
        }
        // Single lines across the boxes of BarSegments, sampled from the frame, come before any crop
        // is read as an image: they cost a few milliseconds a photo and read symbols under shading
        // and low contrast, which the crops and the variants of the whole frame read after them.
        List<BarcodeHit> lines = postal() ? null : lineReads(gray, segments(gray, options.findAll() ? 16 : 8), lineFormats(formats));
        BarcodeHit pending = null;
        if (lines != null) {
            attempts++;
            if (options.findAll()) {
                // A read on two lines only counts with findAll when the frame confirms it.
                lines.removeIf(hit -> !confirmedEarly(hit, gray));
                acceptBand(lines);
            } else if (!lines.isEmpty()) {
                // Without findAll a read returns one symbol: the first of the strongest box. One
                // that the frame does not confirm waits for the crops, and counts when they read nothing.
                if (confirmedEarly(lines.get(0), gray)) {
                    acceptBand(lines.subList(0, 1));
                } else {
                    pending = lines.get(0);
                }
            }
            if (stop()) {
                return;
            }
        }
        BarcodeHit waiting = pending;
        List<LinearBand.Crop> strips = barStrips(gray);
        List<Callable<List<BarcodeHit>>> stripReads = new ArrayList<>();
        for (LinearBand.Crop strip : strips.subList(0, Math.min(MAX_READ_STRIPS, strips.size()))) {
            stripReads.add(() -> readImage(strip.image(), strip.toSource(), "bar-strip", strip.degrees(), false, formats, 0.70, false));
        }
        fanout.inOrder(stripReads, this::stop, read -> {
            if (read != null) {
                attempts++;
                accept(read);
            }
        });
        if (stop()) {
            takeWaiting(waiting);
            return;
        }
        // The bars of a postal code stand on one track of unequal height, which gives segment
        // voting no family of parallel edges of about one length; those read only when asked for.
        boolean postal = formats.contains(Symbology.ROYAL_MAIL) || formats.contains(Symbology.JAPAN_POST);
        bandCrops = postal ? LinearBand.find(gray, fanout, true) : segments(gray, options.findAll() ? 16 : 8);
        segmentCrops = !postal;
        List<LinearBand.Crop> crops = bandCrops;
        int first = postal ? crops.size() : Math.min(8, crops.size());
        Set<Symbology> longer = EnumSet.copyOf(formats);
        longer.retainAll(EnumSet.of(Symbology.EAN_13, Symbology.UPC_A, Symbology.CODE_128, Symbology.CODE_39));
        Set<Symbology> shorter = EnumSet.copyOf(formats);
        shorter.retainAll(EnumSet.of(Symbology.EAN_8, Symbology.UPC_E));

        List<Callable<Candidate>> jobs = new ArrayList<>();
        cropJobs(jobs, crops.subList(0, first), formats);
        jobs.add(() -> new Candidate(Take.THRESHOLDS, null));
        if (!longer.isEmpty()) {
            for (LinearBand.Crop crop : crops.subList(0, first)) {
                jobs.add(thresholdJob(crop, longer, true));
                jobs.add(thresholdJob(crop, longer, false));
            }
        }
        cropJobs(jobs, crops.subList(first, crops.size()), formats);
        if (segmentCrops) {
            rescueJobs(jobs, gray, crops, formats, shorter);
        }
        boolean all = options.findAll();
        boolean[] thresholds = new boolean[2];
        // With findAll the variants have often read something already: the thresholds are off from the start.
        candidatesOff = candidatesOff(thresholds, longer, all);
        fanout.inOrder(jobs, () -> rescueDone(all), candidate -> {
            List<BarcodeHit> read = candidate.read();
            switch (candidate.take()) {
                case CROP -> {
                    if (!stop() && read != null) {
                        attempts++;
                        acceptBand(read);
                    }
                }
                case THRESHOLDS -> {
                    thresholds[0] = true;
                    thresholds[1] = !longer.isEmpty() && hits.isEmpty();
                }
                case THRESHOLD -> {
                    if (thresholds[1] && !stop() && read != null) {
                        attempts++;
                        accept(read);
                    }
                }
                case RESCUE -> acceptRescued(gray, read);
                case SHORT -> {
                    if (hits.isEmpty() && !outOfTime()) {
                        acceptRescued(gray, read);
                    }
                }
            }
            candidatesOff = candidatesOff(thresholds, longer, all);
        });
        takeWaiting(waiting);
    }

    /** The read of the lines that waited for the crops ({@link #readCandidates}), when they read nothing. */
    private void takeWaiting(BarcodeHit waiting) {
        if (waiting != null && hits.isEmpty()) {
            acceptBand(List.of(waiting));
        }
    }

    /** The attempt of a read of {@link ProfileLines} that only two lines of its box agree on. */
    private static final String PAIR = "scan-line-2";
    /** The attempts of a read of {@link RetailEngine}: on three rows or more, and on two. */
    private static final String ROWS = "scan-rows";
    private static final String ROW_PAIR = "scan-rows-2";
    /** What {@link ProfileLines} reads: the codes with a check that two lines of a box confirm; the short retail codes have too few digits for that. */
    private static final Set<Symbology> LINE_FORMATS = EnumSet.of(Symbology.EAN_13, Symbology.UPC_A, Symbology.CODE_128);

    /**
     * Those of {@code formats} the lines read. They read no supplement, so EAN-13 and UPC-A are
     * left to the rows of the crops when one is asked for or required.
     */
    private Set<Symbology> lineFormats(Set<Symbology> formats) {
        Set<Symbology> read = EnumSet.copyOf(formats);
        read.retainAll(LINE_FORMATS);
        if (options.eanAddOn() != EanAddOn.IGNORE) {
            read.remove(Symbology.EAN_13);
            read.remove(Symbology.UPC_A);
        }
        return read;
    }

    /**
     * The symbols {@link ProfileLines} reads on lines across {@code boxes}, as hits with the two
     * ends of the middle line; null when there is no box or nothing it reads is asked for.
     */
    private static List<BarcodeHit> lineReads(GrayU8 gray, List<LinearBand.Crop> boxes, Set<Symbology> read) {
        if (read.isEmpty() || boxes.isEmpty()) {
            return null;
        }
        List<ProfileLines.Quad> quads = new ArrayList<>(boxes.size());
        for (LinearBand.Crop box : boxes) {
            int w = box.image().width;
            int h = box.image().height;
            ImagePoint a = box.toSource().toSource(0, 0);
            ImagePoint b = box.toSource().toSource(w, 0);
            ImagePoint c = box.toSource().toSource(w, h);
            ImagePoint d = box.toSource().toSource(0, h);
            quads.add(new ProfileLines.Quad(a.x(), a.y(), b.x(), b.y(), c.x(), c.y(), d.x(), d.y()));
        }
        List<BarcodeHit> hits = new ArrayList<>();
        for (ProfileLines.Found found : ProfileLines.read(gray, quads, read)) {
            // As for every linear read: two points at least 30 pixels apart.
            if (Math.hypot(found.rightX() - found.leftX(), found.rightY() - found.leftY()) < 30) {
                continue;
            }
            int turned = boxes.get(found.box()).degrees();
            int degrees = !found.upsideDown() && turned == 0 ? -1 : Math.floorMod(turned + (found.upsideDown() ? 180 : 0), 360);
            hits.add(new BarcodeHit(found.text(), found.symbology(),
                    List.of(new ImagePoint(found.leftX(), found.leftY()), new ImagePoint(found.rightX(), found.rightY())),
                    degrees, 0.70, found.support() == 2 ? PAIR : found.profile()));
        }
        // Where no line read: the rows of the retail engine, which follow up a box whose rows show guards.
        if (hits.isEmpty() && (read.contains(Symbology.EAN_13) || read.contains(Symbology.UPC_A))) {
            RetailEngine.Seen seen = RetailEngine.look(gray, quads);
            List<RetailEngine.Found> symbols = seen.symbols();
            if (symbols.isEmpty()) {
                // A second look at the lines of the boxes that showed guards, from restored profiles the frame's pixels must confirm.
                for (ProfileLines.Found found : ProfileLines.recover(gray, quads, seen.strongest(), seen.signed(), read)) {
                    if (Math.hypot(found.rightX() - found.leftX(), found.rightY() - found.leftY()) < 30) {
                        continue;
                    }
                    int turned = boxes.get(found.box()).degrees();
                    int degrees = !found.upsideDown() && turned == 0 ? -1 : Math.floorMod(turned + (found.upsideDown() ? 180 : 0), 360);
                    hits.add(new BarcodeHit(found.text(), found.symbology(),
                            List.of(new ImagePoint(found.leftX(), found.leftY()), new ImagePoint(found.rightX(), found.rightY())),
                            degrees, 0.70, found.profile()));
                }
                // Then the boxes with most guards on sharpened crops.
                if (hits.isEmpty()) {
                    symbols = RetailEngine.restored(gray, quads, seen);
                }
            }
            for (RetailEngine.Found found : symbols) {
                double[] p = found.polygon();
                ImagePoint left = new ImagePoint((p[0] + p[6]) / 2, (p[1] + p[7]) / 2);
                ImagePoint right = new ImagePoint((p[2] + p[4]) / 2, (p[3] + p[5]) / 2);
                boolean upcA = found.text().charAt(0) == '0' && read.contains(Symbology.UPC_A);
                if (Math.hypot(right.x() - left.x(), right.y() - left.y()) < 30 || !upcA && !read.contains(Symbology.EAN_13)) {
                    continue;
                }
                int turned = boxes.get(found.box()).degrees();
                int degrees = !found.upsideDown() && turned == 0 ? -1 : Math.floorMod(turned + (found.upsideDown() ? 180 : 0), 360);
                hits.add(new BarcodeHit(upcA ? found.text().substring(1) : found.text(), upcA ? Symbology.UPC_A : Symbology.EAN_13,
                        List.of(left, right), degrees, 0.70, found.support() == 2 ? ROW_PAIR : ROWS));
            }
        }
        return hits;
    }

    /**
     * What the jobs of {@link #readCandidates} can skip now: the crops and the thresholds once no
     * attempt is left, the thresholds also once anything is read, as they count only on an image
     * nothing has read when they start, the rescue once it is over, and the short codes once
     * anything is read.
     */
    private int candidatesOff(boolean[] thresholds, Set<Symbology> longer, boolean all) {
        boolean noThresholds = thresholds[0] ? !thresholds[1] : longer.isEmpty() || !hits.isEmpty();
        return (stop() ? OFF_ATTEMPTS : 0) | (noThresholds ? OFF_THRESHOLDS : 0)
                | (rescueDone(all) ? OFF_RESCUE : 0) | (!hits.isEmpty() || outOfTime() ? OFF_SHORT : 0);
    }

    /** The jobs of {@link #readCandidates} that can no longer count, which then do not run. */
    private volatile int candidatesOff;
    private static final int OFF_ATTEMPTS = 1;
    private static final int OFF_THRESHOLDS = 2;
    private static final int OFF_RESCUE = 4;
    private static final int OFF_SHORT = 8;

    /** How {@link #readCandidates} takes a read. */
    private enum Take {
        /** A crop, an attempt, without a read across a code already read ({@link #acceptBand}). */
        CROP,
        /** Where the thresholds start: they run only on an image nothing has read. */
        THRESHOLDS,
        /** A threshold of a crop for the longer codes, an attempt. */
        THRESHOLD,
        /** A rescue of a crop, without an attempt ({@link #acceptRescued}). */
        RESCUE,
        /** A short code thresholded on its crop, without an attempt, on an image nothing has read. */
        SHORT
    }

    /** A read of {@link #readCandidates}, null when there was nothing to read or it did not run. */
    private record Candidate(Take take, List<BarcodeHit> read) {
    }

    /** Each crop as it is and stretched. */
    private void cropJobs(List<Callable<Candidate>> jobs, List<LinearBand.Crop> crops, Set<Symbology> formats) {
        for (LinearBand.Crop crop : crops) {
            jobs.add(() -> new Candidate(Take.CROP, (candidatesOff & OFF_ATTEMPTS) != 0 ? null
                    : readImage(crop.image(), crop.toSource(), "linear-band", crop.degrees(), false, formats, 0.70, false)));
            jobs.add(() -> {
                if ((candidatesOff & OFF_ATTEMPTS) != 0) {
                    return new Candidate(Take.CROP, null);
                }
                GrayU8 stretched = GrayImages.stretch(crop.image());
                return new Candidate(Take.CROP, stretched == crop.image() ? null
                        : readImage(stretched, crop.toSource(), "linear-band-stretch", crop.degrees(), false, formats, 0.70, false));
            });
        }
    }

    /** A crop thresholded by Otsu or by the local mean, read for the longer codes. */
    private Callable<Candidate> thresholdJob(LinearBand.Crop crop, Set<Symbology> longer, boolean otsu) {
        return () -> {
            if ((candidatesOff & (OFF_ATTEMPTS | OFF_THRESHOLDS)) != 0) {
                return new Candidate(Take.THRESHOLD, null);
            }
            GrayU8 binary = GrayImages.binaryToLuma(otsu ? Thresholds.otsu(crop.image()) : Thresholds.mean(crop.image()));
            return new Candidate(Take.THRESHOLD, readImage(binary, crop.toSource(), otsu ? "linear-band-otsu" : "linear-band-mean",
                    crop.degrees(), true, longer, 0.70, false));
        };
    }

    /**
     * The crops of {@link BarSegments} read once more, five ways, each only while the budget lasts:
     * <ul>
     * <li>with a white margin: a tight crop cuts the bars at its edges, where the hybrid binarizer
     * finds no quiet zone;</li>
     * <li>turned by 90 degrees: text lines beside a symbol can join its cluster and turn the crop
     * along the text, so that the bars lie across it;</li>
     * <li>sampled again from the frame {@link #LENGTHEN} times as long: the bars a cluster takes in
     * can stop short of the guards, more often on a short code;</li>
     * <li>located again inside a large crop: a small symbol in a crop of a whole label has few
     * edges on the 768-pixel raster of the frame, and many on that of the crop;</li>
     * <li>for EAN-8 and UPC-E, thresholded by Otsu and by the local mean, as the longer codes
     * already are, and like them only on an image nothing has read.</li>
     * </ul>
     * On the rotated photos of the public tapirscan benchmark that fast mode read upright and
     * missed turned, a full read of a crop found the code on 22 of 62. A UPC-E read so counts only
     * when {@link QuietZone} finds no bar of the frame just past its ends. Like the fits these reads
     * take no attempt slot; only the time budget, or a hit without findAll, stops them.
     */
    private void rescueJobs(List<Callable<Candidate>> jobs, GrayU8 gray, List<LinearBand.Crop> crops, Set<Symbology> formats,
            Set<Symbology> shorter) {
        AtomicReferenceArray<Object> located = new AtomicReferenceArray<>(crops.size());
        for (int c = 0; c < crops.size(); c++) {
            LinearBand.Crop crop = crops.get(c);
            int slot = c;
            jobs.add(rescueJob(() -> {
                Transformed padded = Geometry.pad(crop.image(), CROP_MARGIN);
                return readImage(padded.image(), padded.toSource().before(crop.toSource()), "linear-band-pad",
                        crop.degrees(), false, formats, 0.68, false);
            }));
            jobs.add(rescueJob(() -> {
                Transformed turned = Geometry.rotateOrthogonal(crop.image(), 90);
                return readImage(turned.image(), turned.toSource().before(crop.toSource()), "linear-band-90",
                        (crop.degrees() + 90) % 360, false, formats, 0.66, false);
            }));
            jobs.add(rescueJob(() -> lengthenedRead(gray, crop, formats, shorter)));
            if (Math.max(crop.image().width, crop.image().height) >= RELOCATE_SIDE) {
                for (int k = 0; k < RELOCATE_CROPS; k++) {
                    int index = k;
                    for (boolean stretch : new boolean[]{false, true}) {
                        jobs.add(rescueJob(() -> innerRead(built(located, slot, () -> BarSegments.find(crop.image(), RELOCATE_CROPS)),
                                index, crop, formats, stretch)));
                    }
                }
            }
        }
        if (shorter.isEmpty()) {
            return;
        }
        for (LinearBand.Crop crop : crops) {
            jobs.add(() -> new Candidate(Take.SHORT, (candidatesOff & OFF_SHORT) != 0 ? null : shortRead(crop, shorter)));
        }
    }

    private Callable<Candidate> rescueJob(java.util.function.Supplier<List<BarcodeHit>> read) {
        return () -> new Candidate(Take.RESCUE, (candidatesOff & OFF_RESCUE) != 0 ? null : read.get());
    }

    /**
     * The crop sampled again {@link #LENGTHEN} times as long and read. A short code read across the
     * bars of another, or of itself at a slant, can be wrong; both halves of the crop must read it.
     */
    private List<BarcodeHit> lengthenedRead(GrayU8 gray, LinearBand.Crop crop, Set<Symbology> formats, Set<Symbology> shorter) {
        LinearBand.Crop longer = BarSegments.lengthened(gray, crop, LENGTHEN);
        List<BarcodeHit> read = longer == null ? null : readImage(longer.image(), longer.toSource(),
                "linear-band-long", longer.degrees(), false, formats, 0.66, false);
        if (read == null) {
            return null;
        }
        List<BarcodeHit> kept = new ArrayList<>(read.size());
        for (BarcodeHit hit : read) {
            if (!shorter.contains(hit.symbology()) || halvesRead(longer.image(), hit.text(), EnumSet.of(hit.symbology()), false)) {
                kept.add(hit);
            }
        }
        return kept;
    }

    /** Crop {@code index} of those {@link BarSegments} located inside {@code crop}, as it is or stretched. */
    private List<BarcodeHit> innerRead(List<LinearBand.Crop> located, int index, LinearBand.Crop crop, Set<Symbology> formats,
            boolean stretch) {
        if (index >= located.size()) {
            return null;
        }
        LinearBand.Crop inner = located.get(index);
        PixelMap map = inner.toSource().before(crop.toSource());
        int degrees = (inner.degrees() + crop.degrees()) % 360;
        if (!stretch) {
            return readImage(inner.image(), map, "linear-band-inner", degrees, false, formats, 0.66, false);
        }
        GrayU8 stretched = GrayImages.stretch(inner.image());
        return stretched == inner.image() ? null
                : readImage(stretched, map, "linear-band-inner-stretch", degrees, false, formats, 0.66, false);
    }

    /**
     * A short code on the crop thresholded by Otsu and by the local mean. A short code has few
     * digits to check, and one threshold of a whole crop read wrong texts: a read counts when the
     * other threshold reads the same text, or when the top and the bottom half of its own image
     * each do.
     */
    private List<BarcodeHit> shortRead(LinearBand.Crop crop, Set<Symbology> shorter) {
        GrayU8 otsu = GrayImages.binaryToLuma(Thresholds.otsu(crop.image()));
        GrayU8 mean = GrayImages.binaryToLuma(Thresholds.mean(crop.image()));
        BarcodeHit byOtsu = firstRead(otsu, crop, "linear-band-short-otsu", shorter);
        BarcodeHit byMean = firstRead(mean, crop, "linear-band-short-mean", shorter);
        if (byOtsu != null && (byMean != null && byMean.text().equals(byOtsu.text()) || halvesRead(otsu, byOtsu.text(), shorter, true))) {
            return List.of(byOtsu);
        }
        if (byMean != null && halvesRead(mean, byMean.text(), shorter, true)) {
            return List.of(byMean);
        }
        return null;
    }

    /**
     * The traces of a frame the variants read nothing on, without findAll and after the
     * candidates, and unless a read of the candidates waits ({@link #held}) the steps after them:
     * the deblurred crops, the Code 128 and EAN fits, the EAN halves and the colour channels. Each
     * reads only while the steps before it read nothing, and none uses the work of another, so
     * they run side by side and their reads are taken in this order, as the steps one after
     * another took them. On a frame nothing reads they all run anyway, in less time together.
     * With a read waiting the steps after the traces would not run: it is the read.
     */
    private void readMissed(GrayU8 gray) {
        List<Callable<Runnable>> jobs = new ArrayList<>();
        Once<TraceMatrix> matrix = traceJobs(jobs, gray);
        if (held == null) {
            List<Callable<BarcodeHit>> deblurred = deblurredCrops(gray);
            if (!deblurred.isEmpty()) {
                // Deblurring needs an attempt left, as the steps before it did.
                jobs.add(() -> () -> missedOff |= stop() ? OFF_DEBLUR : 0);
                for (Callable<BarcodeHit> crop : deblurred) {
                    jobs.add(() -> {
                        BarcodeHit hit = (missedOff & OFF_DEBLUR) != 0 ? null : crop.call();
                        return () -> added((missedOff & OFF_DEBLUR) != 0 ? null : hit);
                    });
                }
            }
            List<Callable<BarcodeHit>> found = new ArrayList<>();
            if (symbologies.contains(Symbology.CODE_128)) {
                found.addAll(code128Fits(gray, List.of()));
            }
            if (symbologies.contains(Symbology.EAN_13) || symbologies.contains(Symbology.UPC_A)) {
                found.addAll(eanFits(gray));
                // Halves read no supplement, so they cannot give a symbol that has to carry one.
                if (options.eanAddOn() != EanAddOn.REQUIRE) {
                    found.addAll(halves(gray));
                }
            }
            for (Callable<BarcodeHit> read : found) {
                jobs.add(() -> {
                    BarcodeHit hit = read.call();
                    return () -> added(hit);
                });
            }
            for (Callable<List<BarcodeHit>> read : channelReads()) {
                jobs.add(() -> {
                    List<BarcodeHit> hit = read.call();
                    return () -> accept(hit);
                });
            }
        }
        missedOff = 0;
        fanout.inOrder(jobs, () -> !hits.isEmpty() || outOfTime(), this::take);
        keepTrace(gray, matrix);
        if (held != null && hits.isEmpty()) {
            hits = held;
        }
    }

    private void added(BarcodeHit hit) {
        if (hit != null) {
            hits.add(hit);
        }
    }

    /** What the jobs of {@link #readMissed} can skip: the traces once no attempt is left, deblurring when none was. */
    private volatile int missedOff;
    private static final int OFF_TRACES = 1;
    private static final int OFF_DEBLUR = 2;

    /** A value built once, by the first job that asks for it; a job that asks meanwhile waits for it. */
    private static final class Once<T> {
        private java.util.function.Supplier<T> make;
        private T value;

        Once(java.util.function.Supplier<T> make) {
            this.make = make;
        }

        synchronized T get() {
            if (make != null) {
                value = make.get();
                make = null;
            }
            return value;
        }

        /** The value if a job built it, or null. */
        synchronized T made() {
            return value;
        }
    }

    /**
     * The matrix the QR, Micro QR, Aztec and Data Matrix traces of {@code gray} share, built once:
     * the frame's, or late in aggressive mode its inverted copy's, which the detectors read in the
     * one polarity of the matrix.
     */
    private TraceMatrix trace(GrayU8 gray) {
        if (trace == null || traced != gray) {
            trace = TraceMatrix.of(gray);
            traced = gray;
        }
        return trace;
    }

    /**
     * Whether the frame shows what the QR and Micro QR detectors, or the Aztec detector, start
     * from: a finder ring, or a bullseye centre, in either polarity, on the hybrid matrix of the
     * frame, on the matrix of the traces, or for QR on the frame halved or quartered. Of the 143
     * snapshot photos whose QR code a variant read every one shows a ring there, and the one Aztec
     * a variant read shows a centre; of 300 KAIST photos without a barcode 21 show a ring and 52 a
     * centre. A variant reads those symbologies only when the frame shows them.
     */
    private void matrixEvidence(GrayU8 gray, AtomicReferenceArray<Object> built) {
        boolean qr = symbologies.contains(Symbology.QR_CODE) || symbologies.contains(Symbology.MICRO_QR)
                || symbologies.contains(Symbology.RMQR);
        boolean aztec = symbologies.contains(Symbology.AZTEC);
        boolean pdf417 = symbologies.contains(Symbology.PDF_417);
        evidence = true;
        if (!qr && !aztec && !pdf417) {
            return;
        }
        // Cheapest first: the traces read their matrix anyway, a quarter frame is small, and the
        // hybrid matrix of the frame and of the halved frame are built only when still needed.
        // The signs of QR, Aztec and PDF417 are found side by side; each is built once.
        Once<TraceMatrix> frameTrace = new Once<>(() -> trace(gray));
        Once<BlackMatrix> whole = new Once<>(() -> {
            BinaryBitmap bitmap = BinaryBitmap.hybrid(new GrayLuminanceSource(gray));
            return (fanout.parallel() ? bitmap.sideBySide(fanout::runAll) : bitmap).getBlackMatrix();
        });
        List<Runnable> signs = new ArrayList<>(3);
        if (qr) {
            signs.add(() -> {
                boolean shown = frameTrace.get().qrFinders();
                if (!shown && Math.min(gray.width, gray.height) >= 1400) {
                    GrayU8 quarter = Geometry.boxDownscaled(gray, 4).image();
                    shown = TraceMatrix.qrFinders(BinaryBitmap.hybrid(new GrayLuminanceSource(quarter)).getBlackMatrix());
                }
                if (!shown) {
                    shown = TraceMatrix.qrFinders(whole.get());
                }
                if (!shown && Math.min(gray.width, gray.height) >= 800) {
                    Transformed half = built(built, 3, () -> Geometry.boxDownscaled(gray, 2));
                    shown = TraceMatrix.qrFinders(BinaryBitmap.hybrid(new GrayLuminanceSource(half.image())).getBlackMatrix());
                }
                qrEvidence = shown;
            });
        }
        if (aztec) {
            signs.add(() -> aztecEvidence = frameTrace.get().aztecCentres() || TraceMatrix.aztecCentres(whole.get()));
        }
        if (pdf417) {
            signs.add(() -> pdf417Guards = BitmapDecoder.pdf417Guards(whole.get()));
        }
        fanout.runAll(signs);
    }

    /**
     * {@code formats} without what a variant of this frame has no sign of: QR, Micro QR and rMQR
     * without a finder ring, Aztec without a bullseye ({@link #matrixEvidence}), and PDF417
     * without a start or stop pattern in a region of the linear locators ({@link #pdf417Shown}).
     * Unchanged before the evidence is in.
     */
    private Set<Symbology> gated(Set<Symbology> formats) {
        if (!evidence || formats.isEmpty()) {
            return formats;
        }
        EnumSet<Symbology> out = EnumSet.copyOf(formats);
        if (!qrEvidence) {
            out.removeAll(EnumSet.of(Symbology.QR_CODE, Symbology.MICRO_QR, Symbology.RMQR));
        }
        if (!aztecEvidence) {
            out.remove(Symbology.AZTEC);
        }
        if (!pdf417Shown()) {
            out.remove(Symbology.PDF_417);
        }
        return out;
    }

    /**
     * Whether a PDF417 start or stop pattern of the frame lies in a region of {@link #linearRegions},
     * or anywhere when there are no regions to go by. Each of the 73 PDF417 that variants read on
     * the snapshot photos, 31 in fast mode and 42 with findAll, had one there; of 300 KAIST photos
     * without a barcode, 144 have a pattern and a region, 94 a pattern in a region.
     */
    private boolean pdf417Shown() {
        List<double[]> regions = linearRegions;
        if (regions == null) {
            return !pdf417Guards.isEmpty();
        }
        for (int[] guard : pdf417Guards) {
            for (double[] region : regions) {
                if (guard[0] >= region[0] && guard[0] <= region[2] && guard[1] >= region[1] && guard[1] <= region[3]) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * {@link #readImage} for a variant of the whole frame after the original attempts: its linear
     * readers read only the rows that cross a region of {@link #linearRegions}, the rows a symbol
     * the locators found lies on, and the rows along the edges of the frame ({@link #alongBorder}).
     */
    private List<BarcodeHit> readVariant(
            GrayU8 image,
            PixelMap toSource,
            String attempt,
            int degrees,
            boolean globalBinarizer,
            Set<Symbology> formats,
            double confidence,
            boolean multi
    ) {
        if (image.width < 2 || image.height < 2) {
            return null;
        }
        if (formats.isEmpty()) {
            return List.of();
        }
        return BitmapDecoder.read(image, toSource, attempt, degrees, confidence, formats, globalBinarizer, multi,
                options.eanAddOn(), linearRows(image.width, image.height, toSource));
    }

    /**
     * The first {@code limit} crops of {@link BarSegments} on the frame, found once: its list is
     * the same up to any limit, so a shorter one is the start of a longer one.
     */
    private List<LinearBand.Crop> segments(GrayU8 gray, int limit) {
        if (segments == null || segmentLimit < limit) {
            segments = BarSegments.find(gray, limit, fanout);
            segmentLimit = limit;
        }
        return segments.subList(0, Math.min(limit, segments.size()));
    }

    /**
     * The bounding boxes, in frame pixels and widened by {@link #REGION_MARGIN} of their longer
     * side, of the crops of {@link BarSegments}, as many as the band reads take, and the strips of
     * {@link BarStrips}. On 794 snapshot photos that a variant of the whole frame read, the middle
     * of the read lay in one of them on every photo, in the first of eight crops on 763; on photos
     * without a barcode the crops cover about an eighth of the frame, and a third of those photos
     * have none. Null when postal codes are asked for, which the locators do not find.
     */
    private List<double[]> linearRegions(GrayU8 gray) {
        if (linearRegions == null && !postal()) {
            int limit = options.findAll() ? 16 : 8;
            // The two locators are independent: found side by side, as the read waits for both.
            fanout.runAll(List.of(() -> segments(gray, limit), () -> barStrips(gray)));
            List<double[]> boxes = new ArrayList<>();
            for (LinearBand.Crop crop : segments(gray, limit)) {
                boxes.add(widened(crop));
            }
            for (LinearBand.Crop strip : barStrips(gray)) {
                boxes.add(widened(strip));
            }
            frameWidth = gray.width;
            frameHeight = gray.height;
            border = Math.max(BORDER_MIN, BORDER_SHARE * Math.min(gray.width, gray.height));
            linearRegions = boxes;
        }
        return linearRegions;
    }

    private boolean postal() {
        return symbologies.contains(Symbology.ROYAL_MAIL) || symbologies.contains(Symbology.JAPAN_POST);
    }

    private static double[] widened(LinearBand.Crop crop) {
        GrayU8 image = crop.image();
        double x0 = Double.MAX_VALUE;
        double y0 = Double.MAX_VALUE;
        double x1 = -Double.MAX_VALUE;
        double y1 = -Double.MAX_VALUE;
        for (double[] corner : new double[][]{{0, 0}, {image.width, 0}, {image.width, image.height}, {0, image.height}}) {
            ImagePoint p = crop.toSource().toSource(corner[0], corner[1]);
            x0 = Math.min(x0, p.x());
            y0 = Math.min(y0, p.y());
            x1 = Math.max(x1, p.x());
            y1 = Math.max(y1, p.y());
        }
        double margin = REGION_MARGIN * Math.max(x1 - x0, y1 - y0);
        return new double[]{x0 - margin, y0 - margin, x1 + margin, y1 + margin};
    }

    /**
     * The rows of {@code image} turned by {@code degrees}, its map to the frame {@code toFrame},
     * that a read for {@code formats} needs: the rows of {@link #linearRows} and {@link #FILL_MARGIN}
     * around them, which the parallel strips that confirm an EAN read reach; null for every row
     * when a format reads the black matrix or there are no regions to go by.
     */
    private boolean[] filledRows(Set<Symbology> formats, PixelMap toFrame, GrayU8 image, int degrees) {
        for (Symbology symbology : formats) {
            if (!ROW_READERS.contains(symbology)) {
                return null;
            }
        }
        boolean quarter = Math.floorMod(degrees, 180) == 90;
        int width = quarter ? image.height : image.width;
        int height = quarter ? image.width : image.height;
        boolean[] rows = linearRows(width, height, toFrame);
        if (rows == null) {
            return null;
        }
        boolean[] filled = new boolean[height];
        int last = -FILL_MARGIN - 1;
        for (int y = 0; y < height; y++) {
            if (rows[y]) {
                for (int k = Math.max(0, Math.max(last + 1, y - FILL_MARGIN)); k <= Math.min(height - 1, y + FILL_MARGIN); k++) {
                    filled[k] = true;
                }
                last = Math.min(height - 1, y + FILL_MARGIN);
            }
        }
        return filled;
    }

    /**
     * Which rows of an image {@code width} by {@code height}, mapped to the frame by
     * {@code toSource}, cross a region of {@link #linearRegions} or run along an edge of the frame;
     * null for every row when there are no regions to go by.
     */
    private boolean[] linearRows(int width, int height, PixelMap toSource) {
        List<double[]> regions = linearRegions;
        if (regions == null) {
            return null;
        }
        boolean[] rows = new boolean[height];
        for (int y = 0; y < height; y++) {
            ImagePoint a = toSource.toSource(0, y + 0.5);
            ImagePoint b = toSource.toSource(width, y + 0.5);
            if (alongBorder(a, b)) {
                rows[y] = true;
                continue;
            }
            for (double[] region : regions) {
                if (crosses(a.x(), a.y(), b.x(), b.y(), region)) {
                    rows[y] = true;
                    break;
                }
            }
        }
        return rows;
    }

    /**
     * Whether a row from {@code a} to {@code b} runs along an edge of the frame, within
     * {@link #border} of it. The locators leave out a symbol the edge cuts: on the public tapirscan
     * benchmark twelve EAN-8 that only variants of the whole frame read lay with their scan line 4
     * to 80 pixels from an edge, outside every region.
     */
    private boolean alongBorder(ImagePoint a, ImagePoint b) {
        return a.y() < border && b.y() < border
                || a.y() > frameHeight - border && b.y() > frameHeight - border
                || a.x() < border && b.x() < border
                || a.x() > frameWidth - border && b.x() > frameWidth - border;
    }

    /** Whether the segment from ({@code ax}, {@code ay}) to ({@code bx}, {@code by}) meets the box {@code r}. */
    private static boolean crosses(double ax, double ay, double bx, double by, double[] r) {
        double t0 = 0;
        double t1 = 1;
        double dx = bx - ax;
        double dy = by - ay;
        double[] p = {-dx, dx, -dy, dy};
        double[] q = {ax - r[0], r[2] - ax, ay - r[1], r[3] - ay};
        for (int i = 0; i < 4; i++) {
            if (p[i] == 0) {
                if (q[i] < 0) {
                    return false;
                }
            } else {
                double t = q[i] / p[i];
                if (p[i] < 0) {
                    t0 = Math.max(t0, t);
                } else {
                    t1 = Math.min(t1, t);
                }
                if (t0 > t1) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * What the variants of the fast ladder after the two original attempts are decoded for. The
     * Data Matrix detector traces every edge of an image in four directions and both polarities,
     * and on a photo of a linear code that was most of the fast mode's CPU. Without findAll, the
     * variants never read a Data Matrix on the 1225 sample images that the original attempts and
     * {@code dm-trace} with zxing-cpp's own binarizer did not. With findAll they still look for
     * one, because there a variant can add a second symbol.
     */
    private Set<Symbology> variantFormats() {
        if (options.findAll() || !symbologies.contains(Symbology.DATA_MATRIX)) {
            return symbologies;
        }
        EnumSet<Symbology> formats = EnumSet.copyOf(symbologies);
        formats.remove(Symbology.DATA_MATRIX);
        return formats;
    }

    /**
     * What a rotated copy of the image is decoded for. The QR, Micro QR, Aztec and Data Matrix
     * detectors find a symbol at any angle, so a rotated copy only repeats their work: on the
     * 430 Arte-Lab frames and the zxing-cpp samples they never read a symbol there that the
     * upright image missed, and the one matrix read they did make was an Aztec inside a MaxiCode.
     * zxing-cpp rotates for its linear readers only. PDF417 stays, because its rows are read
     * along one axis.
     */
    private Set<Symbology> rotationFormats() {
        EnumSet<Symbology> formats = EnumSet.copyOf(symbologies);
        formats.removeAll(EnumSet.of(Symbology.QR_CODE, Symbology.MICRO_QR, Symbology.RMQR, Symbology.AZTEC, Symbology.DATA_MATRIX));
        return formats;
    }

    /**
     * Runs every job, then counts results in submission order. A hit, the attempt cap,
     * or the deadline discards the jobs that follow, including ones already running.
     */
    /**
     * Independent attempts, accepted in list order. A hit, the attempt cap or the deadline stops
     * the rest from being accepted. An attempt that could not run (too small an image) is not counted.
     */
    private void runAttempts(List<Callable<List<BarcodeHit>>> jobs) {
        fanout.inOrder(jobs, this::stop, found -> {
            if (found != null) {
                attempts++;
                accept(found);
            }
        });
    }

    private void tryOriginal(GrayLuminanceSource source, boolean global, String attempt, double confidence, boolean multi,
            Set<Symbology> formats) {
        if (stop()) {
            return;
        }
        attempts++;
        // Nothing runs beside the first attempts, so their matrix detectors run side by side.
        accept(BitmapDecoder.read(source, PixelMap.IDENTITY, attempt, 0, confidence, formats, global, multi, options.eanAddOn(),
                fanout.parallel() ? fanout::runAll : null));
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
        StepLog log = logged.get();
        if (log != null) {
            log.entries.add(new Logged(Logged.READ, BitmapDecoder.read(image, toSource, attempt, degrees, confidence, formats, globalBinarizer,
                    multi, options.eanAddOn()), null));
            return;
        }
        attempts++;
        accept(BitmapDecoder.read(image, toSource, attempt, degrees, confidence, formats, globalBinarizer, multi, options.eanAddOn()));
    }

    /**
     * As {@link #tryGray}, for a crop of a linear band: a read that lies on the scan line of a code
     * already read with other content ({@link #onReadLine}), or that shares the right half of an
     * EAN-13 read ({@link #sameRightHalf}), is left out, as with the crops of fast mode. With findAll
     * aggressive mode read 9 of its 10 wrong texts on the public tapirscan benchmark on band crops,
     * an EAN-8 or UPC-E across the bars of one it read right; this leaves out 4 of them, and on one
     * photo it hides the right read behind a wrong one read first.
     */
    private void tryBand(
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
        StepLog log = logged.get();
        if (log != null) {
            log.entries.add(new Logged(Logged.BAND, BitmapDecoder.read(image, toSource, attempt, degrees, confidence, formats, globalBinarizer,
                    multi, options.eanAddOn()), null));
            return;
        }
        attempts++;
        for (BarcodeHit hit : BitmapDecoder.read(image, toSource, attempt, degrees, confidence, formats, globalBinarizer, multi, options.eanAddOn())) {
            if (!sameRightHalf(hit) && !(options.findAll() && onReadLine(hit))) {
                hits.add(hit);
            }
        }
    }

    /** Every hit of {@code found}, if a read was made. */
    private void accept(List<BarcodeHit> found) {
        if (found == null) {
            return;
        }
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
        // A step running as a job logs every attempt; the calling thread replays them and stops there.
        StepLog log = logged.get();
        if (log != null) {
            return tailStopped || outOfTime() || beyondBudget(log);
        }
        if (!options.findAll() && !hits.isEmpty()) {
            return true;
        }
        return attempts >= options.maxAttempts() + lent || outOfTime();
    }

    private boolean outOfTime() {
        if (Thread.currentThread().isInterrupted()) {
            return true;
        }
        return budgetNanos != Long.MAX_VALUE && System.nanoTime() - startNanos >= budgetNanos;
    }
}
