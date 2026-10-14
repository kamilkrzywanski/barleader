package io.github.kamilkrzywanski.barcode.locate.dm;

import io.github.kamilkrzywanski.barcode.decode.Decoded;
import com.google.zxing.common.reedsolomon.GenericGF;
import com.google.zxing.common.reedsolomon.ReedSolomonDecoder;
import com.google.zxing.common.reedsolomon.ReedSolomonException;
import io.github.kamilkrzywanski.barcode.decode.TextDecoder;
import io.github.kamilkrzywanski.barcode.locate.dm.DmGeometry.Bits;

import java.util.ArrayList;
import java.util.List;

/**
 * Data Matrix bit decoder ported from zxing-cpp 3.1.1 {@code DMDecoder.cpp},
 * {@code DMBitLayout.cpp}, {@code DMDataBlock.cpp}, and {@code DMVersion.cpp}.
 *
 * <p>Versions 1–48, including DMRE, are recognized from the sampled matrix size.
 * The ISO 144×144 deinterleave is tried first; version 24 retries once with the
 * zxing-cpp fix for issue 259. A checksum or format failure returns null, including
 * a codeword stream that only partly parses. The unlatch codeword 254 as the final
 * codeword is not an error. Without any ECI the bytes are read in UTF-8 when they are
 * valid UTF-8, else ISO-8859-1 ({@link TextDecoder#guessUtf8OrLatin1}). The AIM modifier for
 * GS1 and DMRE is not part of the returned text.
 */
final class DmDecoder {
    private static final String C40_BASIC = "*** 0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final String TEXT_BASIC = "*** 0123456789abcdefghijklmnopqrstuvwxyz";
    private static final String C40_SHIFT2 = "!\"#$%&'()*+,-./:;<=>?@[\\]^_\u001D";
    private static final String TEXT_SHIFT3 = "`ABCDEFGHIJKLMNOPQRSTUVWXYZ{|}~\u007F";
    private static final int ECI_LATIN1 = 3;
    /** {@code version, height, width, blockHeight, blockWidth, ec, count1, data1, count2, data2}. */
    private static final int[][] ROWS = {
            {1, 10, 10, 8, 8, 5, 1, 3, 0, 0},
            {2, 12, 12, 10, 10, 7, 1, 5, 0, 0},
            {3, 14, 14, 12, 12, 10, 1, 8, 0, 0},
            {4, 16, 16, 14, 14, 12, 1, 12, 0, 0},
            {5, 18, 18, 16, 16, 14, 1, 18, 0, 0},
            {6, 20, 20, 18, 18, 18, 1, 22, 0, 0},
            {7, 22, 22, 20, 20, 20, 1, 30, 0, 0},
            {8, 24, 24, 22, 22, 24, 1, 36, 0, 0},
            {9, 26, 26, 24, 24, 28, 1, 44, 0, 0},
            {10, 32, 32, 14, 14, 36, 1, 62, 0, 0},
            {11, 36, 36, 16, 16, 42, 1, 86, 0, 0},
            {12, 40, 40, 18, 18, 48, 1, 114, 0, 0},
            {13, 44, 44, 20, 20, 56, 1, 144, 0, 0},
            {14, 48, 48, 22, 22, 68, 1, 174, 0, 0},
            {15, 52, 52, 24, 24, 42, 2, 102, 0, 0},
            {16, 64, 64, 14, 14, 56, 2, 140, 0, 0},
            {17, 72, 72, 16, 16, 36, 4, 92, 0, 0},
            {18, 80, 80, 18, 18, 48, 4, 114, 0, 0},
            {19, 88, 88, 20, 20, 56, 4, 144, 0, 0},
            {20, 96, 96, 22, 22, 68, 4, 174, 0, 0},
            {21, 104, 104, 24, 24, 56, 6, 136, 0, 0},
            {22, 120, 120, 18, 18, 68, 6, 175, 0, 0},
            {23, 132, 132, 20, 20, 62, 8, 163, 0, 0},
            {24, 144, 144, 22, 22, 62, 8, 156, 2, 155},
            {25, 8, 18, 6, 16, 7, 1, 5, 0, 0},
            {26, 8, 32, 6, 14, 11, 1, 10, 0, 0},
            {27, 12, 26, 10, 24, 14, 1, 16, 0, 0},
            {28, 12, 36, 10, 16, 18, 1, 22, 0, 0},
            {29, 16, 36, 14, 16, 24, 1, 32, 0, 0},
            {30, 16, 48, 14, 22, 28, 1, 49, 0, 0},
            {31, 8, 48, 6, 22, 15, 1, 18, 0, 0},
            {32, 8, 64, 6, 14, 18, 1, 24, 0, 0},
            {33, 8, 80, 6, 18, 22, 1, 32, 0, 0},
            {34, 8, 96, 6, 22, 28, 1, 38, 0, 0},
            {35, 8, 120, 6, 18, 32, 1, 49, 0, 0},
            {36, 8, 144, 6, 22, 36, 1, 63, 0, 0},
            {37, 12, 64, 10, 14, 27, 1, 43, 0, 0},
            {38, 12, 88, 10, 20, 36, 1, 64, 0, 0},
            {39, 16, 64, 14, 14, 36, 1, 62, 0, 0},
            {40, 20, 36, 18, 16, 28, 1, 44, 0, 0},
            {41, 20, 44, 18, 20, 34, 1, 56, 0, 0},
            {42, 20, 64, 18, 14, 42, 1, 84, 0, 0},
            {43, 22, 48, 20, 22, 38, 1, 72, 0, 0},
            {44, 24, 48, 22, 22, 41, 1, 80, 0, 0},
            {45, 24, 64, 22, 14, 46, 1, 108, 0, 0},
            {46, 26, 40, 24, 18, 38, 1, 70, 0, 0},
            {47, 26, 48, 24, 22, 42, 1, 90, 0, 0},
            {48, 26, 64, 24, 14, 50, 1, 118, 0, 0}
    };
    private static final Version[] VERSIONS = versions();
    private static final int[] CORNER1_R = {-1, -1, -1, 0, 0, 1, 2, 3};
    private static final int[] CORNER1_C = {0, 1, 2, -2, -1, -1, -1, -1};
    private static final int[] CORNER2_R = {-3, -2, -1, 0, 0, 0, 0, 1};
    private static final int[] CORNER2_C = {0, 0, 0, -4, -3, -2, -1, -1};
    private static final int[] CORNER3_R = {-1, -1, 0, 0, 0, 1, 1, 1};
    private static final int[] CORNER3_C = {0, -1, -3, -2, -1, -3, -2, -1};
    private static final int[] CORNER4_R = {-3, -2, -1, 0, 0, 1, 2, 3};
    private static final int[] CORNER4_C = {0, 0, 0, -2, -1, -1, -1, -1};
    private static final int[] UTAH_R = {-2, -2, -1, -1, -1, 0, 0, 0};
    private static final int[] UTAH_C = {-2, -1, -2, -1, 0, -2, -1, 0};

