/*
 * Copyright 2020 Axel Waggershauser
 * Copyright 2016 Nu-book Inc.
 * Copyright 2016 ZXing authors
 * Copyright 2024 Axel Waggershauser
 * Copyright 2026 Kamil Krzywański
 */
// SPDX-License-Identifier: Apache-2.0
package io.github.kamilkrzywanski.barcode.decode;

import io.github.kamilkrzywanski.barcode.ImagePoint;
import io.github.kamilkrzywanski.barcode.decode.OneDRow.View;

import java.util.ArrayList;
import java.util.List;

/**
 * DataBar (RSS-14) row decoder ported from zxing-cpp 3.1.1 {@code ODDataBarReader.cpp}
 * and {@code ODDataBarCommon.cpp}.
 *
 * <p>Default reader options enable both DataBar and DataBar Omni and leave {@code tryHarder}
 * off, so the one-pass linear shortcut is not used. A pair has to be seen on two rows before
 * it is accepted. {@link #decodeOmni} is that shortcut: DataBar Omni only, still without
 * {@code tryHarder}. {@link #decodeLimited} is the single-row DataBar Limited reader.
 * The returned text is the element string {@code 01} plus a GTIN-14. Parentheses and the
 * {@code ]e0} identifier are not added; {@link #decodeLocated} also returns the position. A GS1
 * Composite linkage flag is removed from the value. Stacked and omnidirectional symbols share this text. The scan ladder
 * calls {@link #decode} for {@link io.github.kamilkrzywanski.barcode.Symbology#DATABAR} and
 * {@link #decodeLimited} for {@link io.github.kamilkrzywanski.barcode.Symbology#DATABAR_LIMITED}.
 */
final class DataBarDecoder {
    private static final int FULL = 8 + 5 + 8;
    private static final int[] OUTSIDE_EVEN = {1, 10, 34, 70, 126};
    private static final int[] INSIDE_ODD = {4, 20, 48, 81};
    private static final int[] OUTSIDE_GSUM = {0, 161, 961, 2015, 2715};
    private static final int[] INSIDE_GSUM = {0, 336, 1036, 1516};
    private static final int[] OUTSIDE_ODD_WIDEST = {8, 6, 4, 3, 1};
    private static final int[] INSIDE_ODD_WIDEST = {2, 4, 6, 8};
    private static final int LIMITED = 1 + 3 * 14 + 2;
    private static final int[] LIMITED_ODD_SUM = {17, 13, 9, 15, 11, 19, 7};
    private static final int[] LIMITED_ODD_WIDEST = {6, 5, 3, 5, 4, 8, 1};
    private static final int[] LIMITED_T_EVEN = {28, 728, 6454, 203, 2408, 1, 16632};
    private static final int[] LIMITED_G_SUM = {0, 183064, 820064, 1000776, 1491021, 1979845, 1996939};
    /** Packed edge-to-edge check characters. Same 89 values as {@code CheckChars} in the limited reader. */
    private static final int[] LIMITED_CHECK = {
            0x2AAE2, 0x2AA72, 0x2AA3A, 0x2A972,
            0x2A93A, 0x2A8BA, 0x2A572, 0x2A53A,
            0x2A4BA, 0x2A2BA, 0x29572, 0x2953A,
            0x294BA, 0x292BA, 0x28ABA, 0x25572,
            0x2553A, 0x254BA, 0x252BA, 0x24ABA,
            0x22ABA, 0x2AB62, 0x2AB32, 0x2AB1A,
            0x2A9B2, 0x2A99A, 0x2A8DA, 0x2A5B2,
            0x2A59A, 0x2A4DA, 0x2A2DA, 0x295B2,
            0x2959A, 0x294DA, 0x292DA, 0x28ADA,
            0x255B2, 0x2559A, 0x254DA, 0x252DA,
            0x24ADA, 0x22ADA, 0x2ABA2, 0x2AB92,
            0x2A9D2, 0x295D2, 0x255D2, 0x2AD62,
            0x2AD32, 0x2AD1A, 0x2ACB2, 0x296B2,
            0x2969A, 0x2965A, 0x2935A, 0x28B5A,
            0x256B2, 0x2569A, 0x24B5A, 0x2B562,
            0x2B532, 0x2B51A, 0x2B4B2, 0x2B49A,
            0x2B2B2, 0x25AB2, 0x25A9A, 0x25A5A,
            0x2595A, 0x24D5A, 0x22D5A, 0x2D562,
            0x2D532, 0x2D51A, 0x2D4B2, 0x2D49A,
            0x2D45A, 0x2D2B2, 0x2D29A, 0x2CAB2,
            0x35532, 0x3551A, 0x354B2, 0x3549A,
            0x3545A, 0x3529A, 0x3525A, 0x34A9A,
            0x35592
    };
    private static final int[][] FINDERS = {
            {11, 10, 3}, {8, 10, 6}, {6, 10, 8}, {4, 10, 10}, {9, 11, 5},
            {7, 11, 7}, {5, 11, 9}, {6, 11, 8}, {4, 12, 10}
    };

