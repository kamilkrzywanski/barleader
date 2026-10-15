package io.github.kamilkrzywanski.barcode;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.datamatrix.DataMatrixWriter;
import com.google.zxing.datamatrix.encoder.SymbolShapeHint;
import org.junit.jupiter.api.Test;

import java.awt.Dimension;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
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

    /** Barcode-Lib4J writes GS1 Data Matrix outside the minimal encoder, as ZXing does not. */
    @Test
    void gs1DataMatrixMatchesBarcodeLib4j() throws Exception {
        for (String gs1 : List.of("(01)09501101530003(17)250630(10)AB-123", "(00)109501101530003005", "(01)09501101530003(21)12345678\u001D(17)250630")) {
            de.vwsoft.barcodelib4j.twod.TwoDCode code = new de.vwsoft.barcodelib4j.twod.TwoDCode(de.vwsoft.barcodelib4j.twod.TwoDType.GS1_DATAMATRIX);
            code.setContent(new de.vwsoft.barcodelib4j.oned.GS1Validator(gs1, '\u001D').getContent());
            Object symbol = code.buildSymbol();
            // the symbol keeps its dark modules as rectangles in a size with the quiet zone
            Field size = symbol.getClass().getDeclaredField("mySize");
            Field chunks = symbol.getClass().getDeclaredField("myChunks");
            size.setAccessible(true);
            chunks.setAccessible(true);
            Dimension dimension = (Dimension) size.get(symbol);
            boolean[][] theirs = new boolean[dimension.height - 2][dimension.width - 2];
            for (Rectangle chunk : (Rectangle[]) chunks.get(symbol)) {
                for (int y = chunk.y; y < chunk.y + chunk.height; y++) {
                    for (int x = chunk.x; x < chunk.x + chunk.width; x++) {
                        theirs[y - 1][x - 1] = true;
                    }
                }
            }
            assertArrayEquals(theirs, modules(BarcodeWriter.encode(BarcodeType.GS1_DATA_MATRIX, gs1)), gs1);
        }
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
