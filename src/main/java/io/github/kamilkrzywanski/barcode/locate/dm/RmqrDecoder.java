/*
 * Copyright 2016 Nu-book Inc.
 * Copyright 2016 ZXing authors
 * Copyright 2023 gitlost
 * Copyright 2023 Axel Waggershauser
 * Copyright 2026 Kamil Krzywański
 */
// SPDX-License-Identifier: Apache-2.0
package io.github.kamilkrzywanski.barcode.locate.dm;

import io.github.kamilkrzywanski.barcode.decode.Decoded;
import io.github.kamilkrzywanski.barcode.locate.dm.DmGeometry.Bits;

/**
 * rMQR (rectangular Micro QR, ISO/IEC 23941) bit decoder ported from zxing-cpp 3.1.1
 * {@code QRVersion.cpp} ({@code Version::rMQR}, {@code buildFunctionPattern}),
 * {@code QRFormatInformation.cpp} ({@code DecodeRMQR}), {@code QRBitMatrixParser.cpp}
 * ({@code ReadRMQRCodewords}) and {@code QRCodecMode.cpp}. The blocks and the bit stream are
 * those of model-2 QR, in {@link QrDecoder}.
 *
 * <p>The version is the sampled matrix size, as {@code ReadVersion} has it. A checksum or format
 * failure returns null.
 */
final class RmqrDecoder {
    private static final int FORMAT_INFO_MASK = 0x1FAB2;
    private static final int FORMAT_INFO_MASK_SUB = 0x20A7B;
    /** ISO/IEC 23941 Annex C, Table C.1: the masked format sequences beside the finder pattern. */
    private static final int[] MASKED_PATTERNS = {
            0x1FAB2, 0x1E597, 0x1DBDD, 0x1C4F8, 0x1B86C, 0x1A749, 0x19903, 0x18626,
            0x17F0E, 0x1602B, 0x15E61, 0x14144, 0x13DD0, 0x122F5, 0x11CBF, 0x1039A,
            0x0F1CA, 0x0EEEF, 0x0D0A5, 0x0CF80, 0x0B314, 0x0AC31, 0x0927B, 0x08D5E,
            0x07476, 0x06B53, 0x05519, 0x04A3C, 0x036A8, 0x0298D, 0x017C7, 0x008E2,
            0x3F367, 0x3EC42, 0x3D208, 0x3CD2D, 0x3B1B9, 0x3AE9C, 0x390D6, 0x38FF3,
            0x376DB, 0x369FE, 0x357B4, 0x34891, 0x33405, 0x32B20, 0x3156A, 0x30A4F,
            0x2F81F, 0x2E73A, 0x2D970, 0x2C655, 0x2BAC1, 0x2A5E4, 0x29BAE, 0x2848B,
            0x27DA3, 0x26286, 0x25CCC, 0x243E9, 0x23F7D, 0x22058, 0x21E12, 0x20137,
    };
    /** The same beside the finder sub-pattern. */
    private static final int[] MASKED_PATTERNS_SUB = {
            0x20A7B, 0x2155E, 0x22B14, 0x23431, 0x248A5, 0x25780, 0x269CA, 0x276EF,
            0x28FC7, 0x290E2, 0x2AEA8, 0x2B18D, 0x2CD19, 0x2D23C, 0x2EC76, 0x2F353,
            0x30103, 0x31E26, 0x3206C, 0x33F49, 0x343DD, 0x35CF8, 0x362B2, 0x37D97,
            0x384BF, 0x39B9A, 0x3A5D0, 0x3BAF5, 0x3C661, 0x3D944, 0x3E70E, 0x3F82B,
            0x003AE, 0x01C8B, 0x022C1, 0x03DE4, 0x04170, 0x05E55, 0x0601F, 0x07F3A,
            0x08612, 0x09937, 0x0A77D, 0x0B858, 0x0C4CC, 0x0DBE9, 0x0E5A3, 0x0FA86,
            0x108D6, 0x117F3, 0x129B9, 0x1369C, 0x14A08, 0x1552D, 0x16B67, 0x17442,
            0x18D6A, 0x1924F, 0x1AC05, 0x1B320, 0x1CFB4, 0x1D091, 0x1EEDB, 0x1F1FE,
    };
    /** Width and height of each version, version 1 first. */
    static final int[][] SIZES = {
            {43, 7}, {59, 7}, {77, 7}, {99, 7}, {139, 7},
            {43, 9}, {59, 9}, {77, 9}, {99, 9}, {139, 9},
            {27, 11}, {43, 11}, {59, 11}, {77, 11}, {99, 11}, {139, 11},
            {27, 13}, {43, 13}, {59, 13}, {77, 13}, {99, 13}, {139, 13},
            {43, 15}, {59, 15}, {77, 15}, {99, 15}, {139, 15},
            {43, 17}, {59, 17}, {77, 17}, {99, 17}, {139, 17},
    };
    /**
     * {@code [version][level]} for levels M and H, each {ec codewords per block, count, data,
     * count, data}: ISO/IEC 23941 7.5.1, Table 8.
     */
    private static final int[][][] LEVELS = {
            null,
            {{7, 1, 6, 0, 0}, {10, 1, 3, 0, 0}},
            {{9, 1, 12, 0, 0}, {14, 1, 7, 0, 0}},
            {{12, 1, 20, 0, 0}, {22, 1, 10, 0, 0}},
            {{16, 1, 28, 0, 0}, {30, 1, 14, 0, 0}},
            {{24, 1, 44, 0, 0}, {22, 2, 12, 0, 0}},
            {{9, 1, 12, 0, 0}, {14, 1, 7, 0, 0}},
            {{12, 1, 21, 0, 0}, {22, 1, 11, 0, 0}},
            {{18, 1, 31, 0, 0}, {16, 1, 8, 1, 9}},
            {{24, 1, 42, 0, 0}, {22, 2, 11, 0, 0}},
            {{18, 1, 31, 1, 32}, {22, 3, 11, 0, 0}},
            {{8, 1, 7, 0, 0}, {10, 1, 5, 0, 0}},
            {{12, 1, 19, 0, 0}, {20, 1, 11, 0, 0}},
            {{16, 1, 31, 0, 0}, {16, 1, 7, 1, 8}},
            {{24, 1, 43, 0, 0}, {22, 1, 11, 1, 12}},
            {{16, 1, 28, 1, 29}, {30, 1, 14, 1, 15}},
            {{24, 2, 42, 0, 0}, {30, 3, 14, 0, 0}},
            {{9, 1, 12, 0, 0}, {14, 1, 7, 0, 0}},
            {{14, 1, 27, 0, 0}, {28, 1, 13, 0, 0}},
            {{22, 1, 38, 0, 0}, {20, 2, 10, 0, 0}},
            {{16, 1, 26, 1, 27}, {28, 1, 14, 1, 15}},
            {{20, 1, 36, 1, 37}, {26, 1, 11, 2, 12}},
            {{20, 2, 35, 1, 36}, {28, 2, 13, 2, 14}},
            {{18, 1, 33, 0, 0}, {18, 1, 7, 1, 8}},
            {{26, 1, 48, 0, 0}, {24, 2, 13, 0, 0}},
            {{18, 1, 33, 1, 34}, {24, 2, 10, 1, 11}},
            {{24, 2, 44, 0, 0}, {22, 4, 12, 0, 0}},
            {{24, 2, 42, 1, 43}, {26, 1, 13, 4, 14}},
            {{22, 1, 39, 0, 0}, {20, 1, 10, 1, 11}},
            {{16, 2, 28, 0, 0}, {30, 2, 14, 0, 0}},
            {{22, 2, 39, 0, 0}, {28, 1, 12, 2, 13}},
            {{20, 2, 33, 1, 34}, {26, 4, 14, 0, 0}},
            {{20, 4, 38, 0, 0}, {26, 2, 12, 4, 13}},
    };