    private DmDecoder() {
    }

    /** Decodes one sampled symbol, then the L-finder mirror. Null if neither reads. */
    static String decode(Bits bits) {
        Decoded decoded = decodeContent(bits);
        return decoded == null ? null : decoded.text();
    }

    /** {@link #decode(Bits)} with the content bytes. */
    static Decoded decodeContent(Bits bits) {
        if (bits == null) {
            return null;
        }
        Decoded decoded = doDecode(bits);
        if (decoded != null) {
            return decoded;
        }
        return doDecode(flippedL(bits));
    }

    private static Decoded doDecode(Bits bits) {
        Version version = versionFor(bits.height, bits.width);
        if (version == null || !finderIntact(bits)) {
            return null;
        }
        int[] raw = codewords(bits, version);
        if (raw == null) {
            return null;
        }
        boolean fix259 = false;
        ReedSolomonDecoder reedSolomon = new ReedSolomonDecoder(GenericGF.DATA_MATRIX_FIELD_256);
        while (true) {
            Split split = split(raw, version, fix259);
            if (split == null) {
                return null;
            }
            boolean corrected = true;
            for (int block = 0; block < split.words.length; block++) {
                try {
                    reedSolomon.decode(split.words[block], split.words[block].length - split.dataCount[block]);
                } catch (ReedSolomonException failed) {
                    corrected = false;
                    break;
                }
            }
            if (!corrected) {
                if (version.number == 24 && !fix259) {
                    fix259 = true;
                    continue;
                }
                return null;
            }
            byte[] data = reinterleave(split);
            if (data == null) {
                return null;
            }
            return decodeStreamContent(data);
        }
    }

