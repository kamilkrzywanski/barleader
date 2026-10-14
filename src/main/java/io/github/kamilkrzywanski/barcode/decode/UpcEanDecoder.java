package io.github.kamilkrzywanski.barcode.decode;

import io.github.kamilkrzywanski.barcode.decode.OneDRow.View;

/**
 * UPC/EAN row decoder ported from zxing-cpp 3.1.1 {@code ODMultiUPCEANReader.cpp},
 * {@code ODUPCEANCommon.cpp} and {@code GTIN.h}.
 *
 * <p>EAN-13, EAN-8 and UPC-E are tried in that order. A UPC-A symbol is the EAN-13
 * form with a leading zero. {@link #decode(boolean[])} returns UPC-E as {@code 0} plus
 * its UPC-A expansion, the 13-digit GS1 text. {@link #decodeSpan(boolean[])} keeps the
 * eight-digit UPC-E text. A checksum failure returns null. The one-argument decode
 * ignores add-on digits, matching the default {@code EanAddOnSymbol::Ignore}. The scan
 * ladder reads these symbols with this decoder, so a clean symbol keeps an
 * {@code original-*} attempt.
 */
final class UpcEanDecoder {
    private static final int CHAR_LEN = 4;
    private static final int MIN_SIZE = 3 + 6 * 4 + 6;
    private static final float MAX_AVG = 0.48f;
    private static final float MAX_INDIVIDUAL = 0.7f;
    private static final double QUIET_LEFT = 6;
    private static final double QUIET_RIGHT_EAN = 3;
    private static final double QUIET_RIGHT_UPC = 6;
    private static final double QUIET_ADDON = 3;
    private static final int[] START_END = {1, 1, 1};
    private static final int[] EXT_START = {1, 1, 2};
    private static final int[] ADDON_5_PARITY = {0x18, 0x14, 0x12, 0x11, 0x0C, 0x06, 0x03, 0x0A, 0x09, 0x05};
    private static final int[] MIDDLE = {1, 1, 1, 1, 1};
    private static final int[] UPCE_END = {1, 1, 1, 1, 1, 1};
    private static final int[][] L = {
            {3, 2, 1, 1}, {2, 2, 2, 1}, {2, 1, 2, 2}, {1, 4, 1, 1}, {1, 1, 3, 2},
            {1, 2, 3, 1}, {1, 1, 1, 4}, {1, 3, 1, 2}, {1, 2, 1, 3}, {3, 1, 1, 2}
    };
    private static final int[][] L_AND_G = {
            {3, 2, 1, 1}, {2, 2, 2, 1}, {2, 1, 2, 2}, {1, 4, 1, 1}, {1, 1, 3, 2},
            {1, 2, 3, 1}, {1, 1, 1, 4}, {1, 3, 1, 2}, {1, 2, 1, 3}, {3, 1, 1, 2},
            {1, 1, 2, 3}, {1, 2, 2, 2}, {2, 2, 1, 2}, {1, 1, 4, 1}, {2, 3, 1, 1},
            {1, 3, 2, 1}, {4, 1, 1, 1}, {2, 1, 3, 1}, {3, 1, 2, 1}, {2, 1, 1, 3}
    };
    private static final int[] FIRST_DIGIT = {0x00, 0x0B, 0x0D, 0x0E, 0x13, 0x19, 0x1C, 0x15, 0x16, 0x1A};
    private static final int[] NUMSYS_AND_CHECK = {
            0x38, 0x34, 0x32, 0x31, 0x2C, 0x26, 0x23, 0x2A, 0x29, 0x25,
            0x07, 0x0B, 0x0D, 0x0E, 0x13, 0x19, 0x1C, 0x15, 0x16, 0x1A
    };

    private UpcEanDecoder() {
    }

    /** Which guard pattern matched. UPC-A is an EAN-13 whose text starts with {@code 0}. */
    enum Kind {
        EAN_13, EAN_8, UPC_E
    }

