/*
 * Copyright 2007 ZXing authors
 * Copyright 2016 Nu-book Inc.
 * Copyright 2016 ZXing authors
 * Copyright 2023 Axel Waggershauser
 * Copyright 2023 gitlost
 * Copyright 2026 Kamil Krzywański
 */
// SPDX-License-Identifier: Apache-2.0
package io.github.kamilkrzywanski.barcode.qr;

import io.github.kamilkrzywanski.barcode.QrErrorCorrection;

/**
 * A model 2 QR Code version: its size, the centres of its alignment patterns and its error
 * correction blocks at each level (ISO/IEC 18004 table 9, as ZXing's {@code Version} and
 * zxing-cpp's {@code QRVersion.cpp} carry it). The reader takes the alignment patterns, the writer
 * everything.
 */
public final class QrVersion {
    /**
     * Per version: the alignment pattern centres, then for L, M, Q and H the error correction
     * codewords of a block followed by pairs of block count and data codewords a block.
     */
    private static final int[][][] TABLE = {
            {{}, {7, 1, 19}, {10, 1, 16}, {13, 1, 13}, {17, 1, 9}},
            {{6, 18}, {10, 1, 34}, {16, 1, 28}, {22, 1, 22}, {28, 1, 16}},
            {{6, 22}, {15, 1, 55}, {26, 1, 44}, {18, 2, 17}, {22, 2, 13}},
            {{6, 26}, {20, 1, 80}, {18, 2, 32}, {26, 2, 24}, {16, 4, 9}},
            {{6, 30}, {26, 1, 108}, {24, 2, 43}, {18, 2, 15, 2, 16}, {22, 2, 11, 2, 12}},
            {{6, 34}, {18, 2, 68}, {16, 4, 27}, {24, 4, 19}, {28, 4, 15}},
            {{6, 22, 38}, {20, 2, 78}, {18, 4, 31}, {18, 2, 14, 4, 15}, {26, 4, 13, 1, 14}},
            {{6, 24, 42}, {24, 2, 97}, {22, 2, 38, 2, 39}, {22, 4, 18, 2, 19}, {26, 4, 14, 2, 15}},
            {{6, 26, 46}, {30, 2, 116}, {22, 3, 36, 2, 37}, {20, 4, 16, 4, 17}, {24, 4, 12, 4, 13}},
            {{6, 28, 50}, {18, 2, 68, 2, 69}, {26, 4, 43, 1, 44}, {24, 6, 19, 2, 20}, {28, 6, 15, 2, 16}},
            {{6, 30, 54}, {20, 4, 81}, {30, 1, 50, 4, 51}, {28, 4, 22, 4, 23}, {24, 3, 12, 8, 13}},
            {{6, 32, 58}, {24, 2, 92, 2, 93}, {22, 6, 36, 2, 37}, {26, 4, 20, 6, 21}, {28, 7, 14, 4, 15}},
            {{6, 34, 62}, {26, 4, 107}, {22, 8, 37, 1, 38}, {24, 8, 20, 4, 21}, {22, 12, 11, 4, 12}},
            {{6, 26, 46, 66}, {30, 3, 115, 1, 116}, {24, 4, 40, 5, 41}, {20, 11, 16, 5, 17}, {24, 11, 12, 5, 13}},
            {{6, 26, 48, 70}, {22, 5, 87, 1, 88}, {24, 5, 41, 5, 42}, {30, 5, 24, 7, 25}, {24, 11, 12, 7, 13}},
            {{6, 26, 50, 74}, {24, 5, 98, 1, 99}, {28, 7, 45, 3, 46}, {24, 15, 19, 2, 20}, {30, 3, 15, 13, 16}},
            {{6, 30, 54, 78}, {28, 1, 107, 5, 108}, {28, 10, 46, 1, 47}, {28, 1, 22, 15, 23}, {28, 2, 14, 17, 15}},
            {{6, 30, 56, 82}, {30, 5, 120, 1, 121}, {26, 9, 43, 4, 44}, {28, 17, 22, 1, 23}, {28, 2, 14, 19, 15}},
            {{6, 30, 58, 86}, {28, 3, 113, 4, 114}, {26, 3, 44, 11, 45}, {26, 17, 21, 4, 22}, {26, 9, 13, 16, 14}},
            {{6, 34, 62, 90}, {28, 3, 107, 5, 108}, {26, 3, 41, 13, 42}, {30, 15, 24, 5, 25}, {28, 15, 15, 10, 16}},
            {{6, 28, 50, 72, 94}, {28, 4, 116, 4, 117}, {26, 17, 42}, {28, 17, 22, 6, 23}, {30, 19, 16, 6, 17}},
            {{6, 26, 50, 74, 98}, {28, 2, 111, 7, 112}, {28, 17, 46}, {30, 7, 24, 16, 25}, {24, 34, 13}},
            {{6, 30, 54, 78, 102}, {30, 4, 121, 5, 122}, {28, 4, 47, 14, 48}, {30, 11, 24, 14, 25}, {30, 16, 15, 14, 16}},
            {{6, 28, 54, 80, 106}, {30, 6, 117, 4, 118}, {28, 6, 45, 14, 46}, {30, 11, 24, 16, 25}, {30, 30, 16, 2, 17}},
            {{6, 32, 58, 84, 110}, {26, 8, 106, 4, 107}, {28, 8, 47, 13, 48}, {30, 7, 24, 22, 25}, {30, 22, 15, 13, 16}},
            {{6, 30, 58, 86, 114}, {28, 10, 114, 2, 115}, {28, 19, 46, 4, 47}, {28, 28, 22, 6, 23}, {30, 33, 16, 4, 17}},
            {{6, 34, 62, 90, 118}, {30, 8, 122, 4, 123}, {28, 22, 45, 3, 46}, {30, 8, 23, 26, 24}, {30, 12, 15, 28, 16}},
            {{6, 26, 50, 74, 98, 122}, {30, 3, 117, 10, 118}, {28, 3, 45, 23, 46}, {30, 4, 24, 31, 25}, {30, 11, 15, 31, 16}},
            {{6, 30, 54, 78, 102, 126}, {30, 7, 116, 7, 117}, {28, 21, 45, 7, 46}, {30, 1, 23, 37, 24}, {30, 19, 15, 26, 16}},
            {{6, 26, 52, 78, 104, 130}, {30, 5, 115, 10, 116}, {28, 19, 47, 10, 48}, {30, 15, 24, 25, 25}, {30, 23, 15, 25, 16}},
            {{6, 30, 56, 82, 108, 134}, {30, 13, 115, 3, 116}, {28, 2, 46, 29, 47}, {30, 42, 24, 1, 25}, {30, 23, 15, 28, 16}},
            {{6, 34, 60, 86, 112, 138}, {30, 17, 115}, {28, 10, 46, 23, 47}, {30, 10, 24, 35, 25}, {30, 19, 15, 35, 16}},
            {{6, 30, 58, 86, 114, 142}, {30, 17, 115, 1, 116}, {28, 14, 46, 21, 47}, {30, 29, 24, 19, 25}, {30, 11, 15, 46, 16}},
            {{6, 34, 62, 90, 118, 146}, {30, 13, 115, 6, 116}, {28, 14, 46, 23, 47}, {30, 44, 24, 7, 25}, {30, 59, 16, 1, 17}},
            {{6, 30, 54, 78, 102, 126, 150}, {30, 12, 121, 7, 122}, {28, 12, 47, 26, 48}, {30, 39, 24, 14, 25}, {30, 22, 15, 41, 16}},
            {{6, 24, 50, 76, 102, 128, 154}, {30, 6, 121, 14, 122}, {28, 6, 47, 34, 48}, {30, 46, 24, 10, 25}, {30, 2, 15, 64, 16}},
            {{6, 28, 54, 80, 106, 132, 158}, {30, 17, 122, 4, 123}, {28, 29, 46, 14, 47}, {30, 49, 24, 10, 25}, {30, 24, 15, 46, 16}},
            {{6, 32, 58, 84, 110, 136, 162}, {30, 4, 122, 18, 123}, {28, 13, 46, 32, 47}, {30, 48, 24, 14, 25}, {30, 42, 15, 32, 16}},
            {{6, 26, 54, 82, 110, 138, 166}, {30, 20, 117, 4, 118}, {28, 40, 47, 7, 48}, {30, 43, 24, 22, 25}, {30, 10, 15, 67, 16}},
            {{6, 30, 58, 86, 114, 142, 170}, {30, 19, 118, 6, 119}, {28, 18, 47, 31, 48}, {30, 34, 24, 34, 25}, {30, 20, 15, 61, 16}},
    };
    private static final QrVersion[] VERSIONS = new QrVersion[40];

