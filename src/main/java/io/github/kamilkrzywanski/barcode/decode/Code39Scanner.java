package io.github.kamilkrzywanski.barcode.decode;

import io.github.kamilkrzywanski.barcode.decode.Code39Decoder.Span;
import io.github.kamilkrzywanski.barcode.image.BinaryBitmap;

/**
 * Row scan for {@link Code39Decoder}, matching the middle-out loop in ZXing
 * {@code OneDReader} with {@code TRY_HARDER}. A reversed row is the upside-down
 * attempt. Orthogonal rotation stays on the scan ladder.
 */
final class Code39Scanner {
    /** One Code 39. {@code left} and {@code right} are inclusive columns on {@code row}. */
    record Found(String text, int left, int right, int row, boolean upsideDown) {
    }

    private Code39Scanner() {
    }

    static Found read(BinaryBitmap image) {
        if (image == null || image.getWidth() < 2 || image.getHeight() < 2) {
            return null;
        }
        return scan(image);
    }

    private static Found scan(BinaryBitmap image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int rowStep = Math.max(1, height >> 8);
        int middle = height / 2;
        boolean[] row = null;
        for (int step = 0; step < height; step++) {
            int away = (step + 1) / 2;
            boolean above = (step & 1) == 0;
            int y = middle + rowStep * (above ? away : -away);
            if (y < 0 || y >= height) {
                break;
            }
            boolean[] black = image.getBlackRow(y, row);
            if (black == null) {
                continue;
            }
            row = black;
            Found forward = decode(row, y, false);
            if (forward != null) {
                return forward;
            }
            Found reversed = decode(reversed(row), y, true);
            if (reversed != null) {
                return reversed;
            }
        }
        return null;
    }

    private static Found decode(boolean[] black, int y, boolean upsideDown) {
        Span span = Code39Decoder.decodeSpan(black);
        if (span == null || span.text() == null || span.text().isBlank()) {
            return null;
        }
        int left = span.left();
        int right = span.right();
        if (upsideDown) {
            left = black.length - 1 - span.right();
            right = black.length - 1 - span.left();
        }
        if (right < left) {
            return null;
        }
        return new Found(span.text(), left, right, y, upsideDown);
    }

    private static boolean[] reversed(boolean[] black) {
        boolean[] out = new boolean[black.length];
        for (int x = 0; x < black.length; x++) {
            out[x] = black[black.length - 1 - x];
        }
        return out;
    }
}
