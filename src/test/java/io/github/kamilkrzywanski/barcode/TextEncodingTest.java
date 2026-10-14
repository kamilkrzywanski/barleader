package io.github.kamilkrzywanski.barcode;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.datamatrix.DataMatrixWriter;
import com.google.zxing.pdf417.PDF417Writer;
import com.google.zxing.qrcode.QRCodeWriter;
import io.github.kamilkrzywanski.barcode.decode.TextDecoder;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Most generators write UTF-8 into a symbol without an ECI. Those bytes have to come back as the
 * text that was written, not as ISO-8859-1 mojibake.
 */
class TextEncodingTest {
    private static final String POLISH = "Zażółć gęślą jaźń";
    private static final Charset SHIFT_JIS = Charset.forName("Shift_JIS");

    @Test
    void guessesUtf8ShiftJisAndLatin1LikeZxingCpp() {
        assertEquals(TextDecoder.ECI_UTF_8, TextDecoder.guessEci("E=mc²".getBytes(StandardCharsets.UTF_8)));
        assertEquals(TextDecoder.ECI_UTF_8, TextDecoder.guessEci(POLISH.getBytes(StandardCharsets.UTF_8)));
        assertEquals(TextDecoder.ECI_SHIFT_JIS, TextDecoder.guessEci("Google モバイル".getBytes(SHIFT_JIS)));
        assertEquals(TextDecoder.ECI_ISO_8859_1, TextDecoder.guessEci("Zürich Café".getBytes(StandardCharsets.ISO_8859_1)));
        assertEquals(TextDecoder.ECI_ISO_8859_1, TextDecoder.guessEci("plain ascii".getBytes(StandardCharsets.US_ASCII)));
        // zxing-cpp's SimpleSJIS: four bytes that are both Latin-1 symbols and half-width katakana.
        assertEquals(TextDecoder.ECI_SHIFT_JIS, TextDecoder.guessEci(new byte[]{(byte) 0xA1, (byte) 0xA2, (byte) 0xA3, (byte) 0xD0}));
        // A control character other than CR or LF is taken as binary, not Shift_JIS.
        assertEquals(TextDecoder.ECI_ISO_8859_1, TextDecoder.guessEci(new byte[]{0x01, (byte) 0xA1, (byte) 0xA2, (byte) 0xA3}));
        // C0 AF has the shape of a two-byte character, but an overlong form is not valid UTF-8.
        // The control character rules Shift_JIS out, so ISO-8859-1 is left.
        assertEquals(TextDecoder.ECI_ISO_8859_1, TextDecoder.guessEci(new byte[]{0x01, 'a', (byte) 0xC0, (byte) 0xAF}));
    }

    @Test
    void dataMatrixAztecAndPdf417NeverGuessShiftJis() {
        assertEquals(TextDecoder.ECI_ISO_8859_1, TextDecoder.guessUtf8OrLatin1("25°C".getBytes(StandardCharsets.ISO_8859_1)));
        assertEquals(TextDecoder.ECI_ISO_8859_1, TextDecoder.guessUtf8OrLatin1("£5".getBytes(StandardCharsets.ISO_8859_1)));
        assertEquals(TextDecoder.ECI_UTF_8, TextDecoder.guessUtf8OrLatin1(POLISH.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void readsUtf8WithoutEciFromQrDataMatrixAndPdf417() throws Exception {
        // The writers get the UTF-8 bytes as Latin-1 characters and no CHARACTER_SET hint,
        // so each byte goes into the symbol as it is and no ECI is written.
        String raw = new String(POLISH.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
        Map<EncodeHintType, Object> margin = Map.of(EncodeHintType.MARGIN, 4);
        BarcodeReader reader = BarcodeReader.sequential();

        BufferedImage qr = image(new QRCodeWriter().encode(raw, BarcodeFormat.QR_CODE, 300, 300, margin));
        assertText(reader.read(qr), Symbology.QR_CODE);

        BufferedImage dataMatrix = image(new DataMatrixWriter().encode(raw, BarcodeFormat.DATA_MATRIX, 240, 240, Map.of()));
        assertText(reader.read(dataMatrix), Symbology.DATA_MATRIX);

        BufferedImage pdf417 = image(new PDF417Writer().encode(raw, BarcodeFormat.PDF_417, 500, 200, Map.of(EncodeHintType.MARGIN, 20)));
        assertText(reader.read(pdf417), Symbology.PDF_417);
    }

    private static void assertText(List<BarcodeHit> hits, Symbology symbology) {
        assertEquals(1, hits.size(), symbology + " " + hits);
        assertEquals(symbology, hits.get(0).symbology());
        assertEquals(POLISH, hits.get(0).text());
    }

    private static BufferedImage image(BitMatrix matrix) {
        return MatrixToImageWriter.toBufferedImage(matrix);
    }
}