    static {
        for (int i = 0; i < VERSIONS.length; i++) {
            VERSIONS[i] = new QrVersion(i + 1, TABLE[i]);
        }
    }

    private final int number;
    private final int[] alignmentPatternCenters;
    private final EcBlocks[] ecBlocks = new EcBlocks[4];
    private final int totalCodewords;

    private QrVersion(int number, int[][] row) {
        this.number = number;
        this.alignmentPatternCenters = row[0];
        for (int level = 0; level < 4; level++) {
            int[] spec = row[level + 1];
            int groups = (spec.length - 1) / 2;
            int blocks = 0;
            int dataCodewords = 0;
            for (int i = 0; i < groups; i++) {
                blocks += spec[1 + 2 * i];
                dataCodewords += spec[1 + 2 * i] * spec[2 + 2 * i];
            }
            ecBlocks[level] = new EcBlocks(spec[0], blocks, dataCodewords);
        }
        EcBlocks any = ecBlocks[0];
        this.totalCodewords = any.dataCodewords + any.totalEcCodewords();
    }

    /** Version 1 to 40, else null. */
    public static QrVersion of(int number) {
        return number < 1 || number > 40 ? null : VERSIONS[number - 1];
    }

    public int number() {
        return number;
    }

    /** Modules on a side: 17 plus 4 a version. */
    public int dimension() {
        return 17 + 4 * number;
    }

    /** Shared; callers only read it. */
    public int[] alignmentPatternCenters() {
        return alignmentPatternCenters;
    }

    /** Data and error correction codewords together. */
    public int totalCodewords() {
        return totalCodewords;
    }

    public EcBlocks ecBlocks(QrErrorCorrection level) {
        return ecBlocks[level.ordinal()];
    }

    @Override
    public String toString() {
        return Integer.toString(number);
    }

    /** The blocks of one level. Every block has the same number of error correction codewords. */
    public static final class EcBlocks {
        private final int ecCodewordsPerBlock;
        private final int numBlocks;
        private final int dataCodewords;

        private EcBlocks(int ecCodewordsPerBlock, int numBlocks, int dataCodewords) {
            this.ecCodewordsPerBlock = ecCodewordsPerBlock;
            this.numBlocks = numBlocks;
            this.dataCodewords = dataCodewords;
        }

        public int ecCodewordsPerBlock() {
            return ecCodewordsPerBlock;
        }

        public int numBlocks() {
            return numBlocks;
        }

        public int totalEcCodewords() {
            return ecCodewordsPerBlock * numBlocks;
        }

        /** Data codewords of all blocks together. */
        public int dataCodewords() {
            return dataCodewords;
        }
    }
}
