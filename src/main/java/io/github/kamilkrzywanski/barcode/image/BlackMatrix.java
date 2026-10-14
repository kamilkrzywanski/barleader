package io.github.kamilkrzywanski.barcode.image;

/**
 * A binarized image, row-major, {@code true} is black. One boolean a pixel, as zxing-cpp keeps
 * one byte a pixel in its {@code BitMatrix}: the readers take a pixel or a row without unpacking
 * 32-bit words.
 */
public final class BlackMatrix {
    private final int width;
    private final int height;
    private final boolean[] data;

    BlackMatrix(int width, int height) {
        this.width = width;
        this.height = height;
        this.data = new boolean[width * height];
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public boolean get(int x, int y) {
        return data[y * width + x];
    }

    void set(int x, int y) {
        data[y * width + x] = true;
    }

    /** Copies row {@code y} into {@code out}, from {@code offset}. */
    public void copyRow(int y, boolean[] out, int offset) {
        System.arraycopy(data, y * width, out, offset, width);
    }

    /** A copy of every row. */
    public boolean[][] rows() {
        boolean[][] rows = new boolean[height][width];
        for (int y = 0; y < height; y++) {
            System.arraycopy(data, y * width, rows[y], 0, width);
        }
        return rows;
    }
}
