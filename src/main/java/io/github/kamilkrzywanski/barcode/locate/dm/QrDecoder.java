package io.github.kamilkrzywanski.barcode.locate.dm;

import io.github.kamilkrzywanski.barcode.decode.Decoded;
import io.github.kamilkrzywanski.barcode.decode.TextDecoder;
import io.github.kamilkrzywanski.barcode.ecc.GenericGF;
import io.github.kamilkrzywanski.barcode.ecc.ReedSolomonDecoder;
import io.github.kamilkrzywanski.barcode.ecc.ReedSolomonException;
import io.github.kamilkrzywanski.barcode.locate.dm.DmGeometry.Bits;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Model-2 QR bit decoder ported from zxing-cpp 3.1.1 {@code QRDecoder.cpp},
 * {@code QRBitMatrixParser.cpp}, {@code QRFormatInformation.cpp}, {@code QRVersion.cpp},
 * {@code QRDataBlock.cpp}, {@code QRCodecMode.cpp}, and {@code QRDataMask.h}.
 *
 * <p>The version is the sampled matrix size, the same as {@code ReadVersion}.
 * Model 1 and rMQR are not decoded. A checksum or format failure returns null.
 * Byte mode with no ECI in the whole symbol is read in the character set {@code GuessTextEncoding} picks.
 */
