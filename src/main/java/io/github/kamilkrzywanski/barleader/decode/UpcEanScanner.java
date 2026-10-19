/*
 * Copyright 2008 ZXing authors
 * Copyright 2026 Kamil Krzywański
 */
// SPDX-License-Identifier: Apache-2.0
package io.github.kamilkrzywanski.barleader.decode;

import io.github.kamilkrzywanski.barleader.EanAddOn;
import io.github.kamilkrzywanski.barleader.Symbology;
import io.github.kamilkrzywanski.barleader.decode.UpcEanDecoder.Kind;
import io.github.kamilkrzywanski.barleader.decode.UpcEanDecoder.Span;
import io.github.kamilkrzywanski.barleader.image.BinaryBitmap;

import java.util.Set;

/**
 * Row scan for {@link UpcEanDecoder}, matching the middle-out loop in ZXing
 * {@code OneDReader} with {@code TRY_HARDER}. A reversed row is the upside-down
 * attempt. Orthogonal rotation stays on the scan ladder.
 *
 * <p>When no row reads, the rows are scanned again with their edges in quarter pixels, as
 * {@link Code128Scanner} does; the rows that confirm such a hit are read the same way.
 *
 * <p>An EAN-13 whose first digit is {@code 0} is reported as UPC-A when that
 * format was requested, and the leading zero is removed. UPC-E stays eight digits.
 */
final class UpcEanScanner {
    /**
     * One retail symbol. {@code left} and {@code right} are inclusive columns on {@code row};
     * {@code scale} is that of the runs it was read from, 1 for whole pixels.
     */
    record Found(Symbology symbology, String text, int left, int right, int row, boolean upsideDown, String addOn, int scale) {
    }

    private UpcEanScanner() {
    }

    static Found read(BinaryBitmap image, Set<Symbology> symbologies) {
        return read(image, symbologies, EanAddOn.IGNORE);
    }

    /** {@link #read(BinaryBitmap, Set)} with a supplement read, or required, as {@code addOn} says. */
    static Found read(BinaryBitmap image, Set<Symbology> symbologies, EanAddOn addOn) {
        if (image == null || image.getWidth() < 2 || image.getHeight() < 2 || !wanted(symbologies)) {
            return null;
        }
        Found found = scan(image, symbologies, addOn, 1);
        return found != null ? found : scan(image, symbologies, addOn, OneDRow.FINE);
    }

    private static boolean wanted(Set<Symbology> symbologies) {
        return symbologies.contains(Symbology.EAN_13)
                || symbologies.contains(Symbology.EAN_8)
                || symbologies.contains(Symbology.UPC_A)
                || symbologies.contains(Symbology.UPC_E);
    }

    /**
     * Whether a row other than {@code found.row()} reads the same symbology and text, scanning
     * middle-out with the same step as {@link #read}. This is zxing-cpp's {@code minLineCount}
     * of two: a symbol counts once two scan lines agree.
     */
    static boolean seenOnAnotherRow(BinaryBitmap image, Set<Symbology> symbologies, Found found) {
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
            if (y == found.row()) {
                continue;
            }
            int[] runs = image.runs(y, false, found.scale());
            if (runs == null) {
                continue;
            }
            for (Found again : new Found[]{
                    decode(runs, image.getWidth(), y, false, symbologies, EanAddOn.IGNORE, found.scale()),
                    decode(image.runs(y, true, found.scale()), image.getWidth(), y, true, symbologies, EanAddOn.IGNORE, found.scale())}) {
                if (again != null && again.symbology() == found.symbology() && again.text().equals(found.text())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** {@code scale} 1 reads the whole-pixel runs, another scale the runs with edges in {@code 1 / scale} pixel. */
    private static Found scan(BinaryBitmap image, Set<Symbology> symbologies, EanAddOn addOn, int scale) {
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
            Found forward = decode(runs, image.getWidth(), y, false, symbologies, addOn, scale);
            if (forward != null) {
                return withSupplement(image, symbologies, addOn, forward);
            }
            Found reversed = decode(image.runs(y, true, scale), image.getWidth(), y, true, symbologies, addOn, scale);
            if (reversed != null) {
                return withSupplement(image, symbologies, addOn, reversed);
            }
        }
        return null;
    }

    /**
     * In {@link EanAddOn#READ} the first row may run under a short supplement. Another row with
     * the same symbol and a supplement is then preferred, the way zxing-cpp keeps the result that
     * more of its lines agree on.
     */
    private static Found withSupplement(BinaryBitmap image, Set<Symbology> symbologies, EanAddOn addOn, Found found) {
        if (addOn != EanAddOn.READ || !found.addOn().isEmpty()) {
            return found;
        }
        int height = image.getHeight();
        for (int y = 0; y < height; y++) {
            if (y == found.row()) {
                continue;
            }
            int[] runs = image.runs(y, false, found.scale());
            if (runs == null) {
                continue;
            }
            for (Found again : new Found[]{
                    decode(runs, image.getWidth(), y, false, symbologies, EanAddOn.REQUIRE, found.scale()),
                    decode(image.runs(y, true, found.scale()), image.getWidth(), y, true, symbologies, EanAddOn.REQUIRE, found.scale())}) {
                if (again != null && again.symbology() == found.symbology() && again.text().equals(found.text())) {
                    return again;
                }
            }
        }
        return found;
    }

    /** {@code runs} in {@code 1 / scale} pixel; the found columns are whole pixels. */
    private static Found decode(int[] runs, int width, int y, boolean upsideDown, Set<Symbology> symbologies, EanAddOn addOn, int scale) {
        Span span = UpcEanDecoder.decodeSpan(runs, addOn != EanAddOn.IGNORE, addOn == EanAddOn.REQUIRE);
        if (span == null) {
            return null;
        }
        Symbology symbology;
        String text;
        if (span.kind() == Kind.EAN_13) {
            if (span.text().charAt(0) == '0' && symbologies.contains(Symbology.UPC_A)) {
                symbology = Symbology.UPC_A;
                text = span.text().substring(1);
            } else if (symbologies.contains(Symbology.EAN_13)) {
                symbology = Symbology.EAN_13;
                text = span.text();
            } else {
                return null;
            }
        } else if (span.kind() == Kind.EAN_8) {
            if (!symbologies.contains(Symbology.EAN_8)) {
                return null;
            }
            symbology = Symbology.EAN_8;
            text = span.text();
        } else if (symbologies.contains(Symbology.UPC_E)) {
            symbology = Symbology.UPC_E;
            text = span.text();
        } else {
            return null;
        }
        int left = span.left() / scale;
        int right = span.right() / scale;
        if (upsideDown) {
            left = width - 1 - span.right() / scale;
            right = width - 1 - span.left() / scale;
        }
        if (right < left || text.isBlank()) {
            return null;
        }
        return new Found(symbology, text, left, right, y, upsideDown, span.addOn(), scale);
    }

}
