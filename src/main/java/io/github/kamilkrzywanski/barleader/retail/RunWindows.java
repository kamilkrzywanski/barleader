/*
 * Copyright 2026 Florian Nick
 * Copyright 2026 Kamil Krzywański
 */
// SPDX-License-Identifier: Apache-2.0
package io.github.kamilkrzywanski.barleader.retail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Every EAN-13 on the runs of one profile: each 61 runs that start and end with white are a
 * window, read forwards and backwards by {@link EanRuns}. Ported from tapirscan 1.5.0
 * ({@code core/src/multi_profile.rs}: {@code sample_runs}, {@code decode_positions_mode},
 * {@code decode_short_quiet}; {@code core/src/transition.rs}: {@code merge_reads}).
 *
 * <p>Two readings of different digits over the same stretch of the profile refuse each other, and
 * the stretch is kept as rejected: rows that disagree with themselves are no evidence, and the
 * rejected stretch stops a track of agreeing rows from passing over it.
 */
final class RunWindows {
    /** The most symbols one profile gives. */
    static final int MAX_SYMBOLS = 64;

    /** The runs of a profile: where each starts and ends, in samples, and whether it is dark. */
    static final class Runs {
        double[] start = new double[256];
        double[] end = new double[256];
        boolean[] black = new boolean[256];
        int count;

        void add(double from, double to, boolean dark) {
            if (count == start.length) {
                start = Arrays.copyOf(start, count * 2);
                end = Arrays.copyOf(end, count * 2);
                black = Arrays.copyOf(black, count * 2);
            }
            start[count] = from;
            end[count] = to;
            black[count++] = dark;
        }
    }

    /** One reading: thirteen digits from {@code left} to {@code right} in samples, with its cost and lead, read against the row with {@code reversed}. */
    record Read(int[] digits, double left, double right, float cost, float gap, boolean reversed) {
        boolean overlaps(Read other) {
            return left < other.right && other.left < right;
        }

        boolean sameDigits(Read other) {
            return Arrays.equals(digits, other.digits);
        }
    }

    /** A reading as it looks, with whether its checksum holds: one that looks clear and fails it speaks against a weaker one that passes. */
    record Visual(Read read, boolean valid) {
    }

    /** The most looks kept for one row. */
    private static final int MAX_VISUAL = 512;

    /** What a profile reads: its symbols from left to right, the stretches whose readings refused each other, and how many windows had guards. */
    static final class Reads {
        List<Read> symbols = new ArrayList<>();
        final List<double[]> rejected = new ArrayList<>();
        int guardPass;
        /** How every window looked that read clearly or passed its checksum, and whether there were more than are kept. */
        final List<Visual> visual = new ArrayList<>();
        boolean visualCapped;

        void look(Visual v) {
            for (int i = 0; i < visual.size(); i++) {
                Visual old = visual.get(i);
                if (old.valid() == v.valid() && old.read().left() == v.read().left() && old.read().right() == v.read().right()
                        && old.read().sameDigits(v.read())) {
                    if (v.read().cost() < old.read().cost()) {
                        visual.set(i, v);
                    }
                    return;
                }
            }
            if (visual.size() == MAX_VISUAL) {
                visualCapped = true;
                return;
            }
            visual.add(v);
        }

        /** The same readings, with no windows counted: a way to cut the profile that changed nothing saw no new guards. */
        Reads copy() {
            Reads out = new Reads();
            out.symbols.addAll(symbols);
            out.rejected.addAll(rejected);
            out.visual.addAll(visual);
            out.visualCapped = visualCapped;
            return out;
        }
    }

    private RunWindows() {
    }

    /** The runs of {@code profile}, dark where a sample is at least a half. */
    static void runs(float[] profile, int length, Runs out) {
        out.count = 0;
        int start = 0;
        boolean black = profile[0] >= 0.5f;
        for (int i = 1; i <= length; i++) {
            boolean next = i < length && profile[i] >= 0.5f;
            if (i == length || next != black) {
                out.add(start, i, black);
                start = i;
                black = next;
            }
        }
    }

