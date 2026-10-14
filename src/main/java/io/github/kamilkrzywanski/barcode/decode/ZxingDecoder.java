package io.github.kamilkrzywanski.barcode.decode;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.LuminanceSource;
import com.google.zxing.NotFoundException;
import com.google.zxing.ReaderException;
import com.google.zxing.common.BitArray;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.GlobalHistogramBinarizer;
import com.google.zxing.common.HybridBinarizer;
import io.github.kamilkrzywanski.barcode.BarcodeHit;
import io.github.kamilkrzywanski.barcode.EanAddOn;
import io.github.kamilkrzywanski.barcode.ImagePoint;
import io.github.kamilkrzywanski.barcode.Symbology;
import io.github.kamilkrzywanski.barcode.image.GrayLuminanceSource;
import io.github.kamilkrzywanski.barcode.image.PixelMap;
import io.github.kamilkrzywanski.barcode.locate.dm.MatrixReader;
import io.github.kamilkrzywanski.barcode.image.GrayU8;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * One decode pass over a luminance image. QR, Data Matrix, Aztec, and Micro QR are read by
 * {@link io.github.kamilkrzywanski.barcode.locate.dm.MatrixReader} from this attempt's own black matrix,
 * including that matrix inverted. Code 128 is read by {@link Code128Scanner},
 * Code 39 by {@link Code39Scanner}, Code 93 by {@link Code93Scanner}, ITF by {@link ItfScanner},
 * Codabar by {@link CodabarScanner}, PDF417 by {@link Pdf417ScanningDecoder}, EAN/UPC by
 * {@link UpcEanScanner}, and DataBar by {@link DataBarDecoder} and {@link DataBarExpandedReader}.
 * ZXing only binarizes. A linear hit must repeat on a parallel strip. Matrix hits do not. Angled
 * bars can checksum as a short UPC-E on a single row, and that row is not a barcode.
 */
public final class ZxingDecoder {
    private static final double MIN_LINEAR_SPAN_SQ = 30.0 * 30.0;
    /** Pixels between the row ZXing accepted and the confirming strip. */
    private static final int PARALLEL_SHIFT = 4;
    /** Height of a horizontal confirming strip, or width of a vertical one. */
    private static final int PARALLEL_THICKNESS = 8;
    /** Quiet zone added beyond the decoded endpoints. EAN needs more than ten modules. */
    private static final int PARALLEL_MARGIN = 40;

    private ZxingDecoder() {
    }

    public static List<BarcodeHit> read(
            GrayU8 luma,
            PixelMap toSource,
            String attempt,
            int rotationDegrees,
            double confidence,
            Set<Symbology> symbologies,
            boolean binary,
            boolean findAll
    ) {
        return read(luma, toSource, attempt, rotationDegrees, confidence, symbologies, binary, findAll, EanAddOn.IGNORE);
    }

    /** {@link #read(GrayU8, PixelMap, String, int, double, Set, boolean, boolean)} with an EAN/UPC supplement mode. */
    public static List<BarcodeHit> read(
            GrayU8 luma,
            PixelMap toSource,
            String attempt,
            int rotationDegrees,
            double confidence,
            Set<Symbology> symbologies,
            boolean binary,
            boolean findAll,
            EanAddOn addOn
    ) {
        if (luma.width < 2 || luma.height < 2) {
            return List.of();
        }
        return read(new GrayLuminanceSource(luma), toSource, attempt, rotationDegrees, confidence, symbologies, binary, findAll, addOn);
    }

    public static List<BarcodeHit> read(
            LuminanceSource source,
            PixelMap toSource,
            String attempt,
            int rotationDegrees,
            double confidence,
            Set<Symbology> symbologies,
            boolean binary,
            boolean findAll
    ) {
        return read(source, toSource, attempt, rotationDegrees, confidence, symbologies, binary, findAll, EanAddOn.IGNORE);
    }