    static {
        if (LIMITED_CHECK.length != 89 || LIMITED_ODD_SUM.length != LIMITED_G_SUM.length) {
            throw new IllegalStateException("table size mismatch");
        }
    }

    private DataBarDecoder() {
    }

    /**
     * Default DataBar read. Each entry is one binarized scanline, top to bottom.
     * Null when the rows do not hold a checksum-valid symbol seen twice.
     */
    static String decode(boolean[][] rows) {
        Located located = decodeLocated(rows);
        return located == null ? null : located.text();
    }

    /**
     * A symbol and where it sits in the rows: {@code corners} are row-and-column points, two for
     * a symbol read on one line, four for a stacked one, as zxing-cpp's {@code EstimatePosition}.
     */
    record Located(String text, List<ImagePoint> corners) {
    }

    /** {@link #decode(boolean[][])} with the position of the symbol. */
    static Located decodeLocated(boolean[][] rows) {
        if (rows == null) {
            return null;
        }
        List<Pair> left = new ArrayList<>();
        List<Pair> right = new ArrayList<>();
        for (int y = 0; y < rows.length; y++) {
            Match match = scanPairs(OneDRow.runs(rows[y]), y, left, right);
            if (match != null) {
                followDown(rows, y, left, right, match);
                return new Located(match.text, position(match.left, match.right));
            }
        }
        return null;
    }

    private record Match(String text, Pair left, Pair right) {
    }

    /**
     * Keeps reading the rows below a match while they still show one of its pairs, so the last
     * row each pair is seen on, and the position, reach the bottom of the symbol. A few rows
     * without either pair end it.
     */
    private static void followDown(boolean[][] rows, int from, List<Pair> left, List<Pair> right, Match match) {
        int misses = 0;
        for (int y = from + 1; y < rows.length && misses < 3; y++) {
            int before = match.left.lastY + match.right.lastY;
            scanPairs(OneDRow.runs(rows[y]), y, left, right);
            misses = match.left.lastY + match.right.lastY == before ? misses + 1 : 0;
        }
    }

    /** One scanline of a non-stacked DataBar Omni symbol. */
    static String decodeOmni(boolean[] row) {
        if (row == null) {
            return null;
        }
        Located located = scanOmni(OneDRow.runs(row), 0);
        return located == null ? null : located.text();
    }

    /** One scanline of a DataBar Limited symbol. Null when the row does not hold one. */
    static String decodeLimited(boolean[] row) {
        Located located = decodeLimitedLocated(row, 0);
        return located == null ? null : located.text();
    }