    /**
     * For a row of {@code n} samples that cuts a symbol off: how many samples it lacks, positive
     * past its end and negative before its start, or zero. Only half a symbol that reads cleanly,
     * with its outer guard and quiet zone, counts as one cut off.
     */
    static float partialExtension(Runs r, int n) {
        Float missing = EanRuns.missingAfter(r.start, r.end, r.black, r.count, n);
        if (missing != null) {
            return missing;
        }
        double[] start = new double[r.count];
        double[] end = new double[r.count];
        boolean[] black = new boolean[r.count];
        for (int i = 0; i < r.count; i++) {
            start[i] = n - r.end[r.count - 1 - i];
            end[i] = n - r.start[r.count - 1 - i];
            black[i] = r.black[r.count - 1 - i];
        }
        missing = EanRuns.missingAfter(start, end, black, r.count, n);
        return missing != null ? -missing : 0f;
    }

    /** The symbols between quiet zones of seven modules, with {@code guardBias} from widths corrected by their guards. */
    static Reads decode(Runs runs, boolean guardBias) {
        return decode(runs, guardBias, false);
    }

    /**
     * The symbols whose quiet zones are shorter than seven modules: four times the pitch of the
     * guard beside them on both sides, or six and three, or nine on one side where the other ends
     * at a dark edge inside the profile. Such a reading must be clearer than one between full
     * quiet zones, and needs more rows to count.
     */
    static Reads shortQuiet(Runs runs, boolean guardBias) {
        Reads raw = decode(runs, false, true);
        return guardBias ? merge(raw, decode(runs, true, true)) : raw;
    }

    /** {@link #decode(Runs, boolean)}, or with {@code shortQuiet} only the symbols between shorter quiet zones, without merging. */
    static Reads decode(Runs r, boolean guardBias, boolean shortQuiet) {
        Reads out = new Reads();
        List<Read> hypotheses = new ArrayList<>();
        float[] widths = new float[59];
        for (int at = 0; at + 61 <= r.count; at++) {
            if (!r.black[at + 1]) {
                continue;
            }
            double left = r.start[at + 1];
            double right = r.end[at + 59];
            float module = (float) (right - left) / 95f;
            float quietLeft = (float) (r.end[at] - r.start[at]);
            float quietRight = (float) (r.end[at + 60] - r.start[at + 60]);
            float leftPitch = (float) (r.end[at + 3] - r.start[at + 1]) / 3f;
            float rightPitch = (float) (r.end[at + 59] - r.start[at + 57]) / 3f;
            float localLeft = quietLeft / leftPitch;
            float localRight = quietRight / rightPitch;
            boolean ordinaryShort = localLeft >= 4f && localRight >= 4f
                    || localLeft >= 6f && localRight >= 3f
                    || localLeft >= 3f && localRight >= 6f;
            // A strong symbol may end beside packaging: the narrow side must end at a dark edge
            // the profile saw, not at the end of the profile.
            boolean asymmetricShort = !ordinaryShort
                    && (localLeft >= 9f && localRight >= 1f && r.end[at + 60] < r.end[r.count - 1]
                    || localRight >= 9f && localLeft >= 1f && r.start[at] > r.start[0]);
            if (module < 0.8f) {
                continue;
            }
            if (shortQuiet) {
                if (!(ordinaryShort || asymmetricShort) || quietLeft / module >= 7f && quietRight / module >= 7f) {
                    continue;
                }
            } else if (quietLeft < 7f * module || quietRight < 7f * module) {
                continue;
            }
            for (int j = 0; j < 59; j++) {
                widths[j] = (float) (r.end[at + j + 1] - r.start[at + j + 1]);
            }
            boolean guards = true;
            for (int j : EanRuns.GUARDS) {
                guards &= Math.abs(widths[j] / module - 1f) <= 0.65f;
            }
            out.guardPass += guards ? 1 : 0;
            float[] read = widths;
            if (guardBias) {
                read = EanRuns.guardBias(widths);
                if (read == null) {
                    continue;
                }
            }
            for (int pass = 0; pass < 2; pass++) {
                boolean reversed = pass == 1;
                if (shortQuiet && !(localLeft >= 4f && localRight >= 4f)) {
                    float before = reversed ? localRight : localLeft;
                    float after = reversed ? localLeft : localRight;
                    if (asymmetricShort ? before < 9f || after < 1f : before < 6f || after < 3f) {
                        continue;
                    }
                }
                float[] way = read;
                if (reversed) {
                    way = new float[59];
                    for (int j = 0; j < 59; j++) {
                        way[j] = read[58 - j];
                    }
                }
                EanRuns.Evidence evidence = EanRuns.visual(way);
                if (evidence == null) {
                    continue;
                }
                boolean valid = EanRuns.checksum(evidence.digits());
                boolean clear = evidence.cost() <= 0.06f && evidence.gap() >= 0.1f;
                if (!(shortQuiet && !clear) && !(shortQuiet && asymmetricShort && (evidence.cost() > 0.035f || evidence.gap() < 0.2f))
                        && (valid || clear)) {
                    out.look(new Visual(new Read(evidence.digits(), left - 0.5, right - 0.5, evidence.cost(), evidence.gap(), reversed), valid));
                }
                if (!valid) {
                    continue;
                }
                if (shortQuiet && (evidence.cost() > 0.06f || evidence.gap() < 0.1f)) {
                    continue;
                }
                if (shortQuiet && asymmetricShort && (evidence.cost() > 0.035f || evidence.gap() < 0.2f)) {
                    continue;
                }
                hypotheses.add(new Read(evidence.digits(), left - 0.5, right - 0.5, evidence.cost(), evidence.gap(), reversed));
            }
        }
        // A competing text over the same stretch refuses both; equal readings are one.
        boolean[] veto = new boolean[hypotheses.size()];
        for (int i = 0; i < hypotheses.size(); i++) {
            for (int j = i + 1; j < hypotheses.size(); j++) {
                if (hypotheses.get(i).overlaps(hypotheses.get(j)) && !hypotheses.get(i).sameDigits(hypotheses.get(j))) {
                    veto[i] = true;
                    veto[j] = true;
                }
            }
        }
        for (int i = 0; i < hypotheses.size(); i++) {
            Read h = hypotheses.get(i);
            if (veto[i]) {
                out.rejected.add(new double[]{h.left(), h.right()});
                continue;
            }
            boolean known = false;
            for (int k = 0; k < out.symbols.size(); k++) {
                Read old = out.symbols.get(k);
                if (old.left() == h.left() && old.right() == h.right() && old.sameDigits(h)) {
                    if (h.cost() < old.cost()) {
                        out.symbols.set(k, h);
                    }
                    known = true;
                    break;
                }
            }
            if (!known) {
                out.symbols.add(h);
            }
        }
        finish(out);
        return out;
    }

