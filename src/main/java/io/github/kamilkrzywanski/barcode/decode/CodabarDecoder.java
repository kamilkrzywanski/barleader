package io.github.kamilkrzywanski.barcode.decode;

import io.github.kamilkrzywanski.barcode.decode.OneDRow.View;

/**
 * Codabar row decoder ported from zxing-cpp 3.1.1 {@code ODCodabarReader.cpp}.
 *
 * <p>Start and stop letters stay in the text. There is no checksum. The guard test is the
 * fast shape check, not the compiled-out narrow-wide list. The scan ladder reads Codabar
 * with this decoder when that format was requested. It is not part of {@code Symbology.defaults()}.
 */
final class CodabarDecoder {
    private static final int CHAR_LEN = 7;
    private static final int MIN_CHARS = 4;
    private static final float QUIET_SCALE = 0.5f;
    private static final String ALPHABET = "0123456789-$:/.+ABCD";
    private static final int[] ENCODINGS = {
            0x03, 0x06, 0x09, 0x60, 0x12, 0x42, 0x21, 0x24, 0x30, 0x48,
            0x0c, 0x18, 0x45, 0x51, 0x54, 0x15, 0x1A, 0x29, 0x0B, 0x0E
    };

    static {
        if (ALPHABET.length() != ENCODINGS.length) {
            throw new IllegalStateException("table size mismatch");
        }
    }

    private CodabarDecoder() {
    }

    /** One symbol. {@code left} and {@code right} are inclusive columns of the start and stop letters. */
    record Span(String text, int left, int right) {
    }

    /** One symbol on a black/white row. Null when the row does not hold a valid code. */
    static String decode(boolean[] black) {
        Span span = decodeSpan(black);
        return span == null ? null : span.text();
    }

    /** Same symbol as {@link #decode(boolean[])}, with the guard columns. Start and stop letters stay. */
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
        int maxGap = next.sum(0) / 2;
        StringBuilder text = new StringBuilder();
        text.append(decodeChar(next));
        if (!guard(text.charAt(0))) {
            return null;
        }
        char current;
        do {
            if (!next.skipSymbol() || !next.skipSingle(maxGap)) {
                return null;
            }
            current = decodeChar(next);
            if (current == 0) {
                return null;
            }
            text.append(current);
        } while (!guard(current));
        if (text.length() < MIN_CHARS || !next.hasQuietZoneAfter(QUIET_SCALE)) {
            return null;
        }
        int right = next.pixelsInFront() + next.sum(0) - 1;
        if (right < left || text.isEmpty()) {
            return null;
        }
        return new Span(text.toString(), left, right);
    }

    private static View findStart(View row) {
        int minSize = MIN_CHARS * CHAR_LEN;
        if (row.size() < minSize) {
            return null;
        }
        View window = row.sub(0, CHAR_LEN);
        if (window.isAtFirstBar() && isGuard(window, Integer.MAX_VALUE)) {
            return window;
        }
        int limit = row.data() + row.size() - minSize;
        while (window.data() < limit) {
            if (isGuard(window, window.before())) {
                return window;
            }
            window.shift(2);
        }
        return null;
    }

    /** {@code IsLeftGuard} with the narrow-wide list left compiled out. */
    private static boolean isGuard(View view, int space) {
        return space > view.at(0) * 4
                && view.at(0) < view.at(3)
                && view.at(1) + view.at(5) > view.at(3)
                && space > view.sum(0) * QUIET_SCALE;
    }

    private static boolean guard(char c) {
        return c >= 'A' && c <= 'D';
    }

    private static char decodeChar(View view) {
        int pattern = narrowWide(view);
        if (pattern < 0) {
            return 0;
        }
        for (int i = 0; i < ENCODINGS.length; i++) {
            if (ENCODINGS[i] == pattern) {
                return ALPHABET.charAt(i);
            }
        }
        return 0;
    }

    /** {@code NarrowWideBitPattern} over one 7-run character. */
    private static int narrowWide(View view) {
        if (view.size() < CHAR_LEN) {
            return -1;
        }
        int minBar = view.at(0);
        int maxBar = minBar;
        int minSpace = view.at(1);
        int maxSpace = minSpace;
        for (int i = 2; i < CHAR_LEN; i++) {
            int width = view.at(i);
            if ((i & 1) == 0) {
                minBar = Math.min(minBar, width);
                maxBar = Math.max(maxBar, width);
            } else {
                minSpace = Math.min(minSpace, width);
                maxSpace = Math.max(maxSpace, width);
            }
        }
        int maxSpread = maxBar >= 2 * minBar && maxSpace >= 2 * minSpace ? 2 : 4;
        int[] min = {minBar, minSpace};
        int[] max = {maxBar, maxSpace};
        int[] threshold = new int[2];
        for (int i = 0; i < 2; i++) {
            if (max[i] > 4 * (min[i] + 1)
                    || max[i] > maxSpread * max[i ^ 1]
                    || min[i] > maxSpread * (min[i ^ 1] + 1)) {
                return -1;
            }
            threshold[i] = Math.max((min[i] + max[i]) / 2, min[i] * 3 / 2);
        }
        if (threshold[0] == 0 || threshold[1] == 0) {
            return -1;
        }
        int pattern = 0;
        for (int i = 0; i < CHAR_LEN; i++) {
            int width = view.at(i);
            int limit = threshold[i & 1];
            if (width > limit * 2) {
                return -1;
            }
            pattern = (pattern << 1) | (width > limit ? 1 : 0);
        }
        return pattern;
    }
}
