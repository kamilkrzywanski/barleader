/*
 * Copyright 2026 Axel Waggershauser
 * Copyright 2026 Kamil Krzywański
 */
// SPDX-License-Identifier: Apache-2.0
package io.github.kamilkrzywanski.barleader.decode;

import io.github.kamilkrzywanski.barleader.decode.OneDRow.View;

/**
 * Telepen row decoder ported from zxing-cpp 3.1.1 {@code ODTelepenReader.cpp}.
 *
 * <p>{@code BitmapDecoder} calls it on rows of every attempt when {@code Symbology.TELEPEN} is asked for. A checksum or
 * format failure returns null. The text is the raw symbol content: a control stays a control,
 * rather than the escaped {@code <SI>} form {@code Barcode::text()} prints for binary content.
 */
final class TelepenDecoder {
    private static final int MIN_QUIET = 5;
    private static final int[] PREFIX = {1, 1, 1, 1, 1, 1};
    private static final int[][] START = {
            {1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 3, 3},
            {1, 1, 1, 1, 1, 1, 1, 1, 3, 1, 1, 3},
            {1, 1, 1, 1, 1, 1, 3, 1, 1, 1, 1, 3},
    };
    private static final int[][] STOP = {
            {3, 3, 1, 1, 1, 1, 1, 1, 1, 1, 1},
            {3, 1, 1, 3, 1, 1, 1, 1, 1, 1, 1},
            {3, 1, 1, 1, 1, 3, 1, 1, 1, 1, 1},
    };

    private TelepenDecoder() {
    }

    /** Text plus the ISO/IEC 15424 modifier ({@code ]B0}, {@code ]B1}, {@code ]B2}, {@code ]B4}). */
    static final class Symbol {
        final String text;
        final char modifier;

        Symbol(String text, char modifier) {
            this.text = text;
            this.modifier = modifier;
        }
    }

    /** {@code true} is black. Full ASCII and compressed numeric are both accepted. */
    static Symbol decode(boolean[] black) {
        return decode(OneDRow.runs(black), true, true);
    }

    /** Run lengths, including the white run in front of the first bar. Both modes are accepted. */
    static Symbol decode(int[] runs) {
        return decode(runs, true, true);
    }

