package io.github.kamilkrzywanski.barcode.decode;

import io.github.kamilkrzywanski.barcode.decode.OneDRow.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Code 128 decoder ported from zxing-cpp 3.1.1 {@code ODCode128Reader.cpp}
 * and {@code ODCode128Patterns.h}.
 *
 * <p>A checksum or guard failure returns null. The scan ladder reads Code 128 with
 * this decoder, so a clean symbol still keeps an {@code original-*} attempt.
 */
final class Code128Decoder {
    private static final int CHAR_LEN = 6;
    private static final int CHAR_MODS = 11;
    private static final float MAX_AVG_VARIANCE = 0.25f;
    private static final float MAX_INDIVIDUAL_VARIANCE = 0.7f;
    private static final int CODE_SHIFT = 98;
    private static final int CODE_CODE_C = 99;
    private static final int CODE_CODE_B = 100;
    private static final int CODE_CODE_A = 101;
    private static final int CODE_FNC_1 = 102;
    private static final int CODE_FNC_2 = 97;
    private static final int CODE_FNC_3 = 96;
    private static final int CODE_START_A = 103;
    private static final int CODE_START_C = 105;
    private static final int CODE_STOP = 106;
    private static final int MIN_CHARS = 4;
    private static final int[] START_PREFIX = {2, 1, 1};
    private static final double QUIET_ZONE = 5;
    private static final String DIGITS =
            "212222222122222221121223121322131222122213122312132212221213221312231212"
                    + "112232122132122231113222123122123221223211221132221231213212223112"
                    + "312131311222321122321221312212322112322211212123212321232121111323"
                    + "131123131321112313132113132311211313231113231311112133112331132131"
                    + "113123113321133121313121211331231131213113213311213131311123311321"
                    + "331121312113312311332111314111221411431111111224111422121124121421"
                    + "141122141221112214112412122114122411142112142211241211221114413111"
                    + "241112134111111242121142121241114212124112124211411212421112421211"
                    + "212141214121412121111143111341131141114113114311411113411311113141"
                    + "114131311141411131211412211214211232233111";
    private static final int[][] PATTERNS = patterns();
    private static final int[] E2E = e2eCodes();

    private Code128Decoder() {
    }

    /** One symbol on a black/white row. Null when the row does not hold a valid code. */
    static String decode(boolean[] black) {
        Span span = decodeSpan(black);
        return span == null ? null : span.text;
    }

    /**
     * Same symbol as {@link #decode(boolean[])}. {@code left} and {@code right} are inclusive
     * pixel columns of the first bar and the stop pattern.
     */
    record Span(String text, int left, int right) {
    }

    static Span decodeSpan(boolean[] black) {
        if (black == null || black.length == 0) {
            return null;
        }
        View next = View.of(OneDRow.runs(black));
        View[] stopped = new View[1];
        // ODReader's DoDecode with tryHarder: a failed try goes on from the bar after the one it stopped at,
        // so a start-like pattern in the text in front of the symbol does not cost the whole row.
        while (next.size() > 0) {
            Span span = decodeAt(next, stopped, false);
            if (span != null || stopped[0] == null) {
                return span;
            }
            span = decodeAt(next, new View[1], true);
            if (span != null) {
                return span;
            }
            next = stopped[0];
            next.shift(2 - (next.data() - 1) % 2);
            next.extend();
        }
        return null;
    }

    /** {@code Code128Reader::decodePattern}. {@code stopped} gets the view it failed at, null when no guard is left. */
    private static Span decodeAt(View view, View[] stopped, boolean variance) {
        View guard = OneDRow.findLeftGuard(
                view,
                MIN_CHARS * CHAR_LEN,
                START_PREFIX,
                4,
                QUIET_ZONE
        );
        stopped[0] = guard;
        if (guard == null) {
            return null;
        }
        int left = guard.pixelsInFront();
        View next = guard.sub(0, CHAR_LEN);
        stopped[0] = next;
        if (!next.isValid()) {
            return null;
        }
        int start = decodePattern(next, true, variance);
        if (start < CODE_START_A || start > CODE_START_C) {
            return null;
        }
        List<Integer> raw = new ArrayList<>();
        raw.add(start);
        Text text = new Text(start);
        while (true) {
            if (!next.skipSymbol()) {
                return null;
            }
            int code = decodePattern(next, false, variance);
            if (code < 0) {
                return null;
            }
            if (code == CODE_STOP) {
                break;
            }
            if (code >= CODE_START_A || !text.decode(code)) {
                return null;
            }
            raw.add(code);
        }
        if (raw.size() < MIN_CHARS - 1) {
            return null;
        }
        View stop = next.sub(0, CHAR_LEN + 1);
        stopped[0] = stop;
        if (!stop.isValid()
                || stop.at(CHAR_LEN) > stop.sum(CHAR_LEN) / 4
                || !stop.hasQuietZoneAfter((float) (QUIET_ZONE / 13))) {
            return null;
        }
        int checksum = raw.get(0);
        for (int i = 1; i < raw.size() - 1; i++) {
            checksum += i * raw.get(i);
        }
        if (checksum % 103 != raw.get(raw.size() - 1)) {
            return null;
        }
        String value = text.finish();
        if (value.isEmpty()) {
            return null;
        }
        int right = stop.pixelsInFront() + stop.sum(0) - 1;
        if (right < left) {
            return null;
        }
        return new Span(value, left, right);
    }

