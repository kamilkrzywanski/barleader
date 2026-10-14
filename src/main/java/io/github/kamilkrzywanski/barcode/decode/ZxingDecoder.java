package io.github.kamilkrzywanski.barcode.decode;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.ReaderException;
import com.google.zxing.Result;
import com.google.zxing.ResultMetadataType;
import com.google.zxing.ResultPoint;
import com.google.zxing.common.BitArray;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.GlobalHistogramBinarizer;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.multi.GenericMultipleBarcodeReader;
import io.github.kamilkrzywanski.barcode.BarcodeHit;
import io.github.kamilkrzywanski.barcode.ImagePoint;
import io.github.kamilkrzywanski.barcode.Symbology;
import io.github.kamilkrzywanski.barcode.image.GrayLuminanceSource;
import io.github.kamilkrzywanski.barcode.image.PixelMap;
import io.github.kamilkrzywanski.barcode.locate.dm.MatrixReader;
import io.github.kamilkrzywanski.barcode.image.GrayU8;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One decode pass over a luminance image. QR, Data Matrix, Aztec, and Micro QR are read by
 * {@link io.github.kamilkrzywanski.barcode.locate.dm.MatrixReader} from this attempt's own black matrix,
 * including that matrix inverted. Code 128 is read by {@link Code128Scanner},
 * Code 39 by {@link Code39Scanner}, Code 93 by {@link Code93Scanner}, ITF by {@link ItfScanner},
 * Codabar by {@link CodabarScanner}, PDF417 by {@link Pdf417ScanningDecoder}, and EAN/UPC by
 * {@link UpcEanScanner}. Anything still left goes to ZXing with TRY_HARDER and ALSO_INVERTED.
 * A linear hit must repeat on a parallel strip. Matrix hits do not. Angled bars can checksum
 * as a short UPC-E on a single row, and that row is not a barcode.
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
        if (luma.width < 2 || luma.height < 2) {
            return List.of();
        }
        return read(new GrayLuminanceSource(luma), toSource, attempt, rotationDegrees, confidence, symbologies, binary, findAll);
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
        BinaryBitmap owned = null;
        if ((wantsOwned(symbologies) || MatrixReader.wants(symbologies))
                && source.getWidth() >= 2
                && source.getHeight() >= 2) {
            owned = bitmap(source, binary);
        }
        List<BarcodeHit> matrix = owned == null
                ? List.of()
                : matrixHits(owned, toSource, attempt, rotationDegrees, confidence, symbologies, findAll);
        if (!findAll && !matrix.isEmpty()) {
            return List.of(matrix.get(0));
        }
        List<BarcodeHit> pdf417 = owned == null
                ? List.of()
                : pdf417Hits(owned, toSource, attempt, rotationDegrees, confidence, symbologies, findAll);
        List<BarcodeHit> ean = owned == null
                ? List.of()
                : eanHits(source, owned, toSource, attempt, rotationDegrees, confidence, symbologies, binary);
        List<BarcodeHit> code39 = owned == null
                ? List.of()
                : code39Hits(source, owned, toSource, attempt, rotationDegrees, confidence, symbologies);
        List<BarcodeHit> code93 = owned == null
                ? List.of()
                : code93Hits(source, owned, toSource, attempt, rotationDegrees, confidence, symbologies);
        List<BarcodeHit> code128 = owned == null
                ? List.of()
                : code128Hits(source, owned, toSource, attempt, rotationDegrees, confidence, symbologies);
        List<BarcodeHit> itf = owned == null
                ? List.of()
                : itfHits(source, owned, toSource, attempt, rotationDegrees, confidence, symbologies);
        List<BarcodeHit> codabar = owned == null
                ? List.of()
                : codabarHits(source, owned, toSource, attempt, rotationDegrees, confidence, symbologies);
        Set<BarcodeFormat> formats = formats(zxingSymbologies(symbologies));
        if (formats.isEmpty()) {
            return merge(findAll, matrix, List.of(), pdf417, ean, code39, code93, code128, itf, codabar);
        }
        Map<DecodeHintType, Object> hints = hints(formats);
        List<Result> results = new ArrayList<>();
        try {
            Result single = new com.google.zxing.MultiFormatReader().decode(
                    owned != null ? owned : bitmap(source, binary), hints);
            if (single != null) {
                results.add(single);
            }
        } catch (ReaderException ignored) {
            // This variant has no barcode.
        }
        if (findAll) {
            try {
                Result[] many = new GenericMultipleBarcodeReader(new com.google.zxing.MultiFormatReader())
                        .decodeMultiple(bitmap(source, binary), hints);
                if (many != null) {
                    for (Result result : many) {
                        if (result != null) {
                            results.add(result);
                        }
                    }
                }
            } catch (ReaderException ignored) {
                // A single hit is still usable when the multi reader gives up.
            }
        }
        List<BarcodeHit> hits = new ArrayList<>();
        for (Result result : results) {
            if (result == null || result.getText() == null) {
                continue;
            }
            if (isLinear(result) && !confirmedByParallelStrip(source, result)) {
                continue;
            }
            BarcodeHit hit = toHit(result, toSource, attempt, rotationDegrees, confidence);
            if (hit != null) {
                hits.add(hit);
            }
        }
        return merge(findAll, matrix, hits, pdf417, ean, code39, code93, code128, itf, codabar);
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
        boolean[][] bits = new boolean[matrix.getHeight()][matrix.getWidth()];
        for (int y = 0; y < matrix.getHeight(); y++) {
            for (int x = 0; x < matrix.getWidth(); x++) {
                bits[y][x] = matrix.get(x, y);
            }
        }
        return MatrixReader.read(bits, toSource, attempt, rotationDegrees, confidence, symbologies, findAll);
    }

    /**
     * Without {@code findAll} one attempt returns one symbol. A matrix hit keeps that slot.
     * ZXing is next, then PDF417, EAN/UPC, Code 39, Code 93, Code 128, ITF and Codabar.
     */
    private static List<BarcodeHit> merge(
            boolean findAll,
            List<BarcodeHit> matrix,
            List<BarcodeHit> zxing,
            List<BarcodeHit> pdf417,
            List<BarcodeHit> ean,
            List<BarcodeHit> code39,
            List<BarcodeHit> code93,
            List<BarcodeHit> code128,
            List<BarcodeHit> itf,
            List<BarcodeHit> codabar
    ) {
        if (findAll) {
            if (matrix.isEmpty() && pdf417.isEmpty() && ean.isEmpty() && code39.isEmpty() && code93.isEmpty()
                    && code128.isEmpty() && itf.isEmpty() && codabar.isEmpty()) {
                return zxing;
            }
            List<BarcodeHit> all = new ArrayList<>(matrix.size() + zxing.size() + pdf417.size() + ean.size()
                    + code39.size() + code93.size() + code128.size() + itf.size() + codabar.size());
            all.addAll(matrix);
            all.addAll(zxing);
            all.addAll(pdf417);
            all.addAll(ean);
            all.addAll(code39);
            all.addAll(code93);
            all.addAll(code128);
            all.addAll(itf);
            all.addAll(codabar);
            return all;
        }
        if (!matrix.isEmpty()) {
            return List.of(matrix.get(0));
        }
        if (!zxing.isEmpty()) {
            return zxing;
        }
        if (!pdf417.isEmpty()) {
            return List.of(pdf417.get(0));
        }
        if (!ean.isEmpty()) {
            return List.of(ean.get(0));
        }
        if (!code39.isEmpty()) {
            return List.of(code39.get(0));
        }
        if (!code93.isEmpty()) {
            return List.of(code93.get(0));
        }
        if (!code128.isEmpty()) {
            return List.of(code128.get(0));
        }
        if (!itf.isEmpty()) {
            return List.of(itf.get(0));
        }
        return codabar;
    }

    private static boolean wantsOwned(Set<Symbology> symbologies) {
        return symbologies.contains(Symbology.CODE_128)
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
     * Owned formats leave the ZXing format set. QR, Data Matrix, and Aztec are read from this
     * attempt's black matrix. Micro QR has no ZXing format. The caller's symbology set is not modified.
     */
    private static Set<Symbology> zxingSymbologies(Set<Symbology> symbologies) {
        EnumSet<Symbology> remaining = EnumSet.copyOf(symbologies);
        remaining.remove(Symbology.QR_CODE);
        remaining.remove(Symbology.DATA_MATRIX);
        remaining.remove(Symbology.AZTEC);
        remaining.remove(Symbology.CODE_128);
        remaining.remove(Symbology.CODE_39);
        remaining.remove(Symbology.CODE_93);
        remaining.remove(Symbology.ITF);
        remaining.remove(Symbology.CODABAR);
        remaining.remove(Symbology.PDF_417);
        remaining.remove(Symbology.EAN_13);
        remaining.remove(Symbology.EAN_8);
        remaining.remove(Symbology.UPC_A);
        remaining.remove(Symbology.UPC_E);
        return remaining;
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
        boolean[][] bits = new boolean[matrix.getHeight()][matrix.getWidth()];
        for (int y = 0; y < matrix.getHeight(); y++) {
            for (int x = 0; x < matrix.getWidth(); x++) {
                bits[y][x] = matrix.get(x, y);
            }
        }
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
                    attempt
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
            boolean binary
    ) {
        if (!wantsEan(symbologies)) {
            return List.of();
        }
        UpcEanScanner.Found found = UpcEanScanner.read(image, symbologies);
        if (found == null || found.text() == null || found.text().isBlank()) {
            return List.of();
        }
        if (!confirmedEan(source, found, symbologies)) {
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
                attempt
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
        Code128Scanner.Found found = Code128Scanner.read(image);
        if (found == null || found.text() == null || found.text().isBlank()) {
            return List.of();
        }
        if (!confirmedCode128(source, found)) {
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

    private static boolean isLinear(Result result) {
        Symbology symbology = Symbology.fromZxing(result.getBarcodeFormat());
        return symbology != null && symbology.linear();
    }

    /**
     * @return {@code true} when a strip that does not contain the original scan line
     *         decodes to the same symbology and text, or when the image cannot hold such a strip
     */
    private static boolean confirmedByParallelStrip(LuminanceSource source, Result result) {
        ResultPoint[] ends = farthestEnds(result.getResultPoints());
        if (ends == null) {
            return false;
        }
        double dx = Math.abs(ends[0].getX() - ends[1].getX());
        double dy = Math.abs(ends[0].getY() - ends[1].getY());
        if (dx > 2.0 && dy > 2.0) {
            return true;
        }
        boolean horizontal = dx >= dy;
        int origin = (int) Math.round(horizontal
                ? (ends[0].getY() + ends[1].getY()) / 2.0
                : (ends[0].getX() + ends[1].getX()) / 2.0);
        int spanStart = (int) Math.floor(horizontal
                ? Math.min(ends[0].getX(), ends[1].getX())
                : Math.min(ends[0].getY(), ends[1].getY()));
        int spanEnd = (int) Math.ceil(horizontal
                ? Math.max(ends[0].getX(), ends[1].getX())
                : Math.max(ends[0].getY(), ends[1].getY()));
        boolean sawStrip = false;
        for (int shift : new int[]{PARALLEL_SHIFT, -PARALLEL_SHIFT}) {
            LuminanceSource strip = parallelStrip(source, origin, spanStart, spanEnd, shift, horizontal);
            if (strip == null) {
                continue;
            }
            sawStrip = true;
            Result again = decodeSingle(strip, confirmHints(result.getBarcodeFormat()), true);
            if (again != null
                    && result.getBarcodeFormat() == again.getBarcodeFormat()
                    && result.getText().equals(again.getText())) {
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

    private static ResultPoint[] farthestEnds(ResultPoint[] points) {
        if (points == null) {
            return null;
        }
        ResultPoint first = null;
        ResultPoint second = null;
        double best = -1.0;
        for (int i = 0; i < points.length; i++) {
            ResultPoint start = points[i];
            if (!finite(start)) {
                continue;
            }
            for (int j = i + 1; j < points.length; j++) {
                ResultPoint end = points[j];
                if (!finite(end)) {
                    continue;
                }
                double dx = start.getX() - end.getX();
                double dy = start.getY() - end.getY();
                double distance = dx * dx + dy * dy;
                if (distance > best) {
                    best = distance;
                    first = start;
                    second = end;
                }
            }
        }
        if (first == null) {
            return null;
        }
        return new ResultPoint[]{first, second};
    }

    private static boolean finite(ResultPoint point) {
        return point != null && Float.isFinite(point.getX()) && Float.isFinite(point.getY());
    }

    private static Result decodeSingle(LuminanceSource source, Map<DecodeHintType, Object> hints, boolean binary) {
        try {
            return new MultiFormatReader().decode(bitmap(source, binary), hints);
        } catch (ReaderException ignored) {
            return null;
        }
    }

    private static Map<DecodeHintType, Object> confirmHints(BarcodeFormat format) {
        EnumMap<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        hints.put(DecodeHintType.ALSO_INVERTED, Boolean.TRUE);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, EnumSet.of(format));
        hints.put(DecodeHintType.CHARACTER_SET, "UTF-8");
        return hints;
    }

    private static BarcodeHit toHit(
            Result result,
            PixelMap toSource,
            String attempt,
            int rotationDegrees,
            double confidence
    ) {
        if (result.getText() == null || result.getText().isBlank()) {
            return null;
        }
        Symbology symbology = Symbology.fromZxing(result.getBarcodeFormat());
        if (symbology == null) {
            return null;
        }
        List<ImagePoint> corners = new ArrayList<>();
        ResultPoint[] points = result.getResultPoints();
        if (points != null) {
            for (ResultPoint point : points) {
                if (point == null || !Double.isFinite(point.getX()) || !Double.isFinite(point.getY())) {
                    continue;
                }
                ImagePoint mapped = toSource.toSource(point.getX(), point.getY());
                if (Double.isFinite(mapped.x()) && Double.isFinite(mapped.y())) {
                    corners.add(mapped);
                }
            }
        }
        if (symbology.linear() && !plausibleLinear(corners)) {
            return null;
        }
        return new BarcodeHit(
                result.getText(),
                symbology,
                corners,
                orientation(result, rotationDegrees),
                confidence,
                attempt
        );
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

    private static int orientation(Result result, int rotationDegrees) {
        int local = 0;
        boolean known = false;
        Map<ResultMetadataType, Object> metadata = result.getResultMetadata();
        if (metadata != null && metadata.get(ResultMetadataType.ORIENTATION) instanceof Integer degrees) {
            local = degrees;
            known = true;
        }
        if (!known && rotationDegrees == 0) {
            return -1;
        }
        return Math.floorMod(local + rotationDegrees, 360);
    }

    private static BinaryBitmap bitmap(LuminanceSource source, boolean binary) {
        return new BinaryBitmap(binary ? new GlobalHistogramBinarizer(source) : new HybridBinarizer(source));
    }

    private static Map<DecodeHintType, Object> hints(Collection<BarcodeFormat> formats) {
        EnumMap<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        hints.put(DecodeHintType.ALSO_INVERTED, Boolean.TRUE);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, EnumSet.copyOf(formats));
        hints.put(DecodeHintType.CHARACTER_SET, "UTF-8");
        return hints;
    }

    private static Set<BarcodeFormat> formats(Set<Symbology> symbologies) {
        EnumSet<BarcodeFormat> formats = EnumSet.noneOf(BarcodeFormat.class);
        for (Symbology symbology : symbologies) {
            if (symbology.zxing() != null) {
                formats.add(symbology.zxing());
            }
        }
        return formats;
    }
}