    /** {@link #decodeLimited} on row {@code y}, with the two ends of the symbol on that row. */
    static Located decodeLimitedLocated(boolean[] row, int y) {
        if (row == null) {
            return null;
        }
        View next = View.of(OneDRow.runs(row)).sub(-2, LIMITED);
        while (next.shift(2)) {
            if (!isGuard(next.at(27), next.at(43))) {
                continue;
            }
            int space = (next.at(27) + next.at(43)) / 2;
            int leading = next.isAtFirstBar() ? Integer.MAX_VALUE : next.before();
            int trailing = next.after();
            if ((!next.isAtFirstBar() && leading < space) || (!next.isAtLastBar() && trailing < 4 * space)) {
                continue;
            }
            int minBar = Math.min(next.at(0), Math.min(next.at(28), next.at(44)));
            int maxBar = Math.max(next.at(0), Math.max(next.at(28), next.at(44)));
            if (maxBar > minBar * 4 / 3 + 1) {
                continue;
            }
            View left = next.sub(1, 14);
            View check = next.sub(15, 14);
            View right = next.sub(29, 14);
            int leftWidth = left.sum(0);
            int checkWidth = check.sum(0);
            int rightWidth = right.sum(0);
            if (!ratio26to18(leftWidth, checkWidth) || !ratio26to18(rightWidth, checkWidth)) {
                continue;
            }
            double module = (leftWidth + checkWidth + rightWidth) / (double) (26 + 18 + 26);
            if ((!next.isAtFirstBar() && leading < module) || (!next.isAtLastBar() && trailing < 5 * module)) {
                continue;
            }
            int[] checkWidths = normalizedWidths(check, 14, 18, false);
            if (checkWidths == null) {
                continue;
            }
            int checksum = indexOf(LIMITED_CHECK, OneDRow.pack(checkWidths));
            if (checksum < 0) {
                continue;
            }
            Character leftChar = readLimited(left);
            Character rightChar = readLimited(right);
            if (leftChar == null || rightChar == null
                    || (leftChar.checksum + 20 * rightChar.checksum) % 89 != checksum) {
                continue;
            }
            String text = constructLimited(leftChar, rightChar);
            return text == null ? null : new Located(text, line(y, next.pixelsInFront(), next.pixelsTillEnd()));
        }
        return null;
    }

    /** {@code tryHarder}-less one-line DataBar Omni: a left and a right pair next to each other on this row. */
    private static Located scanOmni(int[] runs, int y) {
        View next = View.of(runs).sub(-1, FULL + 1);
        while (next.shift(2)) {
            if (!isLeftPair(next)) {
                continue;
            }
            Pair left = readPair(next, false);
            if (left != null && next.shift(FULL) && isRightPair(next)) {
                Pair right = readPair(next, true);
                if (right != null && checksumValid(left, right)) {
                    String text = construct(left, right);
                    return text == null ? null : new Located(text, line(y, left.xStart, right.xStop));
                }
            }
        }
        return null;
    }

    /** One row of the stacked reader: remember its pairs, then the first plausible valid pairing seen twice. */
    private static Match scanPairs(int[] runs, int y, List<Pair> leftPairs, List<Pair> rightPairs) {
        View row = View.of(runs);
        View next = row.sub(0, FULL + 1);
        while (next.shift(1)) {
            if (isLeftPair(next)) {
                Pair left = readPair(next, false);
                if (left != null) {
                    left.y = y;
                    left.lastY = y;
                    remember(leftPairs, left);
                    next.shift(FULL - 1);
                }
            }
            if (next.shift(1) && isRightPair(next)) {
                Pair right = readPair(next, true);
                if (right != null) {
                    right.y = y;
                    right.lastY = y;
                    remember(rightPairs, right);
                    next.shift(FULL + 2);
                }
            }
        }
        for (Pair left : leftPairs) {
            for (Pair right : rightPairs) {
                if (checksumValid(left, right) && plausible(left, right) && left.count > 1 && right.count > 1) {
                    String text = construct(left, right);
                    return text == null ? null : new Match(text, left, right);
                }
            }
        }
        return null;
    }

    private static void remember(List<Pair> pairs, Pair pair) {
        for (Pair existing : pairs) {
            if (existing.equals(pair)) {
                existing.count++;
                existing.lastY = Math.max(existing.lastY, pair.y);
                return;
            }
        }
        pairs.add(pair);
    }

