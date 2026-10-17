/*
 * Copyright 2008 ZXing authors
 * Copyright 2026 Kamil Krzywański
 */
// SPDX-License-Identifier: Apache-2.0
package io.github.kamilkrzywanski.barcode.decode;

import io.github.kamilkrzywanski.barcode.decode.Code128Decoder.Span;
import io.github.kamilkrzywanski.barcode.image.BinaryBitmap;

import java.util.function.Predicate;

/**
 * Row scan for {@link Code128Decoder}, matching the middle-out loop in ZXing
 * {@code OneDReader} with {@code TRY_HARDER}. A reversed row is the upside-down
 * attempt. Orthogonal rotation stays on the scan ladder, which already turns
 * the image by 90, 180, and 270 degrees. When no row reads, the rows are scanned again with
 * their edges in quarter pixels.
 */
final class Code128Scanner {
    /**
     * Quarters of a pixel. On BarBeR's ParcelBar photos fast mode then reads 269 instead of 99 of the
     * 536 Code 128 symbols with modules under two pixels, and 708 instead of 418 of all 1,110.
     */
    private static final int FINE = 4;

    /** One Code 128. {@code left} and {@code right} are inclusive columns on {@code row}. */
    record Found(String text, int left, int right, int row, boolean upsideDown) {
    }

    private Code128Scanner() {
    }

    static Found read(BinaryBitmap image) {
        return read(image, found -> true);
    }

    /**
     * The first symbol {@code accept} takes. A row whose read is refused does not end the scan, as
     * zxing-cpp keeps scanning until a symbol has been seen on {@code minLineCount} rows.
     */
    static Found read(BinaryBitmap image, Predicate<Found> accept) {
        if (image == null || image.getWidth() < 2 || image.getHeight() < 2) {
            return null;
        }
        Found found = scan(image, accept, 1);
        return found != null ? found : scan(image, accept, FINE);
    }

    /** {@code scale} 1 reads the shared whole-pixel runs, another scale {@link BinaryBitmap#fineRuns}. */
    private static Found scan(BinaryBitmap image, Predicate<Found> accept, int scale) {
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
            int[] runs = scale == 1 ? image.runs(y, false) : image.fineRuns(y, scale);
            if (runs == null) {
                continue;
            }
            Found forward = decode(runs, image.getWidth(), y, false, scale);
            if (forward != null && accept.test(forward)) {
                return forward;
            }
            Found reversed = decode(scale == 1 ? image.runs(y, true) : reverse(runs), image.getWidth(), y, true, scale);
            if (reversed != null && accept.test(reversed)) {
                return reversed;
            }
        }
        return null;
    }

    private static int[] reverse(int[] runs) {
        int[] out = new int[runs.length];
        for (int i = 0; i < runs.length; i++) {
            out[i] = runs[runs.length - 1 - i];
        }
        return out;
    }

    /** {@code runs} in {@code 1 / scale} pixel; the found columns are whole pixels. */
    private static Found decode(int[] runs, int width, int y, boolean upsideDown, int scale) {
        Span span = Code128Decoder.decodeSpan(runs, scale);
        if (span == null) {
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
