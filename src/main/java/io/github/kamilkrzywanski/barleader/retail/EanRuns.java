/*
 * Copyright 2026 Florian Nick
 * Copyright 2026 Kamil Krzywański
 */
// SPDX-License-Identifier: Apache-2.0
package io.github.kamilkrzywanski.barleader.retail;

import java.util.Arrays;

/**
 * An EAN-13 from the 59 run widths between its quiet zones, by how the whole symbol looks. Ported
 * from tapirscan 1.5.0 ({@code core/src/run_ean.rs}: {@code decode_visual_evidence},
 * {@code guard_bias_widths}).
 *
 * <p>Each digit is scored against the ten patterns by the squared distance of its four runs, in
 * sevenths of the digit, and the first digit is the one whose mix of odd and even left digits
 * costs least over all twelve. The checksum only refuses that reading; it never chooses a weaker
 * one in its place.
 */
public final class EanRuns {
    /** The element widths of the digits 0 to 9 of the odd left set, space first; the even set is each of them backwards. */
    static final int[][] DIGITS = {
            {3, 2, 1, 1}, {2, 2, 2, 1}, {2, 1, 2, 2}, {1, 4, 1, 1}, {1, 1, 3, 2},
            {1, 2, 3, 1}, {1, 1, 1, 4}, {1, 3, 1, 2}, {1, 2, 1, 3}, {3, 1, 1, 2}
    };
    /** Which of the six left digits are even, by the first digit; the first of them is the high bit. */
    private static final int[] PARITY = {0, 11, 13, 14, 19, 25, 28, 21, 22, 26};
    /** Where the eleven guard runs lie among the 59. */
    static final int[] GUARDS = {0, 1, 2, 27, 28, 29, 30, 31, 56, 57, 58};
    /** The widest mean cost of the twelve digits, the widest cost of one, and the least lead of a digit over the next best. */
    private static final float MAX_COST = 0.12f;
    private static final float MAX_DIGIT_COST = 0.35f;
    private static final float MIN_GAP = 0.05f;

    /** Thirteen digits with the mean cost of the twelve read and the least lead of one over its runner-up. */
    public record Evidence(int[] digits, float cost, float gap) {
    }

    private EanRuns() {
    }