    private static boolean isLeftPair(View view) {
        return isFinder(view.at(8), view.at(9), view.at(10), view.at(11), view.at(12))
                && isGuard(view.before(), view.at(11))
                && isCharacterPair(view, 16, 15);
    }

    private static boolean isRightPair(View view) {
        return isFinder(view.at(12), view.at(11), view.at(10), view.at(9), view.at(8))
                && isGuard(view.at(9), view.at(21))
                && isCharacterPair(view, 15, 16);
    }

    /** {@code IsFinder}. The commented-out element checks in the source stay out. Package-private for the expanded reader. */
    static boolean isFinder(int a, int b, int c, int d, int e) {
        int w = 2 * (b + c);
        int n = d + e;
        return w + 5 > 9 * n && w - 5 < 13 * n && a < 2 + 4 * e && 4 * a > n;
    }

    static boolean isGuard(int a, int b) {
        return a > b * 3 / 4 - 2 && a < b * 5 / 4 + 2;
    }

    private static boolean isCharacterPair(View view, int modsLeft, int modsRight) {
        float module = finder(view).sum(0) / 15f;
        return isCharacter(leftChar(view), modsLeft, module) && isCharacter(rightChar(view), modsRight, module);
    }

    static boolean isCharacter(View view, int modules, float moduleSize) {
        float error = Math.abs(view.sum(0) / (float) modules / moduleSize - 1f);
        return error < 0.1f;
    }

    private static View finder(View view) {
        return view.sub(8, 5);
    }

    private static View leftChar(View view) {
        return view.sub(0, 8);
    }

    private static View rightChar(View view) {
        return view.sub(13, 8);
    }

    /** Finder number, 1..9, negated when {@code reversed}. Zero is not a finder. */
    private static int parseFinder(View view, boolean reversed) {
        int[] e2e = OneDRow.normalizedE2E(view, 5, 15, 3, reversed);
        if (e2e == null) {
            return 0;
        }
        int best = -1;
        int bestError = 3;
        for (int i = 0; i < FINDERS.length; i++) {
            int error = 0;
            for (int j = 0; j < 3; j++) {
                error += Math.abs(FINDERS[i][j] - e2e[j]);
            }
            if (error < bestError) {
                bestError = error;
                best = i;
            }
        }
        int index = bestError <= 1 ? 1 + best : 0;
        return reversed ? -index : index;
    }

    private static Pair readPair(View view, boolean rightPair) {
        int finder = parseFinder(finder(view), rightPair);
        if (finder == 0) {
            return null;
        }
        Character outside = readCharacter(rightPair ? rightChar(view) : leftChar(view), true, rightPair);
        if (outside == null) {
            return null;
        }
        Character inside = readCharacter(rightPair ? leftChar(view) : rightChar(view), false, rightPair);
        if (inside == null) {
            return null;
        }
        int xStart = view.pixelsInFront() - view.before();
        int xStop = view.pixelsTillEnd() + 2 * view.at(FULL);
        return new Pair(outside, inside, finder, xStart, xStop);
    }

    /** {@code left} of the returned pair is the outside character and {@code right} is the inside one. */
    private static Character readCharacter(View view, boolean outside, boolean rightPair) {
        int[] odd = new int[4];
        int[] even = new int[4];
        if (!readRaw(view, outside ? 16 : 15, outside == rightPair, odd, even)) {
            return null;
        }
        int checksum = portion(odd) + 3 * portion(even);
        if (outside) {
            int group = (12 - sum(odd)) / 2;
            if (group < 0 || group >= OUTSIDE_GSUM.length) {
                return null;
            }
            int oddWidest = OUTSIDE_ODD_WIDEST[group];
            int value = getValue(odd, oddWidest, false) * OUTSIDE_EVEN[group]
                    + getValue(even, 9 - oddWidest, true)
                    + OUTSIDE_GSUM[group];
            return new Character(value, checksum);
        }
        int group = (10 - sum(even)) / 2;
        if (group < 0 || group >= INSIDE_GSUM.length) {
            return null;
        }
        int oddWidest = INSIDE_ODD_WIDEST[group];
        int value = getValue(even, 9 - oddWidest, false) * INSIDE_ODD[group]
                + getValue(odd, oddWidest, true)
                + INSIDE_GSUM[group];
        return new Character(value, checksum);
    }