    /**
     * Solid L on the left and bottom, clock track on the top and right.
     * Up to four wrong modules are kept, which is as many as the official samples use.
     * Above that, more than one eighth of the border is rejected. {@link #decode} tries the mirror.
     */
    private static boolean finderIntact(Bits bits) {
        int wrong = finderMismatches(bits);
        int perimeter = 2 * (bits.width + bits.height - 2);
        return wrong <= 4 || wrong * 8 <= perimeter;
    }

    private static int finderMismatches(Bits bits) {
        int width = bits.width;
        int height = bits.height;
        int wrong = 0;
        for (int x = 0; x < width; x++) {
            if (bits.get(x, 0) != (x % 2 == 0)) {
                wrong++;
            }
            if (!bits.get(x, height - 1)) {
                wrong++;
            }
        }
        for (int y = 1; y < height - 1; y++) {
            if (!bits.get(0, y)) {
                wrong++;
            }
            if (bits.get(width - 1, y) != (y % 2 == 1)) {
                wrong++;
            }
        }
        return wrong;
    }

    /**
     * Moves the solid L to the opposite corner. A rectangle whose height and width
     * are swapped against the version table only matches after this turn.
     */
    private static Bits flippedL(Bits bits) {
        Bits out = new Bits(bits.height, bits.width);
        for (int y = 0; y < out.height; y++) {
            for (int x = 0; x < out.width; x++) {
                if (bits.get(bits.width - 1 - y, bits.height - 1 - x)) {
                    out.set(x, y);
                }
            }
        }
        return out;
    }

    private static Version versionFor(int height, int width) {
        if ((height & 1) != 0 || (width & 1) != 0 || height < 8 || height > 144) {
            return null;
        }
        for (Version version : VERSIONS) {
            if (version.height == height && version.width == width) {
                return version;
            }
        }
        return null;
    }

    private static int[] codewords(Bits bits, Version version) {
        Bits data = extract(bits, version);
        if (data == null) {
            return null;
        }
        return readCodewords(data, version.totalCodewords());
    }

    /** {@code VisitMatrix} read back from a data region. {@code count} is the number of symbol characters. */
    static int[] readCodewords(Bits data, int count) {
        if (data == null || count <= 0) {
            return null;
        }
        int numRows = data.height;
        int numCols = data.width;
        boolean[] visited = new boolean[numRows * numCols];
        int[] out = new int[count];
        int[] cursor = new int[1];
        int guard = numRows * numCols;
        int row = 4;
        int col = 0;
        do {
            if (guard-- == 0) {
                return null;
            }
            if (row == numRows && col == 0) {
                if (!readSymbol(data, corner(CORNER1_R, CORNER1_C, numRows, numCols, visited), out, cursor)) {
                    return null;
                }
            } else if (row == numRows - 2 && col == 0 && numCols % 4 != 0) {
                if (!readSymbol(data, corner(CORNER2_R, CORNER2_C, numRows, numCols, visited), out, cursor)) {
                    return null;
                }
            } else if (row == numRows + 4 && col == 2 && numCols % 8 == 0) {
                if (!readSymbol(data, corner(CORNER3_R, CORNER3_C, numRows, numCols, visited), out, cursor)) {
                    return null;
                }
            } else if (row == numRows - 2 && col == 0 && numCols % 8 == 4) {
                if (!readSymbol(data, corner(CORNER4_R, CORNER4_C, numRows, numCols, visited), out, cursor)) {
                    return null;
                }
            }
            do {
                if (row < numRows && col >= 0 && !visited[row * numCols + col]) {
                    if (!readSymbol(data, utah(row, col, numRows, numCols, visited), out, cursor)) {
                        return null;
                    }
                }
                row -= 2;
                col += 2;
            } while (row >= 0 && col < numCols);
            row += 1;
            col += 3;
            do {
                if (row >= 0 && col < numCols && !visited[row * numCols + col]) {
                    if (!readSymbol(data, utah(row, col, numRows, numCols, visited), out, cursor)) {
                        return null;
                    }
                }
                row += 2;
                col -= 2;
            } while (row < numRows && col >= 0);
            row += 3;
            col += 1;
        } while (row < numRows || col < numCols);
        if (cursor[0] != out.length) {
            return null;
        }
        return out;
    }

