/*
 * Copyright 2010 ZXing authors
 * Copyright 2008 ZXing authors
 * Copyright 2026 Kamil Krzywański
 */
// SPDX-License-Identifier: Apache-2.0
package io.github.kamilkrzywanski.barcode.encode;

import java.util.ArrayList;
import java.util.List;

/**
 * Code 128: code sets A, B and C chosen as the text goes, one forced, or the fewest symbols by
 * dynamic programming. FNC1 to FNC4 are written as the characters U+00F1 to U+00F4, as ZXing takes
 * them; GS1-128 starts with FNC1.
 *
 * <p>Ported from ZXing 3.5.4 {@code com.google.zxing.oned.Code128Writer} (Apache License 2.0, the
 * ZXing authors), with the patterns of {@code Code128Reader}.
 */
public final class Code128Encoder {
    public static final char FNC_1 = 'ñ';
    public static final char FNC_2 = 'ò';
    public static final char FNC_3 = 'ó';
    public static final char FNC_4 = 'ô';

    /** Code set A, B or C forced on the whole text; AUTO chooses. */
    public enum CodeSet { AUTO, A, B, C }

    static final int[][] CODE_PATTERNS = {
            {2, 1, 2, 2, 2, 2}, {2, 2, 2, 1, 2, 2}, {2, 2, 2, 2, 2, 1}, {1, 2, 1, 2, 2, 3}, {1, 2, 1, 3, 2, 2}, {1, 3, 1, 2, 2, 2},
            {1, 2, 2, 2, 1, 3}, {1, 2, 2, 3, 1, 2}, {1, 3, 2, 2, 1, 2}, {2, 2, 1, 2, 1, 3}, {2, 2, 1, 3, 1, 2}, {2, 3, 1, 2, 1, 2},
            {1, 1, 2, 2, 3, 2}, {1, 2, 2, 1, 3, 2}, {1, 2, 2, 2, 3, 1}, {1, 1, 3, 2, 2, 2}, {1, 2, 3, 1, 2, 2}, {1, 2, 3, 2, 2, 1},
            {2, 2, 3, 2, 1, 1}, {2, 2, 1, 1, 3, 2}, {2, 2, 1, 2, 3, 1}, {2, 1, 3, 2, 1, 2}, {2, 2, 3, 1, 1, 2}, {3, 1, 2, 1, 3, 1},
            {3, 1, 1, 2, 2, 2}, {3, 2, 1, 1, 2, 2}, {3, 2, 1, 2, 2, 1}, {3, 1, 2, 2, 1, 2}, {3, 2, 2, 1, 1, 2}, {3, 2, 2, 2, 1, 1},
            {2, 1, 2, 1, 2, 3}, {2, 1, 2, 3, 2, 1}, {2, 3, 2, 1, 2, 1}, {1, 1, 1, 3, 2, 3}, {1, 3, 1, 1, 2, 3}, {1, 3, 1, 3, 2, 1},
            {1, 1, 2, 3, 1, 3}, {1, 3, 2, 1, 1, 3}, {1, 3, 2, 3, 1, 1}, {2, 1, 1, 3, 1, 3}, {2, 3, 1, 1, 1, 3}, {2, 3, 1, 3, 1, 1},
            {1, 1, 2, 1, 3, 3}, {1, 1, 2, 3, 3, 1}, {1, 3, 2, 1, 3, 1}, {1, 1, 3, 1, 2, 3}, {1, 1, 3, 3, 2, 1}, {1, 3, 3, 1, 2, 1},
            {3, 1, 3, 1, 2, 1}, {2, 1, 1, 3, 3, 1}, {2, 3, 1, 1, 3, 1}, {2, 1, 3, 1, 1, 3}, {2, 1, 3, 3, 1, 1}, {2, 1, 3, 1, 3, 1},
            {3, 1, 1, 1, 2, 3}, {3, 1, 1, 3, 2, 1}, {3, 3, 1, 1, 2, 1}, {3, 1, 2, 1, 1, 3}, {3, 1, 2, 3, 1, 1}, {3, 3, 2, 1, 1, 1},
            {3, 1, 4, 1, 1, 1}, {2, 2, 1, 4, 1, 1}, {4, 3, 1, 1, 1, 1}, {1, 1, 1, 2, 2, 4}, {1, 1, 1, 4, 2, 2}, {1, 2, 1, 1, 2, 4},
            {1, 2, 1, 4, 2, 1}, {1, 4, 1, 1, 2, 2}, {1, 4, 1, 2, 2, 1}, {1, 1, 2, 2, 1, 4}, {1, 1, 2, 4, 1, 2}, {1, 2, 2, 1, 1, 4},
            {1, 2, 2, 4, 1, 1}, {1, 4, 2, 1, 1, 2}, {1, 4, 2, 2, 1, 1}, {2, 4, 1, 2, 1, 1}, {2, 2, 1, 1, 1, 4}, {4, 1, 3, 1, 1, 1},
            {2, 4, 1, 1, 1, 2}, {1, 3, 4, 1, 1, 1}, {1, 1, 1, 2, 4, 2}, {1, 2, 1, 1, 4, 2}, {1, 2, 1, 2, 4, 1}, {1, 1, 4, 2, 1, 2},
            {1, 2, 4, 1, 1, 2}, {1, 2, 4, 2, 1, 1}, {4, 1, 1, 2, 1, 2}, {4, 2, 1, 1, 1, 2}, {4, 2, 1, 2, 1, 1}, {2, 1, 2, 1, 4, 1},
            {2, 1, 4, 1, 2, 1}, {4, 1, 2, 1, 2, 1}, {1, 1, 1, 1, 4, 3}, {1, 1, 1, 3, 4, 1}, {1, 3, 1, 1, 4, 1}, {1, 1, 4, 1, 1, 3},
            {1, 1, 4, 3, 1, 1}, {4, 1, 1, 1, 1, 3}, {4, 1, 1, 3, 1, 1}, {1, 1, 3, 1, 4, 1}, {1, 1, 4, 1, 3, 1}, {3, 1, 1, 1, 4, 1},
            {4, 1, 1, 1, 3, 1}, {2, 1, 1, 4, 1, 2}, {2, 1, 1, 2, 1, 4}, {2, 1, 1, 2, 3, 2}, {2, 3, 3, 1, 1, 1, 2},
    };
    private static final int CODE_START_A = 103;
    private static final int CODE_START_B = 104;
    private static final int CODE_START_C = 105;
    private static final int CODE_CODE_A = 101;
    private static final int CODE_CODE_B = 100;
    private static final int CODE_CODE_C = 99;
    private static final int CODE_STOP = 106;
    private static final int CODE_FNC_1 = 102;
    private static final int CODE_FNC_2 = 97;
    private static final int CODE_FNC_3 = 96;
    private static final int CODE_FNC_4_A = 101;
    private static final int CODE_FNC_4_B = 100;

