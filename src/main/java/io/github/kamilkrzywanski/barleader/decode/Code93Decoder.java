/*
 * Copyright 2025 wooyechan
 * Copyright 2026 Axel Waggershauser
 * Copyright 2016 Nu-book Inc.
 * Copyright 2016 ZXing authors
 * Copyright 2020 Axel Waggershauser
 * Copyright 2026 Kamil Krzywański
 */
// SPDX-License-Identifier: Apache-2.0
package io.github.kamilkrzywanski.barleader.decode;

import io.github.kamilkrzywanski.barleader.decode.OneDRow.View;

/**
 * Code 93 row decoder ported from zxing-cpp 3.1.1 {@code ODCode93Reader.cpp}
 * and {@code ODCode93Patterns.h}.
 *
 * <p>Both checksum characters are required. A failure returns null rather than a result
 * flagged with a checksum error. The two checksum characters are then removed and the
 * payload is expanded with the full-ASCII shift characters {@code a b c d}.
 * The scan ladder reads Code 93 with this decoder when that format was requested.
 * It is not part of {@code Symbology.defaults()}.
 */
final class Code93Decoder {
    private static final int CHAR_LEN = 6;
    private static final int CHAR_MODS = 9;
    private static final int MIN_CHARS = 5;
    private static final double QUIET_MODULES = 6;
    private static final float QUIET_SCALE = 0.5f;
    private static final int[] START_PREFIX = {1, 1, 1, 1};
    private static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ-. $/+%abcd*";
    private static final int[][] PATTERNS = {
            {1, 3, 1, 1, 1, 2}, {1, 1, 1, 2, 1, 3}, {1, 1, 1, 3, 1, 2}, {1, 1, 1, 4, 1, 1}, {1, 2, 1, 1, 1, 3},
            {1, 2, 1, 2, 1, 2}, {1, 2, 1, 3, 1, 1}, {1, 1, 1, 1, 1, 4}, {1, 3, 1, 2, 1, 1}, {1, 4, 1, 1, 1, 1},
            {2, 1, 1, 1, 1, 3}, {2, 1, 1, 2, 1, 2}, {2, 1, 1, 3, 1, 1}, {2, 2, 1, 1, 1, 2}, {2, 2, 1, 2, 1, 1},
            {2, 3, 1, 1, 1, 1}, {1, 1, 2, 1, 1, 3}, {1, 1, 2, 2, 1, 2}, {1, 1, 2, 3, 1, 1}, {1, 2, 2, 1, 1, 2},
            {1, 3, 2, 1, 1, 1}, {1, 1, 1, 1, 2, 3}, {1, 1, 1, 2, 2, 2}, {1, 1, 1, 3, 2, 1}, {1, 2, 1, 1, 2, 2},
            {1, 3, 1, 1, 2, 1}, {2, 1, 2, 1, 1, 2}, {2, 1, 2, 2, 1, 1}, {2, 1, 1, 1, 2, 2}, {2, 1, 1, 2, 2, 1},
            {2, 2, 1, 1, 2, 1}, {2, 2, 2, 1, 1, 1}, {1, 1, 2, 1, 2, 2}, {1, 1, 2, 2, 2, 1}, {1, 2, 2, 1, 2, 1},
            {1, 2, 3, 1, 1, 1}, {1, 2, 1, 1, 3, 1}, {3, 1, 1, 1, 1, 2}, {3, 1, 1, 2, 1, 1}, {3, 2, 1, 1, 1, 1},
            {1, 1, 2, 1, 3, 1}, {1, 1, 3, 1, 2, 1}, {2, 1, 1, 1, 3, 1}, {1, 2, 1, 2, 2, 1}, {3, 1, 2, 1, 1, 1},
            {3, 1, 1, 1, 2, 1}, {1, 2, 2, 2, 1, 1}, {1, 1, 1, 1, 4, 1}
    };
    private static final int[] E2E = encodings();
    private static final char[] PERCENTAGE = {
            'A' - 38, 'B' - 38, 'C' - 38, 'D' - 38, 'E' - 38,
            'F' - 11, 'G' - 11, 'H' - 11, 'I' - 11, 'J' - 11,
            'K' + 16, 'L' + 16, 'M' + 16, 'N' + 16, 'O' + 16,
            'P' + 43, 'Q' + 43, 'R' + 43, 'S' + 43, 'T' + 43,
            '\0', '@', '`',
            127, 127, 127
    };

    static {
        if (ALPHABET.length() != PATTERNS.length || E2E[E2E.length - 1] != 1632) {
            throw new IllegalStateException("table size mismatch");
        }
    }

    private Code93Decoder() {
    }

    /** One symbol. {@code left} and {@code right} are inclusive columns of the start and stop patterns. */
    record Span(String text, int left, int right) {
    }

