package io.github.kamilkrzywanski.barcode.image;

import io.github.kamilkrzywanski.barcode.image.GrayU8;

/** A derived grayscale image plus the map back to the source image. */
public final class Transformed {
    private final GrayU8 image;
    private final PixelMap toSource;
    private final int degrees;

    Transformed(GrayU8 image, PixelMap toSource, int degrees) {
        this.image = image;
        this.toSource = toSource;
        this.degrees = degrees;
    }

    public GrayU8 image() {
        return image;
    }

    public PixelMap toSource() {
        return toSource;
    }

    public int degrees() {
        return degrees;
    }
}
