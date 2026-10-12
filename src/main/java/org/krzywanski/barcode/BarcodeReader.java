package org.krzywanski.barcode;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Objects;

/**
 * Pure Java barcode reader. Matrix codes and the priority linear symbols are decoded here.
 * ZXing still supplies the original-image hybrid binarizer, Reed-Solomon, PDF417 error
 * correction, and any format outside that set.
 *
 * <p>A single instance may be shared across threads. Each {@link #read} call keeps
 * its own decoder state.
 */
public final class BarcodeReader {
    public List<BarcodeHit> read(BufferedImage image) {
        return read(image, ReadOptions.fast());
    }

    public List<BarcodeHit> read(BufferedImage image, ReadOptions options) {
        Objects.requireNonNull(image, "image");
        Objects.requireNonNull(options, "options");
        return new Scan(options).run(image);
    }
}
