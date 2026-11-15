/*
 * Copyright 2026 Florian Nick
 * Copyright 2026 Kamil Krzywański
 */
// SPDX-License-Identifier: Apache-2.0
package io.github.kamilkrzywanski.barleader.retail;

import io.github.kamilkrzywanski.barleader.decode.UpcERows;

import java.util.ArrayList;
import java.util.List;

/**
 * Every EAN-8 and UPC-E on the runs of one profile that an EAN-13 does not cover: each 43 runs
 * that start with a bar between quiet zones of two modules are a window of an EAN-8, each 33
 * between four modules one of a UPC-E, read forwards and backwards. Ported from tapirscan 1.5.0
 * ({@code core/src/retail_short.rs}: {@code decode_indices}, {@code decode_widths},
 * {@code decode_widths_base}, {@code insert}); where tapirscan asks its own linear decoder for a
 * second opinion on a UPC-E ({@code confirms_upce}), this asks the row decoder
 * ({@link UpcERows}).
 *
 * <p>A reading has thirteen digits like one of {@link RunWindows}, so that the same rows link
 * into the same tracks: {@link #EAN8} or {@link #UPCE} first, four zeros, and the eight digits
 * of the symbol.
 */
final class ShortWindows {
    /** The first of the thirteen digits of a reading of an EAN-8. */
    static final int EAN8 = 14;
    /** The first of the thirteen digits of a reading of a UPC-E: its eight digits are the number system, six digits and the check digit. */
    static final int UPCE = 18;
    /** The runs and modules of a UPC-E, where its nine guard runs lie, and which of its six digits are even by its check digit in system 0. */
    private static final int E_RUNS = 33;
    private static final float E_MODULES = 51f;
    private static final int[] E_GUARDS = {0, 1, 2, 27, 28, 29, 30, 31, 32};
    private static final int[] E_PARITY = {56, 52, 50, 49, 44, 38, 35, 42, 41, 37};
    /** The runs and modules of an EAN-8, and where its eleven guard runs lie. */
    private static final int RUNS = 43;
    private static final float MODULES = 67f;
    private static final int[] GUARDS = {0, 1, 2, 19, 20, 21, 22, 23, 40, 41, 42};
    /** The widest mean cost of the eight digits, the widest cost of one, and the least lead of a digit over the next best. */
    private static final float MAX_COST = 0.12f;
    private static final float MAX_DIGIT_COST = 0.35f;
    private static final float MIN_GAP = 0.05f;

    /** What a profile reads: its symbols, and the stretches whose readings refused each other. */
    static final class Reads {
        final List<RunWindows.Read> symbols = new ArrayList<>();
        final List<double[]> conflicts = new ArrayList<>();
    }

    private ShortWindows() {
    }

    /**
     * The runs of a profile between its peaks and valleys: the profile in 256 levels of
     * brightness, a turn where it comes back by eight of them, and between each two turns an
     * edge where it first crosses the level halfway between them
     * ({@code core/src/retail_peak_scratch.rs}).
     */
    static void peakRuns(float[] profile, int length, RunWindows.Runs out) {
        out.count = 0;
        if (length == 0) {
            return;
        }
        int[] row = new int[length];
        for (int i = 0; i < length; i++) {
            row[i] = (int) Math.max(0, Math.min(255, Math.round((1f - profile[i]) * 255f)));
        }
        int[] at = new int[length + 1];
        int[] level = new int[length + 1];
        int count = 0;
        int low = row[0];
        int high = row[0];
        int lowAt = 0;
        int highAt = 0;
        int direction = 0;
        for (int i = 0; i < length; i++) {
            int value = row[i];
            if (direction >= 0) {
                if (value >= high) {
                    high = value;
                    highAt = i;
                }
                if (high - value >= 8) {
                    at[count] = highAt;
                    level[count++] = high;
                    direction = -1;
                    low = value;
                    lowAt = i;
                }
            }
            if (direction <= 0) {
                if (value <= low) {
                    low = value;
                    lowAt = i;
                }
                if (value - low >= 8) {
                    at[count] = lowAt;
                    level[count++] = low;
                    direction = 1;
                    high = value;
                    highAt = i;
                }
            }
        }
        at[count] = direction == 1 ? highAt : lowAt;
        level[count++] = direction == 1 ? high : low;
        boolean dark = count > 1 && level[0] < level[1];
        float edge = 0f;
        double x = 0;
        for (int k = 1; k < count; k++) {
            float cut = (level[k - 1] + level[k]) / 2f;
            for (int i = at[k - 1] + 1; i <= at[k]; i++) {
                float previous = row[i - 1];
                float next = row[i];
                if (previous >= cut != next >= cut) {
                    float crossing = i - 1f + (cut - previous) / (next - previous);
                    double end = x + (double) (crossing - edge);
                    out.add(x, end, dark);
                    x = end;
                    dark = !dark;
                    edge = crossing;
                    break;
                }
            }
        }
        out.add(x, x + (double) (length - edge), dark);
    }

