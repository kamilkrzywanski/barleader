/*
 * Copyright 2026 Florian Nick
 * Copyright 2026 Kamil Krzywański
 */
// SPDX-License-Identifier: Apache-2.0
package io.github.kamilkrzywanski.barleader.decode;

import io.github.kamilkrzywanski.barleader.Symbology;
import io.github.kamilkrzywanski.barleader.image.GrayU8;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Linear symbols read from single lines across the boxes a locator found, each line sampled from
 * the frame and cut into bars by {@link ProfileSampler}. The flow of tapirscan 1.5.0's Low mode
 * ({@code bindings/rust/src/fast_linear.rs}): thirteen lines across each box, each cut three ways
 * and read both ways; a text counts once two lines agree on it at the same place. A box that
 * showed a text on one line gets lines close to that one, and then all of them at twice the
 * density. When no box read, the two strongest are read again from a sharpened profile, one
 * without its slow shading, one with a ghost of camera shake removed and one sharpened hard, and
 * there a text needs four lines.
 *
 * <p>The retail codes are read from the runs by {@link RunEan}, Code 128 by the row decoder of this
 * library, which takes the runs in eighths of a sample.
 */
public final class ProfileLines {
    /** A box in frame pixels: the corners at the start and the end of its first line, then of its last, clockwise. */
    public record Quad(double x0, double y0, double x1, double y1, double x2, double y2, double x3, double y3) {
        double[] point(double u, double v) {
            double topX = x0 + (x1 - x0) * u;
            double topY = y0 + (y1 - y0) * u;
            double bottomX = x3 + (x2 - x3) * u;
            double bottomY = y3 + (y2 - y3) * u;
            return new double[]{topX + (bottomX - topX) * v, topY + (bottomY - topY) * v};
        }
    }

    /**
     * One symbol: the box it lies in, as an index of the boxes given, the ends of its middle line
     * in frame pixels, how many lines read it and from which profile ({@code scan-line},
     * {@code scan-line-near}, {@code scan-line-contrast}, {@code scan-line-highpass},
     * {@code scan-line-deghost} or {@code scan-line-sharp}).
     */
    public record Found(int box, Symbology symbology, String text, double leftX, double leftY, double rightX, double rightY,
            boolean upsideDown, int support, String profile) {
    }

    /** Where the lines cross a box, as shares of its height. */
    private static final double[] ROWS = {0.08, 0.15, 0.22, 0.29, 0.36, 0.43, 0.5, 0.57, 0.64, 0.71, 0.78, 0.85, 0.92};
    /** A line starts and ends this share of the box outside it. */
    private static final double MARGIN = 0.15;
    private static final double DENSITY = 1.5;
    private static final int SAMPLE_LIMIT = 4096;
    /** Two reads of one text further apart than this share of the box height are not one symbol. */
    private static final double ROW_GAP = 0.141;
    private static final int[] HIGHPASS_BOXES = {5, 6, 5};
    private static final float SPECK_FRACTION = 0.2f;
    private static final int SPECK_MISSES = 6;
    private static final float SHARPENED_CONTRAST = 6f;
    /** The runs handed to the row decoders are in this many units a sample. */
    private static final int SCALE = 8;
    /** The lines of a band lie this many pixels to either side of its middle line. */
    private static final double BAND_OFFSET = 1.5;
    private static final Set<Symbology> RETAIL = EnumSet.of(Symbology.EAN_13, Symbology.UPC_A, Symbology.EAN_8, Symbology.UPC_E);

    private enum Profile {
        GRAY("scan-line", 0f), CONTRAST("scan-line-contrast", 2.25f), HIGHPASS("scan-line-highpass", 1.5f), DEGHOST("scan-line-deghost", 1f),
        DEGHOST_BACK("scan-line-deghost", 1f), SHARPENED("scan-line-sharp", SHARPENED_CONTRAST),
        // The profiles of Medium's second look at a box, for EAN-13 and UPC-A: one sample a pixel where the profile is restored, two where it is restored wide.
        SOFT("scan-line-soft", 1f, 1, 768), HIGHPASS_UNIT("scan-line-highpass", 1.5f, 1, 768), DEGHOST_UNIT("scan-line-deghost", 1f, 1, 768),
        DEGHOST_BACK_UNIT("scan-line-deghost", 1f, 1, 768), WIDE("scan-line-wide", 2.25f, 2, 1536), BAND("scan-line-band", 2.25f, 2, 1536);

