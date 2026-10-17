/*
 * Copyright 2008 ZXing authors
 * Copyright 2026 Kamil Krzywański
 */
// SPDX-License-Identifier: Apache-2.0
package io.github.kamilkrzywanski.barcode.decode;

import io.github.kamilkrzywanski.barcode.decode.Code39Decoder.Span;
import io.github.kamilkrzywanski.barcode.image.BinaryBitmap;

/**
 * Row scan for {@link Code39Decoder}, matching the middle-out loop in ZXing
 * {@code OneDReader} with {@code TRY_HARDER}. A reversed row is the upside-down
 * attempt. Orthogonal rotation stays on the scan ladder. When no row reads, the rows are
 * scanned again with their edges in quarter pixels, as {@link Code128Scanner} does.
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
        Found found = scan(image, 1);
        return found != null ? found : scan(image, OneDRow.FINE);
    }

    /** {@code scale} 1 reads the whole-pixel runs, another scale the runs with edges in {@code 1 / scale} pixel. */
    private static Found scan(BinaryBitmap image, int scale) {
        int width = image.getWidth();
        int height = image.getHeight();
        int rowStep = Math.max(1, height >> 8);
        int middle = height / 2;
        for (int step = 0; step < height; step++) {
            int away = (step + 1) / 2;
            boolean above = (step & 1) == 0;
            int y = middle + rowStep * (above ? away : -away);
            if (y < 0 || y >= height) {
                break;
            }
            int[] runs = image.runs(y, false, scale);
            if (runs == null) {
                continue;
            }
            Found forward = decode(runs, image.getWidth(), y, false, scale);
            if (forward != null) {
                return forward;
            }
            Found reversed = decode(image.runs(y, true, scale), image.getWidth(), y, true, scale);
            if (reversed != null) {
                return reversed;
            }
        }
        return null;
    }

    /** {@code runs} in {@code 1 / scale} pixel; the found columns are whole pixels. */
    private static Found decode(int[] runs, int width, int y, boolean upsideDown, int scale) {
        Span span = Code39Decoder.decodeSpan(runs);
        if (span == null || span.text() == null || span.text().isBlank()) {
            return null;
        }
        int left = span.left() / scale;
        int right = span.right() / scale;
        if (upsideDown) {
            left = width - 1 - span.right() / scale;
            right = width - 1 - span.left() / scale;
        }
        if (right < left) {
            return null;
        }
        return new Found(span.text(), left, right, y, upsideDown);
    }

}