final class QrDecoder {
    private static final int FORMAT_INFO_MASK_MODEL2 = 0x5412;
    private static final int FORMAT_INFO_MASK_MODEL1 = 0x2825;
    private static final int[] MODEL2_MASKED_PATTERNS = {
            0x5412, 0x5125, 0x5E7C, 0x5B4B, 0x45F9, 0x40CE, 0x4F97, 0x4AA0,
            0x77C4, 0x72F3, 0x7DAA, 0x789D, 0x662F, 0x6318, 0x6C41, 0x6976,
            0x1689, 0x13BE, 0x1CE7, 0x19D0, 0x0762, 0x0255, 0x0D0C, 0x083B,
            0x355F, 0x3068, 0x3F31, 0x3A06, 0x24B4, 0x2183, 0x2EDA, 0x2BED
    };
    /** Format-info bits 3..4. Order is M, L, H, Q. */
    private static final int[] LEVEL_FOR_BITS = {1, 0, 3, 2};
    private static final int[] NUMERIC_COUNT = {10, 12, 14};
    private static final int[] ALPHANUMERIC_COUNT = {9, 11, 13};
    private static final int[] BYTE_COUNT = {8, 16, 16};
    private static final int[] KANJI_COUNT = {8, 10, 12};
    private static final String ALPHANUMERIC = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:";
    private static final int ECI_UNKNOWN = -1;
    private static final int ECI_LATIN1 = 3;
    private static final int ECI_SHIFT_JIS = 20;
    private static final int ECI_GB18030 = 32;
    /**
     * {@code [version][ecLevel]} is {ec codewords per block, count, data, count, data}.
     * Levels are L, M, Q, H. Every level of one version has the same total length.
     */
    private static final int[][][] LEVELS = {
    null,
    {{7, 1, 19, 0, 0}, {10, 1, 16, 0, 0}, {13, 1, 13, 0, 0}, {17, 1, 9, 0, 0}},
    {{10, 1, 34, 0, 0}, {16, 1, 28, 0, 0}, {22, 1, 22, 0, 0}, {28, 1, 16, 0, 0}},
    {{15, 1, 55, 0, 0}, {26, 1, 44, 0, 0}, {18, 2, 17, 0, 0}, {22, 2, 13, 0, 0}},
    {{20, 1, 80, 0, 0}, {18, 2, 32, 0, 0}, {26, 2, 24, 0, 0}, {16, 4, 9, 0, 0}},
    {{26, 1, 108, 0, 0}, {24, 2, 43, 0, 0}, {18, 2, 15, 2, 16}, {22, 2, 11, 2, 12}},
    {{18, 2, 68, 0, 0}, {16, 4, 27, 0, 0}, {24, 4, 19, 0, 0}, {28, 4, 15, 0, 0}},
    {{20, 2, 78, 0, 0}, {18, 4, 31, 0, 0}, {18, 2, 14, 4, 15}, {26, 4, 13, 1, 14}},
    {{24, 2, 97, 0, 0}, {22, 2, 38, 2, 39}, {22, 4, 18, 2, 19}, {26, 4, 14, 2, 15}},
    {{30, 2, 116, 0, 0}, {22, 3, 36, 2, 37}, {20, 4, 16, 4, 17}, {24, 4, 12, 4, 13}},
    {{18, 2, 68, 2, 69}, {26, 4, 43, 1, 44}, {24, 6, 19, 2, 20}, {28, 6, 15, 2, 16}},
    {{20, 4, 81, 0, 0}, {30, 1, 50, 4, 51}, {28, 4, 22, 4, 23}, {24, 3, 12, 8, 13}},
    {{24, 2, 92, 2, 93}, {22, 6, 36, 2, 37}, {26, 4, 20, 6, 21}, {28, 7, 14, 4, 15}},
    {{26, 4, 107, 0, 0}, {22, 8, 37, 1, 38}, {24, 8, 20, 4, 21}, {22, 12, 11, 4, 12}},
    {{30, 3, 115, 1, 116}, {24, 4, 40, 5, 41}, {20, 11, 16, 5, 17}, {24, 11, 12, 5, 13}},
    {{22, 5, 87, 1, 88}, {24, 5, 41, 5, 42}, {30, 5, 24, 7, 25}, {24, 11, 12, 7, 13}},
    {{24, 5, 98, 1, 99}, {28, 7, 45, 3, 46}, {24, 15, 19, 2, 20}, {30, 3, 15, 13, 16}},
    {{28, 1, 107, 5, 108}, {28, 10, 46, 1, 47}, {28, 1, 22, 15, 23}, {28, 2, 14, 17, 15}},
    {{30, 5, 120, 1, 121}, {26, 9, 43, 4, 44}, {28, 17, 22, 1, 23}, {28, 2, 14, 19, 15}},
    {{28, 3, 113, 4, 114}, {26, 3, 44, 11, 45}, {26, 17, 21, 4, 22}, {26, 9, 13, 16, 14}},
    {{28, 3, 107, 5, 108}, {26, 3, 41, 13, 42}, {30, 15, 24, 5, 25}, {28, 15, 15, 10, 16}},
    {{28, 4, 116, 4, 117}, {26, 17, 42, 0, 0}, {28, 17, 22, 6, 23}, {30, 19, 16, 6, 17}},
    {{28, 2, 111, 7, 112}, {28, 17, 46, 0, 0}, {30, 7, 24, 16, 25}, {24, 34, 13, 0, 0}},
    {{30, 4, 121, 5, 122}, {28, 4, 47, 14, 48}, {30, 11, 24, 14, 25}, {30, 16, 15, 14, 16}},
    {{30, 6, 117, 4, 118}, {28, 6, 45, 14, 46}, {30, 11, 24, 16, 25}, {30, 30, 16, 2, 17}},
    {{26, 8, 106, 4, 107}, {28, 8, 47, 13, 48}, {30, 7, 24, 22, 25}, {30, 22, 15, 13, 16}},
    {{28, 10, 114, 2, 115}, {28, 19, 46, 4, 47}, {28, 28, 22, 6, 23}, {30, 33, 16, 4, 17}},
    {{30, 8, 122, 4, 123}, {28, 22, 45, 3, 46}, {30, 8, 23, 26, 24}, {30, 12, 15, 28, 16}},
    {{30, 3, 117, 10, 118}, {28, 3, 45, 23, 46}, {30, 4, 24, 31, 25}, {30, 11, 15, 31, 16}},
    {{30, 7, 116, 7, 117}, {28, 21, 45, 7, 46}, {30, 1, 23, 37, 24}, {30, 19, 15, 26, 16}},
    {{30, 5, 115, 10, 116}, {28, 19, 47, 10, 48}, {30, 15, 24, 25, 25}, {30, 23, 15, 25, 16}},
    {{30, 13, 115, 3, 116}, {28, 2, 46, 29, 47}, {30, 42, 24, 1, 25}, {30, 23, 15, 28, 16}},
    {{30, 17, 115, 0, 0}, {28, 10, 46, 23, 47}, {30, 10, 24, 35, 25}, {30, 19, 15, 35, 16}},
    {{30, 17, 115, 1, 116}, {28, 14, 46, 21, 47}, {30, 29, 24, 19, 25}, {30, 11, 15, 46, 16}},
    {{30, 13, 115, 6, 116}, {28, 14, 46, 23, 47}, {30, 44, 24, 7, 25}, {30, 59, 16, 1, 17}},
    {{30, 12, 121, 7, 122}, {28, 12, 47, 26, 48}, {30, 39, 24, 14, 25}, {30, 22, 15, 41, 16}},
    {{30, 6, 121, 14, 122}, {28, 6, 47, 34, 48}, {30, 46, 24, 10, 25}, {30, 2, 15, 64, 16}},
    {{30, 17, 122, 4, 123}, {28, 29, 46, 14, 47}, {30, 49, 24, 10, 25}, {30, 24, 15, 46, 16}},
    {{30, 4, 122, 18, 123}, {28, 13, 46, 32, 47}, {30, 48, 24, 14, 25}, {30, 42, 15, 32, 16}},
    {{30, 20, 117, 4, 118}, {28, 40, 47, 7, 48}, {30, 43, 24, 22, 25}, {30, 10, 15, 67, 16}},
    {{30, 19, 118, 6, 119}, {28, 18, 47, 31, 48}, {30, 34, 24, 34, 25}, {30, 20, 15, 61, 16}},
    };
    /** Alignment-pattern centers, including the finder column at 6. Version 1 has none. */
    private static final int[][] ALIGN = {
    null,
    {},
    {6, 18},
    {6, 22},
    {6, 26},
    {6, 30},
    {6, 34},
    {6, 22, 38},
    {6, 24, 42},
    {6, 26, 46},
    {6, 28, 50},
    {6, 30, 54},
    {6, 32, 58},
    {6, 34, 62},
    {6, 26, 46, 66},
    {6, 26, 48, 70},
    {6, 26, 50, 74},
    {6, 30, 54, 78},
    {6, 30, 56, 82},
    {6, 30, 58, 86},
    {6, 34, 62, 90},
    {6, 28, 50, 72, 94},
    {6, 26, 50, 74, 98},
    {6, 30, 54, 78, 102},
    {6, 28, 54, 80, 106},
    {6, 32, 58, 84, 110},
    {6, 30, 58, 86, 114},
    {6, 34, 62, 90, 118},
    {6, 26, 50, 74, 98, 122},
    {6, 30, 54, 78, 102, 126},
    {6, 26, 52, 78, 104, 130},
    {6, 30, 56, 82, 108, 134},
    {6, 34, 60, 86, 112, 138},
    {6, 30, 58, 86, 114, 142},
    {6, 34, 62, 90, 118, 146},
    {6, 30, 54, 78, 102, 126, 150},
    {6, 24, 50, 76, 102, 128, 154},
    {6, 28, 54, 80, 106, 132, 158},
    {6, 32, 58, 84, 110, 136, 162},
    {6, 26, 54, 82, 110, 138, 166},
    {6, 30, 58, 86, 114, 142, 170},
    };