    private static boolean readSymbol(Bits data, int[] at, int[] out, int[] cursor) {
        if (at == null || cursor[0] >= out.length) {
            return false;
        }
        int value = 0;
        for (int bit = 0; bit < 8; bit++) {
            value = (value << 1) | (data.get(at[bit * 2 + 1], at[bit * 2]) ? 1 : 0);
        }
        out[cursor[0]++] = value;
        return true;
    }

    /** Absolute corner shape. A negative index wraps once from the far edge and is marked visited. */
    private static int[] corner(int[] rows, int[] cols, int numRows, int numCols, boolean[] visited) {
        int[] at = new int[16];
        for (int bit = 0; bit < 8; bit++) {
            int r = rows[bit] < 0 ? rows[bit] + numRows : rows[bit];
            int c = cols[bit] < 0 ? cols[bit] + numCols : cols[bit];
            if (r < 0 || c < 0 || r >= numRows || c >= numCols) {
                return null;
            }
            visited[r * numCols + c] = true;
            at[bit * 2] = r;
            at[bit * 2 + 1] = c;
        }
        return at;
    }

    private static int[] utah(int row, int col, int numRows, int numCols, boolean[] visited) {
        int[] at = new int[16];
        for (int bit = 0; bit < 8; bit++) {
            int r = row + UTAH_R[bit];
            int c = col + UTAH_C[bit];
            if (r < 0) {
                r += numRows;
                c += 4 - ((numRows + 4) % 8);
            }
            if (c < 0) {
                c += numCols;
                r += 4 - ((numCols + 4) % 8);
            }
            if (r >= numRows) {
                r -= numRows;
            }
            if (r < 0 || c < 0 || r >= numRows || c >= numCols) {
                return null;
            }
            visited[r * numCols + c] = true;
            at[bit * 2] = r;
            at[bit * 2 + 1] = c;
        }
        return at;
    }

    private static Bits extract(Bits bits, Version version) {
        int dataWidth = version.dataWidth();
        int dataHeight = version.dataHeight();
        Bits out = new Bits(dataWidth, dataHeight);
        for (int y = 0; y < dataHeight; y++) {
            for (int x = 0; x < dataWidth; x++) {
                int ix = x + 1 + (x / version.blockWidth) * 2;
                int iy = y + 1 + (y / version.blockHeight) * 2;
                if (ix < 0 || iy < 0 || ix >= bits.width || iy >= bits.height) {
                    return null;
                }
                if (bits.get(ix, iy)) {
                    out.set(x, y);
                }
            }
        }
        return out;
    }

    private static Split split(int[] raw, Version version, boolean fix259) {
        int blocks = version.c1 + version.c2;
        if (blocks == 0) {
            return null;
        }
        int[][] words = new int[blocks][];
        int[] dataCount = new int[blocks];
        int index = 0;
        int[] counts = {version.c1, version.c2};
        int[] dataLens = {version.d1, version.d2};
        for (int group = 0; group < 2; group++) {
            for (int i = 0; i < counts[group]; i++) {
                dataCount[index] = dataLens[group];
                words[index] = new int[version.ec + dataLens[group]];
                index++;
            }
        }
        int numCodewords = words[0].length;
        int numData = numCodewords - version.ec;
        int offset = 0;
        for (int i = 0; i < numData - 1; i++) {
            for (int block = 0; block < blocks; block++) {
                if (offset >= raw.length) {
                    return null;
                }
                words[block][i] = raw[offset++];
            }
        }
        boolean size144 = version.height == 144;
        int longer = size144 ? 8 : blocks;
        for (int block = 0; block < longer; block++) {
            if (offset >= raw.length || numData - 1 >= words[block].length) {
                return null;
            }
            words[block][numData - 1] = raw[offset++];
        }
        for (int i = numData; i < numCodewords; i++) {
            for (int block = 0; block < blocks; block++) {
                int jOffset = size144 && fix259 ? (block + 8) % blocks : block;
                int iOffset = size144 && jOffset > 7 ? i - 1 : i;
                if (offset >= raw.length || iOffset < 0 || iOffset >= words[jOffset].length) {
                    return null;
                }
                words[jOffset][iOffset] = raw[offset++];
            }
        }
        if (offset != raw.length) {
            return null;
        }
        return new Split(words, dataCount);
    }

