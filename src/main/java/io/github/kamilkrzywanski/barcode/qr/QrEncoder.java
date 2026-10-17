/*
 * Copyright 2008 ZXing authors
 * Copyright 2026 Kamil Krzywański
 */
// SPDX-License-Identifier: Apache-2.0
package io.github.kamilkrzywanski.barcode.qr;

import io.github.kamilkrzywanski.barcode.QrErrorCorrection;
import io.github.kamilkrzywanski.barcode.ecc.GenericGF;
import io.github.kamilkrzywanski.barcode.ecc.ReedSolomonEncoder;
import io.github.kamilkrzywanski.barcode.encode.BitBuffer;
import io.github.kamilkrzywanski.barcode.encode.Eci;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Encodes data into a model 2 QR Code: one segment in the most compact single mode, an optional ECI
 * and FNC1 header, the smallest version that fits, the error correction blocks interleaved, and the
 * mask with the lowest penalty. With the same choices the symbol is the one ZXing's {@code Encoder}
 * makes, module for module.
 *
 * <p>Ported from ZXing 3.5.4 {@code com.google.zxing.qrcode.encoder.Encoder} (Apache License 2.0,
 * the ZXing authors).
 */
public final class QrEncoder {
    /** Table 5 of JIS X 0510:2004: the alphanumeric code of each ASCII character, or -1. */
    private static final int[] ALPHANUMERIC_TABLE = {
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,  // 0x00-0x0f
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,  // 0x10-0x1f
            36, -1, -1, -1, 37, 38, -1, -1, -1, -1, 39, 40, -1, 41, 42, 43,  // 0x20-0x2f
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 44, -1, -1, -1, -1, -1,  // 0x30-0x3f
            -1, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24,  // 0x40-0x4f
            25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, -1, -1, -1, -1, -1,  // 0x50-0x5f
    };
    private static final Charset SHIFT_JIS = Charset.isSupported("Shift_JIS") ? Charset.forName("Shift_JIS") : null;

    private QrEncoder() {
    }

    /** The ECI a character set of this name is announced with, or -1 when the table has none. */
    static int eciValue(String charsetName) {
        return Eci.value(charsetName);
    }

    /** One encoded symbol. {@code modules[y][x]} is true for a dark module. */
    public record Encoded(boolean[][] modules, int version, QrErrorCorrection level, int mask) {
    }

    /**
     * Encodes {@code content} in one segment.
     *
     * @param charset       the byte mode encoding, null for ISO-8859-1 without an ECI
     * @param gs1           announces GS1 data with FNC1 in first position
     * @param versionNumber 1 to 40, or 0 for the smallest that fits
     * @param mask          0 to 7, or -1 for the lowest penalty
     * @throws IllegalArgumentException when the data does not fit the version, or any version
     */
    public static Encoded encode(String content, QrErrorCorrection level, Charset charset, boolean gs1, int versionNumber, int mask) {
        return encode(content, level, charset, gs1, versionNumber, mask, false);
    }

