package io.github.kamilkrzywanski.barcode.locate.dm;

import com.google.zxing.common.reedsolomon.GenericGF;
import com.google.zxing.common.reedsolomon.ReedSolomonDecoder;
import com.google.zxing.common.reedsolomon.ReedSolomonException;
import io.github.kamilkrzywanski.barcode.decode.TextDecoder;
import io.github.kamilkrzywanski.barcode.locate.dm.DmGeometry.Bits;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Micro QR bit decoder ported from zxing-cpp 3.1.1 {@code QRDecoder.cpp},
 * {@code QRBitMatrixParser.cpp} ({@code ReadMQRCodewords}),
 * {@code QRFormatInformation.cpp} ({@code DecodeMQR}), {@code QRVersion.cpp}
 * (micro versions), {@code QRCodecMode.cpp}, and {@code QRDataMask.h}.
 *
 * <p>Model-2 QR is {@link QrDecoder}. rMQR is not in this slice.
 * A checksum or format failure returns null: the uncorrected stream is not decoded.
 * Every micro version is a single error-correction block, so the interleaved
 * {@code GetDataBlocks} path is the identity.
 */
final class MicroQrDecoder {
    private static final int FORMAT_INFO_MASK_MODEL2 = 0x5412;
    private static final int FORMAT_INFO_MASK_MICRO = 0x4445;
    /** Masked model-2 format patterns, data values 0..31. */
    private static final int[] MODEL2_MASKED_PATTERNS = {
            0x5412, 0x5125, 0x5E7C, 0x5B4B, 0x45F9, 0x40CE, 0x4F97, 0x4AA0,
            0x77C4, 0x72F3, 0x7DAA, 0x789D, 0x662F, 0x6318, 0x6C41, 0x6976,
            0x1689, 0x13BE, 0x1CE7, 0x19D0, 0x0762, 0x0255, 0x0D0C, 0x083B,
            0x355F, 0x3068, 0x3F31, 0x3A06, 0x24B4, 0x2183, 0x2EDA, 0x2BED
    };
    private static final int[] BITS_TO_VERSION = {1, 2, 2, 3, 3, 4, 4, 4};
    /** Indexed by the three version/level bits. L=0, M=1, Q=2. */
    private static final int[] LEVEL_FOR_BITS = {0, 0, 1, 0, 1, 0, 1, 2};
    /**
     * {@code [version][ecLevel]} is {error-correction codewords, data codewords}.
     * Missing levels are null and fail the decode.
     */
    private static final int[][][] EC_BLOCKS = {
            null,
            {{2, 3}, null, null, null},
            {{5, 5}, {6, 4}, null, null},
            {{6, 11}, {8, 9}, null, null},
            {{8, 16}, {10, 14}, {14, 10}, null}
    };
    private static final String ALPHANUMERIC = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:";

    private MicroQrDecoder() {
    }

    /** Format chosen by {@link #decodeFormat(int)}. Hamming above 3 is not a match. */
    static final class Format {
        int hamming = 255;
        int version;
        int ecLevel;
        int dataMask;
        boolean mirrored;

        boolean valid() {
            return hamming <= 3 && version >= 1 && version <= 4 && dataMask >= 0 && dataMask <= 3;
        }
    }

    /**
     * Decode one sampled symbol. {@code bits} is the module grid, finder at the
     * top left in the chosen rotation. Null means the symbol is not a valid Micro QR.
     */
    static String decode(Bits bits) {
        if (!microSize(bits)) {
            return null;
        }
        Format format = readFormat(bits);
        if (!format.valid()) {
            return null;
        }
        int version = versionForDimension(bits.width);
        int[] block = EC_BLOCKS[version][format.ecLevel];
        if (block == null) {
            return null;
        }
        int ecWords = block[0];
        int dataWords = block[1];
        int[] codewords = readCodewords(bits, version, format, dataWords + ecWords);
        if (codewords == null) {
            return null;
        }
        try {
            new ReedSolomonDecoder(GenericGF.QR_CODE_FIELD_256).decode(codewords, ecWords);
        } catch (ReedSolomonException failed) {
            return null;
        }
        byte[] data = new byte[dataWords];
        for (int i = 0; i < dataWords; i++) {
            data[i] = (byte) codewords[i];
        }
        return decodeStream(data, version);
    }