        final String attempt;
        final float contrast;
        /** Samples a pixel and the most samples of a line; zero for those of the first look. */
        final double density;
        final int limit;

        Profile(String attempt, float contrast) {
            this(attempt, contrast, 0, 0);
        }

        Profile(String attempt, float contrast, double density, int limit) {
            this.attempt = attempt;
            this.contrast = contrast;
            this.density = density;
            this.limit = limit;
        }
    }

    private static final class Observation {
        Symbology symbology;
        String text;
        boolean upsideDown;
        double left;
        double right;
        double first;
        double last;
        double anchor;
        double minSpan;
        int lastRow;
        long seenRows;
        long originalRows;
        boolean dense;
        int support;
    }

    private final GrayU8 frame;
    private final ProfileSampler sampler = new ProfileSampler();
    private int[] scaled = new int[256];
    private float[] reversed = new float[256];

    // The box being read.
    private int box;
    private Quad quad;
    private Profile profile;
    private Set<Symbology> wanted;
    private boolean restored;
    private boolean dense;
    private int speckMisses;
    private final List<Observation> observations = new ArrayList<>();
    private final List<Double> rowPositions = new ArrayList<>();

    private ProfileLines(GrayU8 frame) {
        this.frame = frame;
    }

    /** The symbols of {@code symbologies} on the lines across {@code quads}, the strongest box first. */
    public static List<Found> read(GrayU8 frame, List<Quad> quads, Set<Symbology> symbologies) {
        Set<Symbology> wanted = EnumSet.noneOf(Symbology.class);
        for (Symbology symbology : symbologies) {
            if (RETAIL.contains(symbology) || symbology == Symbology.CODE_128) {
                wanted.add(symbology);
            }
        }
        if (wanted.isEmpty() || quads.isEmpty()) {
            return List.of();
        }
        return new ProfileLines(frame).scan(quads, wanted);
    }

    private List<Found> scan(List<Quad> quads, Set<Symbology> wanted) {
        List<Found> reads = new ArrayList<>();
        List<Found> near = new ArrayList<>();
        int[] refinementLines = new int[1];
        int[] denseLines = new int[1];
        for (int index = 0; index < quads.size(); index++) {
            start(quads, index, Profile.GRAY, wanted);
            for (double v : ROWS) {
                sampleLine(v, false);
            }
            int refined = refine(refinementLines, denseLines);
            appendConfirmed(refined > 0, reads, near);
        }
        reads.addAll(near);
        Set<Symbology> retail = EnumSet.copyOf(wanted);
        retail.retainAll(RETAIL);
        if (!reads.isEmpty() || retail.isEmpty()) {
            return reads;
        }
        // Contrast and wide high-pass hypotheses for the two strongest boxes.
        List<Found> recovered = new ArrayList<>();
        List<Found> unused = new ArrayList<>();
        int strongest = Math.min(2, quads.size());
        boolean[] evidence = new boolean[strongest];
        for (int index = 0; index < strongest; index++) {
            for (Profile hypothesis : new Profile[]{Profile.CONTRAST, Profile.HIGHPASS}) {
                start(quads, index, hypothesis, retail);
                for (double v : ROWS) {
                    sampleLine(v, false);
                }
                evidence[index] |= !observations.isEmpty();
                appendConfirmed(false, recovered, unused);
            }
        }
        recovered.removeIf(read -> read.support() < 4);
        // Camera shake: undo a ghost on either side of the strongest box, for EAN-13 and UPC-A only.
        List<Found> ghosted = new ArrayList<>();
        Set<Symbology> full = EnumSet.copyOf(retail);
        full.retainAll(EnumSet.of(Symbology.EAN_13, Symbology.UPC_A));
        if (recovered.isEmpty() && !full.isEmpty()) {
            for (Profile hypothesis : new Profile[]{Profile.DEGHOST, Profile.DEGHOST_BACK}) {
                start(quads, 0, hypothesis, full);
                for (double v : ROWS) {
                    sampleLine(v, false);
                }
                appendConfirmed(false, ghosted, unused);
                if (!ghosted.isEmpty()) {
                    break;
                }
            }
            ghosted.removeIf(read -> read.support() < 4);
        }
        // Heavy blur on a print of low contrast: sharpen hard where a weaker hypothesis decoded something on a line.
        if (recovered.isEmpty() && ghosted.isEmpty()) {
            for (int index = 0; index < strongest; index++) {
                if (!evidence[index]) {
                    continue;
                }
                start(quads, index, Profile.SHARPENED, retail);
                for (double v : ROWS) {
                    sampleLine(v, false);
                }
                appendConfirmed(false, recovered, unused);
            }
            recovered.removeIf(read -> read.support() < 4);
        }
        recovered.addAll(ghosted);
        // Two different recovered texts are no read.
        for (Found read : recovered) {
            if (!read.text().equals(recovered.get(0).text())) {
                return reads;
            }
        }
        if (!recovered.isEmpty()) {
            reads.add(recovered.get(0));
        }
        return reads;
    }