    private static int decodePattern(View view, boolean start, boolean variance) {
        if (variance) {
            int code = OneDRow.decodeDigit(view, PATTERNS, MAX_AVG_VARIANCE, MAX_INDIVIDUAL_VARIANCE);
            if (code >= 0) {
                return code;
            }
        }
        int[] e2e = OneDRow.normalizedE2E(view, CHAR_LEN, CHAR_MODS);
        int code = e2e == null ? -1 : indexOf(E2E, OneDRow.pack(e2e));
        if (code < 0 && !start) {
            code = OneDRow.decodeDigit(view, PATTERNS, MAX_AVG_VARIANCE, MAX_INDIVIDUAL_VARIANCE);
        }
        return code;
    }

    private static int indexOf(int[] values, int wanted) {
        if (wanted < 0) {
            return -1;
        }
        for (int i = 0; i < values.length; i++) {
            if (values[i] == wanted) {
                return i;
            }
        }
        return -1;
    }

    private static int[][] patterns() {
        if (DIGITS.length() != 107 * CHAR_LEN) {
            throw new ExceptionInInitializerError("Code 128 patterns");
        }
        int[][] out = new int[107][CHAR_LEN];
        for (int i = 0; i < out.length; i++) {
            int sum = 0;
            for (int j = 0; j < CHAR_LEN; j++) {
                int width = DIGITS.charAt(i * CHAR_LEN + j) - '0';
                out[i][j] = width;
                sum += width;
            }
            if (sum != CHAR_MODS) {
                throw new ExceptionInInitializerError("Code 128 pattern " + i);
            }
        }
        return out;
    }

    private static int[] e2eCodes() {
        int[] codes = new int[PATTERNS.length];
        for (int i = 0; i < PATTERNS.length; i++) {
            int[] e2e = new int[CHAR_LEN - 2];
            for (int j = 0; j < e2e.length; j++) {
                e2e[j] = PATTERNS[i][j] + PATTERNS[i][j + 1];
            }
            codes[i] = OneDRow.pack(e2e);
            if (codes[i] < 0) {
                throw new ExceptionInInitializerError("Code 128 e2e " + i);
            }
        }
        return codes;
    }

    /** Strips the checksum character that the last decoded code appended. */
    private static final class Text {
        private int codeSet;
        private final StringBuilder text = new StringBuilder();
        private int kept;
        private boolean fnc1Seen;
        private boolean fnc4All;
        private boolean fnc4Next;
        private boolean shift;

        Text(int startCode) {
            codeSet = 204 - startCode;
        }

        boolean decode(int code) {
            kept = text.length();
            if (codeSet == CODE_CODE_C) {
                if (code < 100) {
                    if (code < 10) {
                        text.append('0');
                    }
                    text.append(code);
                } else if (code == CODE_FNC_1) {
                    fnc1(true);
                } else {
                    codeSet = code;
                }
                return true;
            }
            boolean unshift = shift;
            switch (code) {
                case CODE_FNC_1 -> fnc1(false);
                case CODE_FNC_2 -> {
                    // Message append carries no text.
                }
                case CODE_FNC_3 -> {
                    // Reader programming carries no text.
                }
                case CODE_SHIFT -> {
                    if (shift) {
                        return false;
                    }
                    shift = true;
                    codeSet = codeSet == CODE_CODE_A ? CODE_CODE_B : CODE_CODE_A;
                }
                case CODE_CODE_A, CODE_CODE_B -> {
                    if (codeSet == code) {
                        if (fnc4Next) {
                            fnc4All = !fnc4All;
                        }
                        fnc4Next = !fnc4Next;
                    } else {
                        codeSet = code;
                    }
                }
                case CODE_CODE_C -> codeSet = CODE_CODE_C;
                default -> {
                    int offset;
                    if (codeSet == CODE_CODE_A && code >= 64) {
                        offset = fnc4All == fnc4Next ? -64 : 64;
                    } else {
                        offset = fnc4All == fnc4Next ? ' ' : ' ' + 128;
                    }
                    text.append((char) (code + offset));
                    fnc4Next = false;
                }
            }
            if (unshift) {
                codeSet = codeSet == CODE_CODE_A ? CODE_CODE_B : CODE_CODE_A;
                shift = false;
            }
            return true;
        }

        String finish() {
            return text.substring(0, kept);
        }

        private void fnc1(boolean codeSetC) {
            if (!fnc1Seen && text.length() == 0) {
                fnc1Seen = true;
                return;
            }
            if (!fnc1Seen
                    && ((codeSetC && text.length() == 2 && digit(0) && digit(1))
                    || (!codeSetC && text.length() == 1 && alpha(text.charAt(0))))) {
                fnc1Seen = true;
                return;
            }
            text.append((char) 29);
        }

        private boolean digit(int index) {
            char c = text.charAt(index);
            return c >= '0' && c <= '9';
        }

        private static boolean alpha(char c) {
            return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
        }
    }
}