    /**
     * {@code decodePattern}. {@code alpha} is full ASCII and {@code numeric} is compressed numeric.
     * A start character above 1 needs {@code numeric}.
     */
    static Symbol decode(int[] runs, boolean alpha, boolean numeric) {
        if (runs == null) {
            return null;
        }
        View row = View.of(runs);
        int minSize = 2 * 12 + 16 / 3;
        View guard = findGuard(row, Math.max(minSize, PREFIX.length));
        if (guard == null) {
            return null;
        }
        int startChar = 0;
        int space = guard.isAtFirstBar() ? Integer.MAX_VALUE : guard.before();
        for (int i = 0; i < START.length; i++) {
            if (matches(guard, START[i], space)) {
                startChar = i + 1;
                break;
            }
        }
        if (startChar == 0 || (startChar > 1 && !numeric)) {
            return null;
        }
        View start = guard.sub(0, START[startChar - 1].length);
        int[] threshold = narrowWide(start);
        if (threshold == null) {
            return null;
        }
        int[] stop = STOP[startChar - 1];
        View next = start.sub(START[startChar - 1].length, stop.length);
        StringBuilder raw = new StringBuilder();
        while (next.isValid() && !rightGuard(next, stop)) {
            boolean[] bits = new boolean[12];
            int count = 0;
            boolean inBlock = false;
            int[] wideSum = new int[2];
            int[] wideNum = new int[2];
            int[] narrowSum = new int[2];
            int[] narrowNum = new int[2];
            while (next.isValid() && count < 8) {
                int bar = next.at(0);
                int gap = next.at(1);
                if (bar > threshold[0] * 3 || gap > threshold[1] * 3) {
                    return null;
                }
                boolean wideBar = bar > threshold[0];
                boolean wideGap = gap > threshold[1];
                if (!wideBar && !wideGap) {
                    bits[count++] = true;
                } else if (!wideBar) {
                    count = append(bits, count, inBlock ? 0b10 : 0b01, 2);
                    inBlock = !inBlock;
                } else if (!wideGap) {
                    count = append(bits, count, 0b00, 2);
                } else {
                    count = append(bits, count, 0b010, 3);
                }
                if (count < 0) {
                    return null;
                }
                for (int i = 0; i < 2; i++) {
                    int width = i == 0 ? bar : gap;
                    if (width > threshold[i]) {
                        wideSum[i] += width;
                        wideNum[i]++;
                    } else {
                        narrowSum[i] += width;
                        narrowNum[i]++;
                    }
                }
                next.shift(2);
            }
            if (count != 8 || zeros(bits, count) % 2 != 0) {
                return null;
            }
            raw.append((char) (value(bits) & 0x7F));
            for (int i = 0; i < 2; i++) {
                if (wideNum[i] != 0 && narrowNum[i] != 0) {
                    int wide = (wideSum[i] + wideNum[i] / 2) / wideNum[i];
                    int narrow = (narrowSum[i] + narrowNum[i] / 2) / narrowNum[i];
                    threshold[i] = (wide + narrow) / 2;
                } else if (narrowNum[i] != 0) {
                    threshold[i] = (2 * narrowSum[i] + narrowNum[i] / 2) / narrowNum[i];
                }
            }
        }
        if (raw.length() < 2 || !rightGuard(next, stop)) {
            return null;
        }
        String payload = raw.substring(0, raw.length() - 1);
        int sum = 0;
        for (int i = 0; i < payload.length(); i++) {
            sum += payload.charAt(i);
        }
        int check = (127 - (sum % 127)) % 127;
        if (check != raw.charAt(raw.length() - 1)) {
            return null;
        }
        int mode = startChar;
        if (mode == 1 && numeric && (!alpha || numericLook(payload))) {
            mode = 2;
        }
        int dle = payload.indexOf(0x10);
        if (mode == 1) {
            return new Symbol(payload, '0');
        }
        if (mode == 2 && dle != 0) {
            String digits = dle < 0 ? numeric(payload) : numeric(payload.substring(0, dle));
            String tail = dle < 0 ? "" : payload.substring(dle + 1);
            if (digits == null || digits.isEmpty()) {
                return null;
            }
            return new Symbol(digits + tail, dle < 0 ? '1' : '2');
        }
        if (mode == 3 && dle != 0) {
            String head = dle < 0 ? payload : payload.substring(0, dle);
            String digits = dle < 0 ? "" : numeric(payload.substring(dle + 1));
            if (dle >= 0 && (digits == null || digits.isEmpty())) {
                return null;
            }
            return new Symbol(head + digits, dle < 0 ? '0' : '4');
        }
        return null;
    }

    /** Compressed numeric, or null when a shift-to-alpha codeword appears. */
    private static String numeric(String encoded) {
        StringBuilder decoded = new StringBuilder();
        for (int i = 0; i < encoded.length(); i++) {
            int codeword = encoded.charAt(i);
            if (codeword < 16 || codeword == 127) {
                decoded.append((char) codeword);
            } else if (codeword >= 17 && codeword < 27) {
                decoded.append((char) ('0' + codeword - 17)).append('X');
            } else if (codeword >= 27 && codeword < 127) {
                int value = codeword - 27;
                decoded.append((char) ('0' + value / 10)).append((char) ('0' + value % 10));
            } else {
                return null;
            }
        }
        return decoded.toString();
    }

    /**
     * Start 1 can still be compressed numeric. At most one DLE, not in front, and every control
     * that appears is one the numeric set uses.
     */
    private static boolean numericLook(String text) {
        int dle = 0;
        boolean control = false;
        for (int i = 0; i < text.length(); i++) {
            int code = text.charAt(i);
            if (code == 0x10) {
                dle++;
            }
            if (code < 32) {
                control = true;
            }
            if (code < 16) {
                return false;
            }
        }
        return dle <= 1 && text.charAt(0) != 0x10 && control;
    }

