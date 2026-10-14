package io.github.kamilkrzywanski.barcode.decode;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.NotFoundException;
import com.google.zxing.common.BitArray;
import io.github.kamilkrzywanski.barcode.Symbology;
import io.github.kamilkrzywanski.barcode.decode.UpcEanDecoder.Kind;
import io.github.kamilkrzywanski.barcode.decode.UpcEanDecoder.Span;

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
    record Found(Symbology symbology, String text, int left, int right, int row, boolean upsideDown) {
    }

    private UpcEanScanner() {
    }

    static Found read(BinaryBitmap image, Set<Symbology> symbologies) {
        if (image == null || image.getWidth() < 2 || image.getHeight() < 2 || !wanted(symbologies)) {
            return null;
        }
        return scan(image, symbologies);
    }

    private static boolean wanted(Set<Symbology> symbologies) {
        return symbologies.contains(Symbology.EAN_13)
                || symbologies.contains(Symbology.EAN_8)
                || symbologies.contains(Symbology.UPC_A)
                || symbologies.contains(Symbology.UPC_E);
    }

    private static Found scan(BinaryBitmap image, Set<Symbology> symbologies) {
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
            Found forward = decode(row, y, false, symbologies);
            if (forward != null) {
                return forward;
            }
            Found reversed = decode(reversed(row), y, true, symbologies);
            if (reversed != null) {
                return reversed;
            }
        }
        return null;
    }

    private static Found decode(BitArray row, int y, boolean upsideDown, Set<Symbology> symbologies) {
        boolean[] black = bits(row);
        Span span = UpcEanDecoder.decodeSpan(black);
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
        return new Found(symbology, text, left, right, y, upsideDown);
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