    /**
     * Live {@code ReadDataCharacterRaw}: normalized widths, and no off-by-one repair.
     * The expanded reader calls this with 17 modules.
     */
    static boolean readRaw(View view, int modules, boolean reversed, int[] odd, int[] even) {
        int[] widths = normalizedWidths(view, 8, modules, reversed);
        if (widths == null) {
            return false;
        }
        for (int i = 0; i < widths.length; i++) {
            if ((i & 1) == 0) {
                odd[i / 2] = widths[i];
            } else {
                even[i / 2] = widths[i];
            }
        }
        int minSum = 4;
        int maxSum = modules - minSum;
        int oddSum = sum(odd);
        int evenSum = sum(even);
        int sumError = oddSum + evenSum - modules;
        int oddSumError = Math.min(0, oddSum - (minSum + (modules == 15 ? 1 : 0))) + Math.max(0, oddSum - maxSum);
        int evenSumError = Math.min(0, evenSum - minSum) + Math.max(0, evenSum - (maxSum - (modules == 15 ? 1 : 0)));
        boolean oddParityError = (oddSum & 1) == (modules > 15 ? 1 : 0);
        boolean evenParityError = (evenSum & 1) == (modules < 17 ? 1 : 0);
        return sumError == 0 && oddSumError == 0 && evenSumError == 0 && !oddParityError && !evenParityError;
    }

    /** {@code NormalizedPatternFromE2E}. */
    private static int[] normalizedWidths(View view, int len, int modules, boolean reversed) {
        int[] e2e = OneDRow.normalizedE2E(view, len, modules, len - 2, reversed);
        if (e2e == null) {
            return null;
        }
        boolean minOddIsOne = modules == 15 || modules == 17;
        int[] widths = new int[len];
        int barSum = minOddIsOne ? 8 : 1;
        widths[0] = barSum;
        for (int i = 0; i < e2e.length; i++) {
            widths[i + 1] = e2e[i] - widths[i];
            barSum += widths[i + 1];
        }
        widths[len - 1] = modules - barSum;
        int minOdd = widths[0];
        int minEven = widths[1];
        for (int i = 2; i < len; i++) {
            if ((i & 1) == 0) {
                minOdd = Math.min(minOdd, widths[i]);
            } else {
                minEven = Math.min(minEven, widths[i]);
            }
        }
        if (minOddIsOne && minOdd > 1) {
            int delta = minOdd - 1;
            for (int i = 0; i < len; i += 2) {
                widths[i] -= delta;
                widths[i + 1] += delta;
            }
        } else if (!minOddIsOne && minEven > 1) {
            int delta = minEven - 1;
            for (int i = 0; i < len; i += 2) {
                widths[i] += delta;
                widths[i + 1] -= delta;
            }
        }
        return widths;
    }

    /** {@code GetValue}. {@code noNarrow} is the DataBar outside/inside flag from the source. */
    static int getValue(int[] widths, int maxWidth, boolean noNarrow) {
        int elements = widths.length;
        int n = sum(widths);
        int value = 0;
        int narrowMask = 0;
        for (int bar = 0; bar < elements - 1; bar++) {
            int elmWidth = 1;
            narrowMask |= 1 << bar;
            while (elmWidth < widths[bar]) {
                int subVal = combins(n - elmWidth - 1, elements - bar - 2);
                if (noNarrow && narrowMask == 0
                        && n - elmWidth - (elements - bar - 1) >= elements - bar - 1) {
                    subVal -= combins(n - elmWidth - (elements - bar), elements - bar - 2);
                }
                if (elements - bar - 1 > 1) {
                    int lessVal = 0;
                    for (int mxwElement = n - elmWidth - (elements - bar - 2); mxwElement > maxWidth; mxwElement--) {
                        lessVal += combins(n - elmWidth - mxwElement - 1, elements - bar - 3);
                    }
                    subVal -= lessVal * (elements - 1 - bar);
                } else if (n - elmWidth > maxWidth) {
                    subVal--;
                }
                value += subVal;
                elmWidth++;
                narrowMask &= ~(1 << bar);
            }
            n -= elmWidth;
        }
        return value;
    }

