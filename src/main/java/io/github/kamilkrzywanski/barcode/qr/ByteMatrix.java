package io.github.kamilkrzywanski.barcode.qr;

import java.util.Arrays;

/**
 * The QR matrix while it is built: 0 is light, 1 is dark and -1 is not yet set (ZXing's
 * {@code ByteMatrix}).
 */
final class ByteMatrix {
    private final byte[][] bytes;
    private final int width;
    private final int height;

    ByteMatrix(int width, int height) {
        bytes = new byte[height][width];
        this.width = width;
        this.height = height;
    }

    int getHeight() {
        return height;
    }

    int getWidth() {
        return width;
    }

    byte get(int x, int y) {
        return bytes[y][x];
    }

    /** Row-major; {@code array[y][x]} is the point (x, y). */
    byte[][] getArray() {
        return bytes;
    }

    void set(int x, int y, int value) {
        bytes[y][x] = (byte) value;
    }

    void set(int x, int y, boolean value) {
        bytes[y][x] = (byte) (value ? 1 : 0);
    }

    void clear(byte value) {
        for (byte[] row : bytes) {
            Arrays.fill(row, value);
        }
    }
}