    private static int append(boolean[] bits, int count, int value, int width) {
        if (count + width > bits.length) {
            return -1;
        }
        for (int i = width - 1; i >= 0; i--) {
            bits[count++] = ((value >> i) & 1) != 0;
        }
        return count;
    }

    /** Even parity uses the bits before reversal. The first bit read becomes the low bit. */
    private static int value(boolean[] bits) {
        int value = 0;
        for (int i = 0; i < 8; i++) {
            if (bits[i]) {
                value |= 1 << i;
            }
        }
        return value;
    }

    private static int zeros(boolean[] bits, int count) {
        int zeros = 0;
        for (int i = 0; i < count; i++) {
            if (!bits[i]) {
                zeros++;
            }
        }
        return zeros;
    }

    private static View findGuard(View view, int minSize) {
        if (view.size() < minSize) {
            return null;
        }
        View window = view.sub(0, PREFIX.length);
        if (window.isAtFirstBar() && matches(window, PREFIX, Integer.MAX_VALUE)) {
            return window;
        }
        int limit = view.data() + view.size() - minSize;
        while (window.data() < limit) {
            if (matches(window, PREFIX, window.before())) {
                return window;
            }
            window.shift(2);
        }
        return null;
    }

    private static boolean rightGuard(View view, int[] pattern) {
        if (!view.isValid()) {
            return false;
        }
        int space = view.isAtLastBar() ? Integer.MAX_VALUE : view.after();
        return matches(view, pattern, space);
    }

    /** {@code IsPattern<true>} for a dense pattern: bars and spaces keep separate module sizes. */
    private static boolean matches(View view, int[] pattern, int space) {
        View wide = view.sub(0, pattern.length);
        if (!wide.isValid()) {
            return false;
        }
        double barWidth = 0;
        double gapWidth = 0;
        int barSum = 0;
        int gapSum = 0;
        for (int i = 0; i < pattern.length; i++) {
            if ((i & 1) == 0) {
                barWidth += wide.at(i);
                barSum += pattern[i];
            } else {
                gapWidth += wide.at(i);
                gapSum += pattern[i];
            }
        }
        if (barSum == 0 || gapSum == 0) {
            return false;
        }
        double barModule = barWidth / barSum;
        double gapModule = gapWidth / gapSum;
        double low = Math.min(barModule, gapModule);
        double high = Math.max(barModule, gapModule);
        if (high > 4 * low) {
            return false;
        }
        if (space < MIN_QUIET * gapModule) {
            return false;
        }
        double barLimit = barModule * 0.75 + 0.5;
        double gapLimit = gapModule * 0.6 + 0.5;
        for (int i = 0; i < pattern.length; i++) {
            double module = (i & 1) == 0 ? barModule : gapModule;
            double limit = (i & 1) == 0 ? barLimit : gapLimit;
            if (Math.abs(wide.at(i) - pattern[i] * module) > limit) {
                return false;
            }
        }
        return true;
    }

    /** {@code NarrowWideThreshold}. Null when the bars and spaces are not a narrow/wide pair. */
    private static int[] narrowWide(View view) {
        if (view.size() < 2) {
            return null;
        }
        int minBar = view.at(0);
        int maxBar = minBar;
        int minGap = view.at(1);
        int maxGap = minGap;
        for (int i = 2; i < view.size(); i++) {
            int width = view.at(i);
            if ((i & 1) == 0) {
                minBar = Math.min(minBar, width);
                maxBar = Math.max(maxBar, width);
            } else {
                minGap = Math.min(minGap, width);
                maxGap = Math.max(maxGap, width);
            }
        }
        int spread = maxBar >= 2 * minBar && maxGap >= 2 * minGap ? 2 : 4;
        int[] min = {minBar, minGap};
        int[] max = {maxBar, maxGap};
        int[] threshold = new int[2];
        for (int i = 0; i < 2; i++) {
            int other = i ^ 1;
            if (max[i] > 4 * (min[i] + 1) || max[i] > spread * max[other] || min[i] > spread * (min[other] + 1)) {
                return null;
            }
            threshold[i] = Math.max((min[i] + max[i]) / 2, min[i] * 3 / 2);
            if (threshold[i] == 0) {
                return null;
            }
        }
        return threshold;
    }
}