    static {
        for (int version = 1; version <= 40; version++) {
            int total = blockBytes(LEVELS[version][0]);
            for (int level = 1; level < 4; level++) {
                if (blockBytes(LEVELS[version][level]) != total) {
                    throw new ExceptionInInitializerError("version " + version + " level " + level);
                }
            }
        }
    }

    private QrDecoder() {
    }

    /**
     * Decode one sampled model-2 symbol. {@code bits} is the module grid with the finder
     * at the top left in the chosen rotation. Null means the grid is not a valid model-2 QR.
     */
    static String decode(Bits bits) {
        Decoded decoded = decodeContent(bits);
        return decoded == null ? null : decoded.text();
    }

    /** {@link #decode(Bits)} with the content bytes. */
    static Decoded decodeContent(Bits bits) {
        if (!model2(bits)) {
            return null;
        }
        int version = versionForDimension(bits.width);
        Format format = readFormat(bits);
        if (!format.valid()) {
            return null;
        }
        int[] raw = readCodewords(bits, version, format);
        if (raw == null) {
            return null;
        }
        byte[] data = correct(raw, version, format.ecLevel);
        if (data == null) {
            return null;
        }
        return decodeStreamContent(data, version);
    }

    private static boolean model2(Bits bits) {
        return bits != null && bits.width == bits.height && versionForDimension(bits.width) > 0;
    }

