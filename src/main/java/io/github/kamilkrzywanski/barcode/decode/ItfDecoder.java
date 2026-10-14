package io.github.kamilkrzywanski.barcode.decode;

import io.github.kamilkrzywanski.barcode.decode.OneDRow.View;

/**
 * ITF row decoder ported from zxing-cpp 3.1.1 {@code ODITFReader.cpp}.
 *
 * <p>The minimum length is 6 digits, the default used when ITF is not the only requested format.
 * A symbol that starts on the first bar and ends on the last bar of the row may be half that
 * long. The sole-format minimum of 4 is not offered. The optional GTIN check is not required
 * and is not stripped, including when the last digit is a valid check. No symbology identifier
 * is prefixed. The scan ladder reads ITF with this decoder when that format was requested.
 * {@code ITF} is not in {@code Symbology.defaults()}. The quiet-zone minimum stays 6 modules.
 */
final class ItfDecoder {
    private static final int MIN_CHARS = 6;
    private static final int MIN_QUIET = 6;
    private static final int[] START = {1, 1, 1, 1};
    private static final int[] WEIGHTS = {1, 2, 4, 7, 0};

    private ItfDecoder() {
    }

    /** One symbol. {@code left} and {@code right} are inclusive columns of the start and stop guards. */
    record Span(String text, int left, int right) {
    }

    /** One symbol on a black/white row. Null when the row does not hold a valid code. */
    static String decode(boolean[] black) {
        Span span = decodeSpan(black);
        return span == null ? null : span.text();
    }

    /** Same symbol as {@link #decode(boolean[])}, with the guard columns. */
    static Span decodeSpan(boolean[] black) {
        View start = OneDRow.findLeftGuard(View.of(OneDRow.runs(black)), 4 + 10 + 3, START, 4, MIN_QUIET);
        if (start == null) {
            return null;
        }
        int[] threshold = narrowWide(start.sub(4, 10));
        if (threshold == null) {
            return null;
        }
        for (int i = 0; i < 4; i++) {
            if (start.at(i) > threshold[i & 1]) {
                return null;
            }
        }
        int xStart = start.pixelsInFront();
        boolean startsAtFirstBar = start.isAtFirstBar();
        View next = start.sub(4, 10);
        StringBuilder text = new StringBuilder();
        while (next.isValid() && threshold != null) {
            if (next.at(3) > threshold[1] * 3) {
                break;
            }
            int digitBar = 0;
            int digitSpace = 0;
            int wideBar = 0;
            int wideSpace = 0;
            boolean bad = false;
            for (int i = 0; i < 10; i++) {
                int width = next.at(i);
                int limit = threshold[i & 1];
                if (width > limit * 3 || width < limit / 3) {
                    bad = true;
                }
                int wide = width > limit ? 1 : 0;
                if ((i & 1) == 0) {
                    wideBar += wide;
                    digitBar += WEIGHTS[i / 2] * wide;
                } else {
                    wideSpace += wide;
                    digitSpace += WEIGHTS[i / 2] * wide;
                }
            }
            if (bad || wideBar != 2 || wideSpace != 2) {
                break;
            }
            if (!appendDigit(text, digitBar) || !appendDigit(text, digitSpace)) {
                return null;
            }
            threshold = narrowWide(next);
            next.skipSymbol();
        }
        View stop = next.sub(0, 3);
        if (!stop.isValid() || threshold == null
                || stop.at(0) < threshold[0]
                || stop.at(1) > threshold[1]
                || stop.at(2) > threshold[0]) {
            return null;
        }
        int trailing = stop.after();
        int limit = MIN_QUIET * (threshold[0] + threshold[1]) / 3;
        int smaller = Math.min(trailing, xStart);
        if (!(smaller > limit
                || (stop.isAtLastBar() && startsAtFirstBar
                && Math.max(xStart, trailing) < 2 * smaller + 2))) {
            return null;
        }
        int minimum = startsAtFirstBar && stop.isAtLastBar() ? MIN_CHARS / 2 : MIN_CHARS;
        if (text.length() < minimum) {
            return null;
        }
        int right = stop.pixelsInFront() + stop.sum(0) - 1;
        if (right < xStart) {
            return null;
        }
        return new Span(text.toString(), xStart, right);
    }

    /** {@code ToDigit}. Eleven is the two-of-five pattern for zero. A value outside 0..9 is a format error. */
    private static boolean appendDigit(StringBuilder text, int encoded) {
        int value = encoded == 11 ? 0 : encoded;
        if (value < 0 || value > 9) {
            return false;
        }
        text.append((char) ('0' + value));
        return true;
    }

    /** {@code NarrowWideThreshold} over the whole view. Null when the spread is illegal or a threshold is zero. */
    private static int[] narrowWide(View view) {
        if (!view.isValid() || view.size() < 2) {
            return null;
        }
        int minBar = view.at(0);
        int maxBar = minBar;
        int minSpace = view.at(1);
        int maxSpace = minSpace;
        for (int i = 2; i < view.size(); i++) {
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
                return null;
            }
            threshold[i] = Math.max((min[i] + max[i]) / 2, min[i] * 3 / 2);
        }
        if (threshold[0] == 0 || threshold[1] == 0) {
            return null;
        }
        return threshold;
    }
}