    private enum CType { UNCODABLE, ONE_DIGIT, TWO_DIGITS, FNC_1 }

    private Code128Encoder() {
    }

    /**
     * @param compact the fewest symbols, by ZXing's minimal encoder, instead of the look-ahead
     * @throws IllegalArgumentException for a character Code 128 or the forced code set cannot hold
     */
    public static boolean[] encode(String contents, CodeSet codeSet, boolean compact) {
        int forced = switch (codeSet) {
            case A -> CODE_CODE_A;
            case B -> CODE_CODE_B;
            case C -> CODE_CODE_C;
            default -> -1;
        };
        if (contents.isEmpty()) {
            throw new IllegalArgumentException("Code 128 needs at least one character");
        }
        check(contents, forced);
        return compact && forced == -1 ? new MinimalEncoder().encode(contents) : encodeFast(contents, forced);
    }

    private static void check(String contents, int forcedCodeSet) {
        for (int i = 0; i < contents.length(); i++) {
            char c = contents.charAt(i);
            if (c > 127 && (c < FNC_1 || c > FNC_4)) {
                throw new IllegalArgumentException("Bad character in input: ASCII value=" + (int) c);
            }
            switch (forcedCodeSet) {
                case CODE_CODE_A -> {
                    if (c > 95 && c <= 127) {
                        throw new IllegalArgumentException("Bad character in input for forced code set A: ASCII value=" + (int) c);
                    }
                }
                case CODE_CODE_B -> {
                    if (c < 32) {
                        throw new IllegalArgumentException("Bad character in input for forced code set B: ASCII value=" + (int) c);
                    }
                }
                case CODE_CODE_C -> {
                    if (c < 48 || (c > 57 && c <= 127) || c == FNC_2 || c == FNC_3 || c == FNC_4) {
                        throw new IllegalArgumentException("Bad character in input for forced code set C: ASCII value=" + (int) c);
                    }
                }
                default -> {
                }
            }
        }
    }