    /**
     * One symbol. {@code text} is 13 digits for EAN-13, 8 for EAN-8, and 8 for UPC-E.
     * {@code left} and {@code right} are inclusive pixel columns of the guards.
     */
    record Span(Kind kind, String text, int left, int right) {
    }

    /** {@code true} is black. Returns the digit string, or null. Add-on digits are ignored. */
    static String decode(boolean[] black) {
        return decode(black, false, false);
    }

    /**
     * {@code addOn} reads a 2- or 5-digit add-on, the way {@code EanAddOnSymbol::Read} does.
     * {@code require} drops the symbol when the add-on is missing, the way {@code Require} does.
     * The digits are appended with no separator. UPC-E comes back expanded to 13 digits.
     */
    static String decode(boolean[] black, boolean addOn, boolean require) {
        Raw raw = read(black);
        if (raw == null) {
            return null;
        }
        String text = raw.kind == Kind.UPC_E ? "0" + expandUpce(raw.digits) : raw.digits;
        return withAddOn(raw.begin, raw.end, text, addOn, require);
    }

    /** Same symbol as {@link #decode(boolean[])}, with guard columns and the unexpanded UPC-E text. */
    static Span decodeSpan(boolean[] black) {
        Raw raw = read(black);
        if (raw == null) {
            return null;
        }
        int left = raw.begin.pixelsInFront();
        int right = raw.end.pixelsInFront() + raw.end.sum(0) - 1;
        if (right < left) {
            return null;
        }
        return new Span(raw.kind, raw.digits, left, right);
    }

    private record Raw(Kind kind, String digits, View begin, View end) {
    }

    private static Raw read(boolean[] black) {
        if (black == null || black.length == 0) {
            return null;
        }
        View row = OneDRow.View.of(OneDRow.runs(black));
        View guard = OneDRow.findLeftGuard(row, MIN_SIZE, START_END, START_END.length, QUIET_LEFT);
        if (guard == null) {
            return null;
        }
        String text = ean13(guard);
        if (text != null) {
            return gtin(text) ? new Raw(Kind.EAN_13, text, guard, guard.sub(56, START_END.length)) : null;
        }
        text = ean8(guard);
        if (text != null) {
            return gtin(text) ? new Raw(Kind.EAN_8, text, guard, guard.sub(40, START_END.length)) : null;
        }
        text = upce(guard);
        if (text == null || !gtin("0" + expandUpce(text))) {
            return null;
        }
        return new Raw(Kind.UPC_E, text, guard, guard.sub(27, UPCE_END.length));
    }

    /** Append a checksummed add-on. {@code begin} is the left guard and {@code end} is the right guard. */
    private static String withAddOn(View begin, View end, String text, boolean addOn, boolean require) {
        if (!addOn) {
            return text;
        }
        String extra = readAddOn(begin, end);
        if (extra == null) {
            return require ? null : text;
        }
        return text + extra;
    }

    /**
     * The quiet gap after the right guard is at most 3.5 times the left-guard width.
     * Five digits are tried before two. Each attempt sees the same bar, because
     * {@code AddOn} takes the view by value.
     */
    private static String readAddOn(View begin, View end) {
        if (end == null || !end.isValid()) {
            return null;
        }
        View cursor = end.sub(0, end.size());
        int gap = (int) (begin.sum(0) * 3.5);
        if (!cursor.skipSymbol() || !cursor.skipSingle(gap)) {
            return null;
        }
        String five = addOn(cursor.sub(0, cursor.size()), 5);
        if (five != null) {
            return five;
        }
        return addOn(cursor.sub(0, cursor.size()), 2);
    }