    /**
     * {@code Version::Number} for a model-2 square. Micro QR and every other size are 0.
     */
    static int versionForDimension(int dimension) {
        if (dimension < 21 || dimension > 177 || dimension % 4 != 1) {
            return 0;
        }
        int version = (dimension - 17) / 4;
        return dimensionForVersion(version) == dimension ? version : 0;
    }

    /** {@code Version::SymbolSize} for model 2. Version 0 and versions above 40 are 0. */
    static int dimensionForVersion(int version) {
        if (version < 1 || version > 40) {
            return 0;
        }
        return 17 + 4 * version;
    }

    /**
     * Alignment centers, including the finder column at 6.
     * Version 1 is empty. A number outside 1..40 is not a model-2 version.
     */
    static int alignmentCenterCount(int version) {
        if (version < 1 || version > 40) {
            return -1;
        }
        return ALIGN[version].length;
    }

    /** {@code ECLevelFromBits} for model 2. L is 0, M is 1, Q is 2, H is 3. */
    static int ecLevelFromBits(int bits) {
        return LEVEL_FOR_BITS[bits & 0x03];
    }

    private static int blockBytes(int[] level) {
        int ec = level[0];
        return level[1] * (level[2] + ec) + level[3] * (level[4] + ec);
    }

    static final class Format {
        int hamming = 255;
        int mask;
        int data = 255;
        int bitsIndex = 255;
        int ecLevel;
        int dataMask;
        boolean mirrored;

        boolean model1() {
            return mask == FORMAT_INFO_MASK_MODEL1;
        }

        boolean valid() {
            return hamming <= 3 && !model1() && dataMask >= 0 && dataMask <= 7 && ecLevel >= 0 && ecLevel <= 3;
        }
    }

    private static Format readFormat(Bits bits) {
        int bits1 = 0;
        for (int x = 0; x < 6; x++) {
            bits1 = appendBit(bits1, bits.get(x, 8));
        }
        bits1 = appendBit(bits1, bits.get(7, 8));
        bits1 = appendBit(bits1, bits.get(8, 8));
        bits1 = appendBit(bits1, bits.get(8, 7));
        for (int y = 5; y >= 0; y--) {
            bits1 = appendBit(bits1, bits.get(8, y));
        }

        int dimension = bits.height;
        int bits2 = 0;
        for (int y = dimension - 1; y >= dimension - 8; y--) {
            bits2 = appendBit(bits2, bits.get(8, y));
        }
        for (int x = dimension - 8; x < dimension; x++) {
            bits2 = appendBit(bits2, bits.get(x, 8));
        }
        return decodeFormat(bits1, bits2);
    }

    /**
     * {@code FormatInformation::DecodeQR}. Both samples still wear the format mask.
     * The second sample includes the dark module. A match is model 2 with Hamming distance at most 3.
     */
    static Format decodeFormat(int bits1, int bits2) {
        int mirroredBits2 = mirror(((bits2 >> 1) & 0b111111110000000) | (bits2 & 0b1111111));
        int normalized2 = ((bits2 >> 1) & 0b111111100000000) | (bits2 & 0b11111111);
        return bestFormat(
                new int[]{FORMAT_INFO_MASK_MODEL2, 0, FORMAT_INFO_MASK_MODEL1},
                new int[]{bits1, normalized2, mirror(bits1), mirroredBits2});
    }

    /** Lowest hamming wins. An equal distance keeps the earlier pattern. */
    private static Format bestFormat(int[] masks, int[] copies) {
        Format format = new Format();
        for (int mask : masks) {
            for (int bitsIndex = 0; bitsIndex < copies.length; bitsIndex++) {
                for (int masked : MODEL2_MASKED_PATTERNS) {
                    int pattern = masked ^ FORMAT_INFO_MASK_MODEL2;
                    int hamming = Integer.bitCount((copies[bitsIndex] ^ mask) ^ pattern);
                    if (hamming < format.hamming) {
                        format.mask = mask;
                        format.data = pattern >> 10;
                        format.hamming = hamming;
                        format.bitsIndex = bitsIndex;
                    }
                }
            }
        }
        format.ecLevel = ecLevelFromBits(format.data >> 3);
        format.dataMask = format.data & 0x07;
        format.mirrored = format.bitsIndex > 1;
        return format;
    }