    /** {@link #read(LuminanceSource, PixelMap, String, int, double, Set, boolean, boolean)} with an EAN/UPC supplement mode. */
    public static List<BarcodeHit> read(
            LuminanceSource source,
            PixelMap toSource,
            String attempt,
            int rotationDegrees,
            double confidence,
            Set<Symbology> symbologies,
            boolean binary,
            boolean findAll,
            EanAddOn addOn
    ) {
        if ((!wantsOwned(symbologies) && !MatrixReader.wants(symbologies))
                || source.getWidth() < 2
                || source.getHeight() < 2) {
            return List.of();
        }
        BinaryBitmap owned = bitmap(source, binary);
        List<Supplier<List<BarcodeHit>>> byPriority = List.of(
                () -> matrixHits(owned, toSource, attempt, rotationDegrees, confidence, symbologies, findAll),
                () -> pdf417Hits(owned, toSource, attempt, rotationDegrees, confidence, symbologies, findAll),
                () -> eanHits(source, owned, toSource, attempt, rotationDegrees, confidence, symbologies, binary, addOn),
                () -> code39Hits(source, owned, toSource, attempt, rotationDegrees, confidence, symbologies),
                () -> code93Hits(source, owned, toSource, attempt, rotationDegrees, confidence, symbologies),
                () -> code128Hits(source, owned, toSource, attempt, rotationDegrees, confidence, symbologies),
                () -> itfHits(source, owned, toSource, attempt, rotationDegrees, confidence, symbologies),
                () -> codabarHits(source, owned, toSource, attempt, rotationDegrees, confidence, symbologies),
                () -> dataBarHits(owned, toSource, attempt, rotationDegrees, confidence, symbologies)
        );
        // Without findAll one attempt returns one symbol, so the readers after the first hit do not run.
        if (!findAll) {
            for (Supplier<List<BarcodeHit>> reader : byPriority) {
                List<BarcodeHit> hits = reader.get();
                if (!hits.isEmpty()) {
                    return List.of(hits.get(0));
                }
            }
            return List.of();
        }
        List<BarcodeHit> all = new ArrayList<>();
        for (Supplier<List<BarcodeHit>> reader : byPriority) {
            all.addAll(reader.get());
        }
        return all;
    }

    /**
     * QR, Data Matrix, Aztec, and Micro QR from this attempt's binarizer. The matrix is copied
     * before a later ZXing pass can invert it in place. Matrix symbols skip the linear strip.
     */
    private static List<BarcodeHit> matrixHits(
            BinaryBitmap image,
            PixelMap toSource,
            String attempt,
            int rotationDegrees,
            double confidence,
            Set<Symbology> symbologies,
            boolean findAll
    ) {
        if (!MatrixReader.wants(symbologies)) {
            return List.of();
        }
        BitMatrix matrix;
        try {
            matrix = image.getBlackMatrix();
        } catch (ReaderException ignored) {
            return List.of();
        }
        return MatrixReader.read(matrix, toSource, attempt, rotationDegrees, confidence, symbologies, findAll);
    }

    private static boolean wantsOwned(Set<Symbology> symbologies) {
        return wantsDataBar(symbologies)
                || symbologies.contains(Symbology.CODE_128)
                || symbologies.contains(Symbology.CODE_39)
                || symbologies.contains(Symbology.CODE_93)
                || symbologies.contains(Symbology.ITF)
                || symbologies.contains(Symbology.CODABAR)
                || symbologies.contains(Symbology.PDF_417)
                || wantsEan(symbologies);
    }

    private static boolean wantsEan(Set<Symbology> symbologies) {
        return symbologies.contains(Symbology.EAN_13)
                || symbologies.contains(Symbology.EAN_8)
                || symbologies.contains(Symbology.UPC_A)
                || symbologies.contains(Symbology.UPC_E);
    }