    /** {@code AddOn}. Null when the start, separator, quiet zone, or checksum does not match. */
    private static String addOn(View begin, int digitCount) {
        int length = 3 + digitCount * CHAR_LEN + (digitCount - 1) * 2;
        View ext = begin.sub(0, length);
        if (!ext.isValid()) {
            return null;
        }
        double module = OneDRow.isPattern(ext, EXT_START, 4, 0, 0);
        if (module == 0) {
            return null;
        }
        if (!ext.isAtLastBar() && !(ext.after() > QUIET_ADDON * module - 1)) {
            return null;
        }
        View cursor = ext.sub(EXT_START.length, CHAR_LEN);
        int parity = 0;
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < digitCount; i++) {
            if (!cursor.isValid()) {
                return null;
            }
            int match = OneDRow.decodeDigit(cursor, L_AND_G, MAX_AVG, MAX_INDIVIDUAL, false);
            if (match < 0) {
                return null;
            }
            text.append(digit(match % 10));
            parity = (parity << 1) | (match >= 10 ? 1 : 0);
            cursor.skipSymbol();
            if (i + 1 < digitCount) {
                View separator = cursor.sub(0, 2);
                if (!separator.isValid() || !addOnSeparator(separator, module)) {
                    return null;
                }
                cursor.shift(2);
            }
        }
        if (digitCount == 2) {
            int value = (text.charAt(0) - '0') * 10 + (text.charAt(1) - '0');
            return value % 4 == parity ? text.toString() : null;
        }
        return addon5(text.toString()) == indexOf(ADDON_5_PARITY, parity) ? text.toString() : null;
    }

    /** Separator bars compared with the start pattern's module size, not their own. */
    private static boolean addOnSeparator(View separator, double module) {
        double threshold = module * 0.5 + 0.5;
        return Math.abs(separator.at(0) - module) <= threshold
                && Math.abs(separator.at(1) - module) <= threshold;
    }

    /** {@code Ean5Checksum}. */
    private static int addon5(String digits) {
        int sum = 0;
        for (int i = digits.length() - 2; i >= 0; i -= 2) {
            sum += digits.charAt(i) - '0';
        }
        sum *= 3;
        for (int i = digits.length() - 1; i >= 0; i -= 2) {
            sum += digits.charAt(i) - '0';
        }
        sum *= 3;
        return sum % 10;
    }

    private static String ean13(View begin) {
        View mid = begin.sub(27, MIDDLE.length);
        View end = begin.sub(56, START_END.length);
        if (!rightGuard(end, START_END, QUIET_RIGHT_EAN) || !matches(mid, MIDDLE)) {
            return null;
        }
        View next = begin.sub(START_END.length, CHAR_LEN);
        StringBuilder text = new StringBuilder(" ");
        int[] parity = {0};
        next = digits(6, next, text, L_AND_G, parity);
        if (next == null) {
            return null;
        }
        next = digits(6, next.sub(MIDDLE.length, CHAR_LEN), text, L, null);
        if (next == null) {
            return null;
        }
        int first = indexOf(FIRST_DIGIT, parity[0]);
        if (first < 0) {
            return null;
        }
        text.setCharAt(0, digit(first));
        return text.toString();
    }

    private static String ean8(View begin) {
        View mid = begin.sub(19, MIDDLE.length);
        View end = begin.sub(40, START_END.length);
        if (!rightGuard(end, START_END, QUIET_RIGHT_EAN) || !matches(mid, MIDDLE)) {
            return null;
        }
        float module = (begin.sum(START_END.length) + mid.sum(MIDDLE.length) + end.sum(START_END.length)) / 11f;
        for (int start : new int[]{3, 24}) {
            for (int i = 0; i < 4; i++) {
                if (!plausible(begin, start, i, module)) {
                    return null;
                }
            }
        }
        StringBuilder text = new StringBuilder();
        View next = digits(4, begin.sub(START_END.length, CHAR_LEN), text, L, null);
        if (next == null) {
            return null;
        }
        next = digits(4, next.sub(MIDDLE.length, CHAR_LEN), text, L, null);
        return next == null ? null : text.toString();
    }

    private static String upce(View begin) {
        View end = begin.sub(27, UPCE_END.length);
        if (!rightGuard(end, UPCE_END, QUIET_RIGHT_UPC)) {
            return null;
        }
        float module = (begin.sum(START_END.length) + end.sum(UPCE_END.length)) / 9f;
        for (int i = 0; i < 6; i++) {
            if (!plausible(begin, 3, i, module)) {
                return null;
            }
        }
        StringBuilder text = new StringBuilder(" ");
        int[] parity = {0};
        if (digits(6, begin.sub(START_END.length, CHAR_LEN), text, L_AND_G, parity) == null) {
            return null;
        }
        int index = indexOf(NUMSYS_AND_CHECK, parity[0]);
        if (index < 0) {
            return null;
        }
        text.setCharAt(0, digit(index / 10));
        text.append(digit(index % 10));
        return text.toString();
    }

    private static View digits(int count, View next, StringBuilder text, int[][] patterns, int[] parity) {
        if (!next.isValid()) {
            return null;
        }
        for (int i = 0; i < count; i++) {
            int match = OneDRow.decodeDigit(next, patterns, MAX_AVG, MAX_INDIVIDUAL, false);
            if (match < 0) {
                return null;
            }
            text.append(digit(match % 10));
            if (parity != null) {
                parity[0] = (parity[0] << 1) | (match >= 10 ? 1 : 0);
            }
            next.skipSymbol();
        }
        return next;
    }

    private static boolean plausible(View begin, int start, int index, float reference) {
        View digit = begin.sub(start + index * CHAR_LEN, CHAR_LEN);
        if (!digit.isValid() || reference == 0f) {
            return false;
        }
        float module = digit.sum(CHAR_LEN) / 7f;
        return Math.abs(module / reference - 1f) < 0.2f;
    }

    private static boolean rightGuard(View view, int[] pattern, double quiet) {
        if (view == null || !view.isValid()) {
            return false;
        }
        int space = view.isAtLastBar() ? Integer.MAX_VALUE : view.after();
        return OneDRow.isPattern(view, pattern, pattern.length, space, quiet) != 0;
    }

    private static boolean matches(View view, int[] pattern) {
        return view != null && view.isValid() && OneDRow.isPattern(view, pattern, pattern.length, 0, 0) != 0;
    }

    /** {@code ConvertUPCEtoUPCA}. The check digit is copied only when the input has it. */
    private static String expandUpce(String upce) {
        if (upce.length() < 7) {
            return upce;
        }
        String middle = upce.substring(1, 7);
        char last = middle.charAt(5);
        StringBuilder result = new StringBuilder();
        result.append(upce.charAt(0));
        switch (last) {
            case '0', '1', '2' -> result.append(middle, 0, 2).append(last).append("0000").append(middle, 2, 5);
            case '3' -> result.append(middle, 0, 3).append("00000").append(middle, 3, 5);
            case '4' -> result.append(middle, 0, 4).append("00000").append(middle.charAt(4));
            default -> result.append(middle, 0, 5).append("0000").append(last);
        }
        if (upce.length() >= 8) {
            result.append(upce.charAt(7));
        }
        return result.toString();
    }

    /** {@code GTIN::IsCheckDigitValid}. */
    private static boolean gtin(String digits) {
        if (digits.length() < 2) {
            return false;
        }
        int payload = digits.length() - 1;
        int sum = 0;
        for (int i = payload - 1; i >= 0; i -= 2) {
            int value = digits.charAt(i) - '0';
            if (value < 0 || value > 9) {
                return false;
            }
            sum += value;
        }
        sum *= 3;
        for (int i = payload - 2; i >= 0; i -= 2) {
            int value = digits.charAt(i) - '0';
            if (value < 0 || value > 9) {
                return false;
            }
            sum += value;
        }
        int check = (10 - (sum % 10)) % 10;
        return digits.charAt(payload) == digit(check);
    }

    private static int indexOf(int[] values, int needle) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == needle) {
                return i;
            }
        }
        return -1;
    }

    private static char digit(int value) {
        return (char) ('0' + value);
    }
}
