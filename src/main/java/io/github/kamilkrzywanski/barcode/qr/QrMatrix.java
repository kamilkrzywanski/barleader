package io.github.kamilkrzywanski.barcode.qr;

import io.github.kamilkrzywanski.barcode.QrErrorCorrection;
import io.github.kamilkrzywanski.barcode.encode.BitBuffer;

/**
 * Lays out a QR Code: the finder, timing and alignment patterns, the format and version information
 * and the masked data bits, as in ISO/IEC 18004 and JIS X 0510:2004.
 *
 * <p>Ported from ZXing 3.5.4 {@code com.google.zxing.qrcode.encoder.MatrixUtil} (Apache License 2.0,
 * the ZXing authors).
 */
final class QrMatrix {
    private static final int[][] POSITION_DETECTION_PATTERN = {
            {1, 1, 1, 1, 1, 1, 1},
            {1, 0, 0, 0, 0, 0, 1},
            {1, 0, 1, 1, 1, 0, 1},
            {1, 0, 1, 1, 1, 0, 1},
            {1, 0, 1, 1, 1, 0, 1},
            {1, 0, 0, 0, 0, 0, 1},
            {1, 1, 1, 1, 1, 1, 1},
    };

    private static final int[][] POSITION_ADJUSTMENT_PATTERN = {
            {1, 1, 1, 1, 1},
            {1, 0, 0, 0, 1},
            {1, 0, 1, 0, 1},
            {1, 0, 0, 0, 1},
            {1, 1, 1, 1, 1},
    };


    // Type info cells at the left top corner.
    private static final int[][] TYPE_INFO_COORDINATES = {
            {8, 0},
            {8, 1},
            {8, 2},
            {8, 3},
            {8, 4},
            {8, 5},
            {8, 7},
            {8, 8},
            {7, 8},
            {5, 8},
            {4, 8},
            {3, 8},
            {2, 8},
            {1, 8},
            {0, 8},
    };

    // From Appendix D in JISX0510:2004 (p. 67)
    private static final int VERSION_INFO_POLY = 0x1f25;  // 1 1111 0010 0101

    // From Appendix C in JISX0510:2004 (p.65).
    private static final int TYPE_INFO_POLY = 0x537;
    private static final int TYPE_INFO_MASK_PATTERN = 0x5412;

    private QrMatrix() {
    }

    // Set all cells to -1.  -1 means that the cell is empty (not set yet).
    //
    // JAVAPORT: We shouldn't need to do this at all. The code should be rewritten to begin encoding
    // with the ByteMatrix initialized all to zero.
    static void clearMatrix(ByteMatrix matrix) {
        matrix.clear((byte) -1);
    }

    // Build 2D matrix of QR Code from "dataBits" with "ecLevel", "version" and "getMaskPattern". On
    // success, store the result in "matrix" and return true.
    static void buildMatrix(BitBuffer dataBits, QrErrorCorrection ecLevel, QrVersion version, int maskPattern, ByteMatrix matrix) {
        clearMatrix(matrix);
        embedBasicPatterns(version, matrix);
        // Type information appear with any version.
        embedTypeInfo(ecLevel, maskPattern, matrix);
        // Version info appears from version 7 on.
        maybeEmbedVersionInfo(version, matrix);
        // Data should be embedded at end.
        embedDataBits(dataBits, maskPattern, matrix);
    }

    // Embed basic patterns. On success, modify the matrix and return true.
    // The basic patterns are:
    // - Position detection patterns
    // - Timing patterns
    // - Dark dot at the left bottom corner
    // - Position adjustment patterns, if need be
    static void embedBasicPatterns(QrVersion version, ByteMatrix matrix) {
        // Let's get started with embedding big squares at corners.
        embedPositionDetectionPatternsAndSeparators(matrix);
        // Then, embed the dark dot at the left bottom corner.
        embedDarkDotAtLeftBottomCorner(matrix);

        // Position adjustment patterns appear if version >= 2.
        maybeEmbedPositionAdjustmentPatterns(version, matrix);
        // Timing patterns should be embedded after position adj. patterns.
        embedTimingPatterns(matrix);
    }