    private RmqrDecoder() {
    }

    /** The version of a {@code width} by {@code height} symbol, or 0. */
    static int version(int width, int height) {
        for (int i = 0; i < SIZES.length; i++) {
            if (SIZES[i][0] == width && SIZES[i][1] == height) {
                return i + 1;
            }
        }
        return 0;
    }

    /** Column centres of the alignment patterns, ISO/IEC 23941 Annex D, Table D.1: by width. */
    static int[] alignmentCentres(int width) {
        return switch (width) {
            case 43 -> new int[]{21};
            case 59 -> new int[]{19, 39};
            case 77 -> new int[]{25, 51};
            case 99 -> new int[]{23, 49, 75};
            case 139 -> new int[]{27, 55, 83, 111};
            default -> new int[0];
        };
    }

    static Decoded decodeContent(Bits bits) {
        if (bits == null) {
            return null;
        }
        int version = version(bits.width, bits.height);
        if (version == 0) {
            return null;
        }
        Format format = readFormat(bits);
        if (!format.valid()) {
            return null;
        }
        int[] level = LEVELS[version][format.high ? 1 : 0];
        int[] raw = readCodewords(bits, level);
        if (raw == null) {
            return null;
        }
        byte[] data = QrDecoder.correct(raw, level);
        if (data == null) {
            return null;
        }
        return QrDecoder.decodeStreamContent(data, version, true);
    }

    /** {@code FormatInformation} of rMQR: six data bits, the high one the level and the rest the version. */
    static final class Format {
        int hamming = 255;
        int data;
        boolean high;
        int version;

        boolean valid() {
            return hamming <= 3;
        }
    }