    private static boolean[] encodeFast(String contents, int forcedCodeSet) {
        int length = contents.length();
        List<int[]> patterns = new ArrayList<>();
        int checkSum = 0;
        int checkWeight = 1;
        int codeSet = 0;
        int position = 0;
        while (position < length) {
            int newCodeSet = forcedCodeSet == -1 ? chooseCode(contents, position, codeSet) : forcedCodeSet;
            int patternIndex;
            if (newCodeSet == codeSet) {
                switch (contents.charAt(position)) {
                    case FNC_1 -> patternIndex = CODE_FNC_1;
                    case FNC_2 -> patternIndex = CODE_FNC_2;
                    case FNC_3 -> patternIndex = CODE_FNC_3;
                    case FNC_4 -> patternIndex = codeSet == CODE_CODE_A ? CODE_FNC_4_A : CODE_FNC_4_B;
                    default -> {
                        switch (codeSet) {
                            case CODE_CODE_A -> {
                                patternIndex = contents.charAt(position) - ' ';
                                if (patternIndex < 0) {
                                    patternIndex += '`';
                                }
                            }
                            case CODE_CODE_B -> patternIndex = contents.charAt(position) - ' ';
                            default -> {
                                if (position + 1 == length) {
                                    throw new IllegalArgumentException("Bad number of characters for digit only encoding.");
                                }
                                patternIndex = Integer.parseInt(contents.substring(position, position + 2));
                                position++;
                            }
                        }
                    }
                }
                position++;
            } else {
                if (codeSet == 0) {
                    patternIndex = switch (newCodeSet) {
                        case CODE_CODE_A -> CODE_START_A;
                        case CODE_CODE_B -> CODE_START_B;
                        default -> CODE_START_C;
                    };
                } else {
                    patternIndex = newCodeSet;
                }
                codeSet = newCodeSet;
            }
            patterns.add(CODE_PATTERNS[patternIndex]);
            checkSum += patternIndex * checkWeight;
            if (position != 0) {
                checkWeight++;
            }
        }
        return produceResult(patterns, checkSum);
    }

    static boolean[] produceResult(List<int[]> patterns, int checkSum) {
        checkSum %= 103;
        if (checkSum < 0) {
            throw new IllegalArgumentException("Unable to compute a valid input checksum");
        }
        patterns.add(CODE_PATTERNS[checkSum]);
        patterns.add(CODE_PATTERNS[CODE_STOP]);
        int codeWidth = 0;
        for (int[] pattern : patterns) {
            for (int width : pattern) {
                codeWidth += width;
            }
        }
        boolean[] result = new boolean[codeWidth];
        int pos = 0;
        for (int[] pattern : patterns) {
            pos += Linear.appendPattern(result, pos, pattern, true);
        }
        return result;
    }

    private static CType findCType(CharSequence value, int start) {
        int last = value.length();
        if (start >= last) {
            return CType.UNCODABLE;
        }
        char c = value.charAt(start);
        if (c == FNC_1) {
            return CType.FNC_1;
        }
        if (c < '0' || c > '9') {
            return CType.UNCODABLE;
        }
        if (start + 1 >= last) {
            return CType.ONE_DIGIT;
        }
        c = value.charAt(start + 1);
        if (c < '0' || c > '9') {
            return CType.ONE_DIGIT;
        }
        return CType.TWO_DIGITS;
    }