    /**
     * PDF417 uses this attempt's binarizer. A 90 degree search stays on the ladder.
     * The caller's symbology set is not modified.
     */
    private static List<BarcodeHit> pdf417Hits(
            BinaryBitmap image,
            PixelMap toSource,
            String attempt,
            int rotationDegrees,
            double confidence,
            Set<Symbology> symbologies,
            boolean findAll
    ) {
        if (!symbologies.contains(Symbology.PDF_417)) {
            return List.of();
        }
        BitMatrix matrix;
        try {
            matrix = image.getBlackMatrix();
        } catch (ReaderException ignored) {
            return List.of();
        }
        boolean[][] bits = rows(matrix);
        List<BarcodeHit> hits = new ArrayList<>();
        for (Pdf417ScanningDecoder.Found found : Pdf417ScanningDecoder.read(bits, findAll)) {
            if (found.text() == null || found.text().isBlank()) {
                continue;
            }
            List<ImagePoint> corners = new ArrayList<>(found.corners().size());
            for (ImagePoint corner : found.corners()) {
                addCorner(corners, toSource.toSource(corner.x(), corner.y()));
            }
            hits.add(new BarcodeHit(
                    found.text(),
                    Symbology.PDF_417,
                    corners,
                    Math.floorMod(found.rotation() + rotationDegrees, 360),
                    confidence,
                    attempt,
                    found.bytes(),
                    ""
            ));
        }
        return hits;
    }

    /**
     * UPC-A is an EAN-13 with a leading zero removed, and only when UPC-A was requested.
     * UPC-E stays eight digits. The hit has to repeat on a parallel strip decoded again
     * with the global histogram and the same requested set.
     */
    private static List<BarcodeHit> eanHits(
            LuminanceSource source,
            BinaryBitmap image,
            PixelMap toSource,
            String attempt,
            int rotationDegrees,
            double confidence,
            Set<Symbology> symbologies,
            boolean binary,
            EanAddOn addOn
    ) {
        if (!wantsEan(symbologies)) {
            return List.of();
        }
        UpcEanScanner.Found found = UpcEanScanner.read(image, symbologies, addOn);
        if (found == null || found.text() == null || found.text().isBlank()) {
            return List.of();
        }
        // A short UPC-E or EAN-8 has to repeat on the parallel strip: one row of slanted bars can
        // checksum as one. A full EAN-13 or UPC-A may instead repeat on any other row of this
        // bitmap, which is zxing-cpp's two-line rule and keeps small blurred labels whose only
        // clean rows are far from the strip or right next to the first one. Not on a shrunken
        // image or a band crop: their neighbouring rows are near copies, and on the Arte-Lab
        // photos they repeated checksum-valid wrong codes such as 6328925005431.
        if (!confirmedEan(source, found, symbologies)
                && !(fullRetail(found.symbology())
                        && fullResolution(attempt)
                        && UpcEanScanner.seenOnAnotherRow(image, symbologies, found))) {
            return List.of();
        }
        // The first row that confirms can be a short glitch while the same bitmap's real bars
        // span a much taller run. Foto(726) hybrid returned 2094297032262 on rows 5 px apart
        // and the labeled 8023222032262 on a 54 px run of the same crop.
        if (attempt.startsWith("linear-band") && widerRival(image, found.text())) {
            return List.of();
        }
        // A binary strip hit on one or two adjacent rows is a threshold glitch.
        // Four rows, or two rows at least 8 px apart, still required on a 48-row strip.
        // A taller crop may keep just two intact rows under glare.
        if (binary && attempt.startsWith("linear-band") && !separatedEanRows(image, found.text())) {
            return List.of();
        }
        List<ImagePoint> corners = new ArrayList<>(2);
        addCorner(corners, toSource.toSource(found.left(), found.row()));
        addCorner(corners, toSource.toSource(found.right(), found.row()));
        if (!plausibleLinear(corners)) {
            return List.of();
        }
        return List.of(new BarcodeHit(
                found.text(),
                found.symbology(),
                corners,
                linearOrientation(found.upsideDown(), rotationDegrees),
                confidence,
                attempt,
                null,
                found.addOn()
        ));
    }

