package io.github.kamilkrzywanski.barleader;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.aztec.AztecWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.datamatrix.DataMatrixWriter;
import com.google.zxing.datamatrix.encoder.SymbolShapeHint;
import com.google.zxing.pdf417.PDF417Writer;
import com.google.zxing.pdf417.encoder.Dimensions;
import org.junit.jupiter.api.Test;

import java.awt.Dimension;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MatrixWriterTest {
    private static boolean[][] modules(Barcode code) {
        boolean[][] modules = new boolean[code.height()][code.width()];
        for (int y = 0; y < code.height(); y++) {
            for (int x = 0; x < code.width(); x++) {
                modules[y][x] = code.isDark(x, y);
            }
        }
        return modules;
    }

    private static boolean[][] modules(BitMatrix matrix) {
        boolean[][] modules = new boolean[matrix.getHeight()][matrix.getWidth()];
        for (int y = 0; y < matrix.getHeight(); y++) {
            for (int x = 0; x < matrix.getWidth(); x++) {
                modules[y][x] = matrix.get(x, y);
            }
        }
        return modules;
    }

    private static List<String> texts() {
        Random random = new Random(16022);
        List<String> texts = new ArrayList<>(List.of("A", "Hello, World!", "0123456789012345678901234567890", "ABCDEFGHIJKLMNOPQRSTUVWXYZ",
                "abcdefghijklmnopqrstuvwxyz", "café crème", "[)>\u001E05\u001D0123\u001E\u0004", "ÿþ\u0080 bytes"));
        String alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghij *-./<>!é";
        for (int i = 0; i < 200; i++) {
            StringBuilder text = new StringBuilder();
            for (int j = random.nextInt(60) + 1; j > 0; j--) {
                text.append(alphabet.charAt(random.nextInt(alphabet.length())));
            }
            texts.add(text.toString());
        }
        return texts;
    }

    @Test
    void dataMatrixMatchesZxing() throws Exception {
        DataMatrixWriter zxing = new DataMatrixWriter();
        for (String text : texts()) {
            for (DataMatrixShape shape : DataMatrixShape.values()) {
                Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
                hints.put(EncodeHintType.DATA_MATRIX_SHAPE, SymbolShapeHint.values()[shape.ordinal()]);
                WriteOptions options = WriteOptions.defaults().dataMatrixShape(shape);
                BitMatrix theirs;
                try {
                    theirs = zxing.encode(text, BarcodeFormat.DATA_MATRIX, 0, 0, hints);
                } catch (IllegalArgumentException e) {
                    // longer than the largest rectangle
                    assertThrows(IllegalArgumentException.class, () -> BarcodeWriter.encode(BarcodeType.DATA_MATRIX, text, options), text);
                    continue;
                }
                assertArrayEquals(modules(theirs), modules(BarcodeWriter.encode(BarcodeType.DATA_MATRIX, text, options)), text + " " + shape);
                hints.put(EncodeHintType.DATA_MATRIX_COMPACT, true);
                assertMatchesZxing(hints, text, BarcodeWriter.encode(BarcodeType.DATA_MATRIX, text, options.compact(true)));
            }
        }
        for (String text : List.of("Zażółć gęślą jaźń", "日本語テキスト")) {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.DATA_MATRIX_COMPACT, true);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            for (DataMatrixShape shape : DataMatrixShape.values()) {
                hints.put(EncodeHintType.DATA_MATRIX_SHAPE, SymbolShapeHint.values()[shape.ordinal()]);
                assertMatchesZxing(hints, text, BarcodeWriter.encode(BarcodeType.DATA_MATRIX, text, WriteOptions.defaults().dataMatrixShape(shape)));
            }
        }
        Map<EncodeHintType, Object> gs1 = new EnumMap<>(EncodeHintType.class);
        gs1.put(EncodeHintType.DATA_MATRIX_COMPACT, true);
        gs1.put(EncodeHintType.GS1_FORMAT, true);
        assertArrayEquals(modules(zxing.encode("010950110153000310AB-123", BarcodeFormat.DATA_MATRIX, 0, 0, gs1)),
                modules(BarcodeWriter.encode(BarcodeType.GS1_DATA_MATRIX, "(01)09501101530003(10)AB-123", WriteOptions.defaults().compact(true))));
    }

    /**
     * ZXing's minimal encoder takes the 12x36 rectangle for 33 codewords, not 22, and fails on 17
     * to 33 of them, and it leaves EDIFACT at the end of data without the unlatch, which reads the
     * padding as text. Where ZXing fails or its symbol does not read back, the written one must.
     */
    private static void assertMatchesZxing(Map<EncodeHintType, Object> hints, String text, Barcode code) {
        boolean[][] theirs = null;
        try {
            theirs = modules(new DataMatrixWriter().encode(text, BarcodeFormat.DATA_MATRIX, 0, 0, hints));
        } catch (IllegalArgumentException e) {
            // the rectangle that ZXing sizes wrong
        }
        if (theirs != null && Arrays.deepEquals(theirs, modules(code))) {
            return;
        }
        if (theirs != null) {
            assertNotEquals(List.of(text), read(theirs), "ZXing's symbol reads back: " + text + " " + hints);
        }
        assertEquals(List.of(text), read(modules(code)), text + " " + hints);
    }

    private static List<String> read(boolean[][] modules) {
        int scale = 4;
        BufferedImage image = new BufferedImage((modules[0].length + 2) * scale, (modules.length + 2) * scale, BufferedImage.TYPE_BYTE_GRAY);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int row = y / scale - 1;
                int column = x / scale - 1;
                boolean dark = row >= 0 && row < modules.length && column >= 0 && column < modules[0].length && modules[row][column];
                image.setRGB(x, y, dark ? 0xFF000000 : 0xFFFFFFFF);
            }
        }
        return BarcodeReader.sequential().read(image, ReadOptions.fast()).stream().map(BarcodeHit::text).toList();
    }

    /** The modules of a Barcode-Lib4J symbol, which keeps the dark ones as rectangles in a size with the quiet zone. */
    private static boolean[][] modules(de.vwsoft.barcodelib4j.twod.TwoDCode code) throws Exception {
        int quietZone = code.getQuietZone();
        Object symbol = code.buildSymbol();
        Field size = symbol.getClass().getDeclaredField("mySize");
        Field chunks = symbol.getClass().getDeclaredField("myChunks");
        size.setAccessible(true);
        chunks.setAccessible(true);
        Dimension dimension = (Dimension) size.get(symbol);
        boolean[][] modules = new boolean[dimension.height - 2 * quietZone][dimension.width - 2 * quietZone];
        for (Rectangle chunk : (Rectangle[]) chunks.get(symbol)) {
            for (int y = chunk.y; y < chunk.y + chunk.height; y++) {
                for (int x = chunk.x; x < chunk.x + chunk.width; x++) {
                    modules[y - quietZone][x - quietZone] = true;
                }
            }
        }
        return modules;
    }

    /** Barcode-Lib4J writes GS1 Data Matrix outside the minimal encoder, as ZXing does not. */
    @Test
    void gs1DataMatrixMatchesBarcodeLib4j() throws Exception {
        for (String gs1 : List.of("(01)09501101530003(17)250630(10)AB-123", "(00)109501101530003005", "(01)09501101530003(21)12345678\u001D(17)250630")) {
            de.vwsoft.barcodelib4j.twod.TwoDCode code = new de.vwsoft.barcodelib4j.twod.TwoDCode(de.vwsoft.barcodelib4j.twod.TwoDType.GS1_DATAMATRIX);
            code.setContent(new de.vwsoft.barcodelib4j.oned.GS1Validator(gs1, '\u001D').getContent());
            assertArrayEquals(modules(code), modules(BarcodeWriter.encode(BarcodeType.GS1_DATA_MATRIX, gs1)), gs1);
        }
    }

    @Test
    void pdf417MatchesZxing() throws Exception {
        PDF417Writer zxing = new PDF417Writer();
        List<String> texts = new ArrayList<>(texts());
        texts.addAll(List.of("Zażółć gęślą jaźń", "日本語テキスト", "1234567890".repeat(30), "x".repeat(900)));
        for (String text : texts) {
            for (int level : new int[]{0, 2, 5}) {
                for (boolean truncated : new boolean[]{false, true}) {
                    for (boolean compact : new boolean[]{false, true}) {
                        Pdf417Options pdf417 = Pdf417Options.defaults().errorCorrection(level).truncated(truncated).rowHeight(4);
                        Barcode ours = BarcodeWriter.encode(BarcodeType.PDF_417, text, WriteOptions.defaults().pdf417(pdf417).compact(compact));
                        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
                        hints.put(EncodeHintType.ERROR_CORRECTION, level);
                        hints.put(EncodeHintType.PDF417_COMPACT, truncated);
                        hints.put(EncodeHintType.PDF417_AUTO_ECI, compact);
                        hints.put(EncodeHintType.PDF417_DIMENSIONS, new Dimensions(1, 30, 3, 90));
                        hints.put(EncodeHintType.MARGIN, 0);
                        if (!StandardCharsets.ISO_8859_1.newEncoder().canEncode(text)) {
                            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
                        }
                        // ZXing turns the symbol upright when the height it is given is the other way round
                        int height = ours.height() > ours.width() ? 1 : 0;
                        assertArrayEquals(modules(zxing.encode(text, BarcodeFormat.PDF_417, 0, height, hints)), modules(ours),
                                text + " " + level + " " + truncated + " " + compact);
                    }
                }
            }
        }
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.PDF417_DIMENSIONS, new Dimensions(7, 7, 12, 12));
        hints.put(EncodeHintType.MARGIN, 0);
        Barcode fixed = BarcodeWriter.encode(BarcodeType.PDF_417, "Hello, World!",
                WriteOptions.defaults().pdf417(Pdf417Options.defaults().columns(7).rows(12).rowHeight(4)));
        assertArrayEquals(modules(zxing.encode("Hello, World!", BarcodeFormat.PDF_417, 0, 0, hints)), modules(fixed));
        assertEquals(12 * 4, fixed.height());
        assertEquals(17 * 7 + 69, fixed.width());
    }

    @Test
    void aztecMatchesZxing() {
        AztecWriter zxing = new AztecWriter();
        List<String> texts = new ArrayList<>(texts());
        texts.addAll(List.of("Zażółć gęślą jaźń", "日本語テキスト", "1234567890".repeat(30), "x".repeat(1500)));
        for (String text : texts) {
            for (int percent : new int[]{23, 33, 50}) {
                Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
                hints.put(EncodeHintType.ERROR_CORRECTION, percent);
                if (!StandardCharsets.ISO_8859_1.newEncoder().canEncode(text)) {
                    hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
                }
                assertArrayEquals(modules(zxing.encode(text, BarcodeFormat.AZTEC, 0, 0, hints)),
                        modules(BarcodeWriter.encode(BarcodeType.AZTEC, text, WriteOptions.defaults().aztec(AztecOptions.defaults().errorCorrection(percent)))),
                        text + " " + percent);
            }
        }
        for (int layers : new int[]{-4, -2, 1, 5, 32}) {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.AZTEC_LAYERS, layers);
            hints.put(EncodeHintType.ERROR_CORRECTION, 23);
            assertArrayEquals(modules(zxing.encode("Aztec", BarcodeFormat.AZTEC, 0, 0, hints)),
                    modules(BarcodeWriter.encode(BarcodeType.AZTEC, "Aztec", WriteOptions.defaults().aztec(AztecOptions.defaults().layers(layers)))),
                    "layers " + layers);
        }
        assertThrows(IllegalArgumentException.class, () -> BarcodeWriter.encode(BarcodeType.AZTEC, "x".repeat(100),
                WriteOptions.defaults().aztec(AztecOptions.defaults().layers(-1))));
    }

    @Test
    void pdf417AndAztecMatchBarcodeLib4j() throws Exception {
        for (String text : List.of("Hello, World!", "PDF417 and Aztec from Barcode-Lib4J", "0123456789".repeat(8))) {
            de.vwsoft.barcodelib4j.twod.TwoDCode pdf417 = new de.vwsoft.barcodelib4j.twod.TwoDCode(de.vwsoft.barcodelib4j.twod.TwoDType.PDF417);
            pdf417.setContent(text);
            pdf417.setQuietZone(de.vwsoft.barcodelib4j.twod.TwoDType.PDF417.getDefaultQuietZone());
            assertArrayEquals(modules(pdf417), modules(BarcodeWriter.encode(BarcodeType.PDF_417, text,
                    WriteOptions.defaults().pdf417(Pdf417Options.defaults().rowHeight(4)))), text);
            de.vwsoft.barcodelib4j.twod.TwoDCode aztec = new de.vwsoft.barcodelib4j.twod.TwoDCode(de.vwsoft.barcodelib4j.twod.TwoDType.AZTEC);
            aztec.setContent(text);
            aztec.setQuietZone(de.vwsoft.barcodelib4j.twod.TwoDType.AZTEC.getDefaultQuietZone());
            assertArrayEquals(modules(aztec), modules(BarcodeWriter.encode(BarcodeType.AZTEC, text)), text);
        }
    }

    @Test
    void theReaderReadsWrittenPdf417AndAztec() {
        BarcodeReader reader = BarcodeReader.sequential();
        for (String text : List.of("Hello, World!", "Zażółć gęślą jaźń", "0123456789".repeat(20))) {
            for (BarcodeType type : List.of(BarcodeType.PDF_417, BarcodeType.AZTEC)) {
                for (boolean compact : new boolean[]{false, true}) {
                    Barcode code = BarcodeWriter.encode(type, text, WriteOptions.defaults().compact(compact));
                    List<BarcodeHit> hits = reader.read(code.toImage(3), ReadOptions.fast());
                    assertEquals(List.of(text), hits.stream().map(BarcodeHit::text).toList(), text + " " + type + " " + compact);
                }
            }
        }
        Barcode truncated = BarcodeWriter.encode(BarcodeType.PDF_417, "Truncated PDF417",
                WriteOptions.defaults().pdf417(Pdf417Options.defaults().truncated(true)));
        assertEquals(List.of("Truncated PDF417"), reader.read(truncated.toImage(3), ReadOptions.fast()).stream().map(BarcodeHit::text).toList());
        assertEquals(2, truncated.quietZoneLeft());
        assertEquals(0, BarcodeWriter.encode(BarcodeType.AZTEC, "A").quietZoneLeft());
    }

    @Test
    void theReaderReadsWrittenDataMatrix() {
        BarcodeReader reader = BarcodeReader.sequential();
        for (String text : List.of("Hello, World!", "Zażółć gęślą jaźń", "0123456789012345678901234567890")) {
            for (DataMatrixShape shape : DataMatrixShape.values()) {
                Barcode code = BarcodeWriter.encode(BarcodeType.DATA_MATRIX, text, WriteOptions.defaults().dataMatrixShape(shape));
                List<BarcodeHit> hits = reader.read(code.toImage(5), ReadOptions.fast());
                assertEquals(1, hits.size(), text + " " + shape);
                assertEquals(text, hits.get(0).text(), text + " " + shape);
            }
        }
        Barcode gs1 = BarcodeWriter.encode(BarcodeType.GS1_DATA_MATRIX, "(01)09501101530003(10)AB-123\u001D(17)250630");
        assertEquals("(01)09501101530003(10)AB-123(17)250630", gs1.text());
        List<BarcodeHit> hits = reader.read(gs1.toImage(5), ReadOptions.fast());
        assertEquals("010950110153000310AB-123\u001D17250630", hits.get(0).text());
        assertEquals(1, gs1.quietZoneLeft());
    }
}
