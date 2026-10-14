package io.github.kamilkrzywanski.barcode.decode;

import io.github.kamilkrzywanski.barcode.EanAddOn;
import io.github.kamilkrzywanski.barcode.Symbology;
import io.github.kamilkrzywanski.barcode.decode.UpcEanDecoder.Kind;
import io.github.kamilkrzywanski.barcode.decode.UpcEanDecoder.Span;
import io.github.kamilkrzywanski.barcode.image.BinaryBitmap;

import java.util.Set;

/**
 * Row scan for {@link UpcEanDecoder}, matching the middle-out loop in ZXing
 * {@code OneDReader} with {@code TRY_HARDER}. A reversed row is the upside-down
 * attempt. Orthogonal rotation stays on the scan ladder.
 *
 * <p>An EAN-13 whose first digit is {@code 0} is reported as UPC-A when that
 * format was requested, and the leading zero is removed. UPC-E stays eight digits.
 */
final class UpcEanScanner {
    /** One retail symbol. {@code left} and {@code right} are inclusive columns on {@code row}. */
    record Found(Symbology symbology, String text, int left, int right, int row, boolean upsideDown, String addOn) {
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
        return scan(image, symbologies, addOn);
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
        boolean[] row = null;
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
            boolean[] black = image.getBlackRow(y, row);
            if (black == null) {
                continue;
            }
            row = black;
            for (Found again : new Found[]{
                    decode(row, y, false, symbologies, EanAddOn.IGNORE),
                    decode(reversed(row), y, true, symbologies, EanAddOn.IGNORE)}) {
                if (again != null && again.symbology() == found.symbology() && again.text().equals(found.text())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Found scan(BinaryBitmap image, Set<Symbology> symbologies, EanAddOn addOn) {
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
            Found forward = decode(row, y, false, symbologies, addOn);
            if (forward != null) {
                return withSupplement(image, symbologies, addOn, forward);
            }
            Found reversed = decode(reversed(row), y, true, symbologies, addOn);
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
        boolean[] row = null;
        for (int y = 0; y < height; y++) {
            if (y == found.row()) {
                continue;
            }
            boolean[] black = image.getBlackRow(y, row);
            if (black == null) {
                continue;
            }
            row = black;
            for (Found again : new Found[]{
                    decode(row, y, false, symbologies, EanAddOn.REQUIRE),
                    decode(reversed(row), y, true, symbologies, EanAddOn.REQUIRE)}) {
                if (again != null && again.symbology() == found.symbology() && again.text().equals(found.text())) {
                    return again;
                }
            }
        }
        return found;
    }

    private static Found decode(boolean[] black, int y, boolean upsideDown, Set<Symbology> symbologies, EanAddOn addOn) {
        Span span = UpcEanDecoder.decodeSpan(black, addOn != EanAddOn.IGNORE, addOn == EanAddOn.REQUIRE);
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
        int left = span.left();
        int right = span.right();
        if (upsideDown) {
            left = black.length - 1 - span.right();
            right = black.length - 1 - span.left();
        }
        if (right < left || text.isBlank()) {
            return null;
        }
        return new Found(symbology, text, left, right, y, upsideDown, span.addOn());
    }

    private static boolean[] reversed(boolean[] black) {
        boolean[] out = new boolean[black.length];
        for (int x = 0; x < black.length; x++) {
            out[x] = black[black.length - 1 - x];
        }
        return out;
    }
}