    /** The digits the 59 widths look most like, whatever their checksum, or null when no reading is clear. */
    public static Evidence visual(float[] w) {
        float sum = 0f;
        for (int i = 0; i < 59; i++) {
            if (!(w[i] > 0f) || Float.isInfinite(w[i])) {
                return null;
            }
            sum += w[i];
        }
        float module = sum / 95f;
        if (module < 0.8f) {
            return null;
        }
        for (int i : GUARDS) {
            if (Math.abs(w[i] / module - 1f) > 0.65f) {
                return null;
            }
        }
        // For each of the twelve digits the best odd and even digit (the right half has one set).
        int[][] value = new int[12][2];
        float[][] cost = new float[12][2];
        float[][] gap = new float[12][2];
        float[][] errors = new float[4][4];
        for (int j = 0; j < 12; j++) {
            int start = j < 6 ? 3 + j * 4 : 32 + (j - 6) * 4;
            float four = w[start] + w[start + 1] + w[start + 2] + w[start + 3];
            float scale = four / 7f;
            if (scale < 0.55f * module || scale > 1.8f * module) {
                return null;
            }
            for (int i = 0; i < 4; i++) {
                float ratio = w[start + i] / scale;
                if (ratio < 0.4f || ratio > 4.6f) {
                    return null;
                }
                float normalized = 7f * w[start + i] / four;
                for (int width = 0; width < 4; width++) {
                    float d = normalized - (width + 1);
                    errors[i][width] = d * d;
                }
            }
            for (int side = 0; side < (j < 6 ? 2 : 1); side++) {
                boolean even = side == 1;
                float best = Float.POSITIVE_INFINITY;
                float second = Float.POSITIVE_INFINITY;
                int digit = 0;
                for (int d = 0; d < 10; d++) {
                    int[] pattern = DIGITS[d];
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
        for (int first = 0; first < 10; first++) {
            int[] digits = new int[13];
            digits[0] = first;
            float total = 0f;
            float max = 0f;
            float least = Float.POSITIVE_INFINITY;
            for (int j = 0; j < 12; j++) {
                int side = j < 6 && (PARITY[first] >> (5 - j) & 1) == 1 ? 1 : 0;
                digits[j + 1] = value[j][side];
                total += cost[j][side];
                max = Math.max(max, cost[j][side]);
                least = Math.min(least, gap[j][side]);
            }
            total /= 12f;
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
        if (tied || !(bestCost <= MAX_COST) || bestMax > MAX_DIGIT_COST || bestGap < MIN_GAP) {
            return null;
        }
        return new Evidence(chosen, bestCost, bestGap);
    }

    /**
     * How many samples a row lacks to hold the symbol whose first half it shows, or null: after
     * white of four times its first bar, a start guard, {@code digits} digits that read cleanly
     * in either left set, and a middle guard, of a symbol that with five modules of quiet zone
     * would end past sample {@code n}. The runs are given by where each starts and ends.
     */
    static Float missingAfter(double[] start, double[] end, boolean[] black, int count, int n) {
        float[] widths = new float[3 + 4 * 6 + 5];
        for (int i = 0; i + 1 < count; i++) {
            float quiet = (float) (end[i] - start[i]);
            if (black[i] || quiet < 4f * (float) (end[i + 1] - start[i + 1])) {
                continue;
            }
            // Six digits of an EAN-13 or UPC-A, or four of an EAN-8, whose row is made longer all the same.
            for (int digits = 6; digits >= 4; digits -= 2) {
                int need = 3 + 4 * digits + 5;
                if (i + 1 + need > count) {
                    continue;
                }
                for (int k = 0; k < need; k++) {
                    widths[k] = (float) (end[i + 1 + k] - start[i + 1 + k]);
                }
                float module = halfSymbolModule(widths, digits);
                if (module < 0f || quiet < 5f * module && start[i] > 0) {
                    continue;
                }
                float missing = (float) start[i + 1] + ((digits == 6 ? 95f : 67f) + 5f) * module - n;
                return missing > 0f ? missing : null;
            }
        }
        return null;
    }

    /** The module of half a symbol in {@code widths}: guard, digits and middle guard under the limits of a whole one; -1 when it is none. */
    private static float halfSymbolModule(float[] widths, int digits) {
        int runs = 3 + 4 * digits + 5;
        float sum = 0f;
        for (int i = 0; i < runs; i++) {
            sum += widths[i];
        }
        float module = sum / (3 + 7 * digits + 5);
        if (!(module >= 0.8f)) {
            return -1f;
        }
        int middle = 3 + 4 * digits;
        for (int i = 0; i < runs; i++) {
            if ((i < 3 || i >= middle) && Math.abs(widths[i] / module - 1f) > 0.65f) {
                return -1f;
            }
        }
        float total = 0f;
        for (int j = 0; j < digits; j++) {
            int at = 3 + 4 * j;
            float four = widths[at] + widths[at + 1] + widths[at + 2] + widths[at + 3];
            float scale = four / 7f;
            if (scale < 0.55f * module || scale > 1.8f * module) {
                return -1f;
            }
            float best = Float.POSITIVE_INFINITY;
            for (int i = 0; i < 4; i++) {
                float ratio = widths[at + i] / scale;
                if (ratio < 0.4f || ratio > 4.6f) {
                    return -1f;
                }
            }
            for (int d = 0; d < 10; d++) {
                float odd = 0f;
                float even = 0f;
                for (int i = 0; i < 4; i++) {
                    float normalized = 7f * widths[at + i] / four;
                    float a = normalized - DIGITS[d][i];
                    float b = normalized - DIGITS[d][3 - i];
                    odd += a * a;
                    even += b * b;
                }
                best = Math.min(best, Math.min(odd, even) / 4f);
            }
            if (best > MAX_DIGIT_COST) {
                return -1f;
            }
            total += best;
        }
        return total / digits <= MAX_COST ? module : -1f;
    }

    /** The 95 modules of thirteen digits, a bar as one. */
    public static float[] modules(int[] digits) {
        float[] out = new float[95];
        out[0] = 1f;
        out[2] = 1f;
        out[46] = 1f;
        out[48] = 1f;
        out[92] = 1f;
        out[94] = 1f;
        for (int j = 0; j < 12; j++) {
            int at = j < 6 ? 3 + j * 7 : 50 + (j - 6) * 7;
            boolean even = j < 6 && (PARITY[digits[0]] >> (5 - j) & 1) == 1;
            // A left digit starts with a space, a right one with a bar; an even left digit is the odd one backwards.
            boolean bar = j >= 6;
            for (int i = 0; i < 4; i++) {
                int width = DIGITS[digits[j + 1]][even ? 3 - i : i];
                for (int k = 0; k < width; k++) {
                    out[at++] = bar ? 1f : 0f;
                }
                bar = !bar;
            }
        }
        return out;
    }

    /** The GTIN check of thirteen digits. */
    static boolean checksum(int[] digits) {
        int sum = 0;
        for (int i = 0; i < 13; i++) {
            sum += digits[i] * (i % 2 == 0 ? 1 : 3);
        }
        return sum % 10 == 0;
    }

    /**
     * The 59 widths with the widening of the bars at the cost of the spaces, measured on the
     * guards alone, taken out: one amount for the symbol when its guards agree on it, else one
     * that changes from the start guard to the middle and from there to the end. Null when the
     * guards show no widening worth taking out, or do not look like guards.
     */
    static float[] guardBias(float[] w) {
        float[] corrected = guardBiasGlobal(w);
        return corrected != null ? corrected : guardBiasSpatial(w);
    }

    private static float[] guardBiasGlobal(float[] w) {
        float sum = 0f;
        for (int i = 0; i < 59; i++) {
            if (!(w[i] > 0f) || Float.isInfinite(w[i])) {
                return null;
            }
            sum += w[i];
        }
        float module = sum / 95f;
        float[] black = {w[0], w[2], w[28], w[30], w[56], w[58]};
        float[] white = {w[1], w[27], w[29], w[31], w[57]};
        Arrays.sort(black);
        Arrays.sort(white);
        float b = (black[2] + black[3]) / 2f;
        float s = white[2];
        float bias = (b - s) * 0.5f;
        float pitch = (b + s) / 2f;
        if (module < 0.8f || Math.abs(bias) < 0.04f * module || Math.abs(bias) > 0.4f * module) {
            return null;
        }
        if (pitch < 0.7f * module || pitch > 1.3f * module) {
            return null;
        }
        for (float v : black) {
            if (Math.abs(v - bias - pitch) > 0.35f * module) {
                return null;
            }
        }
        for (float v : white) {
            if (Math.abs(v + bias - pitch) > 0.35f * module) {
                return null;
            }
        }
        float[] out = new float[59];
        for (int i = 0; i < 59; i++) {
            out[i] = w[i] - (i % 2 == 0 ? bias : -bias);
        }
        return out;
    }

    private static float[] guardBiasSpatial(float[] w) {
        float total = 0f;
        for (int i = 0; i < 59; i++) {
            if (!(w[i] > 0f) || Float.isInfinite(w[i])) {
                return null;
            }
            total += w[i];
        }
        float module = total / 95f;
        if (module < 0.8f) {
            return null;
        }
        int[][] groups = {{0, 1, 2}, {27, 28, 29, 30, 31}, {56, 57, 58}};
        float[] bias = new float[3];
        float[] pitch = new float[3];
        float[] anchors = new float[3];
        float[] centers = new float[59];
        float offset = 0f;
        for (int i = 0; i < 59; i++) {
            centers[i] = offset + w[i] * 0.5f;
            offset += w[i];
        }
        for (int k = 0; k < 3; k++) {
            float black = 0f;
            float white = 0f;
            int blacks = 0;
            int whites = 0;
            for (int i : groups[k]) {
                if (i % 2 == 0) {
                    black += w[i];
                    blacks++;
                } else {
                    white += w[i];
                    whites++;
                }
            }
            black /= blacks;
            white /= whites;
            bias[k] = (black - white) * 0.5f;
            pitch[k] = (black + white) / 2f;
            if (pitch[k] < 0.55f * module || pitch[k] > 1.8f * module || Math.abs(bias[k]) > 0.4f * pitch[k]) {
                return null;
            }
            float anchor = 0f;
            for (int i : groups[k]) {
                float corrected = w[i] - (i % 2 == 0 ? bias[k] : -bias[k]);
                if (Math.abs(corrected - pitch[k]) > 0.35f * pitch[k]) {
                    return null;
                }
                anchor += centers[i];
            }
            anchors[k] = anchor / groups[k].length;
        }
        boolean small = true;
        for (int k = 0; k < 3; k++) {
            small &= Math.abs(bias[k]) < 0.04f * pitch[k];
        }
        if (small) {
            return null;
        }
        float[] out = new float[59];
        for (int i = 0; i < 59; i++) {
            int k = centers[i] > anchors[1] ? 1 : 0;
            float t = Math.max(0f, Math.min(1f, (centers[i] - anchors[k]) / (anchors[k + 1] - anchors[k])));
            float b = bias[k] + t * (bias[k + 1] - bias[k]);
            out[i] = w[i] - (i % 2 == 0 ? b : -b);
        }
        return out;
    }
}