    private static int mirror(int bits) {
        return Integer.reverse(bits) >>> 17;
    }

    private static int appendBit(int value, boolean bit) {
        return (value << 1) | (bit ? 1 : 0);
    }

    private static int[] readCodewords(Bits bits, int version, Format format) {
        int[] level = LEVELS[version][format.ecLevel];
        int expected = blockBytes(level);
        Bits function = functionPattern(version);
        int dimension = bits.height;
        int[] result = new int[expected];
        int count = 0;
        int current = 0;
        int bitsRead = 0;
        boolean readingUp = true;
        for (int x = dimension - 1; x > 0; x -= 2) {
            if (x == 6) {
                x--;
            }
            for (int row = 0; row < dimension; row++) {
                int y = readingUp ? dimension - 1 - row : row;
                for (int col = 0; col < 2; col++) {
                    int xx = x - col;
                    if (!function.get(xx, y)) {
                        boolean module = format.mirrored ? bits.get(y, xx) : bits.get(xx, y);
                        current = appendBit(current, dataMask(format.dataMask, xx, y) != module);
                        if (++bitsRead % 8 == 0) {
                            if (count >= expected) {
                                return null;
                            }
                            result[count++] = current;
                            current = 0;
                        }
                    }
                }
            }
            readingUp = !readingUp;
        }
        return count == expected ? result : null;
    }

    static boolean dataMask(int mask, int x, int y) {
        return switch (mask) {
            case 0 -> (y + x) % 2 == 0;
            case 1 -> y % 2 == 0;
            case 2 -> x % 3 == 0;
            case 3 -> (y + x) % 3 == 0;
            case 4 -> (y / 2 + x / 3) % 2 == 0;
            case 5 -> (y * x) % 6 == 0;
            case 6 -> (y * x) % 6 < 3;
            case 7 -> (y + x + (y * x) % 3) % 2 == 0;
            default -> false;
        };
    }

    private static Bits functionPattern(int version) {
        int dimension = dimensionForVersion(version);
        Bits pattern = new Bits(dimension, dimension);
        setRegion(pattern, 0, 0, 9, 9);
        setRegion(pattern, dimension - 8, 0, 8, 9);
        setRegion(pattern, 0, dimension - 8, 9, 8);
        int[] centers = ALIGN[version];
        int max = centers.length;
        for (int x = 0; x < max; x++) {
            int top = centers[x] - 2;
            for (int y = 0; y < max; y++) {
                if ((x == 0 && (y == 0 || y == max - 1)) || (x == max - 1 && y == 0)) {
                    continue;
                }
                setRegion(pattern, centers[y] - 2, top, 5, 5);
            }
        }
        setRegion(pattern, 6, 9, 1, dimension - 17);
        setRegion(pattern, 9, 6, dimension - 17, 1);
        if (version > 6) {
            setRegion(pattern, dimension - 11, 0, 3, 6);
            setRegion(pattern, 0, dimension - 11, 6, 3);
        }
        return pattern;
    }

    private static void setRegion(Bits bits, int left, int top, int width, int height) {
        for (int y = top; y < top + height; y++) {
            for (int x = left; x < left + width; x++) {
                bits.set(x, y);
            }
        }
    }

