/*
 * Copyright 2026 Florian Nick
 * Copyright 2026 Kamil Krzywański
 */
// SPDX-License-Identifier: Apache-2.0
package io.github.kamilkrzywanski.barleader.retail;

import java.util.ArrayList;
import java.util.List;

/**
 * An EAN-13 from the darkness of its 95 modules, for a row whose bars are too blurred to count as
 * runs. Ported from tapirscan 1.5.0 ({@code core/src/profile.rs}: the search for the ends of the
 * symbol; {@code core/src/ean.rs}: {@code visual_fit}; {@code core/src/ean_blur.rs}).
 *
 * <p>The symbol may start at any of the first ten rises of the row in its first third and end at
 * any of the last ten falls in its last third; the four pairs whose eleven guard modules look most
 * like guards are read. Each is read two ways: against sharp modules, each digit by the squared
 * distance of its seven modules, and against modules blurred by a Gaussian of 0.45, 0.65 or 0.85
 * of a module, each digit by its five inner modules, where the guards fit one of those blurs. As
 * with the runs, the first digit is the mix of odd and even digits that costs least, and the
 * checksum only refuses. Two different texts on one row refuse the whole row.
 */
final class EanModules {
    /** The seven modules of the digits 0 to 9 in the odd left set. */
    private static final String[] L = {"0001101", "0011001", "0010011", "0111101", "0100011", "0110001", "0101111", "0111011", "0110111", "0001011"};
    /** Which of the six left digits are even, by the first digit; the first of them is the high bit. */
    private static final int[] PARITY = {0, 11, 13, 14, 19, 25, 28, 21, 22, 26};
    /** The modules of the digits in the odd left, even left and right sets. */
    private static final int[][][] BITS = new int[3][10][7];
    /** Where the eleven guard modules lie and what they are. */
    private static final int[] GUARD_AT = {0, 1, 2, 45, 46, 47, 48, 49, 92, 93, 94};
    private static final float[] GUARD_BIT = {1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1};
    /** The guard modules whose neighbours on both sides are guard modules or quiet zone too. */
    private static final int[] INNER_GUARDS = {0, 1, 46, 47, 48, 93, 94};
    /** The share of a module blurred by 0.45, 0.65 and 0.85 of a module that stays in it, and that reaches the next and the one after. */
    private static final float[][] KERNELS = {
            {0.7334795f, 0.1328312f, 0.0004290465f}, {0.5583107f, 0.21039528f, 0.010449389f}, {0.4450809f, 0.24016584f, 0.037293702f}
    };
    /** What the five inner modules of each digit of each set look like under each blur. */
    private static final float[][][][] TEMPLATES = new float[3][3][10][5];
    private static final float MAX_GUARD = 0.008f;
    private static final float MAX_GUARD_RESIDUAL = 0.04f;
    private static final float MAX_FIT = 0.01f;
    private static final float MIN_DIGIT_GAP = 0.001f;
    private static final float MIN_PARITY_GAP = 0.0002f;
    private static final float MIN_MODEL_GAP = 0.0002f;
    /** The widest mean cost of the sharp reading and its least lead of a digit. */
    private static final float SHARP_COST = 0.1f;
    private static final float SHARP_GAP = 0.02f;

    static {
        for (int d = 0; d < 10; d++) {
            for (int i = 0; i < 7; i++) {
                BITS[0][d][i] = L[d].charAt(i) - '0';
                BITS[1][d][i] = 1 - (L[d].charAt(6 - i) - '0');
                BITS[2][d][i] = 1 - (L[d].charAt(i) - '0');
            }
        }
        for (int model = 0; model < 3; model++) {
            for (int side = 0; side < 3; side++) {
                for (int d = 0; d < 10; d++) {
                    for (int position = 1; position <= 5; position++) {
                        float sum = 0f;
                        for (int offset = -2; offset <= 2; offset++) {
                            int i = position + offset;
                            // A left digit follows a bar and is followed by a space; a right digit the other way.
                            float v = i < 0 ? side == 2 ? 0f : 1f : i > 6 ? side == 2 ? 1f : 0f : BITS[side][d][i];
                            sum += v * KERNELS[model][Math.abs(offset)];
                        }
                        TEMPLATES[model][side][d][position - 1] = sum;
                    }
                }
            }
        }
    }