    /**
     * A hit has to repeat on the same parallel strip as the other linear symbologies,
     * decoded again with the global histogram.
     */
    private static List<BarcodeHit> code39Hits(
            LuminanceSource source,
            BinaryBitmap image,
            PixelMap toSource,
            String attempt,
            int rotationDegrees,
            double confidence,
            Set<Symbology> symbologies
    ) {
        if (!symbologies.contains(Symbology.CODE_39)) {
            return List.of();
        }
        Code39Scanner.Found found = Code39Scanner.read(image);
        if (found == null || found.text() == null || found.text().isBlank()) {
            return List.of();
        }
        if (!confirmedCode39(source, found)) {
            return List.of();
        }
        List<ImagePoint> corners = new ArrayList<>(2);
        addCorner(corners, toSource.toSource(found.left(), found.row()));
        addCorner(corners, toSource.toSource(found.right(), found.row()));
        if (!plausibleLinear(corners)) {
            return List.of();
        }
        return List.of(new BarcodeHit(
                found.text(),
                Symbology.CODE_39,
                corners,
                linearOrientation(found.upsideDown(), rotationDegrees),
                confidence,
                attempt
        ));
    }

    /**
     * A hit has to repeat on the same parallel strip as the other linear symbologies,
     * decoded again with the global histogram. Code 93 is absent from the default set.
     */
    private static List<BarcodeHit> code93Hits(
            LuminanceSource source,
            BinaryBitmap image,
            PixelMap toSource,
            String attempt,
            int rotationDegrees,
            double confidence,
            Set<Symbology> symbologies
    ) {
        if (!symbologies.contains(Symbology.CODE_93)) {
            return List.of();
        }
        Code93Scanner.Found found = Code93Scanner.read(image);
        if (found == null || found.text() == null || found.text().isBlank()) {
            return List.of();
        }
        if (!confirmedCode93(source, found)) {
            return List.of();
        }
        List<ImagePoint> corners = new ArrayList<>(2);
        addCorner(corners, toSource.toSource(found.left(), found.row()));
        addCorner(corners, toSource.toSource(found.right(), found.row()));
        if (!plausibleLinear(corners)) {
            return List.of();
        }
        return List.of(new BarcodeHit(
                found.text(),
                Symbology.CODE_93,
                corners,
                linearOrientation(found.upsideDown(), rotationDegrees),
                confidence,
                attempt
        ));
    }

    /**
     * A hit has to repeat on the same parallel strip as the other linear symbologies,
     * decoded again with the global histogram.
     */
    private static List<BarcodeHit> code128Hits(
            LuminanceSource source,
            BinaryBitmap image,
            PixelMap toSource,
            String attempt,
            int rotationDegrees,
            double confidence,
            Set<Symbology> symbologies
    ) {
        if (!symbologies.contains(Symbology.CODE_128)) {
            return List.of();
        }
        Code128Scanner.Found found = Code128Scanner.read(image, candidate -> candidate.text() != null
                && !candidate.text().isBlank() && confirmedCode128(source, candidate));
        if (found == null) {
            return List.of();
        }
        List<ImagePoint> corners = new ArrayList<>(2);
        addCorner(corners, toSource.toSource(found.left(), found.row()));
        addCorner(corners, toSource.toSource(found.right(), found.row()));
        if (!plausibleLinear(corners)) {
            return List.of();
        }
        return List.of(new BarcodeHit(
                found.text(),
                Symbology.CODE_128,
                corners,
                linearOrientation(found.upsideDown(), rotationDegrees),
                confidence,
                attempt
        ));
    }

    /**
     * A hit has to repeat on the same parallel strip as the other linear symbologies,
     * decoded again with the global histogram. ITF is absent from the default set.
     * The quiet-zone minimum inside the decoder stays 6 modules.
     */
    private static List<BarcodeHit> itfHits(
            LuminanceSource source,
            BinaryBitmap image,
            PixelMap toSource,
            String attempt,
            int rotationDegrees,
            double confidence,
            Set<Symbology> symbologies
    ) {
        if (!symbologies.contains(Symbology.ITF)) {
            return List.of();
        }
        ItfScanner.Found found = ItfScanner.read(image);
        if (found == null || found.text() == null || found.text().isBlank()) {
            return List.of();
        }
        if (!confirmedItf(source, found)) {
            return List.of();
        }
        List<ImagePoint> corners = new ArrayList<>(2);
        addCorner(corners, toSource.toSource(found.left(), found.row()));
        addCorner(corners, toSource.toSource(found.right(), found.row()));
        if (!plausibleLinear(corners)) {
            return List.of();
        }
        return List.of(new BarcodeHit(
                found.text(),
                Symbology.ITF,
                corners,
                linearOrientation(found.upsideDown(), rotationDegrees),
                confidence,
                attempt
        ));
    }