    /** Whether every bar of the runs lies inside one symbol of {@code primary}: then no other reading can stand beside it. */
    static boolean covered(RunWindows.Runs r, RunWindows.Reads primary) {
        if (primary.symbols.isEmpty()) {
            return false;
        }
        int first = -1;
        int last = -1;
        for (int i = 0; i < r.count; i++) {
            if (r.black[i]) {
                first = first < 0 ? i : first;
                last = i;
            }
        }
        if (first < 0) {
            return true;
        }
        for (RunWindows.Read read : primary.symbols) {
            if (read.left() <= r.start[first] - 0.5 && read.right() >= r.end[last] - 0.5) {
                return true;
            }
        }
        return false;
    }

    /** Adds the EAN-8 of {@code r} to {@code out}, and with {@code upcE} its UPC-E, away from what {@code primary} read or rejected. */
    static void decode(RunWindows.Runs r, RunWindows.Reads primary, Reads out, boolean upcE) {
        if (covered(r, primary)) {
            return;
        }
        float[] widths = new float[RUNS];
        float[] turned = new float[RUNS];
        for (int start = 1; start + E_RUNS < r.count; start++) {
            if (!r.black[start]) {
                continue;
            }
            double quiet = r.end[start - 1] - r.start[start - 1];
            if (quiet < 1.5) {
                continue;
            }
            double guard = 0;
            for (int i = 0; i < 3; i++) {
                guard = Math.max(guard, r.end[start + i] - r.start[start + i]);
            }
            if (quiet < 1.2 * guard) {
                continue;
            }
            for (int format = 0; format < (upcE ? 2 : 1); format++) {
                boolean eight = format == 0;
                int count = eight ? RUNS : E_RUNS;
                if (start + count >= r.count) {
                    continue;
                }
                int end = start + count;
                double left = r.start[start];
                double right = r.end[end - 1];
                double module = (right - left) / (eight ? MODULES : E_MODULES);
                // White that the profile saw on both sides, not the end of the profile: two modules for an EAN-8, four for a UPC-E.
                double least = (eight ? 2 : 4) * module;
                if (module < 0.8 || quiet < least || r.end[end] - r.start[end] < least) {
                    continue;
                }
                if (taken(primary, left, right)) {
                    continue;
                }
                float[] forward = eight ? widths : new float[count];
                float[] backward = eight ? turned : new float[count];
                for (int i = 0; i < count; i++) {
                    forward[i] = (float) (r.end[start + i] - r.start[start + i]);
                    backward[count - 1 - i] = forward[i];
                }
                for (int pass = 0; pass < 2; pass++) {
                    float[] w = pass == 0 ? forward : backward;
                    RunWindows.Read read = eight ? read(w, left - 0.5, right - 0.5, pass == 1) : upcE(w, left - 0.5, right - 0.5, pass == 1);
                    if (read != null) {
                        insert(out, read);
                    }
                }
            }
        }
    }