    // Embed type information. On success, modify the matrix.
    static void embedTypeInfo(QrErrorCorrection ecLevel, int maskPattern, ByteMatrix matrix) {
        BitBuffer typeInfoBits = new BitBuffer();
        makeTypeInfoBits(ecLevel, maskPattern, typeInfoBits);

        for (int i = 0; i < typeInfoBits.getSize(); ++i) {
            // Place bits in LSB to MSB order.  LSB (least significant bit) is the last value in
            // "typeInfoBits".
            boolean bit = typeInfoBits.get(typeInfoBits.getSize() - 1 - i);

            // Type info bits at the left top corner. See 8.9 of JISX0510:2004 (p.46).
            int[] coordinates = TYPE_INFO_COORDINATES[i];
            int x1 = coordinates[0];
            int y1 = coordinates[1];
            matrix.set(x1, y1, bit);

            int x2;
            int y2;
            if (i < 8) {
                // Right top corner.
                x2 = matrix.getWidth() - i - 1;
                y2 = 8;
            } else {
                // Left bottom corner.
                x2 = 8;
                y2 = matrix.getHeight() - 7 + (i - 8);
            }
            matrix.set(x2, y2, bit);
        }
    }

    // Embed version information if need be. On success, modify the matrix and return true.
    // See 8.10 of JISX0510:2004 (p.47) for how to embed version information.
    static void maybeEmbedVersionInfo(QrVersion version, ByteMatrix matrix) {
        if (version.number() < 7) {  // Version info is necessary if version >= 7.
            return;  // Don't need version info.
        }
        BitBuffer versionInfoBits = new BitBuffer();
        makeVersionInfoBits(version, versionInfoBits);

        int bitIndex = 6 * 3 - 1;  // It will decrease from 17 to 0.
        for (int i = 0; i < 6; ++i) {
            for (int j = 0; j < 3; ++j) {
                // Place bits in LSB (least significant bit) to MSB order.
                boolean bit = versionInfoBits.get(bitIndex);
                bitIndex--;
                // Left bottom corner.
                matrix.set(i, matrix.getHeight() - 11 + j, bit);
                // Right bottom corner.
                matrix.set(matrix.getHeight() - 11 + j, i, bit);
            }
        }
    }

    // Embed "dataBits" using "getMaskPattern". On success, modify the matrix and return true.
    // For debugging purposes, it skips masking process if "getMaskPattern" is -1.
    // See 8.7 of JISX0510:2004 (p.38) for how to embed data bits.
    static void embedDataBits(BitBuffer dataBits, int maskPattern, ByteMatrix matrix) {
        int bitIndex = 0;
        int direction = -1;
        // Start from the right bottom cell.
        int x = matrix.getWidth() - 1;
        int y = matrix.getHeight() - 1;
        while (x > 0) {
            // Skip the vertical timing pattern.
            if (x == 6) {
                x -= 1;
            }
            while (y >= 0 && y < matrix.getHeight()) {
                for (int i = 0; i < 2; ++i) {
                    int xx = x - i;
                    // Skip the cell if it's not empty.
                    if (!isEmpty(matrix.get(xx, y))) {
                        continue;
                    }
                    boolean bit;
                    if (bitIndex < dataBits.getSize()) {
                        bit = dataBits.get(bitIndex);
                        ++bitIndex;
                    } else {
                        // Padding bit. If there is no bit left, we'll fill the left cells with 0, as described
                        // in 8.4.9 of JISX0510:2004 (p. 24).
                        bit = false;
                    }

                    // Skip masking if mask_pattern is -1.
                    if (maskPattern != -1 && QrMasks.getDataMaskBit(maskPattern, xx, y)) {
                        bit = !bit;
                    }
                    matrix.set(xx, y, bit);
                }
                y += direction;
            }
            direction = -direction;  // Reverse the direction.
            y += direction;
            x -= 2;  // Move to the left.
        }
        // All bits should be consumed.
        if (bitIndex != dataBits.getSize()) {
            throw new IllegalStateException("Not all bits consumed: " + bitIndex + '/' + dataBits.getSize());
        }
    }

    // Return the position of the most significant bit set (to one) in the "value". The most
    // significant bit is position 32. If there is no bit set, return 0. Examples:
    // - findMSBSet(0) => 0
    // - findMSBSet(1) => 1
    // - findMSBSet(255) => 8
    static int findMSBSet(int value) {
        return 32 - Integer.numberOfLeadingZeros(value);
    }