    /**
     * Start and stop letters stay in the text. Codabar is absent from the default set.
     * A hit has to repeat on a parallel strip decoded again with the global histogram.
     */
    private static boolean wantsDataBar(Set<Symbology> symbologies) {
        return symbologies.contains(Symbology.DATABAR)
                || symbologies.contains(Symbology.DATABAR_LIMITED)
                || symbologies.contains(Symbology.DATABAR_EXPANDED);
    }

    /**
     * DataBar from every row of this attempt's black matrix. DataBar and DataBar Expanded only
     * accept a symbol whose pairs were seen on more than one row and whose checksum holds, which
     * is the confirmation the other linear readers get from a parallel strip. DataBar Limited is
     * one row with a checksum. The corners are zxing-cpp's estimate: the two ends of the line
     * through the symbol, or four corners for a stacked one.
     */
    private static List<BarcodeHit> dataBarHits(
            BinaryBitmap image,
            PixelMap toSource,
            String attempt,
            int rotationDegrees,
            double confidence,
            Set<Symbology> symbologies
    ) {
        if (!wantsDataBar(symbologies)) {
            return List.of();
        }
        BitMatrix matrix;
        try {
            matrix = image.getBlackMatrix();
        } catch (ReaderException ignored) {
            return List.of();
        }
        boolean[][] rows = rows(matrix);
        List<BarcodeHit> hits = new ArrayList<>(1);
        int orientation = linearOrientation(false, rotationDegrees);
        if (symbologies.contains(Symbology.DATABAR)) {
            addDataBar(hits, DataBarDecoder.decodeLocated(rows), Symbology.DATABAR, toSource, matrix.getWidth(), orientation, confidence,
                    attempt);
        }
        if (symbologies.contains(Symbology.DATABAR_EXPANDED)) {
            addDataBar(hits, DataBarExpandedReader.decodeLocated(rows), Symbology.DATABAR_EXPANDED, toSource,
                    matrix.getWidth(), orientation, confidence, attempt);
        }
        if (symbologies.contains(Symbology.DATABAR_LIMITED)) {
            for (int y = 0; y < rows.length; y++) {
                DataBarDecoder.Located located = DataBarDecoder.decodeLimitedLocated(rows[y], y);
                if (located != null) {
                    addDataBar(hits, located, Symbology.DATABAR_LIMITED, toSource, matrix.getWidth(), orientation, confidence,
                            attempt);
                    break;
                }
            }
        }
        return hits;
    }

    private static void addDataBar(
            List<BarcodeHit> hits,
            DataBarDecoder.Located located,
            Symbology symbology,
            PixelMap toSource,
            int width,
            int orientation,
            double confidence,
            String attempt
    ) {
        if (located == null || located.text() == null || located.text().isBlank()) {
            return;
        }
        // zxing-cpp ends a right pair two elements past its last bar, which can pass the image edge.
        List<ImagePoint> corners = new ArrayList<>(located.corners().size());
        for (ImagePoint corner : located.corners()) {
            double x = Math.max(0, Math.min(width - 1, corner.x()));
            addCorner(corners, toSource.toSource(x, corner.y()));
        }
        hits.add(new BarcodeHit(located.text(), symbology, corners, orientation, confidence, attempt));
    }

    /** The black matrix as rows of modules, unpacked 32 at a time. */
    private static boolean[][] rows(BitMatrix matrix) {
        int width = matrix.getWidth();
        int height = matrix.getHeight();
        boolean[][] rows = new boolean[height][width];
        BitArray row = new BitArray(width);
        for (int y = 0; y < height; y++) {
            int[] words = matrix.getRow(y, row).getBitArray();
            boolean[] out = rows[y];
            for (int word = 0, x0 = 0; x0 < width; word++, x0 += 32) {
                int value = words[word];
                if (value == 0) {
                    continue;
                }
                int end = Math.min(32, width - x0);
                for (int bit = 0; bit < end; bit++) {
                    out[x0 + bit] = (value & (1 << bit)) != 0;
                }
            }
        }
        return rows;
    }