    /**
     * A UPC-E from 33 widths. Each of its six digits is scored in the odd and in the even set, and
     * of the twenty mixes of the two that a number system and a check digit stand for the one that
     * costs least gives both; a tie reads nothing. The UPC-A it stands for has to check, and the
     * row decoder has to read the same text from the same widths: six digits and a parity are
     * little to go by.
     */
    private static RunWindows.Read upcE(float[] w, double left, double right, boolean reversed) {
        float sum = 0f;
        for (int i = 0; i < E_RUNS; i++) {
            sum += w[i];
        }
        float module = sum / E_MODULES;
        if (module < 0.8f) {
            return null;
        }
        for (int i : E_GUARDS) {
            if (Math.abs(w[i] / module - 1f) > 0.65f) {
                return null;
            }
        }
        int[][] value = new int[6][2];
        float[][] cost = new float[6][2];
        float[][] gap = new float[6][2];
        float[][] errors = new float[4][4];
        for (int j = 0; j < 6; j++) {
            int at = 3 + j * 4;
            float four = w[at] + w[at + 1] + w[at + 2] + w[at + 3];
            float scale = four / 7f;
            if (scale < 0.55f * module || scale > 1.8f * module) {
                return null;
            }
            for (int i = 0; i < 4; i++) {
                float ratio = w[at + i] / scale;
                if (ratio < 0.4f || ratio > 4.6f) {
                    return null;
                }
                float normalized = 7f * w[at + i] / four;
                for (int width = 0; width < 4; width++) {
                    float d = normalized - (width + 1);
                    errors[i][width] = d * d;
                }
            }
            for (int side = 0; side < 2; side++) {
                boolean even = side == 1;
                float best = Float.POSITIVE_INFINITY;
                float second = Float.POSITIVE_INFINITY;
                int digit = 0;
                for (int d = 0; d < 10; d++) {
                    int[] pattern = EanRuns.DIGITS[d];
                    float c = (errors[0][pattern[even ? 3 : 0] - 1] + errors[1][pattern[even ? 2 : 1] - 1]
                            + errors[2][pattern[even ? 1 : 2] - 1] + errors[3][pattern[even ? 0 : 3] - 1]) / 4f;
                    if (c < best) {
                        second = best;
                        best = c;
                        digit = d;
                    } else {
                        second = Math.min(second, c);
                    }
                }
                value[j][side] = digit;
                cost[j][side] = best;
                gap[j][side] = second - best;
            }
        }
        int[] chosen = null;
        float bestCost = Float.POSITIVE_INFINITY;
        float bestMax = 0f;
        float bestGap = 0f;
        boolean tied = false;
        for (int system = 0; system < 2; system++) {
            for (int check = 0; check < 10; check++) {
                int parity = E_PARITY[check] ^ (system == 0 ? 0 : 63);
                int[] digits = new int[13];
                digits[0] = UPCE;
                digits[5] = system;
                digits[12] = check;
                float total = 0f;
                float max = 0f;
                float least = Float.POSITIVE_INFINITY;
                for (int j = 0; j < 6; j++) {
                    int side = parity >> (5 - j) & 1;
                    digits[6 + j] = value[j][side];
                    total += cost[j][side];
                    max = Math.max(max, cost[j][side]);
                    least = Math.min(least, gap[j][side]);
                }
                total /= 6f;
                if (total < bestCost) {
                    bestCost = total;
                    chosen = digits;
                    bestMax = max;
                    bestGap = least;
                    tied = false;
                } else if (total == bestCost) {
                    tied = true;
                }
            }
        }
        if (chosen == null || tied || !(bestCost <= MAX_COST) || bestMax > MAX_DIGIT_COST || bestGap < MIN_GAP || !expandedChecksum(chosen)) {
            return null;
        }
        StringBuilder text = new StringBuilder(8);
        for (int i = 5; i < 13; i++) {
            text.append((char) ('0' + chosen[i]));
        }
        if (!UpcERows.reads(w, text.toString())) {
            return null;
        }
        return new RunWindows.Read(chosen, left, right, bestCost, bestGap, reversed);
    }

