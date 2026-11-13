/*
 * Copyright 2026 Florian Nick
 * Copyright 2026 Kamil Krzywański
 */
// SPDX-License-Identifier: Apache-2.0
package io.github.kamilkrzywanski.barleader.decode;

import io.github.kamilkrzywanski.barleader.Symbology;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * EAN-13, UPC-A, EAN-8 and UPC-E from the runs of a line whose edges lie between samples
 * ({@link ProfileSampler}). Ported from tapirscan 1.5.0 ({@code multiformat/src/linear.rs}:
 * {@code ean}, {@code digit}, {@code ean_guards}, {@code error_adjusted} and the retail part of
 * {@code decode_impl}).
 *
 * <p>Unlike the row decoder of zxing-cpp it measures on the start guard how much wider the bars
 * came out than the spaces, as print, blur and a threshold all widen one colour, and takes that
 * much off every digit before it is compared with the patterns. A symbol may start at any bar
 * with white of four modules before it, half a module where the line itself begins, and needs
 * the same after it.
 */
final class RunEan {
    /** A module narrower than this many samples is not resolved. */
    private static final float MIN_MODULE = 0.65f;
    private static final int[][] DIGITS = {
            {3, 2, 1, 1}, {2, 2, 2, 1}, {2, 1, 2, 2}, {1, 4, 1, 1}, {1, 1, 3, 2},
            {1, 2, 3, 1}, {1, 1, 1, 4}, {1, 3, 1, 2}, {1, 2, 1, 3}, {3, 1, 1, 2}
    };
    /** Which of the six left digits of an EAN-13 are even, by its first digit; the first of them is the high bit. */
    private static final int[] PARITY = {0, 11, 13, 14, 19, 25, 28, 21, 22, 26};
    /** The same for the six digits of a UPC-E of number system 0, by its check digit; inverted for number system 1. */
    private static final int[] UPC_PARITY = {56, 52, 50, 49, 44, 38, 35, 42, 41, 37};
    private static final int[] GUARD = {1, 1, 1};
    private static final int[] MIDDLE = {1, 1, 1, 1, 1};
    private static final int[] UPCE_END = {1, 1, 1, 1, 1, 1};

    /** One symbol: its runs are {@code start} to {@code end}, the end not included; {@code error} is the mean misfit of its digits. */
    record Read(Symbology symbology, String text, int start, int end, float error) {
    }

    private RunEan() {
    }

    /**
     * Every symbol of {@code wanted} on the first {@code count} of {@code runs}, the first of them
     * dark when {@code firstBlack}; a symbol found, the search goes on behind it.
     */
    static List<Read> decode(float[] runs, int count, boolean firstBlack, Set<Symbology> wanted) {
        List<Read> out = new ArrayList<>(1);
        boolean full = wanted.contains(Symbology.EAN_13) || wanted.contains(Symbology.UPC_A);
        boolean ean8 = wanted.contains(Symbology.EAN_8);
        boolean upce = wanted.contains(Symbology.UPC_E);
        if (!full && !ean8 && !upce) {
            return out;
        }
        int minimum = upce ? 33 : ean8 ? 43 : 59;
        int s = firstBlack ? 0 : 1;
        while (s + minimum <= count) {
            boolean quiet = s > 0 && (s == 1 || runs[s - 1] >= runs[s] * 2f);
            Read found = quiet ? ean(runs, count, s, wanted, full, ean8, upce) : null;
            if (found != null) {
                s = found.end();
                if ((s % 2 == 0) != firstBlack) {
                    s++;
                }
                out.add(found);
            } else {
                s += 2;
            }
        }
        return out;
    }

