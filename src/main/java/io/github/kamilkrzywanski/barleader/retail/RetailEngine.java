/*
 * Copyright 2026 Florian Nick
 * Copyright 2026 Kamil Krzywański
 */
// SPDX-License-Identifier: Apache-2.0
package io.github.kamilkrzywanski.barleader.retail;

import io.github.kamilkrzywanski.barleader.decode.ProfileLines.Quad;
import io.github.kamilkrzywanski.barleader.decode.SourceCheck;
import io.github.kamilkrzywanski.barleader.image.GrayU8;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * EAN-13 read from rows across the boxes a locator found, as the primary pass of tapirscan 1.5.0's
 * Medium mode reads them ({@code core/src/candidate_scanner*}, {@code core/src/multi_scan*},
 * {@code core/src/frame.rs}, and the retry mask of {@code bindings/rust/src/detail.rs}).
 *
 * <p>Each row is a line of frame pixels, normalized between its 5th and 95th percentile and cut
 * into runs every way {@link SignalModels} knows; every window of 61 runs is read by
 * {@link EanRuns}, and readings of one stretch that differ refuse each other. Rows that agree are
 * linked into a track where the frame shows the bars running unbroken from one to the next, and a
 * track of two rows far enough apart is a symbol.
 *
 * <p>The rows come in the order of that engine: three of 512 samples across each box, five at one
 * sample a pixel, and then, by what those showed, up to 512 more for a box whose rows had guards
 * and nothing read, 64 for one without, and none where four rows already agree: whole rows
 * spread over the box, and windows of 512 pixels along it. Last, rows one and two pixels beside
 * each reading, across that reading alone.
 *
 * <p>The boxes of this library have their bars across the first side, so the rows along the other
 * side, which that engine reads as well, are counted against the same limits and not read. Not
 * ported: the reuse of stretches already read and the reader of tiny symbols. Of what Medium
 * does after its primary pass, the other shapes of a box are read here ({@link #look}), and the
 * sharpened crops ({@link #restored}).
 */
public final class RetailEngine {
    /**
     * One symbol: the box it lies in, its thirteen digits, its outline in frame pixels as four
     * points, the first two along its first row in the direction of the box, how many rows read
     * it, whether it reads against that direction, and whether one of its rows read it clearly
     * from the widths of its runs: at a cost of 0.08 at most with every digit 0.08 ahead.
     */
    public record Found(int box, String text, double[] polygon, int support, boolean upsideDown, boolean clear) {
    }

    /** One reading of a row, or with {@code ambiguous} a stretch of it whose readings refused each other. */
    private static final class Observation {
        boolean shortQuiet;
        boolean ambiguous;
        int[] digits;
        double fraction;
        double left;
        double right;
        float cost;
        float gap;
        boolean reversed;
        /** Read from the darkness of the modules, whose cost is on another scale than that of run widths. */
        boolean soft;

        Observation copy() {
            Observation o = new Observation();
            o.shortQuiet = shortQuiet;
            o.ambiguous = ambiguous;
            o.digits = digits;
            o.fraction = fraction;
            o.left = left;
            o.right = right;
            o.cost = cost;
            o.gap = gap;
            o.reversed = reversed;
            o.soft = soft;
            return o;
        }
    }

    /** A row to read: along the box ({@code axis} 0) or across it, at {@code fraction} of the other side, from {@code lo} to {@code hi}. */
    private record Segment(int axis, double fraction, double lo, double hi, int samples, boolean unresolved) {
    }

    /** A track that counts: its digits, its outline in frame pixels and its rows. */
    private record Detection(int[] digits, double[] polygon, int support, boolean reversed, boolean clear) {
    }

    /** A box with what its rows read so far and the rows it is still to get. */
    private static final class Candidate {
        final int box;
        final Quad quad;
        final List<Observation> observations = new ArrayList<>();
        /** The readings of EAN-8 on its rows, which link into tracks of their own. */
        final List<Observation> shortObservations = new ArrayList<>();
        List<Detection> detections = List.of();
        List<Segment> plan = List.of();
        List<Segment> confirmation = List.of();
        /** The windows whose guards looked like guards, the long rows tried at shifted levels, the rows spent and those that read something. */
        int guardPass;
        int thresholdProfiles;
        int retryPaths;
        int acceptedPaths;
        int limit;
        /** The pairs of ends on its rows whose guards could be blurred guards. */
        int blurWindows;
        /** The rows read between their peaks and valleys for EAN-8. */
        int peakRows;

        Candidate(int box, Quad quad) {
            this.box = box;
            this.quad = quad;
        }
    }

    /** The rows of the fixed first pass, as shares of the box height, and their length in samples. */
    private static final double[] FIXED_ROWS = {0.2, 0.5, 0.8};
    private static final int FIXED_SAMPLES = 512;
    /** A row starts and ends this share of the box outside it. */
    private static final double MARGIN = 0.15;
    /** The most rows a box gets and a frame gets, and the most boxes. */
    private static final int MAX_PATHS = 512;
    private static final int MAX_FRAME_PATHS = 8192;
    private static final int MAX_BOXES = 64;
    /** The most other shapes of boxes read after the boxes themselves. */
    private static final int MAX_ALTERNATIVES = 24;
    /** The most pixels of crops sharpened for one frame with the narrow blur, and of the one crop with the wide blur. */
    private static final int RESTORED_PIXELS = 131_072;

    private final GrayU8 frame;
    private Candidate current;
    private Quad quad;
    private List<Observation> observations;
    private final RunWindows.Runs runs = new RunWindows.Runs();
    private float[] signal = new float[FIXED_SAMPLES];
    private float[] raw = new float[FIXED_SAMPLES];
    private float[] sorted = new float[FIXED_SAMPLES];
    private int length;
    private int rawLength;
    private int guards;
    private int blurredGuards;
    /** The reading of the row being collected that came from its modules, if it is one of its readings. */
    private RunWindows.Read softRead;
    /** For each box of the last run, whether its rows showed the signs of a symbol. */
    private final List<Boolean> evidence = new ArrayList<>();
    private final List<Integer> boxGuards = new ArrayList<>();
    private final List<Integer> boxBlurred = new ArrayList<>();
    private float[] shifted = new float[FIXED_SAMPLES];
    private final RunWindows.Runs shiftedRuns = new RunWindows.Runs();
    private final SignalModels.Local localRuns = new SignalModels.Local();
    /** Whether the rows are read for EAN-8 as well, and the EAN-8 symbols of the last run: eight digits each. */
    private final boolean shortRows;
    private final List<Found> shorts = new ArrayList<>();
    private final RunWindows.Runs peakRuns = new RunWindows.Runs();

    private RetailEngine(GrayU8 frame) {
        this(frame, false);
    }

    private RetailEngine(GrayU8 frame, boolean shortRows) {
        this.frame = frame;
        this.shortRows = shortRows;
    }

    /**
     * What the rows of the boxes showed: the symbols, and over all boxes how many windows of 61
     * runs had guards that look like guards, and how many pairs of ends had guards that could be
     * blurred guards. A frame with neither shows the engine no sign of an EAN-13. {@code shorts}
     * are the EAN-8 symbols, each with its eight digits, when they were asked for.
     */
    public record Seen(List<Found> symbols, int guards, int blurredGuards, List<Integer> boxGuards, List<Integer> boxBlurredGuards,
            List<Found> shorts) {
        /** The boxes to look at again first: those whose rows showed guards, or four times guards that could be blurred, most guards first. */
        public List<Integer> strongest() {
            List<Integer> boxes = new ArrayList<>();
            for (int box = 0; box < boxGuards.size(); box++) {
                if (boxGuards.get(box) > 0 || boxBlurredGuards.get(box) >= 4) {
                    boxes.add(box);
                }
            }
            boxes.sort((a, b) -> boxGuards.get(b) - boxGuards.get(a));
            return boxes;
        }

        /** The boxes whose rows showed any sign of a symbol: guards, or twice guards that could be blurred. */
        public List<Integer> signed() {
            List<Integer> boxes = new ArrayList<>();
            for (int box = 0; box < boxGuards.size(); box++) {
                if (boxGuards.get(box) > 0 || boxBlurredGuards.get(box) >= 2) {
                    boxes.add(box);
                }
            }
            return boxes;
        }
    }

    /** The EAN-13 symbols on rows across {@code quads}, the boxes of a locator in frame pixels. */
    public static List<Found> read(GrayU8 frame, List<Quad> quads) {
        return look(frame, quads).symbols();
    }

    /**
     * {@link #read} with the signs of a symbol the rows showed. Where no box read, each box whose
     * rows showed guards, or twice guards that could be blurred ones, is read again in five other
     * shapes ({@link #alternatives}), as Medium does with the boxes of its locator
     * ({@code scan_alternatives} of {@code bindings/rust/src/pipeline.rs}); a symbol read there
     * counts unless rows of the frame across it read another text ({@link SourceCheck}).
     *
     * <p>Beyond that engine, a symbol counts only with a row that read it clearly
     * ({@link Found#clear}) or with pixels that follow its modules ({@link SourceCheck#agrees}):
     * on a symbol doubled by camera shake that engine itself returns a wrong text, read the same
     * way on dozens of rows, each at a cost near the limit.
     */
    public static Seen look(GrayU8 frame, List<Quad> quads) {
        return look(frame, quads, false);
    }

    /**
     * {@link #look(GrayU8, List)} that with {@code eight} reads EAN-8 on the same rows as well
     * ({@link Seen#shorts}), as Medium does ({@code core/src/retail_pipeline.rs}): where no
     * EAN-13 reads or was refused, every 43 runs between quiet zones are a window
     * ({@link ShortWindows}), on the row cut at the middle level, between its peaks and valleys,
     * with its levels following it and between its turns. The readings link into tracks as those
     * of an EAN-13 do, and one that lies in an EAN-13 read on three rows, along it and with
     * modules of its size, gives way to it.
     */
    public static Seen look(GrayU8 frame, List<Quad> quads, boolean eight) {
        RetailEngine engine = new RetailEngine(frame, eight);
        List<Found> symbols = engine.run(quads);
        List<Found> shorts = new ArrayList<>(engine.shorts);
        List<Integer> boxGuards = new ArrayList<>(engine.boxGuards);
        List<Integer> boxBlurred = new ArrayList<>(engine.boxBlurred);
        if (symbols.isEmpty()) {
            List<Quad> shapes = new ArrayList<>();
            List<Integer> origin = new ArrayList<>();
            for (int box = 0; box < engine.evidence.size(); box++) {
                if (!engine.evidence.get(box)) {
                    continue;
                }
                for (Quad shape : alternatives(quads.get(box), frame.width, frame.height)) {
                    if (shapes.size() < MAX_ALTERNATIVES) {
                        shapes.add(shape);
                        origin.add(box);
                    }
                }
            }
            if (!shapes.isEmpty()) {
                symbols = new ArrayList<>();
                for (Found found : engine.run(shapes)) {
                    if (!SourceCheck.contradicted(frame, found.polygon(), found.text())) {
                        symbols.add(new Found(origin.get(found.box()), found.text(), found.polygon(), found.support(), found.upsideDown(), found.clear()));
                    }
                }
            }
        }
        // Bars doubled by camera shake are cut wrongly the same way on every row, and read as another
        // text at a cost near the limit. A symbol no row of which read clearly counts only when the
        // frame's pixels follow the modules of its text.
        return new Seen(clearOrAgreeing(frame, symbols), engine.guards, engine.blurredGuards, boxGuards, boxBlurred, shorts);
    }

    private static List<Found> clearOrAgreeing(GrayU8 frame, List<Found> symbols) {
        List<Found> kept = new ArrayList<>();
        for (Found found : symbols) {
            if (found.clear() || SourceCheck.agrees(frame, found.polygon(), found.text())) {
                kept.add(found);
            }
        }
        return kept;
    }

    /**
     * The symbols of boxes read again on sharpened crops, for a frame of which {@code seen} read
     * nothing and a second look at its lines neither: the two boxes
     * whose rows showed most guards, four at least, each on its crop (a fifth of its size and four
     * pixels more on every side, {@link #RESTORED_PIXELS} for both) with a blur of five pixels
     * taken off; then the first of them on a crop with a blur of nine taken off. A symbol counts
     * on five rows, when no lines of the frame across it read another text, and from the wider
     * blur only when the frame's pixels follow the modules of its text. After Medium's
     * {@code recover_restored_regions} and {@code recover_late_wide_crop}
     * ({@code bindings/rust/src/pipeline/restoration.rs}), which rescan the crop with its
     * locator where this reads the box alone.
     */
    public static List<Found> restored(GrayU8 frame, List<Quad> quads, Seen seen) {
        List<Integer> guards = seen.boxGuards();
        List<Integer> boxes = new ArrayList<>();
        for (int box = 0; box < guards.size(); box++) {
            if (guards.get(box) >= 4) {
                boxes.add(box);
            }
        }
        boxes.sort((a, b) -> guards.get(b) - guards.get(a));
        List<Found> out = new ArrayList<>();
        int remaining = RESTORED_PIXELS;
        for (int pass = 0; pass < 2 && out.isEmpty(); pass++) {
            boolean wide = pass == 1;
            for (int box : boxes.subList(0, Math.min(wide ? 1 : 2, boxes.size()))) {
                Quad q = quads.get(box);
                double xmin = Math.min(Math.min(q.x0(), q.x1()), Math.min(q.x2(), q.x3()));
                double xmax = Math.max(Math.max(q.x0(), q.x1()), Math.max(q.x2(), q.x3()));
                double ymin = Math.min(Math.min(q.y0(), q.y1()), Math.min(q.y2(), q.y3()));
                double ymax = Math.max(Math.max(q.y0(), q.y1()), Math.max(q.y2(), q.y3()));
                int x = (int) Math.max(0, Math.min(frame.width, Math.floor(xmin - (xmax - xmin) * 0.2 - 4)));
                int y = (int) Math.max(0, Math.min(frame.height, Math.floor(ymin - (ymax - ymin) * 0.2 - 4)));
                int w = (int) Math.max(0, Math.min(frame.width, Math.ceil(xmax + (xmax - xmin) * 0.2 + 4))) - x;
                int h = (int) Math.max(0, Math.min(frame.height, Math.ceil(ymax + (ymax - ymin) * 0.2 + 4))) - y;
                if (w < 3 || h < 3 || (long) w * h > (wide ? RESTORED_PIXELS : remaining)) {
                    continue;
                }
                remaining -= wide ? 0 : w * h;
                GrayU8 crop = CropSharpen.sharpen(frame, x, y, w, h, wide ? CropSharpen.WIDE : CropSharpen.NARROW);
                Quad local = new Quad(q.x0() - x, q.y0() - y, q.x1() - x, q.y1() - y, q.x2() - x, q.y2() - y, q.x3() - x, q.y3() - y);
                for (Found found : new RetailEngine(crop).run(List.of(local))) {
                    if (found.support() < 5) {
                        continue;
                    }
                    double[] polygon = found.polygon().clone();
                    for (int i = 0; i < 8; i += 2) {
                        polygon[i] += x;
                        polygon[i + 1] += y;
                    }
                    if (SourceCheck.contradicted(frame, polygon, found.text()) || wide && !SourceCheck.agrees(frame, polygon, found.text())) {
                        continue;
                    }
                    out.add(new Found(box, found.text(), polygon, found.support(), found.upsideDown(), found.clear()));
                }
            }
        }
        return clearOrAgreeing(frame, out);
    }

    /**
     * Five other shapes of a box, each about its centre and kept inside a frame of {@code width}
     * by {@code height}: 1.15 times as long and 1.4 times as high, and 1.05 times as long turned
     * by 1.5 and 3 degrees either way. A box a little short of the quiet zones, or a little off
     * the angle of the bars, reads in one of them.
     */
    static List<Quad> alternatives(Quad q, int width, int height) {
        double[][] shapes = {{1.15, 1.4, 0}, {1.05, 1, -1.5}, {1.05, 1, 1.5}, {1.05, 1, -3}, {1.05, 1, 3}};
        double cx = (q.x0() + q.x1() + q.x2() + q.x3()) / 4;
        double cy = (q.y0() + q.y1() + q.y2() + q.y3()) / 4;
        List<Quad> out = new ArrayList<>();
        for (double[] shape : shapes) {
            double c = Math.cos(Math.toRadians(shape[2]));
            double s = Math.sin(Math.toRadians(shape[2]));
            double ux = (q.x1() - q.x0()) * shape[0] / 2;
            double uy = (q.y1() - q.y0()) * shape[0] / 2;
            double vx = (q.x3() - q.x0()) * shape[1] / 2;
            double vy = (q.y3() - q.y0()) * shape[1] / 2;
            double hux = ux * c - uy * s;
            double huy = ux * s + uy * c;
            double hvx = vx * c - vy * s;
            double hvy = vx * s + vy * c;
            double[] p = {cx - hux - hvx, cy - huy - hvy, cx + hux - hvx, cy + huy - hvy, cx + hux + hvx, cy + huy + hvy, cx - hux + hvx, cy - huy + hvy};
            double area = 0;
            for (int i = 0; i < 4; i++) {
                p[2 * i] = Math.max(0, Math.min(width - 1, p[2 * i]));
                p[2 * i + 1] = Math.max(0, Math.min(height - 1, p[2 * i + 1]));
            }
            for (int i = 0; i < 4; i++) {
                int j = (i + 1) % 4;
                area += p[2 * i] * p[2 * j + 1] - p[2 * j] * p[2 * i + 1];
            }
            if (Math.abs(area) > 16) {
                out.add(new Quad(p[0], p[1], p[2], p[3], p[4], p[5], p[6], p[7]));
            }
        }
        return out;
    }

    private void enter(Candidate c) {
        current = c;
        quad = c.quad;
        observations = c.observations;
    }

    private List<Found> run(List<Quad> quads) {
        List<Candidate> all = new ArrayList<>();
        for (int box = 0; box < quads.size() && box < MAX_BOXES; box++) {
            all.add(new Candidate(box, quads.get(box)));
        }
        // Three fixed rows.
        for (Candidate c : all) {
            enter(c);
            for (double fraction : FIXED_ROWS) {
                if (sample(fraction, -MARGIN, 1 + MARGIN, FIXED_SAMPLES, false)) {
                    int before = observations.size();
                    collect(fraction, -MARGIN, 1 + MARGIN, false);
                    if (observations.size() == before) {
                        extend(fraction, -MARGIN, 1 + MARGIN, false);
                    }
                }
            }
            c.detections = detections(assemble(false));
        }
        // Five rows at one sample a pixel, the boxes in turn. The rows across count and are not read.
        int used = 0;
        for (int step = 0; step < 10; step++) {
            for (Candidate c : all) {
                if (used >= MAX_FRAME_PATHS || c.retryPaths >= MAX_PATHS) {
                    continue;
                }
                used++;
                c.retryPaths++;
                if (step % 2 == 0) {
                    enter(c);
                    double fraction = 0.1 + 0.2 * (step / 2);
                    retry(new Segment(0, fraction, -MARGIN, 1 + MARGIN, samples(distance(point(-MARGIN, fraction), point(1 + MARGIN, fraction)), 64), false));
                }
            }
        }
        // How hard to look: a frame that already reads gets little more, a box with guards and no reading the most.
        List<Detection> seen = new ArrayList<>();
        for (Candidate c : all) {
            seen.addAll(c.detections);
        }
        for (Candidate c : all) {
            if (c.observations.size() >= 2) {
                enter(c);
                seen.addAll(detections(assemble(true)));
            }
        }
        boolean strong = !seen.isEmpty();
        boolean confident = false;
        for (Detection d : seen) {
            confident |= d.support() >= 4;
        }
        boolean multiple = multiple(seen);
        int cap = multiple ? 192 : confident ? 10 : strong ? 16 : MAX_PATHS;
        for (Candidate c : all) {
            c.limit = !multiple && !strong ? Math.min(cap, c.guardPass > 0 ? 512 : 64) : cap;
            enter(c);
            c.plan = plan(c);
        }
        boolean refresh = !multiple && !strong;
        for (int step = 0; step < cap && used < MAX_FRAME_PATHS; step++) {
            for (Candidate c : all) {
                if (used >= MAX_FRAME_PATHS) {
                    break;
                }
                if (step >= c.plan.size() || c.retryPaths >= c.limit) {
                    continue;
                }
                used++;
                c.retryPaths++;
                if (c.plan.get(step).axis() == 0) {
                    enter(c);
                    retry(c.plan.get(step));
                }
            }
            // After the same share of rows for every box: one that now reads on four rows needs no more than 128.
            if (refresh && (step == 31 || step == 95)) {
                for (Candidate c : all) {
                    if (c.limit <= 128 || c.observations.size() < 4) {
                        continue;
                    }
                    enter(c);
                    List<Detection> reads = detections(assemble(true));
                    boolean four = false;
                    for (Detection d : reads) {
                        four |= d.support() >= 4;
                    }
                    if (four && !multiple(reads)) {
                        c.limit = 128;
                    }
                }
            }
        }
        // Rows beside each reading.
        for (Candidate c : all) {
            enter(c);
            c.confirmation = confirmation();
        }
        for (int step = 0; step < 64 && used < MAX_FRAME_PATHS; step++) {
            for (Candidate c : all) {
                if (used >= MAX_FRAME_PATHS) {
                    break;
                }
                if (step >= c.confirmation.size() || c.retryPaths >= c.limit) {
                    continue;
                }
                used++;
                c.retryPaths++;
                enter(c);
                retry(c.confirmation.get(step));
            }
        }
        List<Found> found = new ArrayList<>();
        for (Candidate c : all) {
            enter(c);
            if (c.retryPaths > 0) {
                c.detections = detections(assemble(true));
            }
            for (Detection d : c.detections) {
                StringBuilder text = new StringBuilder(13);
                for (int digit : d.digits()) {
                    text.append((char) ('0' + digit));
                }
                found.add(new Found(c.box, text.toString(), d.polygon(), d.support(), d.reversed(), d.clear()));
            }
        }
        shorts.clear();
        if (shortRows) {
            List<double[]> primary = new ArrayList<>();
            for (Candidate c : all) {
                for (Detection d : c.detections) {
                    if (d.support() >= 3) {
                        primary.add(d.polygon());
                    }
                }
            }
            List<Detection> kept = new ArrayList<>();
            for (Candidate c : all) {
                if (c.shortObservations.size() < 2) {
                    continue;
                }
                enter(c);
                observations = c.shortObservations;
                for (Detection d : detections(assemble(true))) {
                    boolean known = false;
                    for (Detection a : kept) {
                        known |= Arrays.equals(a.digits(), d.digits()) && sameSpace(a.polygon(), d.polygon());
                    }
                    if (known || covered(primary, d.polygon())) {
                        continue;
                    }
                    kept.add(d);
                    StringBuilder text = new StringBuilder(8);
                    for (int i = 5; i < 13; i++) {
                        text.append((char) ('0' + d.digits()[i]));
                    }
                    shorts.add(new Found(c.box, text.toString(), d.polygon(), d.support(), d.reversed(), d.clear()));
                }
                observations = c.observations;
            }
        }
        evidence.clear();
        boxGuards.clear();
        boxBlurred.clear();
        for (Candidate c : all) {
            boxGuards.add(c.guardPass);
            boxBlurred.add(c.blurWindows);
            guards += c.guardPass;
            blurredGuards += c.blurWindows;
            evidence.add(c.guardPass > 0 || c.blurWindows >= 2);
        }
        return reconcile(found);
    }

    /** One row: read as sampled, a row between claimed stretches that has no contrast by its whole range, and once more with the contrast of the box alone. */
    private void retry(Segment s) {
        if (sample(s.fraction(), s.lo(), s.hi(), s.samples(), true)) {
            int before = observations.size();
            collect(s.fraction(), s.lo(), s.hi(), true);
            if (observations.size() == before) {
                extend(s.fraction(), s.lo(), s.hi(), true);
            }
        } else if (s.unresolved() && normalizeSparse()) {
            collect(s.fraction(), s.lo(), s.hi(), true);
        }
        // What lies beyond the box does not set the levels.
        if (normalizeInterior(s.lo(), s.hi())) {
            collect(s.fraction(), s.lo(), s.hi(), true);
        }
    }

    /**
     * A row that read nothing and cuts a symbol off, half of it reading cleanly behind its quiet
     * zone, is read once more made longer on that side by what the symbol lacks and a tenth, at
     * most to twice its length.
     */
    private void extend(double fraction, double lo, double hi, boolean full) {
        RunWindows.runs(signal, length, runs);
        float missing = RunWindows.partialExtension(runs, length);
        if (missing == 0f) {
            return;
        }
        double step = (hi - lo) / length;
        double extra = Math.min((Math.abs(missing) * 1.1 + 4) * step, hi - lo);
        double from = missing > 0 ? lo : lo - extra;
        double to = missing > 0 ? hi + extra : hi;
        if (sample(fraction, from, to, (int) Math.max(64, Math.min(4096, Math.round((to - from) / step))), false)) {
            collect(fraction, from, to, full);
        }
    }

    /**
     * Whether an EAN-8 lies in an EAN-13 read on three rows, along it and with modules of its
     * size: the long symbol has the place ({@code retail_protection.rs}).
     */
    private static boolean covered(List<double[]> primary, double[] q) {
        double ax = (q[0] + q[6]) / 2;
        double ay = (q[1] + q[7]) / 2;
        double dx = (q[2] + q[4]) / 2 - ax;
        double dy = (q[3] + q[5]) / 2 - ay;
        double width = Math.hypot(dx, dy);
        if (width < 8) {
            return false;
        }
        for (double[] p : primary) {
            double ux = 0.5 * (p[2] + p[4] - p[0] - p[6]);
            double uy = 0.5 * (p[3] + p[5] - p[1] - p[7]);
            double w = Math.hypot(ux, uy);
            double ratio = width / 67 / (w / 95);
            if (ratio < 0.8 || ratio > 1.25 || Math.abs(dx * ux + dy * uy) < 0.97 * width * w) {
                continue;
            }
            boolean inside = true;
            for (double t : new double[]{0.1, 0.5, 0.9}) {
                inside &= inside(p, ax + t * dx, ay + t * dy);
            }
            if (inside) {
                return true;
            }
        }
        return false;
    }

    private static boolean inside(double[] q, double x, double y) {
        boolean positive = false;
        boolean negative = false;
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) % 4;
            double c = (q[2 * j] - q[2 * i]) * (y - q[2 * i + 1]) - (q[2 * j + 1] - q[2 * i + 1]) * (x - q[2 * i]);
            positive |= c > 1e-6;
            negative |= c < -1e-6;
        }
        return !(positive && negative);
    }

    private static int samples(double pixels, int least) {
        return (int) Math.max(least, Math.min(4096, Math.ceil(pixels)));
    }

    private static double distance(double[] a, double[] b) {
        return Math.hypot(b[0] - a[0], b[1] - a[1]);
    }

    private List<Detection> detections(List<Track> tracks) {
        List<Detection> out = new ArrayList<>();
        for (Track track : tracks) {
            Observation first = track.rows.get(0);
            Observation last = track.rows.get(track.rows.size() - 1);
            double[] p0 = point(first.left, first.fraction);
            double[] p1 = point(first.right, first.fraction);
            double[] p2 = point(last.right, last.fraction);
            double[] p3 = point(last.left, last.fraction);
            boolean clear = false;
            for (Observation o : track.rows) {
                clear |= !o.soft && o.cost <= 0.08f && o.gap >= 0.08f;
            }
            out.add(new Detection(first.digits, new double[]{p0[0], p0[1], p1[0], p1[1], p2[0], p2[1], p3[0], p3[1]}, track.rows.size(), first.reversed, clear));
        }
        return out;
    }

    /** Whether the readings show more than one symbol: two texts, or one text at places more than two pixels apart. */
    private static boolean multiple(List<Detection> items) {
        List<Detection> seen = new ArrayList<>();
        List<double[]> boxes = new ArrayList<>();
        for (Detection d : items) {
            double[] b = bounds(d.polygon());
            for (int i = 0; i < seen.size(); i++) {
                double[] q = boxes.get(i);
                if (!Arrays.equals(seen.get(i).digits(), d.digits()) || q[2] + 2 < b[0] || b[2] + 2 < q[0] || q[3] + 2 < b[1] || b[3] + 2 < q[1]) {
                    return true;
                }
            }
            if (seen.size() >= 64) {
                return true;
            }
            seen.add(d);
            boxes.add(b);
        }
        return false;
    }

    /**
     * Whether a box is worth more than its first rows: one of four lines along it or four across
     * shows 48 runs, counted with a level halfway between its darkest and lightest sample and a
     * dead band of six hundredths of their distance.
     */
    private boolean deep() {
        double[][] corners = {{quad.x0(), quad.y0()}, {quad.x1(), quad.y1()}, {quad.x2(), quad.y2()}, {quad.x3(), quad.y3()}};
        for (int axis = 0; axis < 2; axis++) {
            for (double f : new double[]{0.2, 0.4, 0.6, 0.8}) {
                double[] a = corners[axis];
                double[] b = corners[(axis + 1) % 4];
                double[] c = corners[(axis + 2) % 4];
                double[] d = corners[(axis + 3) % 4];
                double px = a[0] * (1 - f) + d[0] * f;
                double py = a[1] * (1 - f) + d[1] * f;
                double qx = b[0] * (1 - f) + c[0] * f;
                double qy = b[1] * (1 - f) + c[1] * f;
                int n = (int) Math.max(64, Math.min(2048, Math.ceil(Math.hypot(qx - px, qy - py) * 1.25)));
                if (signal.length < n) {
                    signal = new float[n];
                    raw = new float[n];
                    sorted = new float[n];
                }
                float lo = 255f;
                float hi = 0f;
                for (int i = 0; i < n; i++) {
                    double t = -0.125 + 1.25 * i / (n - 1);
                    signal[i] = bilinear(px + (qx - px) * t, py + (qy - py) * t);
                    lo = Math.min(lo, signal[i]);
                    hi = Math.max(hi, signal[i]);
                }
                if (hi - lo < 20f) {
                    continue;
                }
                float level = (lo + hi) / 2f;
                float band = (hi - lo) * 0.06f;
                boolean state = signal[0] > level;
                int count = 0;
                for (int i = 0; i < n; i++) {
                    if (state ? signal[i] < level - band : signal[i] > level + band) {
                        state = !state;
                        count++;
                    }
                }
                if (count >= 48) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The rows a box gets after its first: none when it shows too few runs and no row read anything. */
    private List<Segment> plan(Candidate c) {
        double width = scaleWidth(c, false);
        int reserve = c.limit >= 129 && c.limit <= 256 ? 16 : c.limit >= 24 && c.limit <= 128 ? 8 : c.limit >= 16 && c.limit < 24 ? 4 : 0;
        int remaining = Math.max(0, c.limit - c.retryPaths - reserve);
        // A width that no symbol of the box confirms guides the windows, and the rows stay as dense as without it.
        boolean dense = width > 0 && scaleWidth(c, true) <= 0;
        List<Segment> paths = planWithSupplement(remaining, width, dense);
        if (width > 288) {
            List<Segment> fine = unresolvedPlan(c.detections, remaining);
            fine.addAll(paths.subList(0, Math.min(paths.size(), Math.max(0, remaining - fine.size()))));
            paths = fine;
        }
        if (c.acceptedPaths == 0 && !deep()) {
            return List.of();
        }
        return paths;
    }

    /**
     * The width in pixels of the narrowest reading of the box, or zero without one. Up to 285
     * pixels, and with {@code confirmed} at any width, it counts only inside a symbol of the box
     * with its digits: a reading of one row tells no scale.
     */
    private double scaleWidth(Candidate c, boolean confirmed) {
        Observation best = null;
        double bestWidth = Double.MAX_VALUE;
        for (Observation o : c.observations) {
            if (o.ambiguous) {
                continue;
            }
            double w = distance(point(o.left, o.fraction), point(o.right, o.fraction));
            if (w > 0 && w < bestWidth) {
                best = o;
                bestWidth = w;
            }
        }
        if (best == null) {
            return 0;
        }
        if (bestWidth <= 285 || confirmed) {
            double[] a = point(best.left, best.fraction);
            double[] b = point(best.right, best.fraction);
            boolean inside = false;
            for (Detection d : c.detections) {
                inside |= Arrays.equals(d.digits(), best.digits) && contains(d.polygon(), a) && contains(d.polygon(), b);
            }
            if (!inside) {
                return 0;
            }
        }
        return bestWidth;
    }

    /** Whether {@code p} lies in the outline or within two pixels of it. */
    private static boolean contains(double[] polygon, double[] p) {
        boolean positive = false;
        boolean negative = false;
        for (int i = 0; i < 4; i++) {
            double ax = polygon[2 * i];
            double ay = polygon[2 * i + 1];
            double bx = polygon[2 * ((i + 1) % 4)];
            double by = polygon[2 * ((i + 1) % 4) + 1];
            double cross = (bx - ax) * (p[1] - ay) - (by - ay) * (p[0] - ax);
            double tolerance = 2 * Math.hypot(bx - ax, by - ay);
            positive |= cross > tolerance;
            negative |= cross < -tolerance;
        }
        return !(positive && negative);
    }

    /** The middles of {@code n} places in an order whose every beginning is spread over all of them. */
    private static int[] spreadOrder(int n) {
        int[] out = new int[n];
        int count = 0;
        List<int[]> queue = new ArrayList<>();
        queue.add(new int[]{0, n});
        for (int at = 0; at < queue.size(); at++) {
            int lo = queue.get(at)[0];
            int hi = queue.get(at)[1];
            if (lo >= hi) {
                continue;
            }
            int mid = (lo + hi) / 2;
            out[count++] = mid;
            queue.add(new int[]{lo, mid});
            queue.add(new int[]{mid + 1, hi});
        }
        return out;
    }

    /** The first 128 rows of a box of unknown scale, then those of a plan of 256 that the first left out. */
    private List<Segment> planWithSupplement(int limit, double width, boolean dense) {
        List<Segment> paths = planAllowance(limit, width, dense, 128);
        if (width <= 0 && limit > 128) {
            for (Segment s : planAllowance(limit, width, dense, 256)) {
                if (paths.size() >= Math.min(limit, 256)) {
                    break;
                }
                if (!paths.contains(s)) {
                    paths.add(s);
                }
            }
        }
        return paths;
    }

    /**
     * Rows 24 pixels apart, at least 21 of them, each whole and in windows of 512 pixels that
     * overlap by half; where the width of a symbol is known, a twelfth of it apart, at least five,
     * and in windows of 1.3 widths. Of unknown scale a whole row and a window take turns, both
     * spread over the box; of known scale all rows of one window come before the next.
     */
    private List<Segment> planAllowance(int most, double symbolWidth, boolean dense, int allowance) {
        boolean unknown = symbolWidth <= 0;
        double crossStep = unknown ? 24 : Math.max(symbolWidth / 12, 24);
        double windowPixels = unknown ? 512 : Math.max(symbolWidth * 1.3, 512);
        int limit = unknown ? Math.min(most, allowance) : most;
        double[] lengths = {distance(point(0, 0.5), point(1, 0.5)), distance(point(0.5, 0), point(0.5, 1))};
        double[] widths = new double[2];
        int[] rows = new int[2];
        int[] tiles = new int[2];
        int[][] orders = new int[2][];
        int[][] tileOrders = new int[2][];
        for (int axis = 0; axis < 2; axis++) {
            double cross = lengths[1 - axis];
            rows[axis] = (int) Math.min(128, Math.max(Math.ceil(cross / crossStep), dense || unknown ? 21 : 5));
            widths[axis] = Math.min(windowPixels / Math.max(lengths[axis], 1), 1.3);
            tiles[axis] = widths[axis] >= 1.3 ? 0 : (int) Math.min(64, Math.ceil((1.3 - widths[axis]) / (widths[axis] * 0.5)) + 1);
            orders[axis] = spreadOrder(rows[axis]);
            tileOrders[axis] = spreadOrder(tiles[axis]);
        }
        List<Segment> paths = new ArrayList<>();
        int[] scheduled = new int[2];
        if (unknown) {
            for (int row = 0; row < 128; row++) {
                for (int kind = 0; kind < 2; kind++) {
                    for (int axis = 0; axis < 2; axis++) {
                        int tile = 0;
                        if (kind == 1) {
                            if (tileOrders[axis].length == 0) {
                                continue;
                            }
                            tile = 1 + tileOrders[axis][row % tileOrders[axis].length];
                        }
                        if (row >= rows[axis] || tile > tiles[axis]) {
                            continue;
                        }
                        if (paths.size() >= limit) {
                            return paths;
                        }
                        if (scheduled[kind] >= allowance / 2) {
                            continue;
                        }
                        scheduled[kind]++;
                        paths.add(segment(axis, orders[axis][row], rows[axis], tile, tiles[axis], lengths[axis], widths[axis]));
                    }
                }
            }
            // Then the windows the turns left out.
            for (int rank = 0; rank < 64; rank++) {
                for (int row = 0; row < 128; row++) {
                    for (int axis = 0; axis < 2; axis++) {
                        if (row >= rows[axis] || rank >= tiles[axis]) {
                            continue;
                        }
                        if (paths.size() >= limit) {
                            return paths;
                        }
                        Segment s = segment(axis, orders[axis][row], rows[axis], 1 + tileOrders[axis][rank], tiles[axis], lengths[axis], widths[axis]);
                        if (!paths.contains(s)) {
                            paths.add(s);
                        }
                    }
                }
            }
            return paths;
        }
        for (int tile = 0; tile <= 64; tile++) {
            for (int row = 0; row < 128; row++) {
                for (int axis = 0; axis < 2; axis++) {
                    if (row >= rows[axis] || tile > tiles[axis]) {
                        continue;
                    }
                    if (paths.size() >= limit) {
                        return paths;
                    }
                    paths.add(segment(axis, orders[axis][row], rows[axis], tile, tiles[axis], lengths[axis], widths[axis]));
                }
            }
        }
        return paths;
    }

    /** Row {@code row} of {@code rows}: whole for {@code tile} 0, else window {@code tile} of {@code tiles}, each {@code width} of the box long. */
    private static Segment segment(int axis, int row, int rows, int tile, int tiles, double length, double width) {
        double fraction = (row + 0.5) / rows;
        if (tile == 0) {
            return new Segment(axis, fraction, -MARGIN, 1 + MARGIN, samples(length * 1.3, 64), false);
        }
        double lo = -MARGIN + (1.3 - width) * (tile - 1) / Math.max(tiles - 1, 1);
        return new Segment(axis, fraction, lo, lo + width, samples(length * width, 512), false);
    }

    /**
     * For a box with a symbol wider than 288 pixels: rows 24 pixels apart, at least five, each cut
     * to the stretches no symbol of the box covers, where such a stretch is 76 pixels or more.
     */
    private List<Segment> unresolvedPlan(List<Detection> detections, int limit) {
        List<Segment> paths = new ArrayList<>();
        int[][] orders = new int[2][];
        for (int axis = 0; axis < 2; axis++) {
            double cross = axis == 0 ? distance(point(0.5, 0), point(0.5, 1)) : distance(point(0, 0.5), point(1, 0.5));
            orders[axis] = spreadOrder((int) Math.min(128, Math.max(Math.ceil(cross / 24), 5)));
        }
        for (int rank = 0; rank < 128; rank++) {
            for (int axis = 0; axis < 2; axis++) {
                if (rank >= orders[axis].length) {
                    continue;
                }
                double fraction = (orders[axis][rank] + 0.5) / orders[axis].length;
                List<double[]> claimed = new ArrayList<>();
                for (int i = 0; i < detections.size() && i < 64; i++) {
                    double[] interval = claimedInterval(detections.get(i).polygon(), axis, fraction);
                    if (interval != null) {
                        claimed.add(interval);
                    }
                }
                claimed.sort((a, b) -> Double.compare(a[0], b[0]));
                List<double[]> open = new ArrayList<>();
                double lo = -MARGIN;
                for (double[] interval : claimed) {
                    double a = Math.max(-MARGIN, Math.min(1 + MARGIN, interval[0]));
                    double b = Math.max(-MARGIN, Math.min(1 + MARGIN, interval[1]));
                    if (a > lo) {
                        open.add(new double[]{lo, a});
                    }
                    lo = Math.max(lo, b);
                }
                if (lo < 1 + MARGIN) {
                    open.add(new double[]{lo, 1 + MARGIN});
                }
                for (double[] interval : open) {
                    double[] from = axis == 0 ? point(interval[0], fraction) : point(fraction, interval[0]);
                    double[] to = axis == 0 ? point(interval[1], fraction) : point(fraction, interval[1]);
                    double pixels = distance(from, to);
                    // Under 0.8 samples a module no EAN-13 fits.
                    if (pixels >= 76 && paths.size() < limit) {
                        paths.add(new Segment(axis, fraction, interval[0], interval[1], samples(pixels, 64), true));
                    }
                }
            }
        }
        return paths;
    }

    /** The stretch of the row at {@code fraction} that the outline covers, in shares of the box, or null where the row misses it. */
    private double[] claimedInterval(double[] polygon, int axis, double fraction) {
        // The box as a parallelogram: a frame pixel is its first corner and shares of its first and its last side.
        double ux = quad.x1() - quad.x0();
        double uy = quad.y1() - quad.y0();
        double vx = quad.x3() - quad.x0();
        double vy = quad.y3() - quad.y0();
        double det = ux * vy - uy * vx;
        if (Math.abs(det) < 1e-12) {
            return null;
        }
        double[][] points = new double[4][2];
        for (int i = 0; i < 4; i++) {
            double dx = polygon[2 * i] - quad.x0();
            double dy = polygon[2 * i + 1] - quad.y0();
            double u = (dx * vy - dy * vx) / det;
            double v = (ux * dy - uy * dx) / det;
            points[i][0] = axis == 0 ? u : v;
            points[i][1] = axis == 0 ? v : u;
        }
        double[] xs = new double[2];
        int count = 0;
        for (int i = 0; i < 4; i++) {
            double[] a = points[i];
            double[] b = points[(i + 1) % 4];
            if (a[1] <= fraction && fraction < b[1] || b[1] <= fraction && fraction < a[1]) {
                if (count == 2) {
                    return null;
                }
                xs[count++] = a[0] + (b[0] - a[0]) * (fraction - a[1]) / (b[1] - a[1]);
            }
        }
        if (count != 2) {
            return null;
        }
        return new double[]{Math.min(xs[0], xs[1]), Math.max(xs[0], xs[1])};
    }

    /** Rows one and two pixels to either side of each reading, the clearest first, across the reading and 0.15 of it more on both sides; 64 at most. */
    private List<Segment> confirmation() {
        List<Observation> anchors = new ArrayList<>();
        for (Observation o : observations) {
            if (!o.ambiguous) {
                anchors.add(o);
            }
        }
        anchors.sort((a, b) -> Float.compare(a.cost, b.cost));
        double cross = distance(point(0.5, 0), point(0.5, 1));
        List<Segment> extra = new ArrayList<>();
        if (cross < 1) {
            return extra;
        }
        for (Observation o : anchors) {
            double width = o.right - o.left;
            if (width <= 0) {
                continue;
            }
            double lo = o.left - 0.15 * width;
            double hi = o.right + 0.15 * width;
            double pixels = distance(point(o.left, o.fraction), point(o.right, o.fraction)) * 1.3;
            for (double delta : new double[]{-1, 1, -2, 2}) {
                double fraction = o.fraction + delta / cross;
                if (fraction < 0 || fraction > 1) {
                    continue;
                }
                boolean known = false;
                for (Segment s : extra) {
                    known |= Math.abs(s.fraction() - fraction) * cross < 0.5 && Math.abs(s.lo() - lo) < 0.1 * width && Math.abs(s.hi() - hi) < 0.1 * width;
                }
                if (known) {
                    continue;
                }
                if (extra.size() >= 64) {
                    return extra;
                }
                extra.add(new Segment(0, fraction, lo, hi, samples(pixels, 64), false));
            }
        }
        return extra;
    }

    /** The frame pixel at {@code u} along the box and {@code fraction} across it. */
    private double[] point(double u, double fraction) {
        double topX = quad.x0() + (quad.x1() - quad.x0()) * u;
        double topY = quad.y0() + (quad.y1() - quad.y0()) * u;
        double bottomX = quad.x3() + (quad.x2() - quad.x3()) * u;
        double bottomY = quad.y3() + (quad.y2() - quad.y3()) * u;
        return new double[]{topX + (bottomX - topX) * fraction, topY + (bottomY - topY) * fraction};
    }

    /**
     * Samples a row at the centres of {@code count} equal steps from {@code lo} to {@code hi}
     * along the box, the nearest pixel for 76 to 384 samples and the mean of four otherwise, and
     * normalizes it between its 5th and 95th percentile with dark as one. False for a row of less
     * than eight gray levels.
     */
    private boolean sample(double fraction, double lo, double hi, int count, boolean keepRaw) {
        if (signal.length < count) {
            signal = new float[count];
            // The row kept for the contrast of the box alone outlives a longer row read in between.
            raw = Arrays.copyOf(raw, count);
            sorted = new float[count];
        }
        length = count;
        boolean nearest = count >= 76 && count <= 384;
        for (int i = 0; i < count; i++) {
            double[] p = point(lo + (hi - lo) * (i + 0.5) / count, fraction);
            signal[i] = nearest ? gray(Math.round(p[0]), Math.round(p[1])) : bilinear(p[0], p[1]);
        }
        if (keepRaw) {
            System.arraycopy(signal, 0, raw, 0, count);
            rawLength = count;
        }
        return normalize(signal, 0, count);
    }

    /** Normalizes the row kept by {@link #sample} between the percentiles of its part inside the box. */
    private boolean normalizeInterior(double lo, double hi) {
        if (lo >= 0 && hi <= 1) {
            return false;
        }
        // The row as it was sampled, which a longer row read in between has not replaced.
        length = rawLength;
        int from = interiorBound(lo, hi, false);
        int to = interiorBound(lo, hi, true);
        if (to - from < 64) {
            return false;
        }
        System.arraycopy(raw, 0, signal, 0, length);
        return normalize(raw, from, to);
    }

    /** Normalizes a row without contrast between its percentiles by its whole range: a small symbol can be less than a twentieth of it. */
    private boolean normalizeSparse() {
        float lo = Float.MAX_VALUE;
        float hi = -Float.MAX_VALUE;
        for (int i = 0; i < length; i++) {
            lo = Math.min(lo, signal[i]);
            hi = Math.max(hi, signal[i]);
        }
        if (hi - lo < 8f) {
            return false;
        }
        for (int i = 0; i < length; i++) {
            signal[i] = Math.max(0f, Math.min(1f, (hi - signal[i]) / (hi - lo)));
        }
        return true;
    }

    private int interiorBound(double lo, double hi, boolean upper) {
        int a = 0;
        int b = length;
        while (a < b) {
            int mid = a + (b - a) / 2;
            double u = lo + (hi - lo) * (mid + 0.5) / length;
            if (upper ? u <= 1 : u < 0) {
                a = mid + 1;
            } else {
                b = mid;
            }
        }
        return a;
    }

    /** Sets {@link #signal} to dark-is-one between the 5th and 95th percentile of {@code values[from, to)}. */
    private boolean normalize(float[] values, int from, int to) {
        int n = to - from;
        System.arraycopy(values, from, sorted, 0, n);
        Arrays.sort(sorted, 0, n);
        double hi = sorted[n * 95 / 100];
        double lo = sorted[n * 5 / 100];
        if (hi - lo < 8) {
            return false;
        }
        for (int i = 0; i < length; i++) {
            signal[i] = (float) Math.max(0, Math.min(1, (hi - signal[i]) / (hi - lo)));
        }
        return true;
    }

    private float gray(long x, long y) {
        int cx = (int) Math.max(0, Math.min(frame.width - 1, x));
        int cy = (int) Math.max(0, Math.min(frame.height - 1, y));
        return frame.data[frame.startIndex + cy * frame.stride + cx] & 255;
    }

    private float bilinear(double x, double y) {
        double cx = Math.max(0, Math.min(frame.width - 1, x));
        double cy = Math.max(0, Math.min(frame.height - 1, y));
        int x0 = (int) cx;
        int y0 = (int) cy;
        int x1 = Math.min(x0 + 1, frame.width - 1);
        int y1 = Math.min(y0 + 1, frame.height - 1);
        double fx = cx - x0;
        double fy = cy - y0;
        int row0 = frame.startIndex + y0 * frame.stride;
        int row1 = frame.startIndex + y1 * frame.stride;
        byte[] data = frame.data;
        double top = (data[row0 + x0] & 255) * (1 - fx) + (data[row0 + x1] & 255) * fx;
        double bottom = (data[row1 + x0] & 255) * (1 - fx) + (data[row1 + x1] & 255) * fx;
        return (float) (top * (1 - fy) + bottom * fy);
    }

    /**
     * The readings of the normalized row as observations: those between short quiet zones, marked
     * so, then those between full ones. With {@code full} the row is cut every way the engine
     * knows: also from widths corrected by their guards, without clusters of specks, with levels
     * that follow the row, between its turns, and for a row of 45 to 60 runs, too few for a
     * symbol, at a lower and a higher level. A stretch whose readings refused each other becomes
     * an ambiguous observation.
     */
    private void collect(double fraction, double lo, double hi, boolean full) {
        int before = observations.size();
        RunWindows.runs(signal, length, runs);
        RunWindows.Reads looks = RunWindows.shortQuiet(runs, full);
        add(looks, fraction, lo, hi, true);
        RunWindows.Reads reads = RunWindows.decode(runs, false);
        if (shortRows) {
            ShortWindows.Reads eight = new ShortWindows.Reads();
            ShortWindows.decode(runs, reads, eight);
            addShort(eight, fraction, lo, hi);
            // The same row cut between its peaks and valleys, for 32 rows of a box.
            if (current.peakRows < 32) {
                current.peakRows++;
                ShortWindows.peakRuns(signal, length, peakRuns);
                eight = new ShortWindows.Reads();
                ShortWindows.decode(peakRuns, reads, eight);
                addShort(eight, fraction, lo, hi);
            }
        }
        if (full) {
            reads = RunWindows.merge(reads, SignalModels.clustered(signal, length, runs, reads));
            reads = RunWindows.merge(reads, RunWindows.decode(runs, true));
            RunWindows.Reads local = SignalModels.local(signal, length, true, localRuns);
            RunWindows.Reads localShort = SignalModels.preparedShort(localRuns, true);
            add(localShort, fraction, lo, hi, true);
            looks.visualCapped |= localShort.visualCapped;
            for (RunWindows.Visual v : localShort.visual) {
                looks.look(v);
            }
            if (shortRows) {
                ShortWindows.Reads eight = new ShortWindows.Reads();
                ShortWindows.decode(localRuns.fractional, local, eight);
                ShortWindows.decode(localRuns.integer, local, eight);
                if (localRuns.turns != null) {
                    ShortWindows.decode(localRuns.turns, local, eight);
                }
                addShort(eight, fraction, lo, hi);
            }
            reads = RunWindows.merge(reads, local);
            // A row that lost narrow bars or spaces at the middle level: only the first long rows of a box that read nothing are tried.
            if ((length <= 384 || length != FIXED_SAMPLES && reads.symbols.isEmpty()) && length >= 76 && length <= 1536
                    && runs.count >= 45 && runs.count < 61 && (length <= 384 || current.thresholdProfiles < 8)) {
                current.thresholdProfiles += length > 384 ? 1 : 0;
                if (shifted.length < length) {
                    shifted = new float[length];
                }
                for (float level : new float[]{0.35f, 0.65f}) {
                    for (int i = 0; i < length; i++) {
                        shifted[i] = Math.max(0f, Math.min(1f, signal[i] + 0.5f - level));
                    }
                    RunWindows.runs(shifted, length, shiftedRuns);
                    reads = RunWindows.merge(reads, RunWindows.merge(RunWindows.decode(shiftedRuns, false), RunWindows.decode(shiftedRuns, true)));
                }
            }
        }
        looks.visualCapped |= reads.visualCapped;
        for (RunWindows.Visual v : reads.visual) {
            looks.look(v);
        }
        // The modules themselves, for a fixed row and for a short row at a sample a pixel that read nothing and has about the runs of a symbol.
        RunWindows.Read soft = null;
        softRead = null;
        if ((length == FIXED_SAMPLES || length >= 76 && length <= 384 && reads.symbols.isEmpty() && runs.count >= 30 && runs.count <= 85)
                && reads.rejected.isEmpty()) {
            EanModules.Trace trace = new EanModules.Trace();
            EanModules.Read fit = EanModules.decode(signal, length, trace);
            current.blurWindows += trace.blurWindows;
            if (fit != null) {
                RunWindows.Read p = new RunWindows.Read(fit.digits(), fit.reversed() ? length - 1 - fit.right() : fit.left(),
                        fit.reversed() ? length - 1 - fit.left() : fit.right(), fit.cost(), fit.gap(), fit.reversed());
                soft = p;
                boolean differs = false;
                boolean same = false;
                for (RunWindows.Read r : reads.symbols) {
                    differs |= r.overlaps(p) && !r.sameDigits(p);
                    same |= r.overlaps(p) && r.sameDigits(p);
                }
                if (differs) {
                    reads.rejected.add(new double[]{p.left(), p.right()});
                    reads.symbols.removeIf(r -> r.overlaps(p));
                } else if (!same) {
                    reads.symbols.add(p);
                    softRead = p;
                }
            }
            // Where the two ways of reading the modules differed, the runs do not read either.
            for (float[] interval : trace.rejected) {
                reads.rejected.add(new double[]{interval[0], interval[1]});
                reads.symbols.removeIf(r -> !(r.right() <= interval[0] || r.left() >= interval[1]));
            }
        }
        // A reading of the modules of a row at a sample a pixel is weak evidence: it counts on four rows, like one between short quiet zones.
        if (soft != null && length != FIXED_SAMPLES) {
            RunWindows.Reads weak = new RunWindows.Reads();
            RunWindows.Read p = soft;
            for (RunWindows.Read r : reads.symbols) {
                if (r.sameDigits(p) && r.overlaps(p)) {
                    weak.symbols.add(r);
                }
            }
            reads.symbols.removeAll(weak.symbols);
            add(weak, fraction, lo, hi, true);
        }
        add(reads, fraction, lo, hi, false);
        veto(looks, before, fraction, lo, hi);
        for (int i = before; i < observations.size(); i++) {
            if (!observations.get(i).ambiguous) {
                current.acceptedPaths++;
                break;
            }
        }
    }

    private static boolean sameStretch(double a, double b, double c, double d) {
        return Math.max(0, Math.min(b, d) - Math.max(a, c)) >= 0.8 * Math.max(b - a, d - c);
    }

    /**
     * Takes back the readings of this row that a clearer look refuses: a window that reads
     * clearly and fails its checksum, at less cost than every reading of the same stretch that
     * passes one. The stretch becomes ambiguous, as where two readings differ.
     */
    private void veto(RunWindows.Reads looks, int start, double fraction, double lo, double hi) {
        if (looks.visualCapped) {
            return;
        }
        List<RunWindows.Read> vetoes = new ArrayList<>();
        for (RunWindows.Visual v : looks.visual) {
            if (v.valid() || v.read().cost() > 0.06f || v.read().gap() < 0.1f) {
                continue;
            }
            float best = Float.POSITIVE_INFINITY;
            for (RunWindows.Visual a : looks.visual) {
                if (a.valid() && sameStretch(a.read().left(), a.read().right(), v.read().left(), v.read().right())) {
                    best = Math.min(best, a.read().cost());
                }
            }
            boolean known = false;
            for (RunWindows.Read a : vetoes) {
                known |= a.left() == v.read().left() && a.right() == v.read().right();
            }
            if (v.read().cost() < best && best != Float.POSITIVE_INFINITY && !known) {
                vetoes.add(v.read());
            }
        }
        for (RunWindows.Read v : vetoes) {
            double left = lo + (hi - lo) * (v.left() + 0.5) / length;
            double right = lo + (hi - lo) * (v.right() + 0.5) / length;
            boolean removed = false;
            for (int i = observations.size() - 1; i >= start; i--) {
                Observation o = observations.get(i);
                if (!o.ambiguous && sameStretch(o.left, o.right, left, right)) {
                    observations.remove(i);
                    removed = true;
                }
            }
            if (!removed) {
                continue;
            }
            Observation o = new Observation();
            o.ambiguous = true;
            o.digits = new int[13];
            o.fraction = fraction;
            o.left = left;
            o.right = right;
            o.cost = v.cost();
            o.gap = v.gap();
            observations.add(o);
        }
    }

    private void add(RunWindows.Reads reads, double fraction, double lo, double hi, boolean shortQuiet) {
        current.guardPass += reads.guardPass;
        for (double[] interval : reads.rejected) {
            Observation o = new Observation();
            o.shortQuiet = shortQuiet;
            o.ambiguous = true;
            o.digits = new int[13];
            o.fraction = fraction;
            o.left = lo + (hi - lo) * (interval[0] + 0.5) / length;
            o.right = lo + (hi - lo) * (interval[1] + 0.5) / length;
            observations.add(o);
        }
        for (RunWindows.Read read : reads.symbols) {
            Observation o = new Observation();
            o.shortQuiet = shortQuiet;
            o.digits = read.digits();
            o.fraction = fraction;
            o.left = lo + (hi - lo) * (read.left() + 0.5) / length;
            o.right = lo + (hi - lo) * (read.right() + 0.5) / length;
            o.cost = read.cost();
            o.gap = read.gap();
            o.reversed = read.reversed();
            o.soft = read == softRead;
            observations.add(o);
        }
    }

    /** The EAN-8 readings of a row, each once, and the stretches whose readings refused each other; 512 for a box. */
    private void addShort(ShortWindows.Reads reads, double fraction, double lo, double hi) {
        List<Observation> rows = current.shortObservations;
        for (RunWindows.Read read : reads.symbols) {
            Observation o = new Observation();
            o.digits = read.digits();
            o.fraction = fraction;
            o.left = lo + (hi - lo) * (read.left() + 0.5) / length;
            o.right = lo + (hi - lo) * (read.right() + 0.5) / length;
            o.cost = read.cost();
            o.gap = read.gap();
            o.reversed = read.reversed();
            boolean known = false;
            for (Observation a : rows) {
                known |= !a.ambiguous && Arrays.equals(a.digits, o.digits) && Math.abs(a.fraction - fraction) < 1e-9
                        && Math.min(a.right, o.right) - Math.max(a.left, o.left) > 0.9 * Math.max(a.right - a.left, o.right - o.left);
            }
            if (known) {
                continue;
            }
            if (rows.size() >= 512) {
                return;
            }
            rows.add(o);
        }
        for (double[] interval : reads.conflicts) {
            if (rows.size() >= 512) {
                return;
            }
            Observation o = new Observation();
            o.ambiguous = true;
            o.digits = new int[13];
            o.fraction = fraction;
            o.left = lo + (hi - lo) * (interval[0] + 0.5) / length;
            o.right = lo + (hi - lo) * (interval[1] + 0.5) / length;
            rows.add(o);
        }
    }

    private static final class Track {
        final List<Observation> rows = new ArrayList<>();
        boolean active = true;
    }

    private static double overlap(Observation a, Observation b) {
        return Math.min(a.right, b.right) - Math.max(a.left, b.left);
    }

    private static boolean sameStretch(Observation a, Observation b) {
        return overlap(a, b) > 0.8 * Math.max(a.right - a.left, b.right - b.left);
    }

    /**
     * The tracks of rows that count as symbols. Readings of one row over the same stretch that
     * differ, or are ambiguous, refuse each other and become barriers; equal ones are one. A
     * reading joins the track whose last row reads the same digits over the same stretch, when the
     * frame shows the bars unbroken between the two rows and no barrier lies between them. A track
     * counts with two rows of full quiet zones, or four rows, that lie a pixel apart when one of
     * them is a clear reading, and else two to twelve pixels, a module of the symbol.
     */
    private List<Track> assemble(boolean sourceSupport) {
        List<Observation> obs = new ArrayList<>(observations.size());
        for (Observation o : observations) {
            Observation c = o.copy();
            c.fraction = Math.round(c.fraction * 1e12) / 1e12;
            obs.add(c);
        }
        obs.sort((a, b) -> a.fraction != b.fraction ? Double.compare(a.fraction, b.fraction) : Double.compare(a.left, b.left));
        boolean[] keep = new boolean[obs.size()];
        for (int i = 0; i < obs.size(); i++) {
            keep[i] = !obs.get(i).ambiguous;
        }
        for (int i = 0; i < obs.size(); i++) {
            for (int j = i + 1; j < obs.size() && obs.get(j).fraction == obs.get(i).fraction; j++) {
                Observation a = obs.get(i);
                Observation b = obs.get(j);
                if (overlap(a, b) > 0 && (a.ambiguous || b.ambiguous || !Arrays.equals(a.digits, b.digits))) {
                    keep[i] = false;
                    keep[j] = false;
                }
            }
        }
        List<Observation> barriers = new ArrayList<>();
        List<Observation> unique = new ArrayList<>();
        for (int i = 0; i < obs.size(); i++) {
            Observation o = obs.get(i);
            if (!keep[i]) {
                barriers.add(o);
                continue;
            }
            boolean found = false;
            for (int k = unique.size() - 1; k >= 0 && unique.get(k).fraction == o.fraction; k--) {
                Observation a = unique.get(k);
                if (Arrays.equals(a.digits, o.digits) && sameStretch(a, o)) {
                    if (!o.shortQuiet && a.shortQuiet || o.shortQuiet == a.shortQuiet && o.cost < a.cost) {
                        unique.set(k, o);
                    }
                    found = true;
                    break;
                }
            }
            if (!found) {
                unique.add(o);
            }
        }
        List<Track> tracks = new ArrayList<>();
        Map<String, int[]> density = new HashMap<>();
        int offset = 0;
        while (offset < unique.size()) {
            int end = offset;
            while (end < unique.size() && unique.get(end).fraction == unique.get(offset).fraction) {
                end++;
            }
            List<Observation> row = unique.subList(offset, end);
            // Contradicting evidence between two rows ends the earlier track.
            for (Track track : tracks) {
                if (!track.active) {
                    continue;
                }
                Observation a = track.rows.get(track.rows.size() - 1);
                for (Observation b : barriers) {
                    if (b.fraction > a.fraction && b.fraction <= row.get(0).fraction && Math.min(a.right, b.right) > Math.max(a.left, b.left)) {
                        track.active = false;
                        break;
                    }
                }
                for (Observation b : row) {
                    if (track.active && !Arrays.equals(a.digits, b.digits) && sameStretch(a, b)) {
                        track.active = false;
                    }
                }
            }
            List<List<Integer>> links = new ArrayList<>();
            int[] degree = new int[tracks.size()];
            for (Observation b : row) {
                List<Integer> linked = new ArrayList<>();
                for (int i = 0; i < tracks.size(); i++) {
                    Track track = tracks.get(i);
                    Observation a = track.rows.get(track.rows.size() - 1);
                    if (track.active && Arrays.equals(a.digits, b.digits) && sameStretch(a, b) && connected(a, b, density)) {
                        linked.add(i);
                        degree[i]++;
                    }
                }
                links.add(linked);
            }
            // Tracks whose last rows cover one stretch are one symbol: the most recent stays.
            for (List<Integer> linked : links) {
                if (linked.size() < 2) {
                    continue;
                }
                int newest = linked.get(0);
                for (int i : linked) {
                    if (last(tracks.get(i)).fraction > last(tracks.get(newest)).fraction) {
                        newest = i;
                    }
                }
                boolean same = true;
                for (int i : linked) {
                    same &= sameStretch(last(tracks.get(i)), last(tracks.get(newest)));
                }
                if (same) {
                    for (int i : linked) {
                        if (i != newest) {
                            tracks.get(i).active = false;
                            degree[i]--;
                        }
                    }
                    linked.clear();
                    linked.add(newest);
                }
            }
            for (int j = 0; j < row.size(); j++) {
                List<Integer> linked = links.get(j);
                if (linked.size() == 1 && degree[linked.get(0)] == 1) {
                    tracks.get(linked.get(0)).rows.add(row.get(j));
                } else {
                    Track track = new Track();
                    track.rows.add(row.get(j));
                    tracks.add(track);
                }
            }
            offset = end;
        }
        double[] top = point(0.5, 0);
        double[] bottom = point(0.5, 1);
        double cross = Math.max(1, Math.hypot(bottom[0] - top[0], bottom[1] - top[1]));
        List<Track> supported = new ArrayList<>();
        for (Track track : tracks) {
            Observation a = track.rows.get(0);
            Observation b = last(track);
            int full = 0;
            boolean strong = false;
            for (Observation o : track.rows) {
                full += o.shortQuiet ? 0 : 1;
                strong |= o.cost <= 0.08f && o.gap >= 0.08f;
            }
            if (track.rows.size() < 2 || full < 2 && track.rows.size() < 4) {
                continue;
            }
            double[] left = point(a.left, a.fraction);
            double[] right = point(a.right, a.fraction);
            double pixels = strong ? 1 : Math.max(2, Math.min(12, Math.hypot(right[0] - left[0], right[1] - left[1]) / (a.digits[0] == ShortWindows.EAN8 ? 67 : 95)));
            if (b.fraction - a.fraction < (sourceSupport ? pixels / cross : 0.15) - 1e-6) {
                continue;
            }
            supported.add(track);
        }
        return supported;
    }

    private static Observation last(Track track) {
        return track.rows.get(track.rows.size() - 1);
    }

    /**
     * Whether the frame shows the bars running unbroken from the row of {@code a} to that of
     * {@code b}: at every half pixel between them, 32 pixels across the symbol keep at least half
     * the dark ones of the weaker of the two rows. A step that seems to break is looked at again
     * with 192 pixels, as few samples can fall between the bars.
     */
    private boolean connected(Observation a, Observation b, Map<String, int[]> cache) {
        double u = (a.left + a.right + b.left + b.right) / 4;
        double[] pa = point(u, a.fraction);
        double[] pb = point(u, b.fraction);
        int steps = (int) Math.ceil(Math.hypot(pb[0] - pa[0], pb[1] - pa[1]) * 2);
        if (steps > 4096) {
            return false;
        }
        int[] first = density(a, b, 0, 32, cache);
        if (first == null) {
            return false;
        }
        if (steps == 0) {
            return true;
        }
        int[] end = density(a, b, 1, 32, cache);
        if (end == null) {
            return false;
        }
        int dark = Math.min(first[0], end[0]);
        int light = Math.min(first[1], end[1]);
        for (int i = 1; i < steps; i++) {
            int[] at = density(a, b, i / (double) steps, 32, cache);
            if (at == null) {
                return false;
            }
            if (dark >= 6 && light >= 6 && at[0] * 2 < dark) {
                int[] da = density(a, b, 0, 192, cache);
                int[] db = density(a, b, 1, 192, cache);
                int[] dc = density(a, b, i / (double) steps, 192, cache);
                if (da == null || db == null || dc == null) {
                    return false;
                }
                if (Math.min(da[0], db[0]) >= 36 && Math.min(da[1], db[1]) >= 36 && dc[0] * 2 < Math.min(da[0], db[0])) {
                    return false;
                }
            }
        }
        return true;
    }

    /** How many of {@code n} pixels across the symbol, at share {@code t} of the way from one row to the other, are dark and how many light; null without contrast. */
    private int[] density(Observation a, Observation b, double t, int n, Map<String, int[]> cache) {
        double fraction = a.fraction + (b.fraction - a.fraction) * t;
        double left = a.left + (b.left - a.left) * t;
        double right = a.right + (b.right - a.right) * t;
        String key = fraction + ":" + left + ":" + right + ":" + n;
        if (cache.containsKey(key)) {
            return cache.get(key);
        }
        float[] values = new float[n];
        float lo = 255f;
        float hi = 0f;
        for (int j = 0; j < n; j++) {
            double[] p = point(left + (right - left) * (j + 0.5) / n, fraction);
            values[j] = gray(Math.round(p[0]), Math.round(p[1]));
            lo = Math.min(lo, values[j]);
            hi = Math.max(hi, values[j]);
        }
        int[] result = null;
        if (hi - lo >= 8f) {
            int dark = 0;
            int light = 0;
            for (float v : values) {
                dark += v < lo + 0.35f * (hi - lo) ? 1 : 0;
                light += v > lo + 0.65f * (hi - lo) ? 1 : 0;
            }
            result = new int[]{dark, light};
        }
        if (cache.size() < 4096) {
            cache.put(key, result);
        }
        return result;
    }

    /**
     * The symbols of all boxes as one list: readings of different digits over the same place
     * refuse each other, and of equal ones the reading with most rows stays.
     */
    private static List<Found> reconcile(List<Found> found) {
        boolean[] dropped = new boolean[found.size()];
        for (int i = 0; i < found.size(); i++) {
            for (int j = i + 1; j < found.size(); j++) {
                if (!sameSpace(found.get(i).polygon(), found.get(j).polygon())) {
                    continue;
                }
                if (!found.get(i).text().equals(found.get(j).text())) {
                    dropped[i] = true;
                    dropped[j] = true;
                }
            }
        }
        List<Found> out = new ArrayList<>();
        for (int i = 0; i < found.size(); i++) {
            if (dropped[i]) {
                continue;
            }
            Found candidate = found.get(i);
            boolean beaten = false;
            for (int j = 0; j < found.size() && !beaten; j++) {
                Found other = found.get(j);
                beaten = j != i && !dropped[j] && other.text().equals(candidate.text())
                        && sameSpace(candidate.polygon(), other.polygon())
                        && (other.support() > candidate.support() || other.support() == candidate.support() && j < i);
            }
            if (!beaten) {
                out.add(candidate);
            }
        }
        return out;
    }

    /** Whether the boxes around two outlines cover the same place: half of their union, or most of the smaller. */
    private static boolean sameSpace(double[] p, double[] q) {
        double[] a = bounds(p);
        double[] b = bounds(q);
        double w = Math.min(a[2], b[2]) - Math.max(a[0], b[0]);
        double h = Math.min(a[3], b[3]) - Math.max(a[1], b[1]);
        if (w <= 0 || h <= 0) {
            return false;
        }
        double inter = w * h;
        double areaA = Math.max(1e-9, (a[2] - a[0]) * (a[3] - a[1]));
        double areaB = Math.max(1e-9, (b[2] - b[0]) * (b[3] - b[1]));
        return inter / (areaA + areaB - inter) >= 0.5 || inter / Math.min(areaA, areaB) >= 0.85;
    }

    private static double[] bounds(double[] p) {
        double x0 = Double.MAX_VALUE;
        double y0 = Double.MAX_VALUE;
        double x1 = -Double.MAX_VALUE;
        double y1 = -Double.MAX_VALUE;
        for (int i = 0; i < 8; i += 2) {
            x0 = Math.min(x0, p[i]);
            x1 = Math.max(x1, p[i]);
            y0 = Math.min(y0, p[i + 1]);
            y1 = Math.max(y1, p[i + 1]);
        }
        // A symbol read on rows a pixel apart is a thin outline: give it a height.
        return new double[]{x0 - 1, y0 - 1, x1 + 1, y1 + 1};
    }
}