    /** Deinterleave, correct each block, and concatenate the data codewords. */
    private static byte[] correct(int[] raw, int version, int ecLevel) {
        int[] level = LEVELS[version][ecLevel];
        int ec = level[0];
        int count1 = level[1];
        int data1 = level[2];
        int count2 = level[3];
        int data2 = level[4];
        int blocks = count1 + count2;
        if (blocks == 0 || raw.length != blockBytes(level)) {
            return null;
        }
        int[][] words = new int[blocks][];
        int[] numData = new int[blocks];
        int index = 0;
        for (int i = 0; i < count1; i++) {
            numData[index] = data1;
            words[index] = new int[data1 + ec];
            index++;
        }
        for (int i = 0; i < count2; i++) {
            numData[index] = data2;
            words[index] = new int[data2 + ec];
            index++;
        }
        int shorter = words[0].length;
        int longerStart = words.length - 1;
        while (longerStart >= 0) {
            if (words[longerStart].length == shorter) {
                break;
            }
            longerStart--;
        }
        longerStart++;
        int shorterData = shorter - ec;
        int offset = 0;
        for (int i = 0; i < shorterData; i++) {
            for (int block = 0; block < blocks; block++) {
                words[block][i] = raw[offset++];
            }
        }
        for (int block = longerStart; block < blocks; block++) {
            words[block][shorterData] = raw[offset++];
        }
        int max = words[0].length;
        for (int i = shorterData; i < max; i++) {
            for (int block = 0; block < blocks; block++) {
                int at = block < longerStart ? i : i + 1;
                words[block][at] = raw[offset++];
            }
        }
        if (offset != raw.length) {
            return null;
        }
        ReedSolomonDecoder reedSolomon = new ReedSolomonDecoder(GenericGF.QR_CODE_FIELD_256);
        byte[] data = new byte[dataBytes(level)];
        int written = 0;
        for (int block = 0; block < blocks; block++) {
            try {
                reedSolomon.decode(words[block], words[block].length - numData[block]);
            } catch (ReedSolomonException failed) {
                return null;
            }
            for (int i = 0; i < numData[block]; i++) {
                data[written++] = (byte) words[block][i];
            }
        }
        return data;
    }

    private static int dataBytes(int[] level) {
        return level[1] * level[2] + level[3] * level[4];
    }

    /** {@code DecodeBitStream} for one model-2 version. The text does not include the {@code ]Q} identifier. */
    static String decodeStream(byte[] data, int version) {
        Decoded decoded = decodeStreamContent(data, version);
        return decoded == null ? null : decoded.text();
    }

    /** {@link #decodeStream} with the content bytes. */
    static Decoded decodeStreamContent(byte[] data, int version) {
        try {
            BitReader bits = new BitReader(data);
            Content content = new Content();
            while (!endOfStream(bits)) {
                int mode = bits.read(4);
                switch (mode) {
                    case 0x05 -> content.fnc1 = true;
                    case 0x09 -> {
                        if (content.size() != 0) {
                            return null;
                        }
                        content.fnc1 = true;
                        int app = bits.read(8);
                        if (app < 100) {
                            String indicator = digits(app, 2);
                            if (indicator == null) {
                                return null;
                            }
                            content.append(indicator);
                        } else if ((app >= 165 && app <= 190) || (app >= 197 && app <= 222)) {
                            content.appendByte(app - 100);
                        } else {
                            return null;
                        }
                    }
                    case 0x03 -> {
                        bits.read(4);
                        bits.read(4);
                        bits.read(8);
                    }
                    case 0x07 -> {
                        int eci = parseEci(bits);
                        if (eci < 0) {
                            return null;
                        }
                        content.encoding(eci, true);
                    }
                    case 0x0D -> {
                        if (bits.read(4) != 1) {
                            return null;
                        }
                        int count = bits.read(countBits(0x0D, version));
                        if (!content.hanzi(bits, count)) {
                            return null;
                        }
                    }
                    case 0x01, 0x02, 0x04, 0x08 -> {
                        int count = bits.read(countBits(mode, version));
                        if (!content.segment(mode, bits, count)) {
                            return null;
                        }
                    }
                    default -> {
                        return null;
                    }
                }
            }
            String text = content.render();
            return text.isEmpty() ? null : new Decoded(text, content.raw());
        } catch (IllegalArgumentException truncated) {
            return null;
        }
    }

    private static boolean endOfStream(BitReader bits) {
        int available = bits.available();
        if (available == 0) {
            return true;
        }
        return bits.peek(Math.min(available, 4)) == 0;
    }

    /** Character-count width from ISO 18004. Hanzi uses the Kanji width. */
    static int countBits(int mode, int version) {
        int band = version <= 9 ? 0 : version <= 26 ? 1 : 2;
        return switch (mode) {
            case 0x01 -> NUMERIC_COUNT[band];
            case 0x02 -> ALPHANUMERIC_COUNT[band];
            case 0x04 -> BYTE_COUNT[band];
            case 0x08, 0x0D -> KANJI_COUNT[band];
            default -> 0;
        };
    }