    /**
     * A second look at the boxes of a frame nothing read, for EAN-13 and UPC-A, as Medium takes
     * it after its rows ({@code recover_primary_profiles} of
     * {@code bindings/rust/src/pipeline.rs}). The boxes of {@code strongest}, at most two, are
     * read from profiles sharpened a little at a sample a pixel; the first eight boxes without
     * their slow shading, and when that reads nothing those of {@code shaken}, at most four, with
     * a ghost of camera shake removed. When all that reads nothing, the strongest are read
     * sampled twice as finely and sharpened wide, and then from bands of three lines. A text
     * needs three lines, four unless three read it before the profile was restored, and the
     * frame's own pixels: no lines across it that read another text, and pixels that follow its
     * modules unless five lines agree; from the wide and band profiles one text only, on four
     * lines, with pixels that follow it ({@link SourceCheck}).
     */
    public static List<Found> recover(GrayU8 frame, List<Quad> quads, List<Integer> strongest, List<Integer> shaken, Set<Symbology> symbologies) {
        Set<Symbology> full = EnumSet.noneOf(Symbology.class);
        for (Symbology symbology : symbologies) {
            if (symbology == Symbology.EAN_13 || symbology == Symbology.UPC_A) {
                full.add(symbology);
            }
        }
        if (full.isEmpty() || quads.isEmpty()) {
            return List.of();
        }
        return new ProfileLines(frame).recover(quads, strongest, shaken, full);
    }

    private List<Found> recover(List<Quad> quads, List<Integer> strongest, List<Integer> shaken, Set<Symbology> full) {
        List<Integer> two = strongest.subList(0, Math.min(2, strongest.size()));
        List<Found> reads = new ArrayList<>();
        for (int index : two) {
            secondLook(quads, index, Profile.SOFT, full, 3, false, reads);
        }
        List<Found> unshaded = new ArrayList<>();
        for (int index = 0; index < Math.min(8, quads.size()); index++) {
            secondLook(quads, index, Profile.HIGHPASS_UNIT, full, 5, false, unshaded);
        }
        for (Profile hypothesis : new Profile[]{Profile.DEGHOST_UNIT, Profile.DEGHOST_BACK_UNIT}) {
            if (unshaded.isEmpty()) {
                for (int index : shaken.subList(0, Math.min(4, shaken.size()))) {
                    secondLook(quads, index, hypothesis, full, 5, false, unshaded);
                }
            }
        }
        reads.addAll(unshaded);
        if (!reads.isEmpty()) {
            return reads;
        }
        for (Profile hypothesis : new Profile[]{Profile.WIDE, Profile.BAND}) {
            List<Found> found = new ArrayList<>();
            for (int index : two) {
                if (hypothesis == Profile.BAND && !bandHasEvidence(quads, index, full)) {
                    continue;
                }
                secondLook(quads, index, hypothesis, full, 4, true, found);
            }
            // Two reads there are no independent confirmation of either.
            if (found.size() == 1) {
                return found;
            }
        }
        return reads;
    }