    /** Puts data codewords back in symbol order: index {@code i * blockCount + j}. */
    private static byte[] reinterleave(Split split) {
        int count = split.words.length;
        int total = 0;
        for (int data : split.dataCount) {
            total += data;
        }
        byte[] out = new byte[total];
        boolean[] filled = new boolean[total];
        for (int block = 0; block < count; block++) {
            for (int i = 0; i < split.dataCount[block]; i++) {
                int at = i * count + block;
                if (at >= total || filled[at]) {
                    return null;
                }
                filled[at] = true;
                out[at] = (byte) split.words[block][i];
            }
        }
        for (boolean used : filled) {
            if (!used) {
                return null;
            }
        }
        return out;
    }

    /** {@code DecodedBitStreamParser::Decode}. The {@code ]d} identifier is not part of the text. */
    static String decodeStream(byte[] raw) {
        Decoded decoded = decodeStreamContent(raw);
        return decoded == null ? null : decoded.text();
    }

    /** {@link #decodeStream} with the content bytes. */
    static Decoded decodeStreamContent(byte[] raw) {
        try {
            Decoded decoded = parse(raw);
            if (decoded == null || decoded.text() == null || decoded.text().isEmpty()) {
                return null;
            }
            return decoded;
        } catch (IllegalArgumentException failed) {
            return null;
        }
    }

    private static Decoded parse(byte[] raw) {
        BitReader bits = new BitReader(raw);
        Content content = new Content();
        Shift upper = new Shift();
        StringBuilder trailer = new StringBuilder();
        boolean firstCodeword = true;
        boolean done = false;
        int firstFnc1 = 1;
        while (!done && bits.available() >= 8) {
            int oneByte = bits.read(8);
            switch (oneByte) {
                case 0 -> fail();
                case 129 -> done = true;
                case 230 -> c40OrText(bits, content, true);
                case 231 -> base256(bits, content);
                case 232 -> fnc1(bits, content, firstFnc1);
                case 233 -> {
                    if (!firstCodeword) {
                        fail();
                    }
                    structuredAppend(bits);
                    firstFnc1 = 5;
                }
                case 234 -> {
                    if (!firstCodeword) {
                        fail();
                    }
                }
                case 235 -> upper.set = true;
                case 236 -> {
                    content.append("[)>\u001E05\u001D");
                    trailer.insert(0, "\u001E\u0004");
                }
                case 237 -> {
                    content.append("[)>\u001E06\u001D");
                    trailer.insert(0, "\u001E\u0004");
                }
                case 238 -> x12(bits, content);
                case 239 -> c40OrText(bits, content, false);
                case 240 -> edifact(bits, content);
                case 241 -> content.encoding(parseEci(bits), true);
                default -> {
                    if (oneByte <= 128) {
                        content.appendByte(upper.apply(oneByte) - 1);
                    } else if (oneByte <= 229) {
                        int value = oneByte - 130;
                        content.appendByte('0' + value / 10);
                        content.appendByte('0' + value % 10);
                    } else if (oneByte == 254 && bits.available() == 0) {
                        // Encoders that unlatch to ASCII with the final codeword.
                    } else if (oneByte >= 242) {
                        fail();
                    }
                }
            }
            firstCodeword = false;
        }
        if (trailer.length() > 0) {
            content.append(trailer.toString());
        }
        return new Decoded(content.render(), content.raw());
    }

    private static void fnc1(BitReader bits, Content content, int firstFnc1) {
        int offset = bits.byteOffset();
        if (offset == firstFnc1) {
            return;
        }
        if (offset == firstFnc1 + 1
                && ((content.size() == 1 && isAlpha(content.byteAt(0)))
                || (content.size() == 2 && isDigit(content.byteAt(0)) && isDigit(content.byteAt(1))))) {
            return;
        }
        content.appendByte(29);
    }

    /** Three codewords. The file id is not part of the text. */
    private static void structuredAppend(BitReader bits) {
        bits.read(8);
        bits.read(8);
        bits.read(8);
    }

    private static int parseEci(BitReader bits) {
        int first = bits.read(8);
        if (first <= 127) {
            return first - 1;
        }
        int second = bits.read(8);
        if (first <= 191) {
            return (first - 128) * 254 + 127 + second - 1;
        }
        int third = bits.read(8);
        return (first - 192) * 64516 + 16383 + (second - 1) * 254 + third - 1;
    }

