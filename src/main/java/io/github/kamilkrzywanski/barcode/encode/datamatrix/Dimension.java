package io.github.kamilkrzywanski.barcode.encode.datamatrix;

/**
 * Simply encapsulates a width and height.
 *
 * <p>Ported from ZXing 3.5.4 {@code com.google.zxing.Dimension} (Apache License 2.0, the ZXing authors).
 */
public final class Dimension {

    private final int width;
    private final int height;

    public Dimension(int width, int height) {
        if (width < 0 || height < 0) {
            throw new IllegalArgumentException();
        }
        this.width = width;
        this.height = height;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    @Override
    public boolean equals(Object other) {
        if (other instanceof Dimension) {
            Dimension d = (Dimension) other;
            return width == d.width && height == d.height;
        }
        return false;
    }

    @Override
    public int hashCode() {
        return width * 32713 + height;
    }

    @Override
    public String toString() {
        return width + "x" + height;
    }

}