    private static int combins(int n, int r) {
        int minDenom;
        int maxDenom;
        if (n - r > r) {
            minDenom = r;
            maxDenom = n - r;
        } else {
            minDenom = n - r;
            maxDenom = r;
        }
        int value = 1;
        int j = 1;
        for (int i = n; i > maxDenom; i--) {
            value *= i;
            if (j <= minDenom) {
                value /= j;
                j++;
            }
        }
        while (j <= minDenom) {
            value /= j;
            j++;
        }
        return value;
    }

    private static int portion(int[] counts) {
        int result = 0;
        for (int i = counts.length - 1; i >= 0; i--) {
            result = 9 * result + counts[i];
        }
        return result;
    }

    private static boolean checksumValid(Pair left, Pair right) {
        int leftSum = left.left.checksum + 4 * left.right.checksum;
        int rightSum = right.left.checksum + 4 * right.right.checksum;
        int a = (leftSum + 16 * rightSum) % 79;
        int b = 9 * (Math.abs(left.finder) - 1) + (Math.abs(right.finder) - 1);
        if (b > 72) {
            b--;
        }
        if (b > 8) {
            b--;
        }
        return a == b && symbolValue(left, right) <= 9_999_999_999_999L;
    }

    /**
     * {@code EstimatePosition}: a line between the pairs, or four corners when stacked. Unlike
     * zxing-cpp, which keeps only the row each pair was first seen on, the line runs through the
     * middle of the rows both pairs were seen on, and a stacked box reaches the last row of the
     * lower pair, so it covers the whole symbol.
     */
    private static List<ImagePoint> position(Pair first, Pair last) {
        boolean stacked = Math.abs(first.y - last.y) > (first.xStop - first.xStart)
                || last.xStart < (first.xStart + first.xStop) / 2;
        if (!stacked) {
            int top = Math.min(first.y, last.y);
            int bottom = Math.max(first.lastY, last.lastY);
            return line((top + bottom) / 2, first.xStart, last.xStop);
        }
        Pair upper = first.y <= last.y ? first : last;
        Pair lower = upper == first ? last : first;
        return List.of(
                new ImagePoint(upper.xStart, upper.y), new ImagePoint(upper.xStop, upper.y),
                new ImagePoint(lower.xStop, lower.lastY), new ImagePoint(lower.xStart, lower.lastY));
    }

    static List<ImagePoint> line(int y, int xStart, int xStop) {
        return List.of(new ImagePoint(xStart, y), new ImagePoint(xStop, y));
    }

    private static boolean plausible(Pair left, Pair right) {
        int widthLeft = left.xStop - left.xStart;
        int widthRight = right.xStop - right.xStart;
        int height = Math.abs(left.y - right.y);
        int dxLeft = Math.abs(left.xStart - right.xStart);
        int dxRight = Math.abs(left.xStop - right.xStart);
        return height < widthLeft && height < widthRight
                && widthLeft > widthRight / 2 && widthRight > widthLeft / 2
                && (dxLeft < widthLeft / 2 || dxRight < widthRight / 2);
    }

    private static long symbolValue(Pair leftPair, Pair rightPair) {
        long left = 1597L * leftPair.left.value + leftPair.right.value;
        long right = 1597L * rightPair.left.value + rightPair.right.value;
        long result = 4_537_077L * left + right;
        if (result >= 10_000_000_000_000L) {
            result -= 10_000_000_000_000L;
        }
        return result;
    }