    private static void c40OrText(BitReader bits, Content content, boolean c40) {
        String basic = c40 ? C40_BASIC : TEXT_BASIC;
        Shift upper = new Shift();
        int shift = 0;
        int[] triple;
        while ((triple = nextTriple(bits)) != null) {
            for (int cValue : triple) {
                int active = shift;
                shift = 0;
                switch (active) {
                    case 0 -> {
                        if (cValue < 3) {
                            shift = cValue + 1;
                        } else if (cValue < basic.length()) {
                            content.appendByte(upper.apply(basic.charAt(cValue)));
                        } else {
                            fail();
                        }
                    }
                    case 1 -> content.appendByte(upper.apply(cValue));
                    case 2 -> {
                        if (cValue < C40_SHIFT2.length()) {
                            content.appendByte(upper.apply(C40_SHIFT2.charAt(cValue)));
                        } else if (cValue == 30) {
                            upper.set = true;
                        } else {
                            fail();
                        }
                    }
                    case 3 -> {
                        if (c40) {
                            content.appendByte(upper.apply(cValue + 96));
                        } else if (cValue < TEXT_SHIFT3.length()) {
                            content.appendByte(upper.apply(TEXT_SHIFT3.charAt(cValue)));
                        } else {
                            fail();
                        }
                    }
                    default -> fail();
                }
            }
        }
    }

    private static void x12(BitReader bits, Content content) {
        int[] triple;
        while ((triple = nextTriple(bits)) != null) {
            for (int cValue : triple) {
                if (cValue < 0) {
                    fail();
                } else if (cValue < 4) {
                    content.appendByte("\r*> ".charAt(cValue));
                } else if (cValue < 14) {
                    content.appendByte(cValue + 44);
                } else if (cValue < 40) {
                    content.appendByte(cValue + 51);
                } else {
                    fail();
                }
            }
        }
    }

    /** Sixteen-bit C40/Text/X12 value. Null ends the segment, including unlatch 254. */
    private static int[] nextTriple(BitReader bits) {
        if (bits.available() < 16) {
            return null;
        }
        int first = bits.read(8);
        if (first == 254) {
            return null;
        }
        int full = (first << 8) + bits.read(8) - 1;
        int a = full / 1600;
        full -= a * 1600;
        int b = full / 40;
        return new int[]{a, b, full - b * 40};
    }

    private static void edifact(BitReader bits, Content content) {
        while (bits.available() >= 24) {
            for (int i = 0; i < 4; i++) {
                int value = bits.read(6);
                if (value == 0x1F) {
                    if (bits.bitOffset() != 0) {
                        bits.read(8 - bits.bitOffset());
                    }
                    return;
                }
                if ((value & 0x20) == 0) {
                    value |= 0x40;
                }
                content.appendByte(value);
            }
        }
    }

    private static void base256(BitReader bits, Content content) {
        int position = 1 + bits.byteOffset();
        int d1 = unrandom(bits.read(8), position++);
        int count;
        if (d1 == 0) {
            count = bits.available() / 8;
        } else if (d1 < 250) {
            count = d1;
        } else {
            count = 250 * (d1 - 249) + unrandom(bits.read(8), position++);
        }
        if (count < 0) {
            fail();
        }
        for (int i = 0; i < count; i++) {
            content.appendByte(unrandom(bits.read(8), position++));
        }
    }

    private static int unrandom(int randomized, int position) {
        int pseudo = ((149 * position) % 255) + 1;
        int temp = randomized - pseudo;
        return temp >= 0 ? temp : temp + 256;
    }

    private static boolean isAlpha(int value) {
        return (value >= 'A' && value <= 'Z') || (value >= 'a' && value <= 'z');
    }

    private static boolean isDigit(int value) {
        return value >= '0' && value <= '9';
    }

    private static void fail() {
        throw new IllegalArgumentException("dm");
    }

    private static Version[] versions() {
        if (C40_BASIC.length() != 40 || TEXT_BASIC.length() != 40
                || C40_SHIFT2.length() != 28 || TEXT_SHIFT3.length() != 32 || ROWS.length != 48) {
            throw new ExceptionInInitializerError("Data Matrix character tables");
        }
        Version[] versions = new Version[ROWS.length];
        for (int i = 0; i < ROWS.length; i++) {
            int[] row = ROWS[i];
            Version version = new Version(row[0], row[1], row[2], row[3], row[4], row[5], row[6], row[7], row[8], row[9]);
            int extra = version.dataWidth() * version.dataHeight() - version.totalCodewords() * 8;
            // Versions 2, 4, 6 and 8 leave four modules unvisited. Every other version uses them all.
            if (version.totalCodewords() <= 0 || (extra != 0 && extra != 4)) {
                throw new ExceptionInInitializerError("Data Matrix version " + version.number);
            }
            versions[i] = version;
        }
        return versions;
    }

