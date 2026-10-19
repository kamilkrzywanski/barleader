/*
 * Copyright 2013 ZXing authors
 * Copyright 2026 Kamil Krzywański
 */
// SPDX-License-Identifier: Apache-2.0
package io.github.kamilkrzywanski.barleader.encode.aztec;

import io.github.kamilkrzywanski.barleader.encode.BitBuffer;

/**
 * Ported from ZXing 3.5.4 {@code com.google.zxing.aztec.encoder.Token} (Apache License 2.0, the ZXing authors).
 */
abstract class Token {

    static final Token EMPTY = new SimpleToken(null, 0, 0);

    private final Token previous;

    Token(Token previous) {
        this.previous = previous;
    }

    final Token getPrevious() {
        return previous;
    }

    final Token add(int value, int bitCount) {
        return new SimpleToken(this, value, bitCount);
    }

    final Token addBinaryShift(int start, int byteCount) {
        //int bitCount = (byteCount * 8) + (byteCount <= 31 ? 10 : byteCount <= 62 ? 20 : 21);
        return new BinaryShiftToken(this, start, byteCount);
    }

    abstract void appendTo(BitBuffer bitArray, byte[] text);

}
