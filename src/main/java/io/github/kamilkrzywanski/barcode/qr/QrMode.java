/*
 * Copyright 2007 ZXing authors
 * Copyright 2026 Kamil Krzywański
 */
// SPDX-License-Identifier: Apache-2.0
package io.github.kamilkrzywanski.barcode.qr;

/**
 * The data modes a QR Code segment can be in, with their four mode bits and the width of their
 * character count at each version range (ZXing's {@code Mode}).
 */
enum QrMode {
    TERMINATOR(new int[]{0, 0, 0}, 0x00),
    NUMERIC(new int[]{10, 12, 14}, 0x01),
    ALPHANUMERIC(new int[]{9, 11, 13}, 0x02),
    BYTE(new int[]{8, 16, 16}, 0x04),
    ECI(new int[]{0, 0, 0}, 0x07),
    KANJI(new int[]{8, 10, 12}, 0x08),
    FNC1_FIRST_POSITION(new int[]{0, 0, 0}, 0x05);

    private final int[] characterCountBitsForVersions;
    private final int bits;

    QrMode(int[] characterCountBitsForVersions, int bits) {
        this.characterCountBitsForVersions = characterCountBitsForVersions;
        this.bits = bits;
    }

    /** Bits of the character count in {@code version}: versions 1-9, 10-26 and 27-40 differ. */
    int getCharacterCountBits(QrVersion version) {
        int number = version.number();
        int offset = number <= 9 ? 0 : number <= 26 ? 1 : 2;
        return characterCountBitsForVersions[offset];
    }

    int getBits() {
        return bits;
    }
}
