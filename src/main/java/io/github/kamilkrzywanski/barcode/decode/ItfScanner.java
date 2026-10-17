/*
 * Copyright 2008 ZXing authors
 * Copyright 2026 Kamil Krzywański
 */
// SPDX-License-Identifier: Apache-2.0
package io.github.kamilkrzywanski.barcode.decode;

import io.github.kamilkrzywanski.barcode.decode.ItfDecoder.Span;
import io.github.kamilkrzywanski.barcode.image.BinaryBitmap;

/**
 * Row scan for {@link ItfDecoder}, matching the middle-out loop in ZXing
 * {@code OneDReader} with {@code TRY_HARDER}. A reversed row is the upside-down
 * attempt. Orthogonal rotation stays on the scan ladder.
 */
final class ItfScanner {
    /** One ITF symbol. {@code left} and {@code right} are inclusive columns on {@code row}. */
    record Found(String text, int left, int right, int row, boolean upsideDown) {
    }

    private ItfScanner() {
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
        for (int step = 0; step < height; step++) {
            int away = (step + 1) / 2;
            boolean above = (step & 1) == 0;
            int y = middle + rowStep * (above ? away : -away);
            if (y < 0 || y >= height) {
                break;
            }
            int[] runs = image.runs(y, false);
            if (runs == null) {
                continue;
            }
            Found forward = decode(runs, image.getWidth(), y, false);
            if (forward != null) {
                return forward;
            }
            Found reversed = decode(image.runs(y, true), image.getWidth(), y, true);
            if (reversed != null) {
                return reversed;
            }
        }
        return null;
    }

    private static Found decode(int[] runs, int width, int y, boolean upsideDown) {
        Span span = ItfDecoder.decodeSpan(runs);
        if (span == null || span.text() == null || span.text().isBlank()) {
            return null;
        }
        int left = span.left();
        int right = span.right();
        if (upsideDown) {
            left = width - 1 - span.right();
            right = width - 1 - span.left();
        }
        if (right < left) {
            return null;
        }
        return new Found(span.text(), left, right, y, upsideDown);
    }

}