    private static Read ean(float[] r, int n, int s, Set<Symbology> wanted, boolean full, boolean ean8, boolean upce) {
        if (s + 3 > n) {
            return null;
        }
        float module = (r[s] + r[s + 1] + r[s + 2]) / 3f;
        // Every layout needs this quiet zone in front of it; the guard gives the scale to all of them.
        if (!(s > 0 && r[s - 1] >= module * (s == 1 ? 0.5f : 4f))) {
            return null;
        }
        if (errorAdjusted(r, n, s, GUARD) > 0.25f) {
            return null;
        }
        for (int count : new int[]{12, 8, 6}) {
            if (count == 12 && !full || count == 8 && !ean8 || count == 6 && !upce) {
                continue;
            }
            int end = s + (count == 6 ? 33 : count * 4 + 11);
            if (end > n || !quiet(r, n, s, end, module) || !guards(r, n, s, end, count)) {
                continue;
            }
            float black = (r[s] + r[s + 2]) / 2f;
            float white = r[s + 1];
            float measured = Math.max(-0.45f, Math.min(0.45f, (black - white) / (black + white)));
            float[] gains = count == 8 ? new float[]{measured, 0f, 0.25f, -0.25f, 0.5f, -0.5f} : new float[]{measured, 0f};
            for (float gain : gains) {
                int[] digits = new int[count];
                int parity = 0;
                int at = s + 3;
                float error = 0f;
                boolean ok = true;
                float[] fit = new float[1];
                for (int i = 0; i < count && ok; i++) {
                    if (count != 6 && i == count / 2) {
                        at += 5;
                    }
                    // A digit of the left half starts with a space, one of the right half with a bar.
                    float colourGain = gain * (count == 6 || i < count / 2 ? -1f : 1f);
                    int odd = digit(r, n, at, false, colourGain, fit);
                    float oddError = fit[0];
                    int even = count != 8 && i < 6 ? digit(r, n, at, true, colourGain, fit) : -1;
                    float evenError = fit[0];
                    boolean takeEven;
                    if (odd >= 0 && even >= 0) {
                        takeEven = oddError > evenError;
                    } else if (odd >= 0) {
                        takeEven = false;
                    } else if (even >= 0) {
                        takeEven = true;
                    } else {
                        ok = false;
                        break;
                    }
                    if (count != 8 && i < 6) {
                        parity = parity << 1 | (takeEven ? 1 : 0);
                    }
                    digits[i] = takeEven ? even : odd;
                    error += takeEven ? evenError : oddError;
                    at += 4;
                }
                if (!ok) {
                    continue;
                }
                StringBuilder text = new StringBuilder(13);
                Symbology symbology;
                if (count == 12) {
                    int first = indexOf(PARITY, parity);
                    if (first < 0) {
                        continue;
                    }
                    text.append((char) ('0' + first));
                    append(text, digits);
                    if (!checksum(text)) {
                        continue;
                    }
                    if (first == 0 && wanted.contains(Symbology.UPC_A)) {
                        text.deleteCharAt(0);
                        symbology = Symbology.UPC_A;
                    } else if (wanted.contains(Symbology.EAN_13)) {
                        symbology = Symbology.EAN_13;
                    } else {
                        continue;
                    }
                } else if (count == 8) {
                    append(text, digits);
                    if (!checksum(text)) {
                        continue;
                    }
                    symbology = Symbology.EAN_8;
                } else {
                    int system = 0;
                    int check = indexOf(UPC_PARITY, parity);
                    if (check < 0) {
                        system = 1;
                        check = indexOf(UPC_PARITY, parity ^ 63);
                        if (check < 0) {
                            continue;
                        }
                    }
                    text.append((char) ('0' + system));
                    append(text, digits);
                    text.append((char) ('0' + check));
                    if (!checksum(expandUpce(text))) {
                        continue;
                    }
                    symbology = Symbology.UPC_E;
                }
                return new Read(symbology, text.toString(), s, end, error / count);
            }
        }
        return null;
    }

    /**
     * The digit whose pattern the four runs at {@code at} fit best, read backwards for an even
     * digit, after {@code gain} modules are taken off the runs of one colour and given to the
     * other; -1 when the fit is poor or another digit fits nearly as well. {@code fit} gets the misfit.
     */
    private static int digit(float[] r, int n, int at, boolean even, float gain, float[] fit) {
        fit[0] = 10f;
        if (at + 4 > n) {
            return -1;
        }
        float module = (r[at] + r[at + 1] + r[at + 2] + r[at + 3]) / 7f;
        float[] adjusted = new float[4];
        float sum = 0f;
        for (int i = 0; i < 4; i++) {
            adjusted[i] = r[at + i] - gain * module * (i % 2 == 0 ? 1f : -1f);
            sum += adjusted[i];
        }
        float adjustedModule = sum / 7f;
        if (adjustedModule < MIN_MODULE) {
            return -1;
        }
        int best = 0;
        float bestError = 10f;
        float second = 10f;
        for (int d = 0; d < DIGITS.length; d++) {
            float e = 0f;
            for (int j = 0; j < 4; j++) {
                float delta = Math.abs(adjusted[j] - DIGITS[d][even ? 3 - j : j] * adjustedModule);
                if (delta > adjustedModule) {
                    e = 10f * sum;
                    break;
                }
                e += delta;
            }
            e /= sum;
            if (e < bestError) {
                second = bestError;
                best = d;
                bestError = e;
            } else {
                second = Math.min(second, e);
            }
        }
        fit[0] = bestError;
        return bestError < 0.23f && second - bestError > 0.012f ? best : -1;
    }

