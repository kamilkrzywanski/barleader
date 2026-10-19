package io.github.kamilkrzywanski.barleader;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.aztec.AztecWriter;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.datamatrix.DataMatrixWriter;
import com.google.zxing.oned.Code128Writer;
import com.google.zxing.pdf417.PDF417Writer;
import com.google.zxing.qrcode.QRCodeWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link BarcodeHit#bytes()} keeps binary content, and {@link BarcodeHit#addOn()} carries an EAN/UPC supplement. */
class ContentTest {
    private static final ReadOptions FAST = ReadOptions.fast().timeBudgetMillis(Long.MAX_VALUE);
    private final BarcodeReader reader = BarcodeReader.sequential();

    /** Every byte value, which no character set turns back into the same bytes. */
    private static byte[] allBytes() {
        byte[] bytes = new byte[256];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) i;
        }
        return bytes;
    }

    @Test
    void matrixSymbolsReturnTheirBinaryContent() throws Exception {
        byte[] payload = allBytes();
        // A Latin-1 string with no CHARACTER_SET hint goes into the symbol byte for byte, with no ECI.
        String raw = new String(payload, StandardCharsets.ISO_8859_1);
        assertBytes(new QRCodeWriter().encode(raw, BarcodeFormat.QR_CODE, 400, 400, Map.of(EncodeHintType.MARGIN, 4)),
                Symbology.QR_CODE, payload);
        assertBytes(new DataMatrixWriter().encode(raw, BarcodeFormat.DATA_MATRIX, 400, 400, Map.of()),
                Symbology.DATA_MATRIX, payload);
        assertBytes(new AztecWriter().encode(raw, BarcodeFormat.AZTEC, 400, 400, Map.of()),
                Symbology.AZTEC, payload);
        assertBytes(new PDF417Writer().encode(raw, BarcodeFormat.PDF_417, 900, 400, Map.of(EncodeHintType.MARGIN, 20)),
                Symbology.PDF_417, payload);
    }

    @Test
    void linearSymbolsReturnTheirTextAsBytes() {
        BarcodeHit hit = only(read(new Code128Writer().encode("CODE-128", BarcodeFormat.CODE_128, 460, 140)));
        assertArrayEquals("CODE-128".getBytes(StandardCharsets.ISO_8859_1), hit.bytes());
        assertEquals("", hit.addOn());
    }

    @Test
    void bytesAreACopyAndCountInEquality() {
        BarcodeHit hit = new BarcodeHit("A", Symbology.QR_CODE, List.of(), 0, 1.0, "x", new byte[]{1, 2}, "");
        hit.bytes()[0] = 9;
        assertArrayEquals(new byte[]{1, 2}, hit.bytes());
        assertEquals(hit, new BarcodeHit("A", Symbology.QR_CODE, List.of(), 0, 1.0, "x", new byte[]{1, 2}, ""));
        assertNotEquals(hit, new BarcodeHit("A", Symbology.QR_CODE, List.of(), 0, 1.0, "x", new byte[]{1, 3}, ""));
    }

    /** zxing-cpp's expected text is the main symbol and the supplement with nothing between them. */
    @ParameterizedTest
    @CsvSource({
            "ean13-extension-1/1.png", "ean13-extension-1/2.png", "ean13-extension-1/32.png",
            "ean13-extension-1/33.png", "ean13-extension-1/38.png",
            "upca-extension-1/8.png", "upca-extension-1/9.png", "upca-extension-1/10.png",
            "upca-extension-1/11.png", "upca-extension-1/18.png"
    })
    void readsTheSupplementWhenAskedFor(String name) throws Exception {
        Path image = Path.of(ContentTest.class.getResource("/official-rows/" + name).toURI());
        String expected = Files.readString(image.resolveSibling(image.getFileName().toString().replace(".png", ".txt")),
                StandardCharsets.UTF_8).strip();
        BufferedImage picture = ImageIO.read(image.toFile());

        BarcodeHit read = only(reader.read(picture, FAST.eanAddOn(EanAddOn.READ)));
        String main = read.symbology() == Symbology.UPC_A ? "0" + read.text() : read.text();
        assertEquals(expected, main + read.addOn(), name);
        assertTrue(read.addOn().length() == 2 || read.addOn().length() == 5, name);

        BarcodeHit required = only(reader.read(picture, FAST.eanAddOn(EanAddOn.REQUIRE)));
        assertEquals(read.addOn(), required.addOn(), name);

        BarcodeHit ignored = only(reader.read(picture, FAST));
        assertEquals(read.text(), ignored.text(), name);
        assertEquals("", ignored.addOn(), name);
    }

    @Test
    void requireDropsASymbolWithoutSupplement() throws Exception {
        Path image = Path.of(ContentTest.class.getResource("/official-rows/ean13-1/10.png").toURI());
        BufferedImage picture = ImageIO.read(image.toFile());
        assertEquals(1, reader.read(picture, FAST).size());
        assertEquals(List.of(), reader.read(picture, FAST.eanAddOn(EanAddOn.REQUIRE)));
    }

    private void assertBytes(BitMatrix matrix, Symbology symbology, byte[] expected) {
        BarcodeHit hit = only(read(matrix));
        assertEquals(symbology, hit.symbology());
        assertArrayEquals(expected, hit.bytes(), symbology.name());
    }

    private List<BarcodeHit> read(BitMatrix matrix) {
        return reader.read(MatrixToImageWriter.toBufferedImage(matrix), FAST);
    }

    private static BarcodeHit only(List<BarcodeHit> hits) {
        assertEquals(1, hits.size(), hits.toString());
        return hits.get(0);
    }
}
