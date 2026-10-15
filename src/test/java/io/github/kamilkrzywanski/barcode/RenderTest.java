package io.github.kamilkrzywanski.barcode;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;

import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RenderTest {
    private static final BarcodeReader READER = BarcodeReader.sequential();

    private static List<String> read(BufferedImage image) {
        return READER.read(image, ReadOptions.fast().symbologies(Symbology.values())).stream().map(BarcodeHit::text).toList();
    }

    /** The heights of the bars, by the first module of each, relative to the symbol. */
    private static Set<Double> heights(Drawing drawing) {
        Set<Double> heights = new TreeSet<>();
        for (Rectangle2D.Double bar : drawing.dark) {
            heights.add(bar.height);
        }
        return heights;
    }

    @Test
    void eanGuardBarsReachIntoTheDigits() {
        Barcode ean = BarcodeWriter.encode(BarcodeType.EAN_13, "590123412345");
        Drawing drawing = Drawing.of(ean, RenderOptions.defaults().barHeight(50));
        assertEquals(Set.of(50.0, 55.0), heights(drawing));
        assertTrue(drawing.hasText());
        assertTrue(drawing.height > 55, "the digits go below the guard bars: " + drawing.height);
        assertEquals(11 + 95 + 7, drawing.width);
        // the first digit stands in the left quiet zone, the groups under the halves
        Rectangle2D text = drawing.text.getBounds2D();
        assertTrue(text.getMinX() < 11, text.toString());
        assertTrue(text.getMaxX() < 11 + 95, text.toString());

        Drawing bare = Drawing.of(ean, RenderOptions.defaults().barHeight(50).humanReadable(false));
        assertEquals(Set.of(50.0), heights(bare));
        assertFalse(bare.hasText());
        assertEquals(50, bare.height);
    }

    @Test
    void addOnDigitsStandAboveItsBars() {
        Barcode ean = BarcodeWriter.encode(BarcodeType.EAN_13, "590123412345", WriteOptions.defaults().addOn("12"));
        Drawing drawing = Drawing.of(ean, RenderOptions.defaults());
        double addOnStart = 11 + 95 + 7;
        double top = Double.MAX_VALUE;
        for (Rectangle2D.Double bar : drawing.dark) {
            if (bar.x >= addOnStart) {
                top = Math.min(top, bar.y);
                // shortened at the top, the bottom with the guard bars
                assertEquals(Barcode.DEFAULT_BAR_HEIGHT + 5, bar.y + bar.height, 1e-9);
            }
        }
        assertTrue(top > 5, "the add-on bars start under its digits: " + top);
        assertEquals(List.of("5901234123457"), read(ean.toImage(3)));
    }

    @Test
    void isbnStandsAboveTheSymbol() {
        Barcode isbn = BarcodeWriter.encode(BarcodeType.ISBN_13, "978-3-16-148410");
        Drawing drawing = Drawing.of(isbn, RenderOptions.defaults().barHeight(50));
        double barsTop = drawing.dark.stream().mapToDouble(bar -> bar.y).min().orElseThrow();
        assertTrue(barsTop > 3, "the bars start under the ISBN line: " + barsTop);
        assertTrue(drawing.text.getBounds2D().getMinY() < barsTop);
        assertEquals(List.of("9783161484100"), read(isbn.toImage(3)));
    }

    @Test
    void otherLinesAreCentredAndFitTheSymbol() {
        Barcode code = BarcodeWriter.encode(BarcodeType.CODE_128_C, "12345678901234567890123456");
        Drawing drawing = Drawing.of(code, RenderOptions.defaults());
        Rectangle2D text = drawing.text.getBounds2D();
        assertTrue(text.getMinX() >= code.quietZoneLeft() - 0.5 && text.getMaxX() <= code.quietZoneLeft() + code.width() + 0.5, text.toString());
        assertEquals(drawing.width / 2, text.getCenterX(), 1.5);
        Drawing larger = Drawing.of(code, RenderOptions.defaults().fontSize(20));
        assertTrue(larger.text.getBounds2D().getHeight() > text.getHeight() * 1.5);
        assertEquals("Tab here", Drawing.printable("Tab\thereñ"));
        assertFalse(Drawing.of(BarcodeWriter.encode(BarcodeType.CODE_128, "\t"), RenderOptions.defaults()).hasText());
    }

    @Test
    void everyLinearTypeReadsBackWithItsLine() {
        record Case(BarcodeType type, String content, String text) {
        }
        for (Case c : List.of(
                new Case(BarcodeType.CODE_128, "Hello, World!", "Hello, World!"),
                new Case(BarcodeType.GS1_128, "(01)09501101530003(10)AB-123", "010950110153000310AB-123"),
                new Case(BarcodeType.CODE_39, "CODE-39", "CODE-39"),
                new Case(BarcodeType.CODE_93, "CODE 93", "CODE 93"),
                new Case(BarcodeType.CODABAR, "A1234-5678B", "A1234-5678B"),
                new Case(BarcodeType.ITF, "12345678", "12345678"),
                new Case(BarcodeType.EAN_8, "9638507", "96385074"),
                new Case(BarcodeType.UPC_A, "03600029145", "036000291452"),
                new Case(BarcodeType.UPC_E, "0123456", "01234565"))) {
            Barcode code = BarcodeWriter.encode(c.type(), c.content());
            assertEquals(List.of(c.text()), read(code.toImage(2)), c.type().toString());
            String svg = code.toSvg();
            assertEquals(2, svg.split("<path ", -1).length - 1, "bars and text paths: " + c.type());
        }
    }

    @Test
    void pdfIsThePhysicalSizeAndReadsBack() throws Exception {
        record Case(Barcode code, String text) {
        }
        for (Case c : List.of(
                new Case(BarcodeWriter.encode(BarcodeType.EAN_13, "590123412345", WriteOptions.defaults().addOn("51299")), "5901234123457"),
                new Case(BarcodeWriter.encode(BarcodeType.CODE_128, "ABC-123 in a PDF"), "ABC-123 in a PDF"),
                new Case(BarcodeWriter.encode(BarcodeType.QR_CODE, "Zażółć gęślą jaźń"), "Zażółć gęślą jaźń"),
                new Case(BarcodeWriter.encode(BarcodeType.PDF_417, "PDF417 in a PDF"), "PDF417 in a PDF"),
                new Case(BarcodeWriter.encode(BarcodeType.DATA_MATRIX, "Data Matrix"), "Data Matrix"))) {
            double millimetres = 0.33;
            byte[] pdf = c.code().toPdf(millimetres);
            String head = new String(pdf, 0, 8, StandardCharsets.ISO_8859_1);
            assertEquals("%PDF-1.4", head);
            Drawing drawing = Drawing.of(c.code(), RenderOptions.defaults());
            try (PDDocument document = Loader.loadPDF(pdf)) {
                assertEquals(1, document.getNumberOfPages());
                PDRectangle box = document.getPage(0).getMediaBox();
                assertEquals(drawing.width * millimetres * 72 / 25.4, box.getWidth(), 0.01);
                assertEquals(drawing.height * millimetres * 72 / 25.4, box.getHeight(), 0.01);
                BufferedImage image = new PDFRenderer(document).renderImageWithDPI(0, 600, ImageType.GRAY);
                assertEquals(List.of(c.text()), read(image), c.code().type().toString());
            }
        }
    }
}