    private static List<BarcodeHit> codabarHits(
            LuminanceSource source,
            BinaryBitmap image,
            PixelMap toSource,
            String attempt,
            int rotationDegrees,
            double confidence,
            Set<Symbology> symbologies
    ) {
        if (!symbologies.contains(Symbology.CODABAR)) {
            return List.of();
        }
        CodabarScanner.Found found = CodabarScanner.read(image);
        if (found == null || found.text() == null || found.text().isBlank()) {
            return List.of();
        }
        if (!confirmedCodabar(source, found)) {
            return List.of();
        }
        List<ImagePoint> corners = new ArrayList<>(2);
        addCorner(corners, toSource.toSource(found.left(), found.row()));
        addCorner(corners, toSource.toSource(found.right(), found.row()));
        if (!plausibleLinear(corners)) {
            return List.of();
        }
        return List.of(new BarcodeHit(
                found.text(),
                Symbology.CODABAR,
                corners,
                linearOrientation(found.upsideDown(), rotationDegrees),
                confidence,
                attempt
        ));
    }

    private static void addCorner(List<ImagePoint> corners, ImagePoint point) {
        if (point != null && Double.isFinite(point.x()) && Double.isFinite(point.y())) {
            corners.add(point);
        }
    }

    /** Upright on an unrotated attempt stays unknown. A reversed row is 180 degrees. */
    private static int linearOrientation(boolean upsideDown, int rotationDegrees) {
        if (!upsideDown && rotationDegrees == 0) {
            return -1;
        }
        return Math.floorMod((upsideDown ? 180 : 0) + rotationDegrees, 360);
    }

    /**
     * True when the hit is a short run and another text on this bitmap covers a taller one.
     * A hit that already spans 8 px stays: that is the same separation the binary gate accepts.
     */
    private static boolean widerRival(BinaryBitmap image, String text) {
        Map<String, int[]> support = new HashMap<>();
        BitArray row = new BitArray(image.getWidth());
        for (int y = 0; y < image.getHeight(); y++) {
            try {
                row = image.getBlackRow(y, row);
            } catch (NotFoundException ignored) {
                continue;
            }
            noteEanRow(support, row, y);
            noteEanRow(support, reversedRow(row), y);
        }
        String hitKey = eanKey(text);
        int[] hit = support.get(hitKey);
        if (hit == null) {
            return false;
        }
        int hitSpan = hit[2] - hit[1];
        if (hitSpan >= 8) {
            return false;
        }
        for (Map.Entry<String, int[]> entry : support.entrySet()) {
            if (entry.getKey().equals(hitKey)) {
                continue;
            }
            int span = entry.getValue()[2] - entry.getValue()[1];
            if (span >= 8 && span > hitSpan) {
                return true;
            }
        }
        return false;
    }

    /** {@code stats} is count, first row, last row. The same row in both directions counts once. */
    private static void noteEanRow(Map<String, int[]> support, BitArray row, int y) {
        String span = spanText(row);
        if (span == null) {
            return;
        }
        String key = eanKey(span);
        int[] stats = support.get(key);
        if (stats == null) {
            support.put(key, new int[]{1, y, y});
            return;
        }
        if (stats[2] == y) {
            return;
        }
        stats[0]++;
        stats[2] = y;
    }

    /** A leading-zero EAN-13 and the 12-digit UPC-A are one symbol. */
    private static String eanKey(String text) {
        if (text.length() == 13 && text.charAt(0) == '0') {
            return text.substring(1);
        }
        return text;
    }

