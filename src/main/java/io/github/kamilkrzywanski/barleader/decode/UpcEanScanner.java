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

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Row scan for {@link UpcEanDecoder}, matching the middle-out loop in ZXing
 * {@code OneDReader} with {@code TRY_HARDER}. A reversed row is the upside-down
 * attempt. Orthogonal rotation stays on the scan ladder.
 *
 * <p>When no row decodes, the rows are scanned again with their edges in quarter pixels, as
 * {@link Code128Scanner} does; the rows that confirm such a hit are read the same way.
 *
 * <p>An EAN-13 whose first digit is {@code 0} is reported as UPC-A when that
 * format was requested, and the leading zero is removed. UPC-E stays eight digits.
 */
final class UpcEanScanner {
    /**
     * One retail symbol. {@code left} and {@code right} are inclusive columns on {@code row};
     * {@code scale} is that of the runs it was read from, 1 for whole pixels. {@code late} marks a
     * read behind a guard of its row that did not hold, or after a row whose read from its first
     * guard was refused, where the scan used to end.
     */
    record Found(Symbology symbology, String text, int left, int right, int row, boolean upsideDown, String addOn, int scale,
            boolean late) {
        Found asLate() {
            return new Found(symbology, text, left, right, row, upsideDown, addOn, scale, true);
        }
    }

    private UpcEanScanner() {
    }

    static Found read(BinaryBitmap image, Set<Symbology> symbologies) {
        return read(image, symbologies, EanAddOn.IGNORE);
    }

    /** {@link #read(BinaryBitmap, Set)} with a supplement read, or required, as {@code addOn} says. */
    static Found read(BinaryBitmap image, Set<Symbology> symbologies, EanAddOn addOn) {
        return read(image, symbologies, addOn, found -> true);
    }

    /**
     * The first symbol {@code accept} takes. At whole pixels a row whose read is refused does not
     * end the scan, as zxing-cpp keeps scanning until a symbol has been seen on {@code minLineCount}
     * rows: one row of a glitch in the middle of the image cost the attempt the symbol on the rows
     * around it. A text refused {@link #REFUSALS_A_TEXT} times is not asked about again, and after
     * {@link #REFUSALS} refusals the scan is over.
     *
     * <p>The rows are read with their edges in quarter pixels only when no whole-pixel row decoded
     * from its first guard, and there such a row, refused, still ends the scan, both as before: a
     * blurred symbol read backwards gave the same wrong text on several of those rows, which then
     * confirmed each other. A read behind a guard that did not hold, or after a row refused what
     * it read from its first guard, is {@link Found#late()}: one the scan could not make before,
     * which {@code accept} can ask more of, as the attempts ask it to be an EAN-13 or UPC-A that
     * no other text of the bitmap outvotes.
     */
    static Found read(BinaryBitmap image, Set<Symbology> symbologies, EanAddOn addOn, Predicate<Found> accept) {
        return read(image, symbologies, addOn, accept, true);
    }

    /**
     * {@link #read(BinaryBitmap, Set, EanAddOn, Predicate)} that without {@code late} makes no late
     * read: a row is read from its first guard only, and the first row that decodes decides at
     * whole pixels too. A thresholded image is read so, as every image was: its rows repeat one
     * merged pair of bars, and late reads there gave a wrong left half beside the right half of
     * the label on four photos.
     */
    static Found read(BinaryBitmap image, Set<Symbology> symbologies, EanAddOn addOn, Predicate<Found> accept, boolean late) {
        if (image == null || image.getWidth() < 2 || image.getHeight() < 2 || !wanted(symbologies)) {
            return null;
        }
        boolean[] decoded = new boolean[1];
        Found found = scan(image, symbologies, addOn, 1, decoded, accept, !late, late);
        if (found != null || decoded[0]) {
            return found;
        }
        return scan(image, symbologies, addOn, OneDRow.FINE, decoded, accept, true, late);
    }

