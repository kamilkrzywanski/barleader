/*
 * Copyright 2026 Kamil Krzywański
 */
// SPDX-License-Identifier: Apache-2.0
package io.github.kamilkrzywanski.barcode.image;

/**
 * Row-major view of a {@link GrayU8} for the binarizers, with a crop that shares the pixels.
 */
public final class GrayLuminanceSource {
    private final byte[] luminances;
    private final int width;
    private final int height;
    private final int dataWidth;
    private final int left;
    private final int top;

    /** Shares the pixels of a packed image and copies any other. The binarizers only read them. */
    public GrayLuminanceSource(GrayU8 image) {
        this(packed(image) ? image.data : copy(image), image.width, 0, 0, image.width, image.height);
    }

    private GrayLuminanceSource(byte[] luminances, int dataWidth, int left, int top, int width, int height) {
        if (left < 0 || top < 0 || width < 0 || height < 0 || left + width > dataWidth || (top + height) * (long) dataWidth > luminances.length) {
            throw new IllegalArgumentException("crop rectangle does not fit");
        }
        this.luminances = luminances;
        this.width = width;
        this.height = height;
        this.dataWidth = dataWidth;
        this.left = left;
        this.top = top;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    /** Row {@code y} in {@code row} when it is long enough, else in a new array. */
    public byte[] getRow(int y, byte[] row) {
        if (y < 0 || y >= getHeight()) {
            throw new IllegalArgumentException("row " + y + " outside height " + getHeight());
        }
        if (row == null || row.length < getWidth()) {
            row = new byte[getWidth()];
        }
        System.arraycopy(luminances, (top + y) * dataWidth + left, row, 0, getWidth());
        return row;
    }

    /** The pixels, row-major and packed; the shared array itself when no copy is needed. */
    public byte[] getMatrix() {
        if (width == dataWidth && height * dataWidth == luminances.length && left == 0 && top == 0) {
            return luminances;
        }
        byte[] matrix = new byte[width * height];
        int input = top * dataWidth + left;
        for (int y = 0; y < height; y++) {
            System.arraycopy(luminances, input, matrix, y * width, width);
            input += dataWidth;
        }
        return matrix;
    }

    public GrayLuminanceSource crop(int left, int top, int width, int height) {
        return new GrayLuminanceSource(luminances, dataWidth, this.left + left, this.top + top, width, height);
    }

    public GrayLuminanceSource rotateCounterClockwise() {
        byte[] rotated = new byte[width * height];
        int newWidth = height;
        for (int y = 0; y < height; y++) {
            int row = (top + y) * dataWidth + left;
            for (int x = 0; x < width; x++) {
                rotated[x * newWidth + (newWidth - 1 - y)] = luminances[row + x];
            }
        }
        return new GrayLuminanceSource(rotated, newWidth, 0, 0, newWidth, width);
    }

    private static boolean packed(GrayU8 image) {
        return image.stride == image.width && image.startIndex == 0 && image.data.length == image.width * image.height;
    }

    private static byte[] copy(GrayU8 image) {
        byte[] out = new byte[image.width * image.height];
        for (int y = 0; y < image.height; y++) {
            System.arraycopy(image.data, image.startIndex + y * image.stride, out, y * image.width, image.width);
        }
        return out;
    }
}