    private static String construct(Pair left, Pair right) {
        long value = symbolValue(left, right);
        if (value < 0) {
            return null;
        }
        String payload = String.format("%013d", value);
        return "01" + payload + checkDigit(payload);
    }

    /** {@code Has26to18Ratio}. Both sides are the double comparisons from the limited reader. */
    private static boolean ratio26to18(int wide, int narrow) {
        double upper = wide + 1.5 * wide / 26;
        double lower = wide - 1.5 * wide / 26;
        double other = narrow / 18.0 * 26;
        return upper > other && lower < other;
    }

    /** DataBar Limited data character. Fourteen runs, 26 modules. */
    private static Character readLimited(View view) {
        int[] widths = normalizedWidths(view, 14, 26, false);
        if (widths == null) {
            return null;
        }
        int[] odd = new int[7];
        int[] even = new int[7];
        int checksum = 0;
        for (int i = widths.length - 1; i >= 0; i--) {
            checksum = 3 * checksum + widths[i];
        }
        for (int i = 0; i < widths.length; i++) {
            if ((i & 1) == 0) {
                odd[i / 2] = widths[i];
            } else {
                even[i / 2] = widths[i];
            }
        }
        int group = indexOf(LIMITED_ODD_SUM, sum(odd));
        if (group < 0) {
            return null;
        }
        int oddWidest = LIMITED_ODD_WIDEST[group];
        int value = getValue(odd, oddWidest, false) * LIMITED_T_EVEN[group]
                + getValue(even, 9 - oddWidest, true)
                + LIMITED_G_SUM[group];
        return new Character(value, checksum);
    }

    private static String constructLimited(Character left, Character right) {
        long value = 2_013_571L * left.value + right.value;
        if (value >= 2_015_133_531_096L) {
            value -= 2_015_133_531_096L;
        }
        if (value < 0 || value > 1_999_999_999_999L) {
            return null;
        }
        String payload = String.format("%013d", value);
        return "01" + payload + checkDigit(payload);
    }

    private static int indexOf(int[] table, int value) {
        for (int i = 0; i < table.length; i++) {
            if (table[i] == value) {
                return i;
            }
        }
        return -1;
    }

    /** {@code GTIN::ComputeCheckDigit} on the digits that precede the check. */
    private static char checkDigit(String digits) {
        int sum = 0;
        for (int i = digits.length() - 1; i >= 0; i -= 2) {
            sum += digits.charAt(i) - '0';
        }
        sum *= 3;
        for (int i = digits.length() - 2; i >= 0; i -= 2) {
            sum += digits.charAt(i) - '0';
        }
        return (char) ('0' + (10 - sum % 10) % 10);
    }

    private static int sum(int[] values) {
        int total = 0;
        for (int value : values) {
            total += value;
        }
        return total;
    }

    private static final class Character {
        final int value;
        final int checksum;

        Character(int value, int checksum) {
            this.value = value;
            this.checksum = checksum;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Character)) {
                return false;
            }
            Character that = (Character) other;
            return value == that.value && checksum == that.checksum;
        }

        @Override
        public int hashCode() {
            return value ^ checksum;
        }
    }

    /** {@code left} is the outside character. Equality ignores position and the sighting count. */
    private static final class Pair {
        final Character left;
        final Character right;
        final int finder;
        final int xStart;
        final int xStop;
        /** Rows of the first and the last sighting. */
        int y;
        int lastY;
        int count = 1;

        Pair(Character left, Character right, int finder, int xStart, int xStop) {
            this.left = left;
            this.right = right;
            this.finder = finder;
            this.xStart = xStart;
            this.xStop = xStop;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Pair)) {
                return false;
            }
            Pair that = (Pair) other;
            return finder == that.finder && left.equals(that.left) && right.equals(that.right);
        }

        @Override
        public int hashCode() {
            return left.value ^ left.checksum ^ right.value ^ right.checksum ^ finder;
        }
    }
}
