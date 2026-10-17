/*
 * Copyright 2013 ZXing authors
 * Copyright 2026 Kamil Krzywański
 */
// SPDX-License-Identifier: Apache-2.0
package io.github.kamilkrzywanski.barcode.encode.aztec;

import io.github.kamilkrzywanski.barcode.encode.BitBuffer;

/**
 * Ported from ZXing 3.5.4 {@code com.google.zxing.aztec.encoder.SimpleToken} (Apache License 2.0, the ZXing authors).
 */
final class SimpleToken extends Token {

    // For normal words, indicates value and bitCount
    private final short value;
    private final short bitCount;

    SimpleToken(Token previous, int value, int bitCount) {
        super(previous);
        this.value = (short) value;
        this.bitCount = (short) bitCount;
    }

    @Override
    void appendTo(BitBuffer bitArray, byte[] text) {
        bitArray.appendBits(value, bitCount);
    }

    @Override
    public String toString() {
        int value = this.value & ((1 << bitCount) - 1);
        value |= 1 << bitCount;
        return '<' + Integer.toBinaryString(value | (1 << bitCount)).substring(1) + '>';
    }

}