    /**
     * As {@link #encode(String, QrErrorCorrection, Charset, boolean, int, int)}; {@code compact} splits
     * the text into the segments, modes and character sets that make the fewest bits, as ZXing's
     * {@code QR_COMPACT} does, with {@code charset} preferred for every character it can encode.
     */
    public static Encoded encode(String content, QrErrorCorrection level, Charset charset, boolean gs1, int versionNumber, int mask,
                                 boolean compact) {
        // ZXing's minimal encoder fails on empty text; one empty byte segment is as small as it gets.
        if (compact && !content.isEmpty()) {
            QrVersion fixed = null;
            if (versionNumber != 0) {
                fixed = QrVersion.of(versionNumber);
                if (fixed == null) {
                    throw new IllegalArgumentException("QR Code versions are 1 to 40, not " + versionNumber);
                }
            }
            Charset priority = charset == null || charset.equals(StandardCharsets.ISO_8859_1) ? null : charset;
            MinimalEncoder.ResultList segments = MinimalEncoder.encode(content, fixed, priority, gs1, level);
            BitBuffer bits = new BitBuffer();
            segments.getBits(bits);
            return layout(bits, segments.getVersion(), level, mask);
        }
        Charset encoding = charset == null ? StandardCharsets.ISO_8859_1 : charset;
        QrMode mode = chooseMode(content, encoding);
        BitBuffer headerBits = new BitBuffer();
        if (mode == QrMode.BYTE && charset != null) {
            int eci = eciValue(charset.name());
            if (eci >= 0) {
                appendEci(eci, headerBits);
            }
        }
        BitBuffer dataBits = new BitBuffer();
        appendBytes(content, mode, dataBits, encoding);
        int numLetters = mode == QrMode.BYTE ? dataBits.getSizeInBytes() : content.length();
        return finish(mode, headerBits, dataBits, numLetters, level, gs1, versionNumber, mask);
    }

    /** Encodes {@code data} as bytes, without an ECI: a reader takes them as they are. */
    public static Encoded encode(byte[] data, QrErrorCorrection level, boolean gs1, int versionNumber, int mask) {
        BitBuffer dataBits = new BitBuffer();
        append8BitBytes(data, dataBits);
        return finish(QrMode.BYTE, new BitBuffer(), dataBits, data.length, level, gs1, versionNumber, mask);
    }

    private static Encoded finish(QrMode mode, BitBuffer headerBits, BitBuffer dataBits, int numLetters, QrErrorCorrection level,
                                  boolean gs1, int versionNumber, int mask) {
        if (gs1) {
            appendModeInfo(QrMode.FNC1_FIRST_POSITION, headerBits);
        }
        appendModeInfo(mode, headerBits);
        QrVersion version;
        if (versionNumber != 0) {
            version = QrVersion.of(versionNumber);
            if (version == null) {
                throw new IllegalArgumentException("QR Code versions are 1 to 40, not " + versionNumber);
            }
            if (!willFit(calculateBitsNeeded(mode, headerBits, dataBits, version), version, level)) {
                throw new IllegalArgumentException("Data too big for version " + versionNumber + " at level " + level);
            }
        } else {
            version = recommendVersion(level, mode, headerBits, dataBits);
        }
        BitBuffer headerAndDataBits = new BitBuffer();
        headerAndDataBits.appendBitArray(headerBits);
        appendLengthInfo(numLetters, version, mode, headerAndDataBits);
        headerAndDataBits.appendBitArray(dataBits);
        return layout(headerAndDataBits, version, level, mask);
    }

    /** Terminates and pads the bits, adds the error correction, and places them with the mask. */
    private static Encoded layout(BitBuffer headerAndDataBits, QrVersion version, QrErrorCorrection level, int mask) {
        QrVersion.EcBlocks ecBlocks = version.ecBlocks(level);
        int numDataBytes = version.totalCodewords() - ecBlocks.totalEcCodewords();
        terminateBits(numDataBytes, headerAndDataBits);
        BitBuffer finalBits = interleaveWithEcBytes(headerAndDataBits, version.totalCodewords(), numDataBytes, ecBlocks.numBlocks());

        int dimension = version.dimension();
        ByteMatrix matrix = new ByteMatrix(dimension, dimension);
        int maskPattern = mask >= 0 && mask < QrMasks.NUM_MASK_PATTERNS ? mask : chooseMaskPattern(finalBits, level, version, matrix);
        QrMatrix.buildMatrix(finalBits, level, version, maskPattern, matrix);
        boolean[][] modules = new boolean[dimension][dimension];
        for (int y = 0; y < dimension; y++) {
            for (int x = 0; x < dimension; x++) {
                modules[y][x] = matrix.get(x, y) == 1;
            }
        }
        return new Encoded(modules, version.number(), level, maskPattern);
    }