    /**
     * Best micro format for one 15-bit sample, mask still applied.
     * The mirrored candidate is the 15-bit reversal. A tie keeps the earlier one.
     */
    static Format decodeFormat(int rawBits) {
        int mirrored = (Integer.reverse(rawBits) >>> 17) & 0x7FFF;
        int[] candidates = {rawBits & 0x7FFF, mirrored};
        Format best = new Format();
        for (int bitsIndex = 0; bitsIndex < candidates.length; bitsIndex++) {
            int bits = candidates[bitsIndex];
            for (int masked : MODEL2_MASKED_PATTERNS) {
                int pattern = masked ^ FORMAT_INFO_MASK_MODEL2;
                int hamming = Integer.bitCount((bits ^ FORMAT_INFO_MASK_MICRO) ^ pattern);
                if (hamming < best.hamming) {
                    int data = pattern >> 10;
                    int levelBits = (data >> 2) & 7;
                    best.hamming = hamming;
                    best.version = BITS_TO_VERSION[levelBits];
                    best.ecLevel = ecLevelFromBits(levelBits);
                    best.dataMask = data & 0x03;
                    best.mirrored = bitsIndex == 1;
                }
            }
        }
        return best;
    }

    private static boolean microSize(Bits bits) {
        return bits != null && bits.width == bits.height && versionForDimension(bits.width) > 0;
    }

    /**
     * {@code Version::Number} for a Micro QR square. Model 2 and every other size are 0.
     */
    static int versionForDimension(int dimension) {
        for (int version = 1; version <= 4; version++) {
            if (dimensionForVersion(version) == dimension) {
                return version;
            }
        }
        return 0;
    }

    /** {@code Version::SymbolSize} for Micro QR. Version 0 and versions above 4 are 0. */
    static int dimensionForVersion(int version) {
        if (version < 1 || version > 4) {
            return 0;
        }
        return 9 + 2 * version;
    }

    /** {@code ECLevelFromBits} for Micro QR. L is 0, M is 1, Q is 2. The low three bits select the level. */
    static int ecLevelFromBits(int bits) {
        return LEVEL_FOR_BITS[bits & 0x07];
    }

    /** Format bits on an already sampled grid. Coordinates are not swapped here. */
    static Format readFormat(Bits matrix) {
        int bits = 0;
        for (int x = 1; x < 9; x++) {
            bits = (bits << 1) | (matrix.get(x, 8) ? 1 : 0);
        }
        for (int y = 7; y >= 1; y--) {
            bits = (bits << 1) | (matrix.get(8, y) ? 1 : 0);
        }
        return decodeFormat(bits);
    }

    /**
     * Data codewords, then error-correction codewords.
     * M1 and M3 insert one 4-bit codeword in the low nibble. The bit source reads
     * that byte from the high bit, so four zero bits precede those four data bits.
     */
    static int[] readCodewords(Bits matrix, int version, Format format, int total) {
        int dimension = matrix.height;
        boolean[] function = functionPattern(dimension);
        boolean hasNibble = version % 2 == 1;
        int nibbleIndex = version == 1 ? 3 : (format.ecLevel == 0 ? 11 : 9);
        int[] result = new int[total];
        int count = 0;
        int current = 0;
        int bitsRead = 0;
        boolean readingUp = true;
        for (int x = dimension - 1; x > 0; x -= 2) {
            for (int row = 0; row < dimension; row++) {
                int y = readingUp ? dimension - 1 - row : row;
                for (int col = 0; col < 2; col++) {
                    int xx = x - col;
                    if (function[y * dimension + xx]) {
                        continue;
                    }
                    boolean module = format.mirrored ? matrix.get(y, xx) : matrix.get(xx, y);
                    boolean bit = dataMask(format.dataMask, xx, y) != module;
                    current = ((current << 1) | (bit ? 1 : 0)) & 0xFF;
                    bitsRead++;
                    if (bitsRead == 8 || (bitsRead == 4 && hasNibble && count == nibbleIndex - 1)) {
                        if (count >= total) {
                            return null;
                        }
                        result[count++] = current;
                        current = 0;
                        bitsRead = 0;
                    }
                }
            }
            readingUp = !readingUp;
        }
        if (count != total) {
            return null;
        }
        return result;
    }

    /**
     * Modules that are not data. The top strip is the horizontal timing row and the
     * left strip is the vertical timing column. {@code QRVersion.cpp} labels those
     * two calls the other way around; these coordinates match the calls.
     */
    static boolean[] functionPattern(int dimension) {
        boolean[] pattern = new boolean[dimension * dimension];
        fill(pattern, dimension, 0, 0, 9, 9);
        fill(pattern, dimension, 9, 0, dimension - 9, 1);
        fill(pattern, dimension, 0, 9, 1, dimension - 9);
        return pattern;
    }

    private static void fill(boolean[] pattern, int dimension, int left, int top, int width, int height) {
        for (int y = top; y < top + height; y++) {
            int row = y * dimension;
            for (int x = left; x < left + width; x++) {
                pattern[row + x] = true;
            }
        }
    }

    /** Micro masks 0..3 are QR masks 1, 4, 6, and 7. */
    static boolean dataMask(int mask, int x, int y) {
        return switch (mask) {
            case 0 -> y % 2 == 0;
            case 1 -> ((y / 2) + (x / 3)) % 2 == 0;
            case 2 -> (y * x) % 6 < 3;
            case 3 -> (y + x + ((y * x) % 3)) % 2 == 0;
            default -> false;
        };
    }

