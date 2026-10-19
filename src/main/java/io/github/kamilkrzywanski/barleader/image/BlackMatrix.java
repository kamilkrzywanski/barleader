/*
 * Copyright 2026 Kamil Krzywański
 */
// SPDX-License-Identifier: Apache-2.0
package io.github.kamilkrzywanski.barleader.image;

/**
 * A binarized image, {@code true} is black, as one boolean array a row. One boolean a pixel, as
 * zxing-cpp keeps one byte a pixel in its {@code BitMatrix}: the readers take a pixel or a row
 * without unpacking 32-bit words, and the PDF417 and DataBar readers take the rows themselves.
 */
public final class BlackMatrix {
    private final int width;
    private final int height;
    private final boolean[][] rows;

    BlackMatrix(int width, int height) {
        this.width = width;
        this.height = height;
        this.rows = new boolean[height][width];
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public boolean get(int x, int y) {
        return rows[y][x];
    }

    void set(int x, int y) {
        rows[y][x] = true;
    }

    /** Copies row {@code y} into {@code out}, from {@code offset}. */
    public void copyRow(int y, boolean[] out, int offset) {
        System.arraycopy(rows[y], 0, out, offset, width);
    }

    /**
     * The rows themselves, shared by every reader of the attempt: a reader that changes a module
     * copies the matrix first, as the matrix readers do.
     */
    public boolean[][] rows() {
        return rows;
    }

    /** Row {@code y} itself, shared as {@link #rows()} is. */
    boolean[] row(int y) {
        return rows[y];
    }
}