    private static int chooseCode(CharSequence value, int start, int oldCode) {
        CType lookahead = findCType(value, start);
        if (lookahead == CType.ONE_DIGIT) {
            return oldCode == CODE_CODE_A ? CODE_CODE_A : CODE_CODE_B;
        }
        if (lookahead == CType.UNCODABLE) {
            if (start < value.length()) {
                char c = value.charAt(start);
                if (c < ' ' || (oldCode == CODE_CODE_A && (c < '`' || (c >= FNC_1 && c <= FNC_4)))) {
                    return CODE_CODE_A;
                }
            }
            return CODE_CODE_B;
        }
        if (oldCode == CODE_CODE_A && lookahead == CType.FNC_1) {
            return CODE_CODE_A;
        }
        if (oldCode == CODE_CODE_C) {
            return CODE_CODE_C;
        }
        if (oldCode == CODE_CODE_B) {
            if (lookahead == CType.FNC_1) {
                return CODE_CODE_B;
            }
            lookahead = findCType(value, start + 2);
            if (lookahead == CType.UNCODABLE || lookahead == CType.ONE_DIGIT) {
                return CODE_CODE_B;
            }
            if (lookahead == CType.FNC_1) {
                lookahead = findCType(value, start + 3);
                return lookahead == CType.TWO_DIGITS ? CODE_CODE_C : CODE_CODE_B;
            }
            int index = start + 4;
            while ((lookahead = findCType(value, index)) == CType.TWO_DIGITS) {
                index += 2;
            }
            return lookahead == CType.ONE_DIGIT ? CODE_CODE_B : CODE_CODE_C;
        }
        if (lookahead == CType.FNC_1) {
            lookahead = findCType(value, start + 1);
        }
        return lookahead == CType.TWO_DIGITS ? CODE_CODE_C : CODE_CODE_B;
    }

    /** The fewest symbols, by divide and conquer with memoization over the code set at each position. */
    private static final class MinimalEncoder {
        private enum Charset { A, B, C, NONE }

        private enum Latch { A, B, C, SHIFT, NONE }

        static final String A = " !\"#$%&'()*+,-./0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\]^_\u0000\u0001\u0002"
                + "\u0003\u0004\u0005\u0006\u0007\u0008\u0009\n\u000B\u000C\r\u000E\u000F\u0010\u0011"
                + "\u0012\u0013\u0014\u0015\u0016\u0017\u0018\u0019\u001A\u001B\u001C\u001D\u001E\u001F"
                + "ÿ";
        static final String B = " !\"#$%&'()*+,-./0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\]^_`abcdefghijklmnopqr"
                + "stuvwxyz{|}~\u007Fÿ";
        private static final int CODE_SHIFT = 98;
        private int[][] memoizedCost;
        private Latch[][] minPath;

        private boolean[] encode(String contents) {
            memoizedCost = new int[4][contents.length()];
            minPath = new Latch[4][contents.length()];
            encode(contents, Charset.NONE, 0);
            List<int[]> patterns = new ArrayList<>();
            int[] checkSum = new int[]{0};
            int[] checkWeight = new int[]{1};
            int length = contents.length();
            Charset charset = Charset.NONE;
            for (int i = 0; i < length; i++) {
                Latch latch = minPath[charset.ordinal()][i];
                switch (latch) {
                    case A -> {
                        charset = Charset.A;
                        addPattern(patterns, i == 0 ? CODE_START_A : CODE_CODE_A, checkSum, checkWeight, i);
                    }
                    case B -> {
                        charset = Charset.B;
                        addPattern(patterns, i == 0 ? CODE_START_B : CODE_CODE_B, checkSum, checkWeight, i);
                    }
                    case C -> {
                        charset = Charset.C;
                        addPattern(patterns, i == 0 ? CODE_START_C : CODE_CODE_C, checkSum, checkWeight, i);
                    }
                    case SHIFT -> addPattern(patterns, CODE_SHIFT, checkSum, checkWeight, i);
                    default -> {
                    }
                }
                if (charset == Charset.C) {
                    if (contents.charAt(i) == FNC_1) {
                        addPattern(patterns, CODE_FNC_1, checkSum, checkWeight, i);
                    } else {
                        addPattern(patterns, Integer.parseInt(contents.substring(i, i + 2)), checkSum, checkWeight, i);
                        if (i + 1 < length) {
                            i++;
                        }
                    }
                } else {
                    boolean shiftedToA = charset == Charset.A && latch != Latch.SHIFT || charset == Charset.B && latch == Latch.SHIFT;
                    int patternIndex = switch (contents.charAt(i)) {
                        case FNC_1 -> CODE_FNC_1;
                        case FNC_2 -> CODE_FNC_2;
                        case FNC_3 -> CODE_FNC_3;
                        case FNC_4 -> shiftedToA ? CODE_FNC_4_A : CODE_FNC_4_B;
                        default -> contents.charAt(i) - ' ';
                    };
                    if (shiftedToA && patternIndex < 0) {
                        patternIndex += '`';
                    }
                    addPattern(patterns, patternIndex, checkSum, checkWeight, i);
                }
            }
            memoizedCost = null;
            minPath = null;
            return produceResult(patterns, checkSum[0]);
        }

