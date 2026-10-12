package org.krzywanski.barcode.locate.dm;

import com.google.zxing.common.reedsolomon.GenericGF;
import com.google.zxing.common.reedsolomon.ReedSolomonDecoder;
import com.google.zxing.common.reedsolomon.ReedSolomonException;
import org.krzywanski.barcode.decode.TextDecoder;
import org.krzywanski.barcode.locate.dm.DmGeometry.Bits;

import java.util.ArrayList;
import java.util.List;

/**
 * Aztec bit decoder ported from zxing-cpp 3.1.1 {@code AZDecoder.cpp}.
 *
 * <p>The sampled grid is corrected with the same GF(2^n) fields Java uses for Aztec data,
 * then read as the ISO/IEC 24778 high-level stream. With no ECI the bytes are ISO-8859-1;
 * guessing a charset is not ported. A structured-append header is removed from the text and
 * is not returned as metadata. A leading FNC1 is removed; an AIM indicator letter or digit
 * pair is kept. FLG(7) is ignored. Rune symbols stay in {@link AztecDetector}. The scan
 * ladder still reads Aztec through ZXing, so a clean symbol keeps an {@code original-*} attempt.
 */
final class AztecDecoder {
    private enum Table {
        UPPER, LOWER, MIXED, DIGIT, PUNCT, BINARY
    }

    private static final String[] UPPER_TABLE = {
            "CTRL_PS", " ", "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M", "N", "O", "P",
            "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z", "CTRL_LL", "CTRL_ML", "CTRL_DL", "CTRL_BS"
    };
    private static final String[] LOWER_TABLE = {
            "CTRL_PS", " ", "a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l", "m", "n", "o", "p",
            "q", "r", "s", "t", "u", "v", "w", "x", "y", "z", "CTRL_US", "CTRL_ML", "CTRL_DL", "CTRL_BS"
    };
    private static final String[] MIXED_TABLE = {
            "CTRL_PS", " ", "\1", "\2", "\3", "\4", "\5", "\6", "\7", "\b", "\t", "\n",
            "\13", "\f", "\r", "\33", "\34", "\35", "\36", "\37", "@", "\\", "^", "_",
            "`", "|", "~", "\177", "CTRL_LL", "CTRL_UL", "CTRL_PL", "CTRL_BS"
    };
    private static final String[] PUNCT_TABLE = {
            "FLGN", "\r", "\r\n", ". ", ", ", ": ", "!", "\"", "#", "$", "%", "&", "'", "(", ")",
            "*", "+", ",", "-", ".", "/", ":", ";", "<", "=", ">", "?", "[", "]", "{", "}", "CTRL_UL"
    };
    private static final String[] DIGIT_TABLE = {
            "CTRL_PS", " ", "0", "1", "2", "3", "4", "5", "6", "7", "8", "9", ",", ".", "CTRL_UL", "CTRL_US"
    };

    static {
        if (UPPER_TABLE.length != 32 || LOWER_TABLE.length != 32 || MIXED_TABLE.length != 32
                || PUNCT_TABLE.length != 32 || DIGIT_TABLE.length != 16) {
            throw new IllegalStateException("table size mismatch");
        }
    }

    private AztecDecoder() {
    }

    /** Text of one sampled symbol. Null when the grid is not a decodable Aztec code. */
    static String decode(Bits matrix, boolean compact, int layers, int dataBlocks) {
        if (matrix == null || layers < 1 || dataBlocks < 1) {
            return null;
        }
        try {
            boolean[] raw = extractBits(matrix, compact, layers);
            boolean[] corrected = correctBits(raw, layers, dataBlocks);
            if (corrected == null) {
                return null;
            }
            return text(corrected);
        } catch (Miss ignored) {
            return null;
        }
    }

    /**
     * High-level text of a corrected bit stream, the {@code Decode(BitArray)} entry
     * used by zxing-cpp's decoder tests. Null on a format error.
     */
    static String decodeCorrected(boolean[] bits) {
        if (bits == null || bits.length == 0) {
            return null;
        }
        try {
            return text(bits);
        } catch (Miss ignored) {
            return null;
        }
    }