    /** Thirteen digits from {@code left} to {@code right} in samples of the row as given, read backwards with {@code reversed}. */
    record Read(int[] digits, float cost, float gap, float left, float right, boolean reversed) {
    }

    /** What the search saw besides its reading: the pairs of ends whose guards could be blurred guards, and the stretches whose two readings differed. */
    static final class Trace {
        int blurWindows;
        final List<float[]> rejected = new ArrayList<>();
    }

    private record Fit(int[] digits, float cost, float gap) {
    }

    private EanModules() {
    }

    /** The one text {@code length} samples of a normalized row read as, dark as one, or null. */
    static Read decode(float[] p, int length, Trace trace) {
        Read accepted = null;
        float[] starts = new float[10];
        float[] ends = new float[10];
        float[] modules = new float[95];
        float[] centers = new float[95];
        float[] observed = new float[7];
        for (int pass = 0; pass < 2; pass++) {
            boolean reverse = pass == 1;
            int ns = 0;
            int ne = 0;
            for (int i = 1; i < length; i++) {
                float a = value(p, length, i - 1, reverse);
                float b = value(p, length, i, reverse);
                if (a < 0.5f && b >= 0.5f && i < length * 0.35f && ns < 10) {
                    starts[ns++] = i - 0.5f;
                }
                if (a >= 0.5f && b < 0.5f && i > length * 0.65f) {
                    if (ne < 10) {
                        ends[ne++] = i - 0.5f;
                    } else {
                        System.arraycopy(ends, 1, ends, 0, 9);
                        ends[9] = i - 0.5f;
                    }
                }
            }
            // The four pairs of ends whose guards look most like guards.
            float[][] best = new float[4][];
            float[] bestGuard = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY};
            for (int s = 0; s < ns; s++) {
                hypothesis:
                for (int e = 0; e < ne; e++) {
                    float guard = 0f;
                    for (int k = 0; k < 11; k++) {
                        float v = module(p, length, starts[s], ends[e], GUARD_AT[k], reverse) - GUARD_BIT[k];
                        guard += v * v / 11f;
                        if (guard >= bestGuard[3]) {
                            continue hypothesis;
                        }
                    }
                    for (int i = 0; i < 4; i++) {
                        if (guard < bestGuard[i]) {
                            for (int j = 3; j > i; j--) {
                                best[j] = best[j - 1];
                                bestGuard[j] = bestGuard[j - 1];
                            }
                            best[i] = new float[]{starts[s], ends[e]};
                            bestGuard[i] = guard;
                            break;
                        }
                    }
                }
            }
            for (float[] ends2 : best) {
                if (ends2 == null) {
                    continue;
                }
                float left = ends2[0];
                float right = ends2[1];
                float guard = 0f;
                for (int k = 0; k < 11; k++) {
                    float v = module(p, length, left, right, GUARD_AT[k], reverse) - GUARD_BIT[k];
                    guard += v * v;
                }
                for (int k = 0; k < 7; k++) {
                    observed[k] = sample(p, length, left + (INNER_GUARDS[k] + 0.5f) * (right - left) / 95f, reverse);
                }
                boolean sharpAllowed = guard / 11f <= SHARP_COST;
                boolean blurAllowed = blurredGuardPossible(observed);
                if (!sharpAllowed && !blurAllowed) {
                    continue;
                }
                Fit sharp = null;
                if (sharpAllowed) {
                    for (int i = 0; i < 95; i++) {
                        modules[i] = module(p, length, left, right, i, reverse);
                    }
                    sharp = sharp(modules);
                }
                Fit blurred = null;
                if (blurAllowed) {
                    trace.blurWindows++;
                    for (int i = 0; i < 95; i++) {
                        centers[i] = sample(p, length, left + (i + 0.5f) * (right - left) / 95f, reverse);
                    }
                    blurred = blurred(centers);
                }
                // Neither way of reading can settle between two texts, and no checksum either.
                if (sharp != null && blurred != null && !java.util.Arrays.equals(sharp.digits(), blurred.digits())) {
                    trace.rejected.add(reverse ? new float[]{length - 1 - right, length - 1 - left} : new float[]{left, right});
                    return null;
                }
                Fit result = sharp != null && blurred != null ? sharp.cost() <= blurred.cost() ? sharp : blurred : sharp != null ? sharp : blurred;
                if (result == null) {
                    continue;
                }
                if (accepted != null && !java.util.Arrays.equals(accepted.digits(), result.digits())) {
                    return null;
                }
                if (accepted == null || result.cost() < accepted.cost()) {
                    accepted = new Read(result.digits(), result.cost(), result.gap(), left, right, reverse);
                }
            }
        }
        return accepted;
    }

    private static float value(float[] p, int length, int i, boolean reverse) {
        return p[reverse ? length - 1 - i : i];
    }

    /** The row at {@code x} samples from its start, or from its end with {@code reverse}, between the two samples around it. */
    private static float sample(float[] p, int length, float x, boolean reverse) {
        x = Math.max(0f, Math.min(length - 1, x));
        int i = (int) x;
        float a = value(p, length, i, reverse);
        return a + (value(p, length, Math.min(i + 1, length - 1), reverse) - a) * (x - i);
    }

    /** Module {@code i} of a symbol from {@code left} to {@code right}: the mean of its middle and a sixth of a module to either side. */
    private static float module(float[] p, int length, float left, float right, int i, boolean reverse) {
        float pitch = (right - left) / 95f;
        float center = left + (i + 0.5f) * pitch;
        return (sample(p, length, center - pitch / 6f, reverse) + sample(p, length, center, reverse) + sample(p, length, center + pitch / 6f, reverse)) / 3f;
    }

    /** The text of 95 sharp modules: guards and the mean of the digits within {@link #SHARP_COST}, each digit ahead by {@link #SHARP_GAP}, and the checksum. */
    private static Fit sharp(float[] p) {
        float guard = 0f;
        for (int k = 0; k < 11; k++) {
            float v = p[GUARD_AT[k]] - GUARD_BIT[k];
            guard += v * v;
        }
        if (guard / 11f > SHARP_COST) {
            return null;
        }
        int[][] value = new int[12][2];
        float[][] cost = new float[12][2];
        float[][] gap = new float[12][2];
        for (int j = 0; j < 12; j++) {
            int start = j < 6 ? 3 + j * 7 : 50 + (j - 6) * 7;
            for (int side = 0; side < (j < 6 ? 2 : 1); side++) {
                int[][] patterns = BITS[j < 6 ? side : 2];
                float best = Float.POSITIVE_INFINITY;
                float second = Float.POSITIVE_INFINITY;
                int digit = 0;
                for (int d = 0; d < 10; d++) {
                    float c = 0f;
                    for (int i = 0; i < 7; i++) {
                        float v = p[start + i] - patterns[d][i];
                        c += v * v;
                    }
                    c /= 7f;
                    if (c < best) {
                        second = best;
                        best = c;
                        digit = d;
                    } else if (c < second) {
                        second = c;
                    }
                }
                value[j][side] = digit;
                cost[j][side] = best;
                gap[j][side] = second - best;
            }
        }
        int[] chosen = null;
        float bestCost = Float.POSITIVE_INFINITY;
        float bestGap = 0f;
        boolean tied = false;
        for (int first = 0; first < 10; first++) {
            int[] digits = new int[13];
            digits[0] = first;
            float total = 0f;
            float least = Float.POSITIVE_INFINITY;
            for (int j = 0; j < 12; j++) {
                int side = j < 6 && (PARITY[first] >> (5 - j) & 1) == 1 ? 1 : 0;
                digits[j + 1] = value[j][side];
                total += cost[j][side];
                least = Math.min(least, gap[j][side]);
            }
            total /= 12f;
            if (total < bestCost) {
                bestCost = total;
                bestGap = least;
                chosen = digits;
                tied = false;
            } else if (total == bestCost) {
                tied = true;
            }
        }
        if (chosen == null || tied || bestCost > SHARP_COST || bestGap < SHARP_GAP || !EanRuns.checksum(chosen)) {
            return null;
        }
        return new Fit(chosen, bestCost, bestGap);
    }

    private static float guardTarget(int model, int position) {
        float[] k = KERNELS[model];
        return position == 0 || position == 94 ? k[0] + k[2] : position == 46 || position == 48 ? k[0] + 2f * k[2] : 2f * k[1];
    }

    /** How far the seven inner guard modules are from guards under blur {@code model}, or -1 when too far. */
    private static float guardFit(float[] values, int model) {
        float cost = 0f;
        for (int j = 0; j < 7; j++) {
            float d = values[j] - guardTarget(model, INNER_GUARDS[j]);
            if (d * d > MAX_GUARD_RESIDUAL) {
                return -1f;
            }
            cost += d * d;
        }
        cost /= 7f;
        return cost <= MAX_GUARD ? cost : -1f;
    }

    private static boolean blurredGuardPossible(float[] values) {
        for (int model = 0; model < 3; model++) {
            if (guardFit(values, model) >= 0f) {
                return true;
            }
        }
        return false;
    }

    /**
     * The text of 95 module middles under the blur that fits best, when it fits clearly: no other
     * blur within {@link #MIN_MODEL_GAP}, and no other blur that reads another text plausibly,
     * whatever that text's checksum.
     */
    private static Fit blurred(float[] p) {
        float[] guards = new float[7];
        for (int k = 0; k < 7; k++) {
            guards[k] = p[INNER_GUARDS[k]];
        }
        Fit[] reads = new Fit[3];
        float[] scores = new float[3];
        float[] parityGaps = new float[3];
        Fit best = null;
        int bestModel = -1;
        float second = Float.POSITIVE_INFINITY;
        for (int model = 0; model < 3; model++) {
            float guard = guardFit(guards, model);
            if (guard < 0f) {
                continue;
            }
            int[][] value = new int[12][2];
            float[][] cost = new float[12][2];
            float[][] gap = new float[12][2];
            for (int j = 0; j < 12; j++) {
                int start = j < 6 ? 3 + j * 7 : 50 + (j - 6) * 7;
                for (int side = 0; side < (j < 6 ? 2 : 1); side++) {
                    float[][] templates = TEMPLATES[model][j < 6 ? side : 2];
                    float least = Float.POSITIVE_INFINITY;
                    float next = Float.POSITIVE_INFINITY;
                    int digit = 0;
                    for (int d = 0; d < 10; d++) {
                        float c = 0f;
                        for (int i = 0; i < 5; i++) {
                            float delta = p[start + i + 1] - templates[d][i];
                            c += delta * delta;
                        }
                        c /= 5f;
                        if (c < least) {
                            next = least;
                            least = c;
                            digit = d;
                        } else if (c < next) {
                            next = c;
                        }
                    }
                    value[j][side] = digit;
                    cost[j][side] = least;
                    gap[j][side] = next - least;
                }
            }
            int[] chosen = new int[13];
            float chosenCost = Float.POSITIVE_INFINITY;
            float chosenGap = 0f;
            float nextCost = Float.POSITIVE_INFINITY;
            for (int first = 0; first < 10; first++) {
                int[] digits = new int[13];
                digits[0] = first;
                float total = 0f;
                float least = Float.POSITIVE_INFINITY;
                for (int j = 0; j < 12; j++) {
                    int side = j < 6 && (PARITY[first] >> (5 - j) & 1) == 1 ? 1 : 0;
                    digits[j + 1] = value[j][side];
                    total += cost[j][side];
                    least = Math.min(least, gap[j][side]);
                }
                total /= 12f;
                if (total < chosenCost) {
                    nextCost = chosenCost;
                    chosenCost = total;
                    chosenGap = least;
                    chosen = digits;
                } else if (total < nextCost) {
                    nextCost = total;
                }
            }
            reads[model] = new Fit(chosen, chosenCost, chosenGap);
            scores[model] = (chosenCost * 60f + guard * 7f) / 67f;
            parityGaps[model] = nextCost - chosenCost;
            if (best == null || scores[model] < scores[bestModel]) {
                second = best == null ? Float.POSITIVE_INFINITY : scores[bestModel];
                best = reads[model];
                bestModel = model;
            } else {
                second = Math.min(second, scores[model]);
            }
        }
        if (best == null || !plausible(best, parityGaps[bestModel]) || second - scores[bestModel] < MIN_MODEL_GAP) {
            return null;
        }
        for (int model = 0; model < 3; model++) {
            if (reads[model] != null && plausible(reads[model], parityGaps[model]) && !java.util.Arrays.equals(reads[model].digits(), best.digits())) {
                return null;
            }
        }
        return EanRuns.checksum(best.digits()) ? best : null;
    }

    private static boolean plausible(Fit fit, float parityGap) {
        return fit.cost() <= MAX_FIT && fit.gap() >= MIN_DIGIT_GAP && parityGap >= MIN_PARITY_GAP;
    }
}
