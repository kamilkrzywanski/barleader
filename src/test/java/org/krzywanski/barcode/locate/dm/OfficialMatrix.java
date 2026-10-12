package org.krzywanski.barcode.locate.dm;

import org.krzywanski.barcode.image.GrayU8;

/**
 * Local-average bits for the official sample locks.
 * The same binarizer the QR, Data Matrix, Aztec, and Micro QR detectors use.
 */
public final class OfficialMatrix {
    private OfficialMatrix() {
    }

    public static boolean[][] from(GrayU8 gray) {
        DmGeometry.Bits bits = DmBinarizer.blackMatrix(gray);
        boolean[][] image = new boolean[bits.height][bits.width];
        for (int y = 0; y < bits.height; y++) {
            for (int x = 0; x < bits.width; x++) {
                image[y][x] = bits.get(x, y);
            }
        }
        return image;
    }

    /** Upright scanline from {@code GlobalHistogramBinarizer::getPatternRow}. Null when the row is flat. */
    public static boolean[] row(GrayU8 gray, int y) {
        return RowHistogram.bits(gray, y);
    }
}