    private static boolean[] extractBits(Bits matrix, boolean compact, int layers) {
        int base = (compact ? 11 : 14) + layers * 4;
        int[] map = new int[base];
        if (compact) {
            for (int i = 0; i < base; i++) {
                map[i] = i;
            }
        } else {
            int matrixSize = base + 1 + 2 * ((base / 2 - 1) / 15);
            int origin = base / 2;
            int center = matrixSize / 2;
            for (int i = 0; i < origin; i++) {
                int shifted = i + i / 15;
                map[origin - i - 1] = center - shifted - 1;
                map[origin + i] = center + shifted + 1;
            }
        }
        boolean[] raw = new boolean[totalBits(layers, compact)];
        for (int layer = 0, rowOffset = 0; layer < layers; layer++) {
            int rowSize = (layers - layer) * 4 + (compact ? 9 : 12);
            int low = layer * 2;
            int high = base - 1 - low;
            for (int j = 0; j < rowSize; j++) {
                int column = j * 2;
                for (int k = 0; k < 2; k++) {
                    raw[rowOffset + column + k] = module(matrix, map[low + k], map[low + j]);
                    raw[rowOffset + 2 * rowSize + column + k] = module(matrix, map[low + j], map[high - k]);
                    raw[rowOffset + 4 * rowSize + column + k] = module(matrix, map[high - k], map[high - j]);
                    raw[rowOffset + 6 * rowSize + column + k] = module(matrix, map[high - j], map[low + k]);
                }
            }
            rowOffset += rowSize * 8;
        }
        return raw;
    }

    private static boolean module(Bits matrix, int x, int y) {
        if (x < 0 || y < 0 || x >= matrix.width || y >= matrix.height) {
            throw new Miss();
        }
        return matrix.get(x, y);
    }

    private static boolean[] correctBits(boolean[] raw, int layers, int dataBlocks) {
        int wordSize;
        GenericGF field;
        if (layers <= 2) {
            wordSize = 6;
            field = GenericGF.AZTEC_DATA_6;
        } else if (layers <= 8) {
            wordSize = 8;
            field = GenericGF.AZTEC_DATA_8;
        } else if (layers <= 22) {
            wordSize = 10;
            field = GenericGF.AZTEC_DATA_10;
        } else {
            wordSize = 12;
            field = GenericGF.AZTEC_DATA_12;
        }
        int words = raw.length / wordSize;
        if (words < dataBlocks) {
            return null;
        }
        int[] data = new int[words];
        int offset = raw.length % wordSize;
        for (int i = 0; i < words; i++) {
            data[i] = read(raw, offset, wordSize);
            offset += wordSize;
        }
        try {
            new ReedSolomonDecoder(field).decode(data, words - dataBlocks);
        } catch (ReedSolomonException failed) {
            return null;
        }
        int mask = (1 << wordSize) - 1;
        boolean[] corrected = new boolean[dataBlocks * wordSize];
        int count = 0;
        for (int i = 0; i < dataBlocks; i++) {
            int word = data[i];
            if (word == 0 || word == mask) {
                return null;
            }
            if (word == 1 || word == mask - 1) {
                boolean black = word > 1;
                for (int bit = 0; bit < wordSize - 1; bit++) {
                    corrected[count++] = black;
                }
            } else {
                for (int bit = wordSize - 1; bit >= 0; bit--) {
                    corrected[count++] = ((word >> bit) & 1) != 0;
                }
            }
        }
        if (count == corrected.length) {
            return corrected;
        }
        boolean[] trimmed = new boolean[count];
        System.arraycopy(corrected, 0, trimmed, 0, count);
        return trimmed;
    }

    private static String text(boolean[] bits) {
        Out out = new Out();
        boolean fnc1 = false;
        Table latch = Table.UPPER;
        Table shift = Table.UPPER;
        Cursor cursor = new Cursor(bits);
        while (cursor.remaining() >= (shift == Table.DIGIT ? 4 : 5)) {
            if (shift == Table.BINARY) {
                if (cursor.remaining() <= 6) {
                    break;
                }
                int length = cursor.read(5);
                if (length == 0) {
                    length = cursor.read(11) + 31;
                }
                for (int i = 0; i < length; i++) {
                    out.push(cursor.read(8));
                }
                shift = latch;
            } else {
                int size = shift == Table.DIGIT ? 4 : 5;
                String value = character(shift, cursor.read(size));
                if (value.startsWith("CTRL_")) {
                    latch = shift;
                    shift = table(value.charAt(5));
                    if (value.charAt(6) == 'L') {
                        latch = shift;
                    }
                } else if ("FLGN".equals(value)) {
                    int flag = cursor.read(3);
                    if (flag == 0) {
                        fnc1 = true;
                        out.push(29);
                    } else if (flag <= 6) {
                        out.eci(eci(cursor, flag));
                    }
                    shift = latch;
                } else {
                    out.append(value);
                    shift = latch;
                }
            }
        }
        if (out.bytes.isEmpty()) {
            return null;
        }
        if (bits.length > 20 && read(bits, 0, 5) == 29 && read(bits, 5, 5) == 29) {
            stripStructuredAppend(out);
        }
        if (out.bytes.isEmpty()) {
            return null;
        }
        if (fnc1) {
            stripFnc1(out);
        }
        return out.render();
    }