    /** One symbol on a black/white row. Null when the row does not hold a valid code. */
    static String decode(boolean[] black) {
        Span span = decodeSpan(black);
        return span == null ? null : span.text();
    }

    /** Same symbol as {@link #decode(boolean[])}, with the guard columns. */
    static Span decodeSpan(boolean[] black) {
        return decodeSpan(OneDRow.runs(black));
    }

    /** {@link #decodeSpan(boolean[])} on the run lengths of the row. */
    static Span decodeSpan(int[] runs) {
        View next = findStart(View.of(runs));
        if (next == null) {
            return null;
        }
        int left = next.pixelsInFront();
        StringBuilder text = new StringBuilder();
        char current;
        do {
            if (!next.skipSymbol()) {
                return null;
            }
            current = decodeChar(next);
            if (current == 0) {
                return null;
            }
            text.append(current);
        } while (current != '*');
        text.setLength(text.length() - 1);
        if (text.length() < MIN_CHARS - 2) {
            return null;
        }
        View stop = next.sub(0, CHAR_LEN + 1);
        if (!stop.isValid()
                || stop.at(CHAR_LEN) > stop.sum(CHAR_LEN) / 4
                || !stop.hasQuietZoneAfter(QUIET_SCALE)) {
            return null;
        }
        if (!checksum(text, text.length() - 2, 20) || !checksum(text, text.length() - 1, 15)) {
            return null;
        }
        text.setLength(text.length() - 2);
        String expanded = fullAscii(text);
        if (expanded == null || expanded.isEmpty()) {
            return null;
        }
        int right = stop.pixelsInFront() + stop.sum(0) - 1;
        if (right < left) {
            return null;
        }
        return new Span(expanded, left, right);
    }

    private static View findStart(View row) {
        int minSize = MIN_CHARS * CHAR_LEN;
        if (row.size() < minSize) {
            return null;
        }
        View window = row.sub(0, CHAR_LEN);
        if (window.isAtFirstBar() && isStart(window, Integer.MAX_VALUE)) {
            return window;
        }
        int limit = row.data() + row.size() - minSize;
        while (window.data() < limit) {
            if (isStart(window, window.before())) {
                return window;
            }
            window.shift(2);
        }
        return null;
    }

    private static boolean isStart(View window, int space) {
        if (OneDRow.isPattern(window, START_PREFIX, START_PREFIX.length, space, QUIET_MODULES) == 0) {
            return false;
        }
        if (!(window.at(4) > 3 * window.at(5) - 2)) {
            return false;
        }
        return decodeChar(window) == '*';
    }

    private static char decodeChar(View view) {
        int[] e2e = OneDRow.normalizedE2E(view, CHAR_LEN, CHAR_MODS);
        if (e2e == null) {
            return 0;
        }
        int code = OneDRow.pack(e2e);
        for (int i = 0; i < E2E.length; i++) {
            if (E2E[i] == code) {
                return ALPHABET.charAt(i);
            }
        }
        return 0;
    }

    private static boolean checksum(CharSequence text, int position, int weightMax) {
        int weight = 1;
        int sum = 0;
        for (int i = position - 1; i >= 0; i--) {
            int index = ALPHABET.indexOf(text.charAt(i));
            if (index < 0) {
                return false;
            }
            sum += weight * index;
            if (++weight > weightMax) {
                weight = 1;
            }
        }
        return text.charAt(position) == ALPHABET.charAt(sum % 47);
    }

    /** Same expansion as Code 39, with shift characters {@code a b c d}. */
    private static String fullAscii(CharSequence encoded) {
        StringBuilder out = new StringBuilder(encoded.length());
        for (int i = 0; i < encoded.length(); i++) {
            char c = encoded.charAt(i);
            if (c == 'a' || c == 'b' || c == 'c' || c == 'd') {
                if (i + 1 >= encoded.length()) {
                    return null;
                }
                char next = encoded.charAt(++i);
                if (next < 'A' || next > 'Z') {
                    return null;
                }
                if (c == 'a') {
                    c = (char) (next - 64);
                } else if (c == 'b') {
                    c = PERCENTAGE[next - 'A'];
                } else if (c == 'c') {
                    c = (char) (next - 32);
                } else {
                    c = (char) (next + 32);
                }
            }
            out.append(c);
        }
        return out.toString();
    }

    private static int[] encodings() {
        int[] codes = new int[PATTERNS.length];
        for (int i = 0; i < PATTERNS.length; i++) {
            int[] pattern = PATTERNS[i];
            int total = 0;
            for (int width : pattern) {
                total += width;
            }
            double module = (double) total / CHAR_MODS;
            int[] e2e = new int[CHAR_LEN - 2];
            for (int pair = 0; pair < e2e.length; pair++) {
                e2e[pair] = (int) ((pattern[pair] + pattern[pair + 1]) / module + 0.5);
            }
            codes[i] = OneDRow.pack(e2e);
        }
        return codes;
    }
}