    // Calculate BCH (Bose-Chaudhuri-Hocquenghem) code for "value" using polynomial "poly". The BCH
    // code is used for encoding type information and version information.
    // Example: Calculation of version information of 7.
    // f(x) is created from 7.
    //   - 7 = 000111 in 6 bits
    //   - f(x) = x^2 + x^1 + x^0
    // g(x) is given by the standard (p. 67)
    //   - g(x) = x^12 + x^11 + x^10 + x^9 + x^8 + x^5 + x^2 + 1
    // Multiply f(x) by x^(18 - 6)
    //   - f'(x) = f(x) * x^(18 - 6)
    //   - f'(x) = x^14 + x^13 + x^12
    // Calculate the remainder of f'(x) / g(x)
    //         x^2
    //         __________________________________________________
    //   g(x) )x^14 + x^13 + x^12
    //         x^14 + x^13 + x^12 + x^11 + x^10 + x^7 + x^4 + x^2
    //         --------------------------------------------------
    //                              x^11 + x^10 + x^7 + x^4 + x^2
    //
    // The remainder is x^11 + x^10 + x^7 + x^4 + x^2
    // Encode it in binary: 110010010100
    // The return value is 0xc94 (1100 1001 0100)
    //
    // Since all coefficients in the polynomials are 1 or 0, we can do the calculation by bit
    // operations. We don't care if coefficients are positive or negative.
    static int calculateBCHCode(int value, int poly) {
        if (poly == 0) {
            throw new IllegalArgumentException("0 polynomial");
        }
        // If poly is "1 1111 0010 0101" (version info poly), msbSetInPoly is 13. We'll subtract 1
        // from 13 to make it 12.
        int msbSetInPoly = findMSBSet(poly);
        value <<= msbSetInPoly - 1;
        // Do the division business using exclusive-or operations.
        while (findMSBSet(value) >= msbSetInPoly) {
            value ^= poly << (findMSBSet(value) - msbSetInPoly);
        }
        // Now the "value" is the remainder (i.e. the BCH code)
        return value;
    }

    // Make bit vector of type information. On success, store the result in "bits" and return true.
    // Encode error correction level and mask pattern. See 8.9 of
    // JISX0510:2004 (p.45) for details.
    static void makeTypeInfoBits(QrErrorCorrection ecLevel, int maskPattern, BitBuffer bits) {
        if (maskPattern < 0 || maskPattern >= QrMasks.NUM_MASK_PATTERNS) {
            throw new IllegalStateException("Invalid mask pattern");
        }
        int typeInfo = (QrEncoder.formatBits(ecLevel) << 3) | maskPattern;
        bits.appendBits(typeInfo, 5);

        int bchCode = calculateBCHCode(typeInfo, TYPE_INFO_POLY);
        bits.appendBits(bchCode, 10);

        BitBuffer maskBits = new BitBuffer();
        maskBits.appendBits(TYPE_INFO_MASK_PATTERN, 15);
        bits.xor(maskBits);

        if (bits.getSize() != 15) {  // Just in case.
            throw new IllegalStateException("should not happen but we got: " + bits.getSize());
        }
    }

    // Make bit vector of version information. On success, store the result in "bits" and return true.
    // See 8.10 of JISX0510:2004 (p.45) for details.
    static void makeVersionInfoBits(QrVersion version, BitBuffer bits) {
        bits.appendBits(version.number(), 6);
        int bchCode = calculateBCHCode(version.number(), VERSION_INFO_POLY);
        bits.appendBits(bchCode, 12);

        if (bits.getSize() != 18) {  // Just in case.
            throw new IllegalStateException("should not happen but we got: " + bits.getSize());
        }
    }

    // Check if "value" is empty.
    private static boolean isEmpty(int value) {
        return value == -1;
    }