    /** ISO/IEC 24778 structured append. The id and index stay out of the returned text. */
    private static void stripStructuredAppend(Out out) {
        int index = 0;
        if (out.bytes.get(0) == ' ') {
            int space = -1;
            for (int i = 1; i < out.bytes.size(); i++) {
                if (out.bytes.get(i) == ' ') {
                    space = i;
                    break;
                }
            }
            if (space < 0) {
                return;
            }
            index = space + 1;
        }
        if (index + 1 >= out.bytes.size()
                || !upper(out.bytes.get(index))
                || !upper(out.bytes.get(index + 1))) {
            return;
        }
        out.erase(0, index + 2);
    }

    private static void stripFnc1(Out out) {
        if (out.bytes.isEmpty()) {
            return;
        }
        if (out.bytes.get(0) == 29) {
            out.erase(0, 1);
        } else if (out.bytes.size() > 1 && alpha(out.bytes.get(0)) && out.bytes.get(1) == 29) {
            out.erase(1, 1);
        } else if (out.bytes.size() > 2
                && digit(out.bytes.get(0))
                && digit(out.bytes.get(1))
                && out.bytes.get(2) == 29) {
            out.erase(2, 1);
        }
    }

    private static int eci(Cursor cursor, int digits) {
        int value = 0;
        for (int i = 0; i < digits; i++) {
            value = 10 * value + cursor.read(4) - 2;
        }
        return value;
    }

    private static String character(Table table, int code) {
        return switch (table) {
            case UPPER -> UPPER_TABLE[code];
            case LOWER -> LOWER_TABLE[code];
            case MIXED -> MIXED_TABLE[code];
            case PUNCT -> PUNCT_TABLE[code];
            case DIGIT -> DIGIT_TABLE[code];
            case BINARY -> throw new Miss();
        };
    }

    private static Table table(char name) {
        return switch (name) {
            case 'L' -> Table.LOWER;
            case 'P' -> Table.PUNCT;
            case 'M' -> Table.MIXED;
            case 'D' -> Table.DIGIT;
            case 'B' -> Table.BINARY;
            default -> Table.UPPER;
        };
    }

    private static boolean upper(int value) {
        return value >= 'A' && value <= 'Z';
    }

    private static boolean alpha(int value) {
        return upper(value) || (value >= 'a' && value <= 'z');
    }

    private static boolean digit(int value) {
        return value >= '0' && value <= '9';
    }

    private static int read(boolean[] bits, int offset, int length) {
        int value = 0;
        for (int i = 0; i < length; i++) {
            value = (value << 1) | (bits[offset + i] ? 1 : 0);
        }
        return value;
    }

    private static int totalBits(int layers, boolean compact) {
        return ((compact ? 88 : 112) + 16 * layers) * layers;
    }

    private static final class Out {
        private final List<Integer> bytes = new ArrayList<>();
        private final List<int[]> encodings = new ArrayList<>();
        private boolean hasEci;

        private void push(int value) {
            bytes.add(value & 0xFF);
        }

        private void append(String value) {
            for (int i = 0; i < value.length(); i++) {
                push(value.charAt(i));
            }
        }

        private void eci(int eci) {
            if (!hasEci) {
                encodings.clear();
            }
            encodings.add(new int[]{eci, bytes.size()});
            hasEci = true;
        }

        private void erase(int position, int count) {
            for (int i = 0; i < count; i++) {
                bytes.remove(position);
            }
            for (int[] encoding : encodings) {
                if (encoding[1] > position) {
                    encoding[1] -= count;
                }
            }
        }

        private String render() {
            if (bytes.isEmpty()) {
                return null;
            }
            if (!hasEci) {
                return decode(0, bytes.size(), 3);
            }
            StringBuilder text = new StringBuilder();
            int first = encodings.isEmpty() ? bytes.size() : encodings.get(0)[1];
            if (first > 0) {
                text.append(decode(0, first, 3));
            }
            for (int i = 0; i < encodings.size(); i++) {
                int position = encodings.get(i)[1];
                int end = i + 1 == encodings.size() ? bytes.size() : encodings.get(i + 1)[1];
                if (position < end) {
                    text.append(decode(position, end, encodings.get(i)[0]));
                }
            }
            return text.length() == 0 ? null : text.toString();
        }

        private String decode(int from, int to, int eci) {
            byte[] raw = new byte[to - from];
            for (int i = 0; i < raw.length; i++) {
                raw[i] = (byte) (int) bytes.get(from + i);
            }
            return TextDecoder.decodeOrLatin1(raw, eci);
        }
    }

    /** MSB-first cursor. A read past the end throws {@link Miss}. */
    private static final class Cursor {
        private final boolean[] bits;
        private int offset;

        private Cursor(boolean[] bits) {
            this.bits = bits;
        }

        private int remaining() {
            return bits.length - offset;
        }

        private int read(int length) {
            if (length < 0 || remaining() < length) {
                throw new Miss();
            }
            int value = AztecDecoder.read(bits, offset, length);
            offset += length;
            return value;
        }
    }

    private static final class Miss extends RuntimeException {
        @Override
        public synchronized Throwable fillInStackTrace() {
            return this;
        }
    }
}
