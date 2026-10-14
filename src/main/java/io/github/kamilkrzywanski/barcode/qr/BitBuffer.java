package io.github.kamilkrzywanski.barcode.qr;

import java.util.Arrays;

/**
 * A growing sequence of bits, most significant first within each appended value: the part of
 * ZXing's {@code BitArray} the QR encoder writes with.
 */
final class BitBuffer {
    private boolean[] bits = new boolean[64];
    private int size;

    int getSize() {
        return size;
    }

    int getSizeInBytes() {
        return (size + 7) / 8;
    }

    boolean get(int i) {
        return bits[i];
    }

    void appendBit(boolean bit) {
        ensureCapacity(size + 1);
        bits[size++] = bit;
    }

    /** The low {@code numBits} bits of {@code value}, highest first. */
    void appendBits(int value, int numBits) {
        if (numBits < 0 || numBits > 32) {
            throw new IllegalArgumentException("Num bits must be between 0 and 32");
        }
        ensureCapacity(size + numBits);
        for (int numBitsLeft = numBits - 1; numBitsLeft >= 0; numBitsLeft--) {
            bits[size++] = (value & (1 << numBitsLeft)) != 0;
        }
    }

    void appendBitArray(BitBuffer other) {
        ensureCapacity(size + other.size);
        System.arraycopy(other.bits, 0, bits, size, other.size);
        size += other.size;
    }

    void xor(BitBuffer other) {
        if (size != other.size) {
            throw new IllegalArgumentException("Sizes don't match");
        }
        for (int i = 0; i < size; i++) {
            bits[i] ^= other.bits[i];
        }
    }

    /** {@code numBytes} bytes from bit {@code bitOffset} on, the first bit as the high bit of a byte. */
    void toBytes(int bitOffset, byte[] array, int offset, int numBytes) {
        for (int i = 0; i < numBytes; i++) {
            int theByte = 0;
            for (int j = 0; j < 8; j++) {
                if (get(bitOffset)) {
                    theByte |= 1 << (7 - j);
                }
                bitOffset++;
            }
            array[offset + i] = (byte) theByte;
        }
    }

    private void ensureCapacity(int needed) {
        if (needed > bits.length) {
            bits = Arrays.copyOf(bits, Math.max(needed, bits.length * 2));
        }
    }

    @Override
    public String toString() {
        StringBuilder out = new StringBuilder(size + (size / 8) + 1);
        for (int i = 0; i < size; i++) {
            if ((i & 0x07) == 0) {
                out.append(' ');
            }
            out.append(get(i) ? 'X' : '.');
        }
        return out.toString();
    }
}