        private static void addPattern(List<int[]> patterns, int patternIndex, int[] checkSum, int[] checkWeight, int position) {
            patterns.add(CODE_PATTERNS[patternIndex]);
            if (position != 0) {
                checkWeight[0]++;
            }
            checkSum[0] += patternIndex * checkWeight[0];
        }

        private static boolean isDigit(char c) {
            return c >= '0' && c <= '9';
        }

        private boolean canEncode(CharSequence contents, Charset charset, int position) {
            char c = contents.charAt(position);
            return switch (charset) {
                case A -> c == FNC_1 || c == FNC_2 || c == FNC_3 || c == FNC_4 || A.indexOf(c) >= 0;
                case B -> c == FNC_1 || c == FNC_2 || c == FNC_3 || c == FNC_4 || B.indexOf(c) >= 0;
                case C -> c == FNC_1 || (position + 1 < contents.length() && isDigit(c) && isDigit(contents.charAt(position + 1)));
                default -> false;
            };
        }

        /** The cost of the text from {@code position} on, starting in {@code charset}. */
        private int encode(CharSequence contents, Charset charset, int position) {
            int mCost = memoizedCost[charset.ordinal()][position];
            if (mCost > 0) {
                return mCost;
            }
            int minCost = Integer.MAX_VALUE;
            Latch minLatch = Latch.NONE;
            boolean atEnd = position + 1 >= contents.length();
            Charset[] sets = new Charset[]{Charset.A, Charset.B};
            for (int i = 0; i <= 1; i++) {
                if (canEncode(contents, sets[i], position)) {
                    int cost = 1;
                    Latch latch = Latch.NONE;
                    if (charset != sets[i]) {
                        cost++;
                        latch = Latch.valueOf(sets[i].toString());
                    }
                    if (!atEnd) {
                        cost += encode(contents, sets[i], position + 1);
                    }
                    if (cost < minCost) {
                        minCost = cost;
                        minLatch = latch;
                    }
                    cost = 1;
                    if (charset == sets[(i + 1) % 2]) {
                        cost++;
                        latch = Latch.SHIFT;
                        if (!atEnd) {
                            cost += encode(contents, charset, position + 1);
                        }
                        if (cost < minCost) {
                            minCost = cost;
                            minLatch = latch;
                        }
                    }
                }
            }
            if (canEncode(contents, Charset.C, position)) {
                int cost = 1;
                Latch latch = Latch.NONE;
                if (charset != Charset.C) {
                    cost++;
                    latch = Latch.C;
                }
                int advance = contents.charAt(position) == FNC_1 ? 1 : 2;
                if (position + advance < contents.length()) {
                    cost += encode(contents, Charset.C, position + advance);
                }
                if (cost < minCost) {
                    minCost = cost;
                    minLatch = latch;
                }
            }
            if (minCost == Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Bad character in input: ASCII value=" + (int) contents.charAt(position));
            }
            memoizedCost[charset.ordinal()][position] = minCost;
            minPath[charset.ordinal()][position] = minLatch;
            return minCost;
        }
    }
}