    private static void embedTimingPatterns(ByteMatrix matrix) {
        // -8 is for skipping position detection patterns (size 7), and two horizontal/vertical
        // separation patterns (size 1). Thus, 8 = 7 + 1.
        for (int i = 8; i < matrix.getWidth() - 8; ++i) {
            int bit = (i + 1) % 2;
            // Horizontal line.
            if (isEmpty(matrix.get(i, 6))) {
                matrix.set(i, 6, bit);
            }
            // Vertical line.
            if (isEmpty(matrix.get(6, i))) {
                matrix.set(6, i, bit);
            }
        }
    }

    // Embed the lonely dark dot at left bottom corner. JISX0510:2004 (p.46)
    private static void embedDarkDotAtLeftBottomCorner(ByteMatrix matrix) {
        if (matrix.get(8, matrix.getHeight() - 8) == 0) {
            throw new IllegalStateException();
        }
        matrix.set(8, matrix.getHeight() - 8, 1);
    }

    private static void embedHorizontalSeparationPattern(int xStart, int yStart, ByteMatrix matrix) {
        for (int x = 0; x < 8; ++x) {
            if (!isEmpty(matrix.get(xStart + x, yStart))) {
                throw new IllegalStateException();
            }
            matrix.set(xStart + x, yStart, 0);
        }
    }

    private static void embedVerticalSeparationPattern(int xStart, int yStart, ByteMatrix matrix) {
        for (int y = 0; y < 7; ++y) {
            if (!isEmpty(matrix.get(xStart, yStart + y))) {
                throw new IllegalStateException();
            }
            matrix.set(xStart, yStart + y, 0);
        }
    }

    private static void embedPositionAdjustmentPattern(int xStart, int yStart, ByteMatrix matrix) {
        for (int y = 0; y < 5; ++y) {
            int[] patternY = POSITION_ADJUSTMENT_PATTERN[y];
            for (int x = 0; x < 5; ++x) {
                matrix.set(xStart + x, yStart + y, patternY[x]);
            }
        }
    }

    private static void embedPositionDetectionPattern(int xStart, int yStart, ByteMatrix matrix) {
        for (int y = 0; y < 7; ++y) {
            int[] patternY = POSITION_DETECTION_PATTERN[y];
            for (int x = 0; x < 7; ++x) {
                matrix.set(xStart + x, yStart + y, patternY[x]);
            }
        }
    }

    // Embed position detection patterns and surrounding vertical/horizontal separators.
    private static void embedPositionDetectionPatternsAndSeparators(ByteMatrix matrix) {
        // Embed three big squares at corners.
        int pdpWidth = POSITION_DETECTION_PATTERN[0].length;
        // Left top corner.
        embedPositionDetectionPattern(0, 0, matrix);
        // Right top corner.
        embedPositionDetectionPattern(matrix.getWidth() - pdpWidth, 0, matrix);
        // Left bottom corner.
        embedPositionDetectionPattern(0, matrix.getWidth() - pdpWidth, matrix);

        // Embed horizontal separation patterns around the squares.
        int hspWidth = 8;
        // Left top corner.
        embedHorizontalSeparationPattern(0, hspWidth - 1, matrix);
        // Right top corner.
        embedHorizontalSeparationPattern(matrix.getWidth() - hspWidth, hspWidth - 1, matrix);
        // Left bottom corner.
        embedHorizontalSeparationPattern(0, matrix.getWidth() - hspWidth, matrix);

        // Embed vertical separation patterns around the squares.
        int vspSize = 7;
        // Left top corner.
        embedVerticalSeparationPattern(vspSize, 0, matrix);
        // Right top corner.
        embedVerticalSeparationPattern(matrix.getHeight() - vspSize - 1, 0, matrix);
        // Left bottom corner.
        embedVerticalSeparationPattern(vspSize, matrix.getHeight() - vspSize, matrix);
    }

    // Embed position adjustment patterns if need be.
    private static void maybeEmbedPositionAdjustmentPatterns(QrVersion version, ByteMatrix matrix) {
        if (version.number() < 2) {  // The patterns appear if version >= 2
            return;
        }
        int[] coordinates = version.alignmentPatternCenters();
        for (int y : coordinates) {
            for (int x : coordinates) {
                if (isEmpty(matrix.get(x, y))) {
                    // An unset centre gets a pattern; -2 since the coordinates are its centre, not its corner.
                    embedPositionAdjustmentPattern(x - 2, y - 2, matrix);
                }
            }
        }
    }
}