    /** The check of the UPC-A that the eight digits of a UPC-E stand for. */
    private static boolean expandedChecksum(int[] digits) {
        int[] d = new int[8];
        System.arraycopy(digits, 5, d, 0, 8);
        int[] a = new int[12];
        a[0] = d[0];
        a[1] = d[1];
        a[2] = d[2];
        switch (d[6]) {
            case 0, 1, 2 -> {
                a[3] = d[6];
                a[8] = d[3];
                a[9] = d[4];
                a[10] = d[5];
            }
            case 3 -> {
                a[3] = d[3];
                a[9] = d[4];
                a[10] = d[5];
            }
            case 4 -> {
                a[3] = d[3];
                a[4] = d[4];
                a[10] = d[5];
            }
            default -> {
                a[3] = d[3];
                a[4] = d[4];
                a[5] = d[5];
                a[10] = d[6];
            }
        }
        a[11] = d[7];
        int sum = 0;
        for (int i = 0; i < 12; i++) {
            sum += a[11 - i] * (i % 2 == 0 ? 1 : 3);
        }
        return sum % 10 == 0;
    }

    private static boolean taken(RunWindows.Reads primary, double left, double right) {
        for (RunWindows.Read read : primary.symbols) {
            if (read.left() < right && left < read.right()) {
                return true;
            }
        }
        for (double[] interval : primary.rejected) {
            if (interval[0] < right && left < interval[1]) {
                return true;
            }
        }
        return false;
    }

    /**
     * One more reading of a profile: a stretch that conflicted takes none, a reading over a symbol
     * of other digits refuses it and itself and leaves a conflict, and of two equal ones the
     * clearer stays. A profile gives {@link RunWindows#MAX_SYMBOLS} symbols at most.
     */
    static void insert(Reads out, RunWindows.Read read) {
        for (double[] interval : out.conflicts) {
            if (interval[0] < read.right() && read.left() < interval[1]) {
                return;
            }
        }
        for (int i = 0; i < out.symbols.size(); i++) {
            RunWindows.Read old = out.symbols.get(i);
            if (!old.overlaps(read)) {
                continue;
            }
            if (!old.sameDigits(read)) {
                double from = Math.min(old.left(), read.left());
                double to = Math.max(old.right(), read.right());
                out.symbols.removeIf(r -> r.left() < to && from < r.right());
                out.conflicts.add(new double[]{from, to});
            } else if (read.cost() < old.cost()) {
                out.symbols.set(i, read);
            }
            return;
        }
        if (out.symbols.size() < RunWindows.MAX_SYMBOLS) {
            out.symbols.add(read);
        }
    }

    /** The reading of 43 widths as they are, or else with the widening of the bars that the three guards show taken out. */
    private static RunWindows.Read read(float[] w, double left, double right, boolean reversed) {
        RunWindows.Read plain = base(w, left, right, reversed);
        if (plain != null) {
            return plain;
        }
        float[] gains = {gain(w, 0, 3), gain(w, 19, 5), gain(w, 40, 3)};
        if (Math.abs(gains[0]) < 0.025f && Math.abs(gains[1]) < 0.025f && Math.abs(gains[2]) < 0.025f) {
            return null;
        }
        float[] corrected = new float[RUNS];
        for (int i = 0; i < RUNS; i++) {
            float module;
            if (i < 3) {
                module = (w[0] + w[1] + w[2]) / 3f;
            } else if (i >= 40) {
                module = (w[40] + w[41] + w[42]) / 3f;
            } else if (i >= 19 && i < 24) {
                module = (w[19] + w[20] + w[21] + w[22] + w[23]) / 5f;
            } else {
                int start = i < 19 ? 3 + (i - 3) / 4 * 4 : 24 + (i - 24) / 4 * 4;
                module = (w[start] + w[start + 1] + w[start + 2] + w[start + 3]) / 7f;
            }
            float g = i < 21 ? gains[0] + (gains[1] - gains[0]) * i / 21f : gains[1] + (gains[2] - gains[1]) * (i - 21) / 21f;
            corrected[i] = w[i] - g * module * (i % 2 == 0 ? 1f : -1f);
            if (corrected[i] <= 0f) {
                return null;
            }
        }
        return base(corrected, left, right, reversed);
    }