    private static boolean separatedEanRows(BinaryBitmap image, String text) {
        int count = 0;
        int first = -1;
        int last = -1;
        BitArray row = new BitArray(image.getWidth());
        for (int y = 0; y < image.getHeight(); y++) {
            try {
                row = image.getBlackRow(y, row);
            } catch (NotFoundException ignored) {
                continue;
            }
            if (!eanRowMatches(row, text) && !eanRowMatches(reversedRow(row), text)) {
                continue;
            }
            count++;
            if (first < 0) {
                first = y;
            }
            last = y;
            if (count >= 4 || last - first >= 8) {
                return true;
            }
        }
        // A 48-row strip still needs four rows or an 8 px gap: that is where a
        // one-row threshold glitch repeats. A taller crop can keep only a couple of
        // intact rows when glare covers the rest (Dataset1/05102009191).
        return count >= 2 && image.getHeight() > 48;
    }

    private static boolean eanRowMatches(BitArray row, String text) {
        String span = spanText(row);
        if (span == null) {
            return false;
        }
        if (span.equals(text)) {
            return true;
        }
        return text.length() == 12
                && span.length() == 13
                && span.charAt(0) == '0'
                && span.regionMatches(1, text, 0, 12);
    }

    private static String spanText(BitArray row) {
        boolean[] black = new boolean[row.getSize()];
        for (int x = 0; x < black.length; x++) {
            black[x] = row.get(x);
        }
        UpcEanDecoder.Span span = UpcEanDecoder.decodeSpan(black);
        return span == null ? null : span.text();
    }

    private static BitArray reversedRow(BitArray row) {
        BitArray out = new BitArray(row.getSize());
        int last = row.getSize() - 1;
        for (int x = 0; x < row.getSize(); x++) {
            if (row.get(last - x)) {
                out.set(x);
            }
        }
        return out;
    }

    private static boolean fullResolution(String attempt) {
        return !attempt.startsWith("linear-band") && !attempt.startsWith("downscale") && !attempt.equals("scale-0.5");
    }

    private static boolean fullRetail(Symbology symbology) {
        return symbology == Symbology.EAN_13 || symbology == Symbology.UPC_A;
    }

    private static boolean confirmedEan(LuminanceSource source, UpcEanScanner.Found found, Set<Symbology> symbologies) {
        boolean sawStrip = false;
        for (int shift : new int[]{PARALLEL_SHIFT, -PARALLEL_SHIFT}) {
            LuminanceSource strip = parallelStrip(source, found.row(), found.left(), found.right(), shift, true);
            if (strip == null) {
                continue;
            }
            sawStrip = true;
            UpcEanScanner.Found again = UpcEanScanner.read(bitmap(strip, true), symbologies);
            if (again != null && found.symbology() == again.symbology() && found.text().equals(again.text())) {
                return true;
            }
        }
        return !sawStrip;
    }

    private static boolean confirmedCode39(LuminanceSource source, Code39Scanner.Found found) {
        boolean sawStrip = false;
        for (int shift : new int[]{PARALLEL_SHIFT, -PARALLEL_SHIFT}) {
            LuminanceSource strip = parallelStrip(source, found.row(), found.left(), found.right(), shift, true);
            if (strip == null) {
                continue;
            }
            sawStrip = true;
            Code39Scanner.Found again = Code39Scanner.read(bitmap(strip, true));
            if (again != null && found.text().equals(again.text())) {
                return true;
            }
        }
        return !sawStrip;
    }

    private static boolean confirmedCode93(LuminanceSource source, Code93Scanner.Found found) {
        boolean sawStrip = false;
        for (int shift : new int[]{PARALLEL_SHIFT, -PARALLEL_SHIFT}) {
            LuminanceSource strip = parallelStrip(source, found.row(), found.left(), found.right(), shift, true);
            if (strip == null) {
                continue;
            }
            sawStrip = true;
            Code93Scanner.Found again = Code93Scanner.read(bitmap(strip, true));
            if (again != null && found.text().equals(again.text())) {
                return true;
            }
        }
        return !sawStrip;
    }

    private static boolean confirmedItf(LuminanceSource source, ItfScanner.Found found) {
        boolean sawStrip = false;
        for (int shift : new int[]{PARALLEL_SHIFT, -PARALLEL_SHIFT}) {
            LuminanceSource strip = parallelStrip(source, found.row(), found.left(), found.right(), shift, true);
            if (strip == null) {
                continue;
            }
            sawStrip = true;
            ItfScanner.Found again = ItfScanner.read(bitmap(strip, true));
            if (again != null && found.text().equals(again.text())) {
                return true;
            }
        }
        return !sawStrip;
    }