    private static int parseEci(BitReader bits) {
        int first = bits.read(8);
        if ((first & 0x80) == 0) {
            return first & 0x7F;
        }
        if ((first & 0xC0) == 0x80) {
            return ((first & 0x3F) << 8) | bits.read(8);
        }
        if ((first & 0xE0) == 0xC0) {
            return ((first & 0x1F) << 16) | bits.read(16);
        }
        return -1;
    }

    private static String digits(int value, int width) {
        if (value < 0) {
            return null;
        }
        String text = Integer.toString(value);
        if (text.length() > width) {
            return null;
        }
        return "0".repeat(width - text.length()) + text;
    }

    /**
     * Byte blocks and the ECI markers that cover them. Once an ECI has been seen,
     * a later non-ECI switch does not start a new block: those bytes stay in the
     * current ECI, matching {@code Content::switchEncoding}.
     */
    private static final class Content {
        private final List<Integer> bytes = new ArrayList<>();
        private final List<int[]> segments = new ArrayList<>();
        private boolean hasEci;
        private boolean fnc1;

        int size() {
            return bytes.size();
        }

        void encoding(int eci, boolean isEci) {
            if (isEci && !hasEci) {
                segments.clear();
            }
            if (isEci || !hasEci) {
                segments.add(new int[]{eci, bytes.size()});
            }
            hasEci |= isEci;
        }

        void append(String text) {
            for (int i = 0; i < text.length(); i++) {
                appendByte(text.charAt(i));
            }
        }

        void appendByte(int value) {
            bytes.add(value & 0xFF);
        }

        byte[] raw() {
            byte[] out = new byte[bytes.size()];
            for (int i = 0; i < out.length; i++) {
                out[i] = (byte) (int) bytes.get(i);
            }
            return out;
        }

        boolean segment(int mode, BitReader bits, int count) {
            return switch (mode) {
                case 0x01 -> numeric(bits, count);
                case 0x02 -> alphanumeric(bits, count);
                case 0x04 -> bytes(bits, count);
                case 0x08 -> kanji(bits, count);
                default -> false;
            };
        }

        boolean numeric(BitReader bits, int count) {
            encoding(ECI_LATIN1, false);
            while (count > 0) {
                int group = Math.min(count, 3);
                String text = digits(bits.read(1 + 3 * group), group);
                if (text == null) {
                    return false;
                }
                append(text);
                count -= group;
            }
            return true;
        }

        boolean alphanumeric(BitReader bits, int count) {
            StringBuilder buffer = new StringBuilder();
            while (count > 1) {
                int value = bits.read(11);
                if (!appendAlpha(buffer, value / 45) || !appendAlpha(buffer, value % 45)) {
                    return false;
                }
                count -= 2;
            }
            if (count == 1 && !appendAlpha(buffer, bits.read(6))) {
                return false;
            }
            String text = fnc1 ? massage(buffer.toString()) : buffer.toString();
            encoding(ECI_LATIN1, false);
            append(text);
            return true;
        }

        boolean bytes(BitReader bits, int count) {
            encoding(ECI_UNKNOWN, false);
            for (int i = 0; i < count; i++) {
                appendByte(bits.read(8));
            }
            return true;
        }

        boolean kanji(BitReader bits, int count) {
            encoding(ECI_SHIFT_JIS, false);
            for (int i = 0; i < count; i++) {
                int value = bits.read(13);
                int assembled = ((value / 0x0C0) << 8) | (value % 0x0C0);
                assembled += assembled < 0x01F00 ? 0x08140 : 0x0C140;
                appendByte(assembled >> 8);
                appendByte(assembled);
            }
            return true;
        }

        boolean hanzi(BitReader bits, int count) {
            encoding(ECI_GB18030, false);
            for (int i = 0; i < count; i++) {
                int value = bits.read(13);
                int assembled = ((value / 0x060) << 8) | (value % 0x060);
                assembled += assembled < 0x00A00 ? 0x0A1A1 : 0x0A6A1;
                appendByte(assembled >> 8);
                appendByte(assembled);
            }
            return true;
        }