    /** How much wider the bars of {@code count} guard runs are than their spaces, as a share of a module. */
    private static float gain(float[] w, int start, int count) {
        float dark = 0f;
        float light = 0f;
        int darks = 0;
        int lights = 0;
        for (int i = start; i < start + count; i++) {
            if (i % 2 == 0) {
                dark += w[i];
                darks++;
            } else {
                light += w[i];
                lights++;
            }
        }
        float a = dark / darks;
        float b = light / lights;
        return Math.max(-0.45f, Math.min(0.45f, (a - b) / (a + b)));
    }

    private static RunWindows.Read base(float[] w, double left, double right, boolean reversed) {
        float sum = 0f;
        for (int i = 0; i < RUNS; i++) {
            sum += w[i];
        }
        float module = sum / MODULES;
        if (module < 0.8f) {
            return null;
        }
        for (int i : GUARDS) {
            if (Math.abs(w[i] / module - 1f) > 0.65f) {
                return null;
            }
        }
        for (int j = 0; j < 8; j++) {
            int at = 3 + j * 4 + (j >= 4 ? 5 : 0);
            float scale = (w[at] + w[at + 1] + w[at + 2] + w[at + 3]) / 7f;
            if (scale < 0.55f * module || scale > 1.8f * module) {
                return null;
            }
            for (int i = 0; i < 4; i++) {
                float ratio = w[at + i] / scale;
                if (ratio < 0.4f || ratio > 4.6f) {
                    return null;
                }
            }
        }
        int[] digits = new int[13];
        digits[0] = EAN8;
        float cost = 0f;
        float max = 0f;
        float gap = Float.POSITIVE_INFINITY;
        float[][] errors = new float[4][4];
        for (int j = 0; j < 8; j++) {
            int at = 3 + j * 4 + (j >= 4 ? 5 : 0);
            float four = w[at] + w[at + 1] + w[at + 2] + w[at + 3];
            for (int i = 0; i < 4; i++) {
                float normalized = 7f * w[at + i] / four;
                for (int width = 0; width < 4; width++) {
                    float d = normalized - (width + 1);
                    errors[i][width] = d * d;
                }
            }
            float best = Float.POSITIVE_INFINITY;
            float second = Float.POSITIVE_INFINITY;
            int digit = 0;
            for (int d = 0; d < 10; d++) {
                int[] pattern = EanRuns.DIGITS[d];
                float c = (errors[0][pattern[0] - 1] + errors[1][pattern[1] - 1] + errors[2][pattern[2] - 1] + errors[3][pattern[3] - 1]) / 4f;
                if (c < best) {
                    second = best;
                    best = c;
                    digit = d;
                } else {
                    second = Math.min(second, c);
                }
            }
            digits[5 + j] = digit;
            cost += best;
            max = Math.max(max, best);
            gap = Math.min(gap, second - best);
        }
        cost /= 8f;
        if (!(cost <= MAX_COST) || max > MAX_DIGIT_COST || gap < MIN_GAP || !checksum(digits)) {
            return null;
        }
        return new RunWindows.Read(digits, left, right, cost, gap, reversed);
    }

    /** The GTIN check of the eight digits of a reading. */
    static boolean checksum(int[] digits) {
        int sum = 0;
        for (int i = 0; i < 8; i++) {
            sum += digits[12 - i] * (i % 2 == 0 ? 1 : 3);
        }
        return sum % 10 == 0;
    }
}