    /** The two format bits of a level: L is 01, M 00, Q 11 and H 10. */
    static int formatBits(QrErrorCorrection level) {
        return switch (level) {
            case L -> 0x01;
            case M -> 0x00;
            case Q -> 0x03;
            case H -> 0x02;
        };
    }

    /** The sum of the four penalty rules of JIS X 0510:2004 table 21. */
    private static int calculateMaskPenalty(ByteMatrix matrix) {
        return QrMasks.applyMaskPenaltyRule1(matrix)
                + QrMasks.applyMaskPenaltyRule2(matrix)
                + QrMasks.applyMaskPenaltyRule3(matrix)
                + QrMasks.applyMaskPenaltyRule4(matrix);
    }

    /**
     * The smallest version the data fits. The width of the character count depends on the version,
     * so a first guess assumes version 1 and the second uses the guess, as ZXing does.
     */
    private static QrVersion recommendVersion(QrErrorCorrection level, QrMode mode, BitBuffer headerBits, BitBuffer dataBits) {
        int provisionalBitsNeeded = calculateBitsNeeded(mode, headerBits, dataBits, QrVersion.of(1));
        QrVersion provisionalVersion = chooseVersion(provisionalBitsNeeded, level);
        int bitsNeeded = calculateBitsNeeded(mode, headerBits, dataBits, provisionalVersion);
        return chooseVersion(bitsNeeded, level);
    }

    private static int calculateBitsNeeded(QrMode mode, BitBuffer headerBits, BitBuffer dataBits, QrVersion version) {
        return headerBits.getSize() + mode.getCharacterCountBits(version) + dataBits.getSize();
    }

    /** The alphanumeric code of {@code code}, or -1 when the alphanumeric mode has no such character. */
    static int getAlphanumericCode(int code) {
        return code < ALPHANUMERIC_TABLE.length ? ALPHANUMERIC_TABLE[code] : -1;
    }

    /**
     * The one mode for the whole content: Kanji when the encoding is Shift_JIS and every character
     * is double-byte Kanji, else numeric, alphanumeric or byte, whichever holds every character.
     */
    static QrMode chooseMode(String content, Charset encoding) {
        if (SHIFT_JIS != null && SHIFT_JIS.equals(encoding) && isOnlyDoubleByteKanji(content)) {
            return QrMode.KANJI;
        }
        boolean hasNumeric = false;
        boolean hasAlphanumeric = false;
        for (int i = 0; i < content.length(); ++i) {
            char c = content.charAt(i);
            if (c >= '0' && c <= '9') {
                hasNumeric = true;
            } else if (getAlphanumericCode(c) != -1) {
                hasAlphanumeric = true;
            } else {
                return QrMode.BYTE;
            }
        }
        if (hasAlphanumeric) {
            return QrMode.ALPHANUMERIC;
        }
        if (hasNumeric) {
            return QrMode.NUMERIC;
        }
        return QrMode.BYTE;
    }

    static boolean isOnlyDoubleByteKanji(String content) {
        byte[] bytes = content.getBytes(SHIFT_JIS);
        int length = bytes.length;
        if (length % 2 != 0) {
            return false;
        }
        for (int i = 0; i < length; i += 2) {
            int byte1 = bytes[i] & 0xFF;
            if ((byte1 < 0x81 || byte1 > 0x9F) && (byte1 < 0xE0 || byte1 > 0xEB)) {
                return false;
            }
        }
        return true;
    }

    private static int chooseMaskPattern(BitBuffer bits, QrErrorCorrection level, QrVersion version, ByteMatrix matrix) {
        int minPenalty = Integer.MAX_VALUE;
        int bestMaskPattern = -1;
        for (int maskPattern = 0; maskPattern < QrMasks.NUM_MASK_PATTERNS; maskPattern++) {
            QrMatrix.buildMatrix(bits, level, version, maskPattern, matrix);
            int penalty = calculateMaskPenalty(matrix);
            if (penalty < minPenalty) {
                minPenalty = penalty;
                bestMaskPattern = maskPattern;
            }
        }
        return bestMaskPattern;
    }

