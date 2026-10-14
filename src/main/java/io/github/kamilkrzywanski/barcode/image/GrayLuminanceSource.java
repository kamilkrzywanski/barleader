package io.github.kamilkrzywanski.barcode.image;

import io.github.kamilkrzywanski.barcode.image.GrayU8;
import com.google.zxing.LuminanceSource;

/**
 * Row-major view of a {@link GrayU8} for ZXing. Crop and 90° rotation are supported
 * because several ZXing detectors call them while searching.
 */
public final class GrayLuminanceSource extends LuminanceSource {
    private final byte[] luminances;
    private final int dataWidth;
    private final int left;
    private final int top;

    public GrayLuminanceSource(GrayU8 image) {
        this(copy(image), image.width, 0, 0, image.width, image.height);
    }

    private GrayLuminanceSource(byte[] luminances, int dataWidth, int left, int top, int width, int height) {
        super(width, height);
        if (left < 0 || top < 0 || width < 0 || height < 0 || left + width > dataWidth || (top + height) * (long) dataWidth > luminances.length) {
            throw new IllegalArgumentException("crop rectangle does not fit");
        }
        this.luminances = luminances;
        this.dataWidth = dataWidth;
        this.left = left;
        this.top = top;
    }

    @Override
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

    @Override
    public byte[] getMatrix() {
        int width = getWidth();
        int height = getHeight();
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

    @Override
    public boolean isCropSupported() {
        return true;
    }

    @Override
    public LuminanceSource crop(int left, int top, int width, int height) {
        return new GrayLuminanceSource(luminances, dataWidth, this.left + left, this.top + top, width, height);
    }

    @Override
    public boolean isRotateSupported() {
        return true;
    }

    @Override
    public LuminanceSource rotateCounterClockwise() {
        int width = getWidth();
        int height = getHeight();
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

    private static byte[] copy(GrayU8 image) {
        byte[] out = new byte[image.width * image.height];
        for (int y = 0; y < image.height; y++) {
            System.arraycopy(image.data, image.startIndex + y * image.stride, out, y * image.width, image.width);
        }
        return out;
    }
}