    private record Version(int number, int height, int width, int blockHeight, int blockWidth,
                           int ec, int c1, int d1, int c2, int d2) {
        int totalCodewords() {
            return c1 * (d1 + ec) + c2 * (d2 + ec);
        }

        int dataWidth() {
            return (width / blockWidth) * blockWidth;
        }

        int dataHeight() {
            return (height / blockHeight) * blockHeight;
        }
    }

    private record Split(int[][] words, int[] dataCount) {
    }

    /** One-shot addition of 128, then the flag clears. The sum is masked after the caller adjusts it. */
    private static final class Shift {
        private boolean set;

        int apply(int value) {
            if (set) {
                value += 128;
                set = false;
            }
            return value & 0xFF;
        }
    }

    /**
     * Raw bytes plus ECI segments. Once an ECI has been seen, later bytes stay in that
     * segment until the next ECI, matching {@code Content::switchEncoding}.
     */
    private static final class Content {
        private final List<Integer> bytes = new ArrayList<>();
        private final List<int[]> segments = new ArrayList<>();
        private boolean hasEci;

        int size() {
            return bytes.size();
        }

        int byteAt(int index) {
            return bytes.get(index);
        }

        byte[] raw() {
            byte[] out = new byte[bytes.size()];
            for (int i = 0; i < out.length; i++) {
                out[i] = (byte) (int) bytes.get(i);
            }
            return out;
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

        String render() {
            StringBuilder text = new StringBuilder();
            if (segments.isEmpty()) {
                // No ECI anywhere: all the bytes are UTF-8 when they are valid UTF-8, else ISO-8859-1.
                byte[] all = new byte[bytes.size()];
                for (int i = 0; i < all.length; i++) {
                    all[i] = (byte) (int) bytes.get(i);
                }
                write(text, TextDecoder.guessUtf8OrLatin1(all), 0, bytes.size());
            } else {
                if (segments.get(0)[1] != 0) {
                    write(text, ECI_LATIN1, 0, segments.get(0)[1]);
                }
                for (int i = 0; i < segments.size(); i++) {
                    int start = segments.get(i)[1];
                    int end = i + 1 == segments.size() ? bytes.size() : segments.get(i + 1)[1];
                    if (start != end) {
                        write(text, segments.get(i)[0], start, end);
                    }
                }
            }
            return text.toString();
        }

        private void write(StringBuilder text, int eci, int start, int end) {
            byte[] raw = new byte[end - start];
            for (int i = start; i < end; i++) {
                raw[i - start] = (byte) (int) bytes.get(i);
            }
            text.append(TextDecoder.decodeOrLatin1(raw, eci));
        }
    }

    /** MSB-first cursor matching {@code BitSource}. Exposes the offsets modes use for position. */
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

        int byteOffset() {
            return byteOffset;
        }

        int bitOffset() {
            return bitOffset;
        }

        int read(int numBits) {
            if (numBits < 1 || numBits > 32 || numBits > available()) {
                throw new IllegalArgumentException("dm bits");
            }
            int result = 0;
            if (bitOffset > 0) {
                int bitsLeft = 8 - bitOffset;
                int toRead = Math.min(numBits, bitsLeft);
                int keep = bitsLeft - toRead;
                int mask = (0xFF >> (8 - toRead)) << keep;
                result = ((bytes[byteOffset] & 0xFF) & mask) >> keep;
                numBits -= toRead;
                bitOffset += toRead;
                if (bitOffset == 8) {
                    bitOffset = 0;
                    byteOffset++;
                }
            }
            while (numBits >= 8) {
                result = (result << 8) | (bytes[byteOffset] & 0xFF);
                byteOffset++;
                numBits -= 8;
            }
            if (numBits > 0) {
                int keep = 8 - numBits;
                int mask = (0xFF >> keep) << keep;
                result = (result << numBits) | (((bytes[byteOffset] & 0xFF) & mask) >> keep);
                bitOffset += numBits;
            }
            return result;
        }
    }
}