    private static QrVersion chooseVersion(int numInputBits, QrErrorCorrection level) {
        for (int versionNum = 1; versionNum <= 40; versionNum++) {
            QrVersion version = QrVersion.of(versionNum);
            if (willFit(numInputBits, version, level)) {
                return version;
            }
        }
        throw new IllegalArgumentException("Data too big for any QR Code version at level " + level);
    }

    /** Whether {@code numInputBits} fit the data codewords of {@code version} at {@code level}. */
    static boolean willFit(int numInputBits, QrVersion version, QrErrorCorrection level) {
        int numDataBytes = version.totalCodewords() - version.ecBlocks(level).totalEcCodewords();
        int totalInputBytes = (numInputBits + 7) / 8;
        return numDataBytes >= totalInputBytes;
    }

    /** The terminator, the padding to a byte, and the 0xEC 0x11 pad codewords (JIS X 0510:2004 8.4.8, 8.4.9). */
    static void terminateBits(int numDataBytes, BitBuffer bits) {
        int capacity = numDataBytes * 8;
        if (bits.getSize() > capacity) {
            throw new IllegalArgumentException("data bits cannot fit in the QR Code" + bits.getSize() + " > " + capacity);
        }
        for (int i = 0; i < 4 && bits.getSize() < capacity; ++i) {
            bits.appendBit(false);
        }
        int numBitsInLastByte = bits.getSize() & 0x07;
        if (numBitsInLastByte > 0) {
            for (int i = numBitsInLastByte; i < 8; i++) {
                bits.appendBit(false);
            }
        }
        int numPaddingBytes = numDataBytes - bits.getSizeInBytes();
        for (int i = 0; i < numPaddingBytes; ++i) {
            bits.appendBits((i & 0x01) == 0 ? 0xEC : 0x11, 8);
        }
        if (bits.getSize() != capacity) {
            throw new IllegalStateException("Bits size does not equal capacity");
        }
    }

    /**
     * The data and error correction codewords of block {@code blockId}: blocks of the second group
     * hold one data codeword more (JIS X 0510:2004 table 12).
     */
    static void getNumDataBytesAndNumEcBytesForBlockId(int numTotalBytes, int numDataBytes, int numRsBlocks, int blockId,
                                                       int[] numDataBytesInBlock, int[] numEcBytesInBlock) {
        if (blockId >= numRsBlocks) {
            throw new IllegalStateException("Block ID too large");
        }
        int numRsBlocksInGroup2 = numTotalBytes % numRsBlocks;
        int numRsBlocksInGroup1 = numRsBlocks - numRsBlocksInGroup2;
        int numTotalBytesInGroup1 = numTotalBytes / numRsBlocks;
        int numTotalBytesInGroup2 = numTotalBytesInGroup1 + 1;
        int numDataBytesInGroup1 = numDataBytes / numRsBlocks;
        int numDataBytesInGroup2 = numDataBytesInGroup1 + 1;
        int numEcBytesInGroup1 = numTotalBytesInGroup1 - numDataBytesInGroup1;
        int numEcBytesInGroup2 = numTotalBytesInGroup2 - numDataBytesInGroup2;
        if (numEcBytesInGroup1 != numEcBytesInGroup2) {
            throw new IllegalStateException("EC bytes mismatch");
        }
        if (numTotalBytes != (numDataBytesInGroup1 + numEcBytesInGroup1) * numRsBlocksInGroup1
                + (numDataBytesInGroup2 + numEcBytesInGroup2) * numRsBlocksInGroup2) {
            throw new IllegalStateException("Total bytes mismatch");
        }
        if (blockId < numRsBlocksInGroup1) {
            numDataBytesInBlock[0] = numDataBytesInGroup1;
            numEcBytesInBlock[0] = numEcBytesInGroup1;
        } else {
            numDataBytesInBlock[0] = numDataBytesInGroup2;
            numEcBytesInBlock[0] = numEcBytesInGroup2;
        }
    }

