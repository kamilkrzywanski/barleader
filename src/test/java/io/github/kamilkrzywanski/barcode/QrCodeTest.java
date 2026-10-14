package io.github.kamilkrzywanski.barcode;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.EncodeHintType;
import com.google.zxing.Result;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.google.zxing.qrcode.encoder.ByteMatrix;
import com.google.zxing.qrcode.encoder.Encoder;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QrCodeTest {
    private static final String ALPHANUMERIC = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:";

    /** ZXing's matrix for the hints this library's options stand for. */
    private static boolean[][] zxing(String text, QrOptions options, boolean[] usedCharset) throws Exception {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        Charset charset = options.charset();
        if (charset == null && !StandardCharsets.ISO_8859_1.newEncoder().canEncode(text)) {
            charset = StandardCharsets.UTF_8;
        }
        if (charset != null) {
            hints.put(EncodeHintType.CHARACTER_SET, charset.name());
            usedCharset[0] = true;
        }
        if (options.gs1()) {
            hints.put(EncodeHintType.GS1_FORMAT, true);
        }
        if (options.version() != 0) {
            hints.put(EncodeHintType.QR_VERSION, options.version());
        }
        if (options.mask() >= 0) {
            hints.put(EncodeHintType.QR_MASK_PATTERN, options.mask());
        }
        ByteMatrix matrix = Encoder.encode(text, ErrorCorrectionLevel.valueOf(options.errorCorrection().name()), hints).getMatrix();
        boolean[][] modules = new boolean[matrix.getHeight()][matrix.getWidth()];
        for (int y = 0; y < matrix.getHeight(); y++) {
            for (int x = 0; x < matrix.getWidth(); x++) {
                modules[y][x] = matrix.get(x, y) == 1;
            }
        }
        return modules;
    }

    private static boolean[][] modules(QrCode qr) {
        boolean[][] modules = new boolean[qr.size()][qr.size()];
        for (int y = 0; y < qr.size(); y++) {
            for (int x = 0; x < qr.size(); x++) {
                modules[y][x] = qr.isDark(x, y);
            }
        }
        return modules;
    }

    private static String random(Random random, String alphabet, int length) {
        StringBuilder out = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            out.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return out.toString();
    }

    private static List<String> samples() {
        Random random = new Random(20261015L);
        List<String> texts = new ArrayList<>(List.of(
                "", "0", "12345678901234567890", "HELLO WORLD", "https://example.com/?q=1&r=2",
                "café crème", "Zażółć gęślą jaźń", "日本語のテキスト", "😀 emoji", "a\u0000b\nc\r\n"));
        String ascii = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 !\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~";
        for (int length : new int[]{1, 7, 17, 40, 100, 300, 900}) {
            texts.add(random(random, "0123456789", length * 2));
            texts.add(random(random, ALPHANUMERIC, length));
            texts.add(random(random, ascii, length));
            texts.add(random(random, "ąćęłńóśźżĄĆĘŁŃÓŚŹŻ abc", Math.max(1, length / 3)));
        }
        return texts;
    }

    @Test
    void everyModuleMatchesZxingForTheSameChoices() throws Exception {
        int compared = 0;
        for (String text : samples()) {
            for (QrErrorCorrection level : QrErrorCorrection.values()) {
                QrOptions options = QrOptions.defaults().errorCorrection(level);
                QrCode qr;
                try {
                    qr = QrCode.encode(text, options);
                } catch (IllegalArgumentException tooBig) {
                    continue;
                }
                boolean[] usedCharset = new boolean[1];
                assertArrayEquals(zxing(text, options, usedCharset), modules(qr), text + " at " + level);
                compared++;
            }
        }
        assertTrue(compared > 100, "compared " + compared);
    }

    @Test
    void fixedVersionsMasksCharsetsAndGs1MatchZxing() throws Exception {
        List<QrOptions> variants = List.of(
                QrOptions.defaults().version(10),
                QrOptions.defaults().mask(3),
                QrOptions.defaults().version(7).mask(5).errorCorrection(QrErrorCorrection.H),
                QrOptions.defaults().charset(StandardCharsets.UTF_8),
                QrOptions.defaults().charset(StandardCharsets.ISO_8859_1),
                QrOptions.defaults().charset(Charset.forName("windows-1250")),
                QrOptions.defaults().charset(Charset.forName("Shift_JIS")),
                QrOptions.defaults().gs1(true));
        for (QrOptions options : variants) {
            for (String text : List.of("0104012345678901\u001D1012345", "HELLO", "Zażółć", "日本", "plain text 123")) {
                QrCode qr;
                try {
                    qr = QrCode.encode(text, options);
                } catch (IllegalArgumentException unencodable) {
                    continue;
                }
                assertArrayEquals(zxing(text, options, new boolean[1]), modules(qr), text);
            }
        }
    }

    @Test
    void theReaderReadsWhatTheWriterWrites() {
        BarcodeReader reader = BarcodeReader.sequential();
        for (String text : List.of("https://example.com", "Zażółć gęślą jaźń", "0123456789", "日本語", "😀 emoji")) {
            for (QrErrorCorrection level : QrErrorCorrection.values()) {
                QrCode qr = QrCode.encode(text, QrOptions.defaults().errorCorrection(level));
                List<BarcodeHit> hits = reader.read(qr.toImage(4), ReadOptions.fast());
                assertEquals(1, hits.size(), text + " at " + level);
                assertEquals(Symbology.QR_CODE, hits.get(0).symbology());
                assertEquals(text, hits.get(0).text(), text + " at " + level);
            }
        }
    }

    @Test
    void bytesComeBackAsTheyWere() {
        byte[] data = new byte[200];
        new Random(7).nextBytes(data);
        QrCode qr = QrCode.encode(data, QrOptions.defaults());
        List<BarcodeHit> hits = BarcodeReader.sequential().read(qr.toImage(3), ReadOptions.fast());
        assertEquals(1, hits.size());
        assertArrayEquals(data, hits.get(0).bytes());
    }

    @Test
    void zxingReadsItToo() throws Exception {
        QrCode qr = QrCode.encode("Zażółć gęślą jaźń", QrOptions.defaults().errorCorrection(QrErrorCorrection.Q));
        BufferedImage image = qr.toImage(5);
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.TRY_HARDER, true);
        Result result = new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(image))), hints);
        assertEquals("Zażółć gęślą jaźń", result.getText());
    }

    @Test
    void theDrawingsHaveTheQuietZoneAndOneUnitAModule() {
        QrCode qr = QrCode.encode("HELLO");
        assertEquals(21, qr.size());
        assertEquals(1, qr.version());
        BufferedImage image = qr.toImage(3, 2);
        assertEquals((21 + 4) * 3, image.getWidth());
        assertEquals(0xFFFFFFFF, image.getRGB(0, 0));
        assertEquals(0xFF000000, image.getRGB(2 * 3, 2 * 3));
        String svg = qr.toSvg();
        assertTrue(svg.startsWith("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 29 29\""), svg);
        assertTrue(svg.contains("M4 4h7v1h-7z"), svg);
    }

    @Test
    void whatCannotBeEncodedSaysSo() {
        assertThrows(IllegalArgumentException.class, () -> QrCode.encode("x".repeat(3000)));
        assertThrows(IllegalArgumentException.class, () -> QrCode.encode("Zażółć", QrOptions.defaults().charset(StandardCharsets.ISO_8859_1)));
        assertThrows(IllegalArgumentException.class, () -> QrCode.encode("x".repeat(100), QrOptions.defaults().version(1)));
        assertThrows(IllegalArgumentException.class, () -> QrOptions.defaults().version(41));
        assertThrows(IllegalArgumentException.class, () -> QrOptions.defaults().mask(8));
        assertThrows(IllegalArgumentException.class, () -> QrCode.encode("x").toImage(0));
    }
}
