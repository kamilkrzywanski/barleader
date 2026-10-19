/*
 * Copyright 2013 ZXing authors
 * Copyright 2026 Kamil Krzywański
 */
// SPDX-License-Identifier: Apache-2.0
package io.github.kamilkrzywanski.barleader.encode.aztec;

import io.github.kamilkrzywanski.barleader.encode.BitBuffer;

/**
 * Ported from ZXing 3.5.4 {@code com.google.zxing.aztec.encoder.BinaryShiftToken} (Apache License 2.0, the ZXing authors).
 */
final class BinaryShiftToken extends Token {

    private final int binaryShiftStart;
    private final int binaryShiftByteCount;

    BinaryShiftToken(Token previous,
                     int binaryShiftStart,
                     int binaryShiftByteCount) {
        super(previous);
        this.binaryShiftStart = binaryShiftStart;
        this.binaryShiftByteCount = binaryShiftByteCount;
    }

    @Override
    public void appendTo(BitBuffer bitArray, byte[] text) {
        int bsbc = binaryShiftByteCount;
        for (int i = 0; i < bsbc; i++) {
            if (i == 0 || (i == 31 && bsbc <= 62)) {
                // We need a header before the first character, and before
                // character 31 when the total byte code is <= 62
                bitArray.appendBits(31, 5);  // BINARY_SHIFT
                if (bsbc > 62) {
                    bitArray.appendBits(bsbc - 31, 16);
                } else if (i == 0) {
                    // 1 <= binaryShiftByteCode <= 62
                    bitArray.appendBits(Math.min(bsbc, 31), 5);
                } else {
                    // 32 <= binaryShiftCount <= 62 and i == 31
                    bitArray.appendBits(bsbc - 31, 5);
                }
            }
            bitArray.appendBits(text[binaryShiftStart + i], 8);
        }
    }

    @Override
    public String toString() {
        return "<" + binaryShiftStart + "::" + (binaryShiftStart + binaryShiftByteCount - 1) + '>';
    }

}