    private static boolean confirmedCodabar(LuminanceSource source, CodabarScanner.Found found) {
        boolean sawStrip = false;
        for (int shift : new int[]{PARALLEL_SHIFT, -PARALLEL_SHIFT}) {
            LuminanceSource strip = parallelStrip(source, found.row(), found.left(), found.right(), shift, true);
            if (strip == null) {
                continue;
            }
            sawStrip = true;
            CodabarScanner.Found again = CodabarScanner.read(bitmap(strip, true));
            if (again != null && found.text().equals(again.text())) {
                return true;
            }
        }
        return !sawStrip;
    }

    private static boolean confirmedCode128(LuminanceSource source, Code128Scanner.Found found) {
        boolean sawStrip = false;
        for (int shift : new int[]{PARALLEL_SHIFT, -PARALLEL_SHIFT}) {
            LuminanceSource strip = parallelStrip(source, found.row(), found.left(), found.right(), shift, true);
            if (strip == null) {
                continue;
            }
            sawStrip = true;
            Code128Scanner.Found again = Code128Scanner.read(bitmap(strip, true));
            if (again != null && found.text().equals(again.text())) {
                return true;
            }
        }
        return !sawStrip;
    }

    private static LuminanceSource parallelStrip(
            LuminanceSource source,
            int origin,
            int spanStart,
            int spanEnd,
            int shift,
            boolean horizontal
    ) {
        int crossStart = shift > 0 ? origin + shift : origin + shift - PARALLEL_THICKNESS;
        int alongStart = spanStart - PARALLEL_MARGIN;
        int alongEnd = spanEnd + PARALLEL_MARGIN;
        int left = horizontal ? alongStart : crossStart;
        int top = horizontal ? crossStart : alongStart;
        int width = horizontal ? alongEnd - alongStart : PARALLEL_THICKNESS;
        int height = horizontal ? PARALLEL_THICKNESS : alongEnd - alongStart;
        int[] box = clampBox(source.getWidth(), source.getHeight(), left, top, width, height);
        if (box == null || !source.isCropSupported()) {
            return null;
        }
        int crossOrigin = horizontal ? box[1] : box[0];
        int crossLength = horizontal ? box[3] : box[2];
        if (crossOrigin <= origin && origin < crossOrigin + crossLength) {
            return null;
        }
        return source.crop(box[0], box[1], box[2], box[3]);
    }

    /** @return left, top, width, height, or null when the clamped box is thinner than a strip */
    private static int[] clampBox(int imageWidth, int imageHeight, int left, int top, int width, int height) {
        if (width <= 0 || height <= 0) {
            return null;
        }
        if (left < 0) {
            width += left;
            left = 0;
        }
        if (top < 0) {
            height += top;
            top = 0;
        }
        if (left >= imageWidth || top >= imageHeight) {
            return null;
        }
        if (left + width > imageWidth) {
            width = imageWidth - left;
        }
        if (top + height > imageHeight) {
            height = imageHeight - top;
        }
        if (width < PARALLEL_THICKNESS || height < PARALLEL_THICKNESS) {
            return null;
        }
        return new int[]{left, top, width, height};
    }

    private static boolean plausibleLinear(List<ImagePoint> corners) {
        if (corners.size() < 2) {
            return false;
        }
        double max = 0.0;
        for (int i = 0; i < corners.size(); i++) {
            for (int j = i + 1; j < corners.size(); j++) {
                double dx = corners.get(i).x() - corners.get(j).x();
                double dy = corners.get(i).y() - corners.get(j).y();
                max = Math.max(max, dx * dx + dy * dy);
            }
        }
        return max >= MIN_LINEAR_SPAN_SQ;
    }

    private static BinaryBitmap bitmap(LuminanceSource source, boolean binary) {
        return new BinaryBitmap(binary ? new GlobalHistogramBinarizer(source) : new HybridBinarizer(source));
    }

}