    /**
     * The readings of two ways to cut one profile: what either rejected refuses both, overlapping
     * readings of different digits refuse each other, and equal ones over the same stretch are one.
     */
    static Reads merge(Reads raw, Reads clean) {
        Reads out = new Reads();
        out.guardPass = raw.guardPass + clean.guardPass;
        out.visual.addAll(raw.visual);
        out.visualCapped = raw.visualCapped || clean.visualCapped;
        for (Visual v : clean.visual) {
            out.look(v);
        }
        out.rejected.addAll(raw.rejected);
        out.rejected.addAll(clean.rejected);
        List<Read> all = new ArrayList<>(raw.symbols);
        all.addAll(clean.symbols);
        boolean[] bad = new boolean[all.size()];
        for (int i = 0; i < all.size(); i++) {
            for (int j = i + 1; j < all.size(); j++) {
                if (all.get(i).overlaps(all.get(j)) && !all.get(i).sameDigits(all.get(j))) {
                    bad[i] = true;
                    bad[j] = true;
                }
            }
        }
        for (int i = 0; i < all.size(); i++) {
            if (bad[i]) {
                out.rejected.add(new double[]{all.get(i).left(), all.get(i).right()});
            }
        }
        for (int i = 0; i < all.size(); i++) {
            Read h = all.get(i);
            boolean refused = bad[i];
            for (double[] interval : out.rejected) {
                refused |= h.left() < interval[1] && interval[0] < h.right();
            }
            if (refused) {
                continue;
            }
            boolean known = false;
            for (Read old : out.symbols) {
                known |= old.sameDigits(h) && Math.min(old.right(), h.right()) - Math.max(old.left(), h.left())
                        > 0.8 * Math.max(old.right() - old.left(), h.right() - h.left());
            }
            if (!known) {
                out.symbols.add(h);
            }
        }
        finish(out);
        return out;
    }

    private static void finish(Reads out) {
        out.symbols.sort((a, b) -> Double.compare(a.left(), b.left()));
        if (out.symbols.size() > MAX_SYMBOLS) {
            out.symbols = new ArrayList<>(out.symbols.subList(0, MAX_SYMBOLS));
        }
    }
}