    /** Whether one of seven lines of a band across the box reads anything: the bands are read in full only then. */
    private boolean bandHasEvidence(List<Quad> quads, int index, Set<Symbology> formats) {
        start(quads, index, Profile.BAND, formats);
        for (double v : new double[]{0.08, 0.22, 0.36, 0.5, 0.64, 0.78, 0.92}) {
            sampleLine(v, false);
            if (!observations.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reads box {@code index} from {@code hypothesis} and adds its texts that the frame confirms:
     * with pixels that follow the text when {@code agreement} asks for it or fewer than
     * {@code free} lines read it, and never with lines that read another text.
     */
    private void secondLook(List<Quad> quads, int index, Profile hypothesis, Set<Symbology> formats, int free, boolean agreement, List<Found> out) {
        start(quads, index, hypothesis, formats);
        for (double v : ROWS) {
            sampleLine(v, false);
        }
        boolean refined = refine(new int[1], new int[1]) > 0;
        double height = height();
        for (Observation o : observations) {
            // A restored profile repeats its own errors: four lines, unless three read the text before it was restored.
            int required = Long.bitCount(o.originalRows) < 3 ? 4 : 3;
            int confirmation = refined ? Math.max(required, o.dense ? 4 : 3) : required;
            if (o.support < confirmation || refined && (o.last - o.first) * height < o.minSpan || agreement && o.support < 4) {
                continue;
            }
            double[] p0 = quad.point(o.left, o.first);
            double[] p1 = quad.point(o.right, o.first);
            double[] p2 = quad.point(o.right, o.last);
            double[] p3 = quad.point(o.left, o.last);
            double[] polygon = o.upsideDown ? new double[]{p2[0], p2[1], p3[0], p3[1], p0[0], p0[1], p1[0], p1[1]}
                    : new double[]{p0[0], p0[1], p1[0], p1[1], p2[0], p2[1], p3[0], p3[1]};
            String digits = o.symbology == Symbology.UPC_A ? "0" + o.text : o.text;
            if ((agreement || o.support < free) && !SourceCheck.agrees(frame, polygon, digits) || SourceCheck.contradicted(frame, polygon, digits)) {
                continue;
            }
            double middle = (o.first + o.last) / 2;
            double[] left = quad.point(o.left, middle);
            double[] right = quad.point(o.right, middle);
            out.add(new Found(box, o.symbology, o.text, left[0], left[1], right[0], right[1], o.upsideDown, o.support, hypothesis.attempt));
        }
    }

    private void start(List<Quad> quads, int index, Profile hypothesis, Set<Symbology> formats) {
        box = index;
        quad = quads.get(index);
        profile = hypothesis;
        wanted = formats;
        restored = false;
        dense = false;
        speckMisses = 0;
        observations.clear();
        rowPositions.clear();
    }

    /** One line across the box at height {@code v}: cut each way the profile allows, read, and its reads admitted. */
    private void sampleLine(double v, boolean twice) {
        double[] a = quad.point(-MARGIN, v);
        double[] b = quad.point(1 + MARGIN, v);
        if (profile == Profile.BAND) {
            sampler.sampleBand(frame, a[0], a[1], b[0], b[1], profile.density, profile.limit, BAND_OFFSET);
        } else if (profile.density > 0 && !twice) {
            sampler.sample(frame, a[0], a[1], b[0], b[1], profile.density, profile.limit);
        } else {
            sampler.sample(frame, a[0], a[1], b[0], b[1], twice ? 2 * DENSITY : DENSITY, SAMPLE_LIMIT);
        }
        if (!sampler.hasContrast()) {
            return;
        }
        int methods = profile.contrast > 0f ? 6 : 3;
        for (int method = 0; method < methods; method++) {
            restored = method >= 3;
            if (method == 3) {
                switch (profile) {
                    // The profile spans 1.3 box lengths and an EAN-13 has 95 modules; a box holds some quiet zone,
                    // so 0.8 of a box module is about one module of the symbol.
                    case DEGHOST, DEGHOST_UNIT -> sampler.restoreDeghost((float) (0.8 / (1.3 * 95)), 0.5f, false);
                    case DEGHOST_BACK, DEGHOST_BACK_UNIT -> sampler.restoreDeghost((float) (0.8 / (1.3 * 95)), 0.5f, true);
                    case HIGHPASS, HIGHPASS_UNIT -> sampler.restoreHighpass(HIGHPASS_BOXES, profile.contrast);
                    case WIDE, BAND -> sampler.restoreContrastWide(1.5f);
                    default -> sampler.restoreContrast(profile.contrast);
                }
            }
            // A profile sharpened hard is read by the one level of its whole line only: the local
            // cuts follow the amplified noise and misread it the same way on every line.
            if (profile == Profile.SHARPENED && method != 5) {
                continue;
            }
            int cut = method % 3;
            if (cut == 1) {
                sampler.adaptiveThreshold();
            } else {
                sampler.threshold(cut == 0);
            }
            boolean[] decoded = decodeRuns(a, b, v);
            if (!decoded[0] && despeckle()) {
                boolean[] again = decodeRuns(a, b, v);
                decoded[1] |= again[1];
                speckMisses += again[0] ? 0 : 1;
                // The next cut sizes its window from the runs with their specks.
                sampler.restoreSpecks();
            }
            // A read across half the box leaves no room for another symbol on the line.
            if (decoded[1]) {
                break;
            }
        }
    }

    /** A line that read nothing is read once more without its specks; a box no line decoded stops after a few tries. */
    private boolean despeckle() {
        // The second look reads no line without its specks.
        if (restored || profile.density > 0 || observations.isEmpty() && speckMisses >= SPECK_MISSES) {
            return false;
        }
        boolean removed = sampler.removeSpecks(SPECK_FRACTION);
        speckMisses += removed ? 0 : 1;
        return removed;
    }

    /** Reads the runs along the line and against it; returns whether anything decoded and whether a read spans half the box. */
    private boolean[] decodeRuns(double[] a, double[] b, double v) {
        int count = sampler.runCount();
        float[] edges = sampler.edges();
        boolean any = false;
        boolean wide = false;
        if (count < 16) {
            return new boolean[]{false, false};
        }
        double span = (1 + 2 * MARGIN) / (sampler.samples() - 1);
        boolean firstBlack = sampler.firstBlack();
        if (retailWanted()) {
            if (reversed.length < count) {
                reversed = new float[count * 2];
            }
            float[] runs = sampler.runs();
            for (int i = 0; i < count; i++) {
                reversed[i] = runs[count - 1 - i];
            }
            for (boolean reverse : new boolean[]{false, true}) {
                boolean first = reverse ? firstBlack ^ (count % 2 == 0) : firstBlack;
                for (RunEan.Read read : RunEan.decode(reverse ? reversed : runs, count, first, wanted)) {
                    int start = reverse ? count - read.end() : read.start();
                    int end = reverse ? count - read.start() : read.end();
                    wide |= admit(read.symbology(), read.text(), reverse, -MARGIN + span * edges[start], -MARGIN + span * edges[end],
                            read.end() - read.start(), v);
                    any = true;
                }
            }
        }
        if (wanted.contains(Symbology.CODE_128)) {
            // The row decoder of Code 128 takes whole numbers: the runs in eighths of a sample, white first and white last.
            boolean lastBlack = firstBlack == ((count & 1) == 1);
            int length = count + (firstBlack ? 1 : 0) + (lastBlack ? 1 : 0);
            if (scaled.length < length) {
                scaled = new int[length * 2];
            }
            int at = 0;
            if (firstBlack) {
                scaled[at++] = 0;
            }
            int origin = Math.round(edges[0] * SCALE);
            int previous = origin;
            for (int i = 1; i <= count; i++) {
                int edge = Math.round(edges[i] * SCALE);
                scaled[at++] = edge - previous;
                previous = edge;
            }
            if (lastBlack) {
                scaled[at] = 0;
            }
            int total = previous - origin;
            for (boolean reverse : new boolean[]{false, true}) {
                int[] runs = new int[length];
                for (int i = 0; i < length; i++) {
                    runs[i] = scaled[reverse ? length - 1 - i : i];
                }
                Code128Decoder.Span code = Code128Decoder.decodeSpan(runs, SCALE);
                if (code != null && !code.text().isBlank()) {
                    double from = reverse ? total - code.right() : code.left();
                    double to = reverse ? total - code.left() : code.right();
                    wide |= admit(Symbology.CODE_128, code.text(), reverse, -MARGIN + span * from / SCALE, -MARGIN + span * to / SCALE,
                            6 * (code.text().length() + 3) + 1, v);
                    any = true;
                }
            }
        }
        return new boolean[]{any, wide};
    }

    private boolean retailWanted() {
        for (Symbology symbology : RETAIL) {
            if (wanted.contains(symbology)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Counts a read of the line at height {@code v} that lies from {@code l} to {@code r} along
     * the box: the line joins a read of the same text at the same place on a line not too far, or
     * starts one. Returns whether the read spans half the box.
     */
    private boolean admit(Symbology symbology, String text, boolean reverse, double l, double r, int runsInSymbol, double v) {
        int row = -1;
        for (int i = 0; i < rowPositions.size(); i++) {
            if (Math.abs(rowPositions.get(i) - v) < 1e-9) {
                row = i;
                break;
            }
        }
        if (row < 0) {
            if (rowPositions.size() >= 64) {
                return r - l >= 0.5;
            }
            row = rowPositions.size();
            rowPositions.add(v);
        }
        for (Observation old : observations) {
            if (old.symbology == symbology && old.text.equals(text) && old.upsideDown == reverse
                    && Math.abs(old.left - l) < 0.06 && Math.abs(old.right - r) < 0.06 && Math.abs(v - old.anchor) <= ROW_GAP) {
                if (!restored) {
                    old.originalRows |= 1L << row;
                }
                if ((old.seenRows & 1L << row) == 0) {
                    old.seenRows |= 1L << row;
                    old.support++;
                    old.dense |= dense;
                    old.first = Math.min(old.first, v);
                    old.last = Math.max(old.last, v);
                    old.anchor = v;
                    old.lastRow = row;
                }
                return r - l >= 0.5;
            }
        }
        double[] left = quad.point(l, v);
        double[] right = quad.point(r, v);
        double width = Math.hypot(right[0] - left[0], right[1] - left[1]);
        Observation created = new Observation();
        created.symbology = symbology;
        created.text = text;
        created.upsideDown = reverse;
        // Two mean run widths are about three narrow modules: lines closer than that are no two confirmations.
        created.minSpan = Math.max(2 * width / Math.max(1, runsInSymbol), 2);
        created.left = l;
        created.right = r;
        created.first = v;
        created.last = v;
        created.anchor = v;
        created.lastRow = row;
        created.seenRows = 1L << row;
        created.originalRows = restored ? 0 : 1L << row;
        created.dense = dense;
        created.support = 1;
        observations.add(created);
        return r - l >= 0.5;
    }

    /**
     * When no text of the box has its two lines yet: two more lines close to each line that read
     * one, and when that is still not three lines far enough apart, those and the thirteen at
     * twice the density. Returns how many lines it read.
     */
    private int refine(int[] used, int[] denseUsed) {
        for (Observation observation : observations) {
            if (observation.support >= 2) {
                return 0;
            }
        }
        int before = used[0];
        double height = height();
        List<double[]> anchors = new ArrayList<>();
        for (Observation observation : observations) {
            double delta = Math.max(0.018, 0.6 * observation.minSpan / height);
            boolean known = false;
            for (double[] anchor : anchors) {
                known |= Math.abs(anchor[0] - observation.anchor) < 1e-9;
            }
            if (delta <= 0.07 && !known) {
                anchors.add(new double[]{observation.anchor, delta});
            }
            if (anchors.size() == 4) {
                break;
            }
        }
        List<Double> retry = new ArrayList<>();
        for (double[] anchor : anchors) {
            for (double offset : new double[]{-anchor[1], anchor[1]}) {
                if (used[0] >= 32) {
                    break;
                }
                retry.add(anchor[0] + offset);
                sampleLine(anchor[0] + offset, false);
                used[0]++;
            }
        }
        int denseBefore = denseUsed[0];
        boolean settled = false;
        for (Observation observation : observations) {
            settled |= observation.support >= 3 && (observation.last - observation.first) * height >= observation.minSpan;
        }
        if (!retry.isEmpty() && !settled) {
            dense = true;
            for (double v : ROWS) {
                retry.add(v);
            }
            for (double v : retry) {
                if (denseUsed[0] >= 32) {
                    break;
                }
                double[] a = quad.point(-MARGIN, v);
                double[] b = quad.point(1 + MARGIN, v);
                if (Math.hypot(b[0] - a[0], b[1] - a[1]) * DENSITY >= SAMPLE_LIMIT) {
                    continue;
                }
                sampleLine(v, true);
                denseUsed[0]++;
            }
        }
        return used[0] - before + denseUsed[0] - denseBefore;
    }

    /**
     * The texts of the box with enough lines: two, or after {@link #refine}, whose lines lie close
     * and tell less, three, four when read at twice the density, and far enough apart.
     */
    private void appendConfirmed(boolean recovery, List<Found> reads, List<Found> near) {
        double height = height();
        for (Observation o : observations) {
            int required = 2;
            int confirmation = recovery ? Math.max(required, o.dense ? 4 : 3) : required;
            if (o.support < confirmation || recovery && (o.last - o.first) * height < o.minSpan) {
                continue;
            }
            double middle = (o.first + o.last) / 2;
            double[] left = quad.point(o.left, middle);
            double[] right = quad.point(o.right, middle);
            Found found = new Found(box, o.symbology, o.text, left[0], left[1], right[0], right[1], o.upsideDown, o.support,
                    recovery ? "scan-line-near" : profile.attempt);
            (recovery ? near : reads).add(found);
        }
    }

    private double height() {
        double[] top = quad.point(0.5, 0);
        double[] bottom = quad.point(0.5, 1);
        return Math.hypot(bottom[0] - top[0], bottom[1] - top[1]);
    }
}