    /**
     * Splits the data codewords into blocks, adds each block's error correction, and interleaves
     * them: the first data codeword of every block, the second, and so on, then the error
     * correction the same way (JIS X 0510:2004 8.6).
     */
    static BitBuffer interleaveWithEcBytes(BitBuffer bits, int numTotalBytes, int numDataBytes, int numRsBlocks) {
        if (bits.getSizeInBytes() != numDataBytes) {
            throw new IllegalStateException("Number of bits and data bytes does not match");
        }
        int dataBytesOffset = 0;
        int maxNumDataBytes = 0;
        int maxNumEcBytes = 0;
        List<BlockPair> blocks = new ArrayList<>(numRsBlocks);
        for (int i = 0; i < numRsBlocks; ++i) {
            int[] numDataBytesInBlock = new int[1];
            int[] numEcBytesInBlock = new int[1];
            getNumDataBytesAndNumEcBytesForBlockId(numTotalBytes, numDataBytes, numRsBlocks, i, numDataBytesInBlock, numEcBytesInBlock);
            int size = numDataBytesInBlock[0];
            byte[] dataBytes = new byte[size];
            bits.toBytes(8 * dataBytesOffset, dataBytes, 0, size);
            byte[] ecBytes = generateEcBytes(dataBytes, numEcBytesInBlock[0]);
            blocks.add(new BlockPair(dataBytes, ecBytes));
            maxNumDataBytes = Math.max(maxNumDataBytes, size);
            maxNumEcBytes = Math.max(maxNumEcBytes, ecBytes.length);
            dataBytesOffset += numDataBytesInBlock[0];
        }
        if (numDataBytes != dataBytesOffset) {
            throw new IllegalStateException("Data bytes does not match offset");
        }
        BitBuffer result = new BitBuffer();
        for (int i = 0; i < maxNumDataBytes; ++i) {
            for (BlockPair block : blocks) {
                byte[] dataBytes = block.dataBytes();
                if (i < dataBytes.length) {
                    result.appendBits(dataBytes[i], 8);
                }
            }
        }
        for (int i = 0; i < maxNumEcBytes; ++i) {
            for (BlockPair block : blocks) {
                byte[] ecBytes = block.errorCorrectionBytes();
                if (i < ecBytes.length) {
                    result.appendBits(ecBytes[i], 8);
                }
            }
        }
        if (numTotalBytes != result.getSizeInBytes()) {
            throw new IllegalStateException("Interleaving error: " + numTotalBytes + " and " + result.getSizeInBytes() + " differ.");
        }
        return result;
    }

    static byte[] generateEcBytes(byte[] dataBytes, int numEcBytesInBlock) {
        int numDataBytes = dataBytes.length;
        int[] toEncode = new int[numDataBytes + numEcBytesInBlock];
        for (int i = 0; i < numDataBytes; i++) {
            toEncode[i] = dataBytes[i] & 0xFF;
        }
        new ReedSolomonEncoder(GenericGF.QR_CODE_FIELD_256).encode(toEncode, numEcBytesInBlock);
        byte[] ecBytes = new byte[numEcBytesInBlock];
        for (int i = 0; i < numEcBytesInBlock; i++) {
            ecBytes[i] = (byte) toEncode[numDataBytes + i];
        }
        return ecBytes;
    }

    static void appendModeInfo(QrMode mode, BitBuffer bits) {
        bits.appendBits(mode.getBits(), 4);
    }