    /**
     * How often a scan asks about one text, and how many refusals end it. Whether a read counts
     * depends on its row: a short code has to repeat on the strip beside it, and a read behind a
     * guard that did not hold counts only as an EAN-13 or UPC-A. With two asks, two such rows used
     * up a text before the row that counts: two EAN-8 of tapirscan's benchmark went unread.
     */
    private static final int REFUSALS_A_TEXT = 3;
    private static final int REFUSALS = 8;

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
    static boolean seenOnAnotherRow(BinaryBitmap image, Set<Symbology> symbologies, Found found, boolean everyGuard) {
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
                    decode(runs, image.getWidth(), y, false, symbologies, EanAddOn.IGNORE, found.scale(), everyGuard),
                    decode(image.runs(y, true, found.scale()), image.getWidth(), y, true, symbologies, EanAddOn.IGNORE, found.scale(),
                            everyGuard)}) {
                if (again != null && again.symbology() == found.symbology() && again.text().equals(found.text())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * {@code scale} 1 reads the whole-pixel runs, another scale the runs with edges in {@code 1 / scale}
     * pixel. {@code decoded} is set once a row decoded from its first guard, whatever became of the
     * read; with {@code firstDecides} such a read, refused, ends the scan. Without {@code everyGuard}
     * a row is read from its first guard only.
     */
    private static Found scan(BinaryBitmap image, Set<Symbology> symbologies, EanAddOn addOn, int scale, boolean[] decoded,
            Predicate<Found> accept, boolean firstDecides, boolean everyGuard) {
        int height = image.getHeight();
        int rowStep = Math.max(1, height >> 8);
        int middle = height / 2;
        Map<String, Integer> refused = new HashMap<>();
        int refusals = 0;
        boolean firstRefused = false;
        for (int step = 0; step < height; step++) {
            int away = (step + 1) / 2;
            boolean above = (step & 1) == 0;
            int y = middle + rowStep * (above ? away : -away);
            if (y < 0 || y >= height) {
                break;
            }
            if (image.runs(y, false, scale) == null) {
                continue;
            }
            for (boolean upsideDown : new boolean[]{false, true}) {
                Found found = decode(image.runs(y, upsideDown, scale), image.getWidth(), y, upsideDown, symbologies, addOn, scale,
                        everyGuard);
                if (found == null) {
                    continue;
                }
                boolean first = !found.late();
                decoded[0] |= first;
                found = withSupplement(image, symbologies, addOn, found, everyGuard);
                if ((!first || firstRefused) && !found.late()) {
                    found = found.asLate();
                }
                String key = found.symbology() + found.text();
                int times = refused.getOrDefault(key, 0);
                if (times < REFUSALS_A_TEXT && accept.test(found)) {
                    return found;
                }
                if (first && firstDecides) {
                    return null;
                }
                firstRefused |= first;
                refused.put(key, times + 1);
                if (++refusals >= REFUSALS) {
                    return null;
                }
            }
        }
        return null;
    }

    /**
     * In {@link EanAddOn#READ} the first row may run under a short supplement. Another row with
     * the same symbol and a supplement is then preferred, the way zxing-cpp keeps the result that
     * more of its lines agree on.
     */
    private static Found withSupplement(BinaryBitmap image, Set<Symbology> symbologies, EanAddOn addOn, Found found,
            boolean everyGuard) {
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
                    decode(runs, image.getWidth(), y, false, symbologies, EanAddOn.REQUIRE, found.scale(), everyGuard),
                    decode(image.runs(y, true, found.scale()), image.getWidth(), y, true, symbologies, EanAddOn.REQUIRE, found.scale(),
                            everyGuard)}) {
                if (again != null && again.symbology() == found.symbology() && again.text().equals(found.text())) {
                    return again;
                }
            }
        }
        return found;
    }

    /** {@code runs} in {@code 1 / scale} pixel; the found columns are whole pixels. */
    private static Found decode(int[] runs, int width, int y, boolean upsideDown, Set<Symbology> symbologies, EanAddOn addOn, int scale,
            boolean everyGuard) {
        Span span = UpcEanDecoder.decodeSpan(runs, addOn != EanAddOn.IGNORE, addOn == EanAddOn.REQUIRE, everyGuard);
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
        return new Found(symbology, text, left, right, y, upsideDown, span.addOn(), scale, span.later());
    }

}