    /** Both copies, beside the finder pattern and beside the sub-pattern, as {@code ReadFormatInformation} reads them. */
    private static Format readFormat(Bits bits) {
        int bits1 = 0;
        for (int y = 3; y >= 1; y--) {
            bits1 = appendBit(bits1, bits.get(11, y));
        }
        for (int x = 10; x >= 8; x--) {
            for (int y = 5; y >= 1; y--) {
                bits1 = appendBit(bits1, bits.get(x, y));
            }
        }
        int width = bits.width;
        int height = bits.height;
        int bits2 = 0;
        for (int x = 3; x <= 5; x++) {
            bits2 = appendBit(bits2, bits.get(width - x, height - 6));
        }
        for (int x = 6; x <= 8; x++) {
            for (int y = 2; y <= 6; y++) {
                bits2 = appendBit(bits2, bits.get(width - x, height - y));
            }
        }
        return decodeFormat(bits1, bits2, true);
    }

    /**
     * {@code DecodeRMQR}: the nearest valid sequence to the first copy and, when {@code second}
     * is read, to the second. {@code bits2} is ignored without it.
     */
    static Format decodeFormat(int bits1, int bits2, boolean second) {
        Format format = new Format();
        best(format, bits1, MASKED_PATTERNS, FORMAT_INFO_MASK);
        if (second) {
            best(format, bits2, MASKED_PATTERNS_SUB, FORMAT_INFO_MASK_SUB);
        }
        format.high = (format.data >> 5 & 1) == 1;
        format.version = (format.data & 0x1F) + 1;
        return format;
    }

    private static void best(Format format, int bits, int[] patterns, int mask) {
        for (int masked : patterns) {
            int pattern = masked ^ mask;
            int hamming = Integer.bitCount((bits ^ mask) ^ pattern);
            if (hamming < format.hamming) {
                format.data = pattern >> 12;
                format.hamming = hamming;
            }
        }
    }

    private static int appendBit(int value, boolean bit) {
        return (value << 1) | (bit ? 1 : 0);
    }

    /** {@code ReadRMQRCodewords}: column pairs from the right, up and down in turn, under data mask 4. */
    private static int[] readCodewords(Bits bits, int[] level) {
        int expected = level[1] * (level[2] + level[0]) + level[3] * (level[4] + level[0]);
        boolean[][] function = functionPattern(bits.width, bits.height);
        int[] result = new int[expected];
        int count = 0;
        int current = 0;
        int read = 0;
        boolean up = true;
        int width = bits.width;
        int height = bits.height;
        for (int x = width - 2; x > 0; x -= 2) {
            for (int row = 0; row < height; row++) {
                int y = up ? height - 1 - row : row;
                for (int column = 0; column < 2; column++) {
                    int xx = x - column;
                    if (function[y][xx]) {
                        continue;
                    }
                    current = appendBit(current, mask(xx, y) != bits.get(xx, y)) & 0xFF;
                    if (++read % 8 == 0) {
                        if (count == expected) {
                            return null;
                        }
                        result[count++] = current;
                        current = 0;
                    }
                }
            }
            up = !up;
        }
        return count == expected ? result : null;
    }

    /** Data mask 4, the only one rMQR uses. */
    private static boolean mask(int x, int y) {
        return (y / 2 + x / 3) % 2 == 0;
    }

    /** {@code Version::buildFunctionPattern} of rMQR, indexed {@code [y][x]}. */
    static boolean[][] functionPattern(int width, int height) {
        boolean[][] pattern = new boolean[height][width];
        region(pattern, 0, 0, width, 1);
        region(pattern, 0, height - 1, width, 1);
        region(pattern, 0, 1, 1, height - 2);
        region(pattern, width - 1, 1, 1, height - 2);
        for (int cx : alignmentCentres(width)) {
            region(pattern, cx - 1, 1, 3, 2);
            region(pattern, cx - 1, height - 3, 3, 2);
            region(pattern, cx, 3, 1, height - 6);
        }
        // Finder pattern and separator; on R7 the finder's bottom is flush with the edge.
        region(pattern, 1, 1, 7, 7 - (height == 7 ? 1 : 0));
        region(pattern, 8, 1, 3, 5);
        region(pattern, 11, 1, 1, 3);
        region(pattern, width - 5, height - 5, 4, 4);
        region(pattern, width - 8, height - 6, 3, 5);
        region(pattern, width - 5, height - 6, 3, 1);
        pattern[1][width - 2] = true;
        if (height > 9) {
            pattern[height - 2][1] = true;
        }
        return pattern;
    }

    private static void region(boolean[][] pattern, int left, int top, int width, int height) {
        for (int y = top; y < top + height; y++) {
            for (int x = left; x < left + width; x++) {
                pattern[y][x] = true;
            }
        }
    }
}
