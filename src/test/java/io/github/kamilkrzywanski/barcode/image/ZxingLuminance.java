package io.github.kamilkrzywanski.barcode.image;

import com.google.zxing.client.j2se.BufferedImageLuminanceSource;

import java.awt.image.BufferedImage;

/**
 * The luminance ZXing's {@code BufferedImageLuminanceSource} computes from an image, as a source
 * for this library's binarizers, so a test feeds the decoders the same pixels it always did.
 */
public final class ZxingLuminance {
    private ZxingLuminance() {
    }

    public static GrayLuminanceSource of(BufferedImage image) {
        BufferedImageLuminanceSource zxing = new BufferedImageLuminanceSource(image);
        return new GrayLuminanceSource(new GrayU8(zxing.getMatrix(), zxing.getWidth(), zxing.getHeight()));
    }
}