        String render() {
            StringBuilder text = new StringBuilder();
            int fallback = hasEci ? ECI_LATIN1 : ECI_UNKNOWN;
            // Content::guessEncoding: without any ECI, the bytes of every block of unknown
            // encoding together pick one character set for all of those blocks.
            int guessed = hasEci ? ECI_LATIN1 : TextDecoder.guessEci(unknownBytes(fallback));
            if (segments.isEmpty()) {
                write(text, fallback, guessed, 0, bytes.size());
            } else {
                if (segments.get(0)[1] != 0) {
                    write(text, fallback, guessed, 0, segments.get(0)[1]);
                }
                for (int i = 0; i < segments.size(); i++) {
                    int start = segments.get(i)[1];
                    int end = i + 1 == segments.size() ? bytes.size() : segments.get(i + 1)[1];
                    if (start != end) {
                        write(text, segments.get(i)[0], guessed, start, end);
                    }
                }
            }
            return text.toString();
        }

        private byte[] unknownBytes(int fallback) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int first = segments.isEmpty() ? bytes.size() : segments.get(0)[1];
            if (fallback == ECI_UNKNOWN) {
                for (int i = 0; i < first; i++) {
                    out.write(bytes.get(i));
                }
            }
            for (int i = 0; i < segments.size(); i++) {
                if (segments.get(i)[0] != ECI_UNKNOWN) {
                    continue;
                }
                int end = i + 1 == segments.size() ? bytes.size() : segments.get(i + 1)[1];
                for (int j = segments.get(i)[1]; j < end; j++) {
                    out.write(bytes.get(j));
                }
            }
            return out.toByteArray();
        }

        private void write(StringBuilder text, int eci, int guessed, int start, int end) {
            if (eci == ECI_UNKNOWN) {
                eci = guessed;
            }
            byte[] raw = new byte[end - start];
            for (int i = start; i < end; i++) {
                raw[i - start] = (byte) (int) bytes.get(i);
            }
            text.append(TextDecoder.decodeOrLatin1(raw, eci));
        }
    }

    private static String massage(String buffer) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < buffer.length(); i++) {
            char c = buffer.charAt(i);
            if (c != '%') {
                out.append(c);
            } else if (i + 1 < buffer.length() && buffer.charAt(i + 1) == '%') {
                out.append('%');
                i++;
            } else {
                out.append((char) 0x1D);
            }
        }
        return out.toString();
    }

    private static boolean appendAlpha(StringBuilder text, int index) {
        if (index < 0 || index >= ALPHANUMERIC.length()) {
            return false;
        }
        text.append(ALPHANUMERIC.charAt(index));
        return true;
    }

    /** MSB-first bit cursor. A read past the end throws {@link IllegalArgumentException}. */
    private static final class BitReader {
        private final byte[] bytes;
        private int byteOffset;
        private int bitOffset;

        BitReader(byte[] bytes) {
            this.bytes = bytes;
        }

        int available() {
            return 8 * (bytes.length - byteOffset) - bitOffset;
        }

        int read(int numBits) {
            return take(numBits, true);
        }

        int peek(int numBits) {
            return take(numBits, false);
        }

        private int take(int numBits, boolean commit) {
            if (numBits < 1 || numBits > 32 || numBits > available()) {
                throw new IllegalArgumentException();
            }
            int offset = byteOffset;
            int bit = bitOffset;
            int result = 0;
            if (bit > 0) {
                int bitsLeft = 8 - bit;
                int toRead = Math.min(numBits, bitsLeft);
                int keep = bitsLeft - toRead;
                int mask = (0xFF >> (8 - toRead)) << keep;
                result = ((bytes[offset] & 0xFF) & mask) >> keep;
                numBits -= toRead;
                bit += toRead;
                if (bit == 8) {
                    bit = 0;
                    offset++;
                }
            }
            while (numBits >= 8) {
                result = (result << 8) | (bytes[offset] & 0xFF);
                offset++;
                numBits -= 8;
            }
            if (numBits > 0) {
                int keep = 8 - numBits;
                int mask = (0xFF >> keep) << keep;
                result = (result << numBits) | (((bytes[offset] & 0xFF) & mask) >> keep);
                bit += numBits;
            }
            if (commit) {
                byteOffset = offset;
                bitOffset = bit;
            }
            return result;
        }
    }
}
