package org.krzywanski.barcode.decode;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.NotFoundException;
import com.google.zxing.common.BitArray;
import org.krzywanski.barcode.decode.Code39Decoder.Span;

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
        BitArray row = new BitArray(width);
        for (int step = 0; step < height; step++) {
            int away = (step + 1) / 2;
            boolean above = (step & 1) == 0;
            int y = middle + rowStep * (above ? away : -away);
            if (y < 0 || y >= height) {
                break;
            }
            try {
                row = image.getBlackRow(y, row);
            } catch (NotFoundException ignored) {
                continue;
            }
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

    private static Found decode(BitArray row, int y, boolean upsideDown) {
        boolean[] black = bits(row);
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

    private static boolean[] bits(BitArray row) {
        boolean[] black = new boolean[row.getSize()];
        for (int x = 0; x < black.length; x++) {
            black[x] = row.get(x);
        }
        return black;
    }

    private static BitArray reversed(BitArray row) {
        boolean[] black = bits(row);
        BitArray out = new BitArray(black.length);
        for (int x = 0; x < black.length; x++) {
            if (black[black.length - 1 - x]) {
                out.set(x);
            }
        }
        return out;
    }
}