    private static boolean guards(float[] r, int n, int s, int end, int count) {
        boolean centre = count == 6 || !(errorAdjusted(r, n, s + 3 + count / 2 * 4, MIDDLE) > 0.25f);
        return centre && !(errorAdjusted(r, n, end - (count == 6 ? 6 : 3), count == 6 ? UPCE_END : GUARD) > 0.25f);
    }

    /** Whether the runs before {@code start} and at {@code end} are quiet zones: four modules, or half a module at an end of the line. */
    private static boolean quiet(float[] r, int n, int start, int end, float module) {
        return start > 0
                && r[start - 1] >= module * (start == 1 ? 0.5f : 4f)
                && (end == n || r[end] >= module * (end + 1 == n ? 0.5f : 4f));
    }

    /**
     * How far the runs at {@code at} are from {@code pattern}, as a share of their width, after the
     * widening of one colour they share is taken out; 10 for no fit.
     */
    private static float errorAdjusted(float[] r, int n, int at, int[] pattern) {
        int length = pattern.length;
        if (at < 0 || at + length > n) {
            return 10f;
        }
        float sum = 0f;
        float units = 0f;
        for (int i = 0; i < length; i++) {
            sum += r[at + i];
            units += pattern[i];
        }
        float module = sum / units;
        if (module < MIN_MODULE) {
            return 10f;
        }
        float bias;
        if (length % 2 == 0) {
            // Print and a threshold widen every bar and narrow every space; a pattern of even length keeps its width.
            float total = 0f;
            for (int i = 0; i < length; i++) {
                total += (r[at + i] - pattern[i] * module) * (i % 2 == 0 ? 1f : -1f);
            }
            bias = Math.max(-module * 0.5f, Math.min(module * 0.5f, total / length));
        } else {
            // Guards are all ones: the runs of each colour give its width.
            float evenSum = 0f;
            float oddSum = 0f;
            for (int i = 0; i < length; i++) {
                if (i % 2 == 0) {
                    evenSum += r[at + i];
                } else {
                    oddSum += r[at + i];
                }
            }
            float evenMean = evenSum / ((length + 1) / 2);
            float oddMean = oddSum / (length / 2);
            module = (evenMean + oddMean) / 2f;
            bias = Math.max(-module * 0.5f, Math.min(module * 0.5f, (evenMean - oddMean) * 0.5f));
        }
        float e = Math.abs(bias) * 0.1f;
        for (int i = 0; i < length; i++) {
            float d = Math.abs(r[at + i] - pattern[i] * module - bias * (i % 2 == 0 ? 1f : -1f));
            if (d > module) {
                return 10f;
            }
            e += d;
        }
        return e / sum;
    }

    private static void append(StringBuilder text, int[] digits) {
        for (int digit : digits) {
            text.append((char) ('0' + digit));
        }
    }

    /** The GTIN check: from the last digit, the digits times 1 and 3 in turn add up to a multiple of ten. */
    private static boolean checksum(CharSequence digits) {
        int sum = 0;
        for (int i = digits.length() - 1, k = 0; i >= 0; i--, k++) {
            sum += (digits.charAt(i) - '0') * (k % 2 == 0 ? 1 : 3);
        }
        return digits.length() > 0 && sum % 10 == 0;
    }

    /** The UPC-A a UPC-E of eight digits stands for. */
    private static String expandUpce(CharSequence d) {
        StringBuilder out = new StringBuilder(12).append(d, 0, 3);
        switch (d.charAt(6)) {
            case '0', '1', '2' -> out.append(d.charAt(6)).append("0000").append(d, 3, 6);
            case '3' -> out.append(d.charAt(3)).append("00000").append(d, 4, 6);
            case '4' -> out.append(d, 3, 5).append("00000").append(d.charAt(5));
            default -> out.append(d, 3, 6).append("0000").append(d.charAt(6));
        }
        return out.append(d.charAt(7)).toString();
    }

    private static int indexOf(int[] values, int wanted) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == wanted) {
                return i;
            }
        }
        return -1;
    }
}
