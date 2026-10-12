package org.krzywanski.barcode.decode;

import org.krzywanski.barcode.decode.OneDRow.View;

/**
 * Code 39 row decoder ported from zxing-cpp 3.1.1 {@code ODCode39Reader.cpp}.
 * Narrow and wide runs are separated by {@code NarrowWideThreshold} in {@code ODRowReader.h}.
 *
 * <p>The reader options this follows are the library defaults: the optional checksum is not
 * required, and both standard and extended Code 39 are enabled. A checksum character that
 * matches is kept in the text and is not fed through the extended-ASCII expansion. A checksum
 * that does not match stays as payload. Code 32 and PZN are not read. Add-on symbology
 * identifiers are not prefixed. The scan ladder reads Code 39 with this decoder, so a clean
 * symbol keeps an {@code original-*} attempt.
 */
final class Code39Decoder {
    private static final int CHAR_LEN = 9;
    private static final int NARROW_COUNT = 6;
    private static final int MIN_CHARS = 3;
    /** {@code QUIET_ZONE_SCALE * 12}, with {@code QUIET_ZONE_SCALE = 1/3}. */
    private static final double QUIET_MODULES = 4;
    private static final float QUIET_SCALE = 1f / 3f;
    /** Narrow-element indexes of the asterisk, {@code FixedSparsePattern<9, 6>}. */
    private static final int[] START_NARROW = {0, 2, 3, 5, 7, 8};
    private static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ-. $/+%*";
    private static final int[] ENCODINGS = {
            0x034, 0x121, 0x061, 0x160, 0x031, 0x130, 0x070, 0x025, 0x124, 0x064,
            0x109, 0x049, 0x148, 0x019, 0x118, 0x058, 0x00D, 0x10C, 0x04C, 0x01C,
            0x103, 0x043, 0x142, 0x013, 0x112, 0x052, 0x007, 0x106, 0x046, 0x016,
            0x181, 0x0C1, 0x1C0, 0x091, 0x190, 0x0D0, 0x085, 0x184, 0x0C4, 0x0A8,
            0x0A2, 0x08A, 0x02A, 0x094
    };
    private static final char[] PERCENTAGE = {
            'A' - 38, 'B' - 38, 'C' - 38, 'D' - 38, 'E' - 38,
            'F' - 11, 'G' - 11, 'H' - 11, 'I' - 11, 'J' - 11,
            'K' + 16, 'L' + 16, 'M' + 16, 'N' + 16, 'O' + 16,
            'P' + 43, 'Q' + 43, 'R' + 43, 'S' + 43, 'T' + 43,
            '\0', '@', '`',
            127, 127, 127
    };

    static {
        if (ALPHABET.length() != ENCODINGS.length || PERCENTAGE.length != 26) {
            throw new IllegalStateException("table size mismatch");
        }
    }

    private Code39Decoder() {
    }

    /** One symbol. {@code left} and {@code right} are inclusive columns of the start and stop asterisks. */
    record Span(String text, int left, int right) {
    }

    /** One symbol on a black/white row. Null when the row does not hold a Code 39 symbol. */
    static String decode(boolean[] black) {
        Span span = decodeSpan(black);
        return span == null ? null : span.text();
    }

    /** Same symbol as {@link #decode(boolean[])}, with the asterisk columns. */
    static Span decodeSpan(boolean[] black) {
        View next = OneDRow.findSparseGuard(
                View.of(OneDRow.runs(black)),
                MIN_CHARS * CHAR_LEN,
                CHAR_LEN,
                START_NARROW,
                NARROW_COUNT,
                QUIET_MODULES
        );
        if (next == null || decodeChar(next) != '*') {
            return null;
        }
        int left = next.pixelsInFront();
        int maxGap = next.sum(0) / 2;
        StringBuilder text = new StringBuilder();
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
        } while (current != '*');
        text.setLength(text.length() - 1);
        if (text.length() < MIN_CHARS - 2 || !next.hasQuietZoneAfter(QUIET_SCALE)) {
            return null;
        }
        int right = next.pixelsInFront() + next.sum(0) - 1;
        if (right < left) {
            return null;
        }
        String value = finish(text);
        if (value == null) {
            return null;
        }
        return new Span(value, left, right);
    }

    private static String finish(StringBuilder text) {
        char last = text.charAt(text.length() - 1);
        text.deleteCharAt(text.length() - 1);
        int checksum = 0;
        for (int i = 0; i < text.length(); i++) {
            int index = ALPHABET.indexOf(text.charAt(i));
            if (index < 0) {
                return null;
            }
            checksum += index;
        }
        boolean valid = last == ALPHABET.charAt(checksum % 43);
        if (!valid) {
            text.append(last);
        }
        if (containsShift(text)) {
            String expanded = fullAscii(text);
            if (expanded != null && !expanded.isEmpty()) {
                text.setLength(0);
                text.append(expanded);
            }
        }
        if (valid) {
            text.append(last);
        }
        return text.length() == 0 ? null : text.toString();
    }

    /** {@code DecodeCode39AndCode93FullASCII} with the Code 39 shift characters. Null is a format error. */
    private static String fullAscii(CharSequence encoded) {
        StringBuilder out = new StringBuilder(encoded.length());
        for (int i = 0; i < encoded.length(); i++) {
            char c = encoded.charAt(i);
            if (c == '$' || c == '%' || c == '/' || c == '+') {
                if (i + 1 >= encoded.length()) {
                    return null;
                }
                char next = encoded.charAt(++i);
                if (next < 'A' || next > 'Z') {
                    return null;
                }
                if (c == '$') {
                    c = (char) (next - 64);
                } else if (c == '%') {
                    c = PERCENTAGE[next - 'A'];
                } else if (c == '/') {
                    c = (char) (next - 32);
                } else {
                    c = (char) (next + 32);
                }
            }
            out.append(c);
        }
        return out.toString();
    }

    private static boolean containsShift(CharSequence text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '$' || c == '%' || c == '/' || c == '+') {
                return true;
            }
        }
        return false;
    }

    private static char decodeChar(View view) {
        if (view.size() < CHAR_LEN) {
            return 0;
        }
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

    /** {@code NarrowWideBitPattern}: 1 is wide, 0 is narrow, -1 is not a character. */
    private static int narrowWide(View view) {
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
            int otherMin = min[i ^ 1];
            int otherMax = max[i ^ 1];
            if (max[i] > 4 * (min[i] + 1)
                    || max[i] > maxSpread * otherMax
                    || min[i] > maxSpread * (otherMin + 1)) {
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
