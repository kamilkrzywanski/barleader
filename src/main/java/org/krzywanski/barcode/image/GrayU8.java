package org.krzywanski.barcode.image;

/** 8-bit grayscale. One row is {@link #stride} bytes starting at {@link #startIndex}. */
public final class GrayU8 {
    public final byte[] data;
    public final int width;
    public final int height;
    public final int stride;
    public final int startIndex;

    public GrayU8(int width, int height) {
        if (width < 1 || height < 1) {
            throw new IllegalArgumentException("image must be at least 1x1");
        }
        this.width = width;
        this.height = height;
        this.stride = width;
        this.startIndex = 0;
        this.data = new byte[Math.multiplyExact(width, height)];
    }

    public int get(int x, int y) {
        return data[startIndex + y * stride + x] & 0xFF;
    }

    public void set(int x, int y, int value) {
        data[startIndex + y * stride + x] = (byte) value;
    }
}