    static void appendLengthInfo(int numLetters, QrVersion version, QrMode mode, BitBuffer bits) {
        int numBits = mode.getCharacterCountBits(version);
        if (numLetters >= (1 << numBits)) {
            throw new IllegalArgumentException(numLetters + " is bigger than " + ((1 << numBits) - 1));
        }
        bits.appendBits(numLetters, numBits);
    }

    /** {@code content} in {@code mode}; byte mode in {@code encoding}. */
    static void appendBytes(String content, QrMode mode, BitBuffer bits, Charset encoding) {
        switch (mode) {
            case NUMERIC -> appendNumericBytes(content, bits);
            case ALPHANUMERIC -> appendAlphanumericBytes(content, bits);
            case BYTE -> append8BitBytes(content.getBytes(encoding), bits);
            case KANJI -> appendKanjiBytes(content, bits);
            default -> throw new IllegalArgumentException("Invalid mode: " + mode);
        }
    }

    /** Three digits in ten bits, then two in seven or one in four. */
    static void appendNumericBytes(CharSequence content, BitBuffer bits) {
        int length = content.length();
        int i = 0;
        while (i < length) {
            int num1 = content.charAt(i) - '0';
            if (i + 2 < length) {
                int num2 = content.charAt(i + 1) - '0';
                int num3 = content.charAt(i + 2) - '0';
                bits.appendBits(num1 * 100 + num2 * 10 + num3, 10);
                i += 3;
            } else if (i + 1 < length) {
                int num2 = content.charAt(i + 1) - '0';
                bits.appendBits(num1 * 10 + num2, 7);
                i += 2;
            } else {
                bits.appendBits(num1, 4);
                i++;
            }
        }
    }

    /** Two characters in 11 bits, a last one in six. */
    static void appendAlphanumericBytes(CharSequence content, BitBuffer bits) {
        int length = content.length();
        int i = 0;
        while (i < length) {
            int code1 = getAlphanumericCode(content.charAt(i));
            if (code1 == -1) {
                throw new IllegalArgumentException("Not an alphanumeric mode character: " + content.charAt(i));
            }
            if (i + 1 < length) {
                int code2 = getAlphanumericCode(content.charAt(i + 1));
                if (code2 == -1) {
                    throw new IllegalArgumentException("Not an alphanumeric mode character: " + content.charAt(i + 1));
                }
                bits.appendBits(code1 * 45 + code2, 11);
                i += 2;
            } else {
                bits.appendBits(code1, 6);
                i++;
            }
        }
    }

    static void append8BitBytes(byte[] bytes, BitBuffer bits) {
        for (byte b : bytes) {
            bits.appendBits(b, 8);
        }
    }

    /** Each Shift_JIS pair in 13 bits. */
    static void appendKanjiBytes(String content, BitBuffer bits) {
        byte[] bytes = content.getBytes(SHIFT_JIS);
        if (bytes.length % 2 != 0) {
            throw new IllegalArgumentException("Kanji byte size not even");
        }
        int maxI = bytes.length - 1;
        for (int i = 0; i < maxI; i += 2) {
            int byte1 = bytes[i] & 0xFF;
            int byte2 = bytes[i + 1] & 0xFF;
            int code = (byte1 << 8) | byte2;
            int subtracted = -1;
            if (code >= 0x8140 && code <= 0x9ffc) {
                subtracted = code - 0x8140;
            } else if (code >= 0xe040 && code <= 0xebbf) {
                subtracted = code - 0xc140;
            }
            if (subtracted == -1) {
                throw new IllegalArgumentException("Invalid byte sequence");
            }
            int encoded = ((subtracted >> 8) * 0xc0) + (subtracted & 0xff);
            bits.appendBits(encoded, 13);
        }
    }

    /** The ECI mode and an assignment number below 128, in eight bits. */
    private static void appendEci(int value, BitBuffer bits) {
        bits.appendBits(QrMode.ECI.getBits(), 4);
        bits.appendBits(value, 8);
    }
}