    /**
     * Micro QR has no ECI, so byte mode is read in the character set {@code GuessTextEncoding}
     * picks from all byte segments together, as {@code Content::guessEncoding} does.
     */
    private static String decodeStream(byte[] data, int version) {
        BitReader bits = new BitReader(data);
        StringBuilder text = new StringBuilder();
        List<int[]> byteOffsets = new ArrayList<>();
        ByteArrayOutputStream byteData = new ByteArrayOutputStream();
        int modeBits = version - 1;
        int terminator = version * 2 + 1;
        try {
            while (!endOfStream(bits, terminator)) {
                int mode = modeBits == 0 ? 0 : bits.read(modeBits);
                if (mode < 0 || mode > 3) {
                    return null;
                }
                int count = bits.read(countBits(mode, version));
                boolean ok = switch (mode) {
                    case 0 -> numeric(bits, count, text);
                    case 1 -> alphanumeric(bits, count, text);
                    case 2 -> bytes(bits, count, text.length(), byteOffsets, byteData);
                    case 3 -> kanji(bits, count, text);
                    default -> false;
                };
                if (!ok) {
                    return null;
                }
            }
        } catch (IllegalArgumentException | ArrayIndexOutOfBoundsException truncated) {
            return null;
        }
        if (!byteOffsets.isEmpty()) {
            byte[] all = byteData.toByteArray();
            int eci = TextDecoder.guessEci(all);
            // Back to front, so an insert does not move the offsets still to come.
            for (int i = byteOffsets.size() - 1; i >= 0; i--) {
                int[] segment = byteOffsets.get(i);
                byte[] raw = Arrays.copyOfRange(all, segment[1], segment[1] + segment[2]);
                text.insert(segment[0], TextDecoder.decodeOrLatin1(raw, eci));
            }
        }
        if (text.isEmpty()) {
            return null;
        }
        return text.toString();
    }

    private static boolean endOfStream(BitReader bits, int terminator) {
        int available = Math.min(bits.available(), terminator);
        return available == 0 || bits.peek(available) == 0;
    }

    /** Character-count width from the Micro QR column of ISO 18004. */
    static int countBits(int mode, int version) {
        return switch (mode) {
            case 0 -> new int[]{3, 4, 5, 6}[version - 1];
            case 1 -> new int[]{3, 4, 5}[version - 2];
            case 2 -> new int[]{4, 5}[version - 3];
            case 3 -> new int[]{3, 4}[version - 3];
            default -> 0;
        };
    }

    private static boolean numeric(BitReader bits, int count, StringBuilder text) {
        while (count > 0) {
            int group = Math.min(count, 3);
            String digits = digits(bits.read(1 + 3 * group), group);
            if (digits == null) {
                return false;
            }
            text.append(digits);
            count -= group;
        }
        return true;
    }

    /** Zero-padded, and null when {@code value} needs more than {@code width} digits. */
    private static String digits(int value, int width) {
        if (value < 0) {
            return null;
        }
        String raw = Integer.toString(value);
        if (raw.length() > width) {
            return null;
        }
        return "0".repeat(width - raw.length()) + raw;
    }

    private static boolean alphanumeric(BitReader bits, int count, StringBuilder text) {
        while (count > 1) {
            int value = bits.read(11);
            if (!appendAlpha(text, value / 45) || !appendAlpha(text, value % 45)) {
                return false;
            }
            count -= 2;
        }
        if (count == 1 && !appendAlpha(text, bits.read(6))) {
            return false;
        }
        return true;
    }

    private static boolean appendAlpha(StringBuilder text, int index) {
        if (index < 0 || index >= ALPHANUMERIC.length()) {
            return false;
        }
        text.append(ALPHANUMERIC.charAt(index));
        return true;
    }

    /** Records the segment's bytes and where its text goes. They are decoded once all segments are read. */
    private static boolean bytes(BitReader bits, int count, int textOffset, List<int[]> offsets, ByteArrayOutputStream data) {
        offsets.add(new int[]{textOffset, data.size(), count});
        for (int i = 0; i < count; i++) {
            data.write(bits.read(8));
        }
        return true;
    }

    private static boolean kanji(BitReader bits, int count, StringBuilder text) {
        byte[] raw = new byte[count * 2];
        for (int i = 0; i < count; i++) {
            int value = bits.read(13);
            int assembled = ((value / 0x0C0) << 8) | (value % 0x0C0);
            assembled += assembled < 0x01F00 ? 0x08140 : 0x0C140;
            raw[i * 2] = (byte) (assembled >> 8);
            raw[i * 2 + 1] = (byte) assembled;
        }
        text.append(TextDecoder.decodeOrLatin1(raw, 20));
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
