package io.github.kamilkrzywanski.barcode.locate.dm;

import io.github.kamilkrzywanski.barcode.image.GrayU8;
import com.google.zxing.EncodeHintType;
import com.google.zxing.ReaderException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.decoder.Decoder;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.google.zxing.qrcode.encoder.ByteMatrix;
import com.google.zxing.qrcode.encoder.Encoder;
import com.google.zxing.qrcode.encoder.QRCode;
import org.junit.jupiter.api.Test;
import io.github.kamilkrzywanski.barcode.locate.dm.DmGeometry.Bits;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class QrDecoderTest {
    @Test
    void roundTripsEveryModel2Version() throws Exception {
        for (int version = 1; version <= 40; version++) {
            assertRoundTrip("A", ErrorCorrectionLevel.M, version, null);
        }
    }

    @Test
    void roundTripsZxingWriterAcrossVersions() throws Exception {
        for (int version : new int[]{1, 5, 7, 10, 20, 40}) {
            assertRoundTrip("QR-PRO-HELLO", ErrorCorrectionLevel.M, version, null);
        }
        assertRoundTrip("12345678901234567890", ErrorCorrectionLevel.L, 5, null);
        assertRoundTrip("qr-byte-ok", ErrorCorrectionLevel.Q, 3, null);
        assertRoundTrip("caf\u00e9", ErrorCorrectionLevel.H, 2, null);
        Map<EncodeHintType, Object> utf8 = new EnumMap<>(EncodeHintType.class);
        utf8.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        assertRoundTrip("za\u017c\u00f3\u0142\u0107", ErrorCorrectionLevel.M, 4, utf8);
    }

    @Test
    void readsAMirroredSymbol() throws Exception {
        Bits modules = modules(encode("QR-SKEW", ErrorCorrectionLevel.Q, 5, null));
        Bits mirrored = new Bits(modules.width, modules.height);
        for (int y = 0; y < modules.height; y++) {
            for (int x = 0; x < modules.width; x++) {
                if (modules.get(y, x)) {
                    mirrored.set(x, y);
                }
            }
        }
        assertEquals("QR-SKEW", QrDecoder.decode(mirrored));
    }

    @Test
    void rejectsABrokenGridAndAWrongSize() throws Exception {
        Bits modules = modules(encode("QR-PRO-HELLO", ErrorCorrectionLevel.L, 1, null));
        Bits broken = new Bits(modules.width, modules.height);
        for (int y = 0; y < modules.height; y++) {
            for (int x = 0; x < modules.width; x++) {
                boolean value = modules.get(x, y);
                if (x >= 9 && x < 16 && y >= 9 && y < 16) {
                    value = !value;
                }
                if (value) {
                    broken.set(x, y);
                }
            }
        }
        assertNull(QrDecoder.decode(broken));
        assertNull(QrDecoder.decode(new Bits(20, 20)));
        assertNull(QrDecoder.decode(new Bits(21, 21)));
    }

    @Test
    void detectorReadsARenderedVersionTenSymbol() throws Exception {
        QRCode code = encode("VERSION-TEN-QR", ErrorCorrectionLevel.M, 10, null);
        ByteMatrix matrix = code.getMatrix();
        int scale = 4;
        int quiet = 4;
        int modules = matrix.getWidth();
        int side = (modules + quiet * 2) * scale;
        GrayU8 gray = new GrayU8(side, side);
        for (int y = 0; y < side; y++) {
            for (int x = 0; x < side; x++) {
                gray.set(x, y, 255);
            }
        }
        for (int y = 0; y < modules; y++) {
            for (int x = 0; x < modules; x++) {
                if (matrix.get(x, y) == 0) {
                    continue;
                }
                int originX = (x + quiet) * scale;
                int originY = (y + quiet) * scale;
                for (int ty = 0; ty < scale; ty++) {
                    for (int tx = 0; tx < scale; tx++) {
                        gray.set(originX + tx, originY + ty, 0);
                    }
                }
            }
        }
        List<QrDetector.Found> found = QrDetector.read(gray);
        assertTrue(found.stream().anyMatch(hit -> "VERSION-TEN-QR".equals(hit.text())), found.toString());
    }

    /** Locked to zxing-cpp {@code QRDecodedBitStreamParserTest}. Byte mode without an ECI stays ISO-8859-1. */
    @Test
    void readsTheOfficialBitStreamFixtures() {
        assertEquals("\u00f1\u00f2\u00f3", QrDecoder.decodeStream(pack(
                0x04, 4, 0x03, 8, 0xF1, 8, 0xF2, 8, 0xF3, 8), 1));
        // cpp GuessTextEncoding would read this as halfwidth katakana. That guess is not ported.
        assertEquals("\u00a1\u00a2\u00a3\u00d0", QrDecoder.decodeStream(pack(
                0x04, 4, 0x04, 8, 0xA1, 8, 0xA2, 8, 0xA3, 8, 0xD0, 8), 1));
        assertEquals("\u00ed\u00f3\u00fa", QrDecoder.decodeStream(pack(
                0x07, 4, 0x02, 8, 0x04, 4, 0x03, 8, 0xA1, 8, 0xA2, 8, 0xA3, 8), 1));
        assertEquals("\u963f", QrDecoder.decodeStream(pack(0x0D, 4, 0x01, 4, 0x01, 8, 0x03C1, 13), 1));
        assertEquals("\u30a2", QrDecoder.decodeStream(pack(0x0D, 4, 0x01, 4, 0x01, 8, 0x0181, 13), 1));
        // Kanji values 28 and 31 are Shift-JIS 0x815C and 0x815F. Java's Shift_JIS maps those to U+2014 and U+FF3C.
        assertEquals("\u2015", QrDecoder.decodeStream(pack(0x08, 4, 1, 8, 28, 13), 1));
        assertEquals("\\", QrDecoder.decodeStream(pack(0x08, 4, 1, 8, 31, 13), 1));

        assertEquals("A", QrDecoder.decodeStream(raw(0x20, 0x09, 0x40), 1));
        assertEquals("2001", QrDecoder.decodeStream(raw(0x51, 0x01, 0x0C, 0x81, 0x00), 1));
        assertEquals("2001301", QrDecoder.decodeStream(raw(0x10, 0x10, 0xC8, 0x15, 0x10, 0x0D, 0x2D, 0x00), 1));
        assertEquals("99A", QrDecoder.decodeStream(raw(0x96, 0x32, 0x00, 0x94, 0x00), 1));
        assertEquals("AB", QrDecoder.decodeStream(raw(0x9A, 0x52, 0x00, 0x96, 0x00), 1));
        assertEquals("aB", QrDecoder.decodeStream(raw(0x9C, 0x52, 0x00, 0x96, 0x00), 1));
        assertNull(QrDecoder.decodeStream(raw(0x9A, 0x42, 0x00, 0x96, 0x00), 1));
        assertEquals("9112%\u001d2012", QrDecoder.decodeStream(raw(
                0x52, 0x05, 0x99, 0x60, 0x5F, 0xB5, 0x35, 0x80, 0x01, 0x08, 0x00, 0xEC, 0x11), 1));
    }

    /**
     * Locked to zxing-cpp {@code QRFormatInformationTest} model 2.
     * Equality is the data mask, the error-correction level, and the model. Hamming is not compared.
     * Level 2 is Quality. rMQR is not decoded.
     */
    @Test
    void readsTheOfficialFormatInformation() {
        QrDecoder.Format expected = QrDecoder.decodeFormat(MASKED_FORMAT, MASKED_FORMAT_2);
        assertTrue(expected.hamming <= 3);
        assertFalse(expected.model1());
        assertEquals(0x07, expected.dataMask);
        assertEquals(2, expected.ecLevel);
        assertSameFormat(expected, QrDecoder.decodeFormat(MASKED_FORMAT ^ 0x5412, MASKED_FORMAT_2));

        assertSameFormat(expected, QrDecoder.decodeFormat(MASKED_FORMAT ^ 0x01, MASKED_FORMAT_2 ^ 0x01));
        assertSameFormat(expected, QrDecoder.decodeFormat(MASKED_FORMAT ^ 0x03, MASKED_FORMAT_2 ^ 0x03));
        assertSameFormat(expected, QrDecoder.decodeFormat(MASKED_FORMAT ^ 0x07, MASKED_FORMAT_2 ^ 0x07));
        QrDecoder.Format unexpected = QrDecoder.decodeFormat(MASKED_FORMAT ^ 0x0F, MASKED_FORMAT_2 ^ 0x0F);
        assertFalse(sameFormat(expected, unexpected));
        assertFalse(unexpected.hamming <= 3 && !unexpected.model1());

        assertSameFormat(expected, QrDecoder.decodeFormat(MASKED_FORMAT ^ 0x03, MASKED_FORMAT_2 ^ 0x0F));
    }

    /**
     * Locked to zxing-cpp {@code QRVersionTest} model-2 numbers and
     * {@code GetProvisionalVersionForDimension}, and to {@code QRErrorCorrectionLevelTest::ForBits}.
     * Version 1 has no alignment centers. L is 0, M is 1, Q is 2, H is 3.
     */
    @Test
    void matchesOfficialVersionDimensionsAndLevels() {
        assertEquals(0, QrDecoder.versionForDimension(0));
        assertEquals(0, QrDecoder.versionForDimension(17));
        assertEquals(0, QrDecoder.versionForDimension(20));
        assertEquals(0, QrDecoder.dimensionForVersion(0));
        assertEquals(0, QrDecoder.dimensionForVersion(41));
        assertEquals(-1, QrDecoder.alignmentCenterCount(0));
        for (int i = 1; i <= 40; i++) {
            int dimension = 17 + 4 * i;
            assertEquals(dimension, QrDecoder.dimensionForVersion(i), "dimension " + i);
            assertEquals(i, QrDecoder.versionForDimension(dimension), "version " + i);
            int centers = QrDecoder.alignmentCenterCount(i);
            if (i > 1) {
                assertTrue(centers > 0, "alignment " + i);
            } else {
                assertEquals(0, centers);
            }
        }
        assertEquals(1, QrDecoder.ecLevelFromBits(0));
        assertEquals(0, QrDecoder.ecLevelFromBits(1));
        assertEquals(3, QrDecoder.ecLevelFromBits(2));
        assertEquals(2, QrDecoder.ecLevelFromBits(3));
        assertEquals(1, QrDecoder.ecLevelFromBits(4));
        assertEquals(2, QrDecoder.ecLevelFromBits(-1));
    }

    /**
     * Locked to zxing-cpp {@code QRModeTest::CharacterCount} and the model-2 widths in {@code QRCodecMode.cpp}.
     * Versions 1–9, 10–26, and 27–40. Hanzi uses the Kanji width.
     */
    @Test
    void matchesOfficialCharacterCountBits() {
        assertEquals(10, QrDecoder.countBits(0x01, 5));
        assertEquals(12, QrDecoder.countBits(0x01, 26));
        assertEquals(14, QrDecoder.countBits(0x01, 40));
        assertEquals(9, QrDecoder.countBits(0x02, 6));
        assertEquals(8, QrDecoder.countBits(0x04, 7));
        assertEquals(8, QrDecoder.countBits(0x08, 8));
        int[] numeric = {10, 12, 14};
        int[] alphanumeric = {9, 11, 13};
        int[] bytes = {8, 16, 16};
        int[] kanji = {8, 10, 12};
        for (int version = 1; version <= 40; version++) {
            int band = version <= 9 ? 0 : version <= 26 ? 1 : 2;
            assertEquals(numeric[band], QrDecoder.countBits(0x01, version), "numeric " + version);
            assertEquals(alphanumeric[band], QrDecoder.countBits(0x02, version), "alnum " + version);
            assertEquals(bytes[band], QrDecoder.countBits(0x04, version), "byte " + version);
            assertEquals(kanji[band], QrDecoder.countBits(0x08, version), "kanji " + version);
            assertEquals(kanji[band], QrDecoder.countBits(0x0D, version), "hanzi " + version);
            assertEquals(0, QrDecoder.countBits(0x00, version));
            assertEquals(0, QrDecoder.countBits(0x10, version));
        }
    }

    /** Locked to zxing-cpp {@code QRDataMaskTest}. Masks 5..7 use the test predicates, which match {@code QRDataMask.h}. */
    @Test
    void matchesTheOfficialDataMasks() {
        for (int mask = 0; mask < 8; mask++) {
            for (int version = 1; version <= 40; version++) {
                int dimension = 17 + 4 * version;
                for (int y = 0; y < dimension; y++) {
                    for (int x = 0; x < dimension; x++) {
                        if (QrDecoder.dataMask(mask, x, y) != officialMask(mask, x, y)) {
                            fail("mask " + mask + " version " + version + " at " + x + "," + y);
                        }
                    }
                }
            }
        }
    }

    private static void assertRoundTrip(
            String text,
            ErrorCorrectionLevel level,
            int version,
            Map<EncodeHintType, Object> extra
    ) throws Exception {
        QRCode code = encode(text, level, version, extra);
        assertEquals(version, code.getVersion().getVersionNumber(), text);
        Bits bits = modules(code);
        assertEquals(text, QrDecoder.decode(bits), "v" + version + " " + text);
        assertEquals(text, zxingText(bits), "zxing v" + version + " " + text);
    }

    /**
     * Locked to zxing-cpp {@code QREncoderTest}. Each pair of characters is one module.
     * The grid is the symbol. A leading FNC1 is not part of the text. A later one is U+001D.
     * The Shift_JIS kanji pair is U+65E5 U+672C.
     */
    @Test
    void readsTheOfficialEncoderPictures() {
        assertEquals("ABCDEF", QrDecoder.decode(spaced(WRITER_ALPHANUMERIC)));
        assertEquals("hello", QrDecoder.decode(spaced(WRITER_UTF8)));
        assertEquals("\u00e9", QrDecoder.decode(spaced(WRITER_BINARY)));
        assertEquals("\u65e5\u672c", QrDecoder.decode(spaced(WRITER_KANJI)));
        assertEquals("0123", QrDecoder.decode(spaced(WRITER_NUMERIC)));
        assertEquals("100001\u001d11171218", QrDecoder.decode(spaced(WRITER_GS1)));
        assertEquals("hello", QrDecoder.decode(spaced(WRITER_GS1_ECI)));
    }

    /** Writer art uses a space between modules. Trailing spaces are part of the row. */
    private static Bits spaced(String[] rows) {
        int width = rows[0].length() / 2;
        Bits bits = new Bits(width, rows.length);
        for (int y = 0; y < rows.length; y++) {
            if (rows[y].length() != width * 2) {
                throw new IllegalArgumentException("row " + y);
            }
            for (int x = 0; x < width; x++) {
                if (rows[y].charAt(x * 2) == 'X') {
                    bits.set(x, y);
                }
            }
        }
        return bits;
    }

    private static final String[] WRITER_ALPHANUMERIC = {
            "X X X X X X X     X   X     X X X X X X X ",
            "X           X   X   X   X   X           X ",
            "X   X X X   X               X   X X X   X ",
            "X   X X X   X     X     X   X   X X X   X ",
            "X   X X X   X     X   X     X   X X X   X ",
            "X           X   X     X X   X           X ",
            "X X X X X X X   X   X   X   X X X X X X X ",
            "                X       X                 ",
            "        X X X X   X X   X   X X       X   ",
            "        X X   X X X     X X X X   X X   X ",
            "X         X X     X   X       X X X   X X ",
            "X     X X X     X X X X         X         ",
            "  X X X X X X   X   X   X X X     X X     ",
            "                X X       X X       X   X ",
            "X X X X X X X   X X X X           X X     ",
            "X           X   X X   X       X   X X X X ",
            "X   X X X   X   X     X       X X     X X ",
            "X   X X X   X       X X   X         X X X ",
            "X   X X X   X     X   X       X X         ",
            "X           X     X     X     X X       X ",
            "X X X X X X X       X     X         X X X ",
    };

    private static final String[] WRITER_UTF8 = {
            "X X X X X X X       X X     X X X X X X X ",
            "X           X       X X     X           X ",
            "X   X X X   X   X     X X   X   X X X   X ",
            "X   X X X   X   X       X   X   X X X   X ",
            "X   X X X   X     X X       X   X X X   X ",
            "X           X         X     X           X ",
            "X X X X X X X   X   X   X   X X X X X X X ",
            "                  X X X X                 ",
            "      X X   X X         X         X X     ",
            "                X X   X     X   X X X X X ",
            "X X       X X X       X X     X   X   X X ",
            "        X X     X           X   X X       ",
            "  X X     X X     X X X   X X X X X X X X ",
            "                X X X   X X X X X X X X X ",
            "X X X X X X X   X   X       X             ",
            "X           X     X       X       X X     ",
            "X   X X X   X   X       X   X       X     ",
            "X   X X X   X   X X X X   X     X   X X   ",
            "X   X X X   X     X X X     X     X   X X ",
            "X           X             X X   X X       ",
            "X X X X X X X         X   X     X   X     ",
    };

    private static final String[] WRITER_BINARY = {
            "X X X X X X X     X X X X   X X X X X X X ",
            "X           X           X   X           X ",
            "X   X X X   X   X X   X     X   X X X   X ",
            "X   X X X   X   X X X X X   X   X X X   X ",
            "X   X X X   X       X       X   X X X   X ",
            "X           X     X     X   X           X ",
            "X X X X X X X   X   X   X   X X X X X X X ",
            "                    X X X                 ",
            "      X X   X X     X             X X     ",
            "X   X           X X       X     X   X   X ",
            "X X       X X X X X X X     X   X X X X X ",
            "X   X X X     X X   X     X       X X X X ",
            "      X   X X   X       X X X       X X X ",
            "                X   X X X     X   X   X X ",
            "X X X X X X X   X       X X X X X     X X ",
            "X           X     X       X   X X     X   ",
            "X   X X X   X   X   X X X X   X     X   X ",
            "X   X X X   X   X X   X   X X   X X X     ",
            "X   X X X   X           X   X     X X X   ",
            "X           X     X X X         X   X     ",
            "X X X X X X X     X X X   X X       X     ",
    };

    private static final String[] WRITER_KANJI = {
            "X X X X X X X     X   X     X X X X X X X ",
            "X           X   X X         X           X ",
            "X   X X X   X     X X X X   X   X X X   X ",
            "X   X X X   X           X   X   X X X   X ",
            "X   X X X   X   X X X X X   X   X X X   X ",
            "X           X     X X X     X           X ",
            "X X X X X X X   X   X   X   X X X X X X X ",
            "                    X                     ",
            "X   X   X   X       X   X       X     X   ",
            "X X   X       X   X X X   X   X   X       ",
            "  X         X X X X X X   X X X   X   X   ",
            "X X X     X   X       X X X   X X   X     ",
            "  X X     X X   X X   X   X X X   X     X ",
            "                X   X       X       X   X ",
            "X X X X X X X           X       X     X X ",
            "X           X       X       X       X X X ",
            "X   X X X   X   X       X   X   X   X   X ",
            "X   X X X   X         X   X   X   X   X   ",
            "X   X X X   X   X   X X   X X X     X   X ",
            "X           X         X X X   X X X   X   ",
            "X X X X X X X   X X   X   X X X     X     ",
    };

    private static final String[] WRITER_NUMERIC = {
            "X X X X X X X     X X   X   X X X X X X X ",
            "X           X     X     X   X           X ",
            "X   X X X   X   X           X   X X X   X ",
            "X   X X X   X   X   X X X   X   X X X   X ",
            "X   X X X   X   X X   X X   X   X X X   X ",
            "X           X   X X     X   X           X ",
            "X X X X X X X   X   X   X   X X X X X X X ",
            "                X X X X X                 ",
            "X   X X X X X     X X   X   X X X X X     ",
            "X X       X     X   X   X     X     X     ",
            "  X X   X X X X   X X X   X     X X   X X ",
            "X   X X   X   X     X         X X   X     ",
            "    X     X X X       X   X     X   X     ",
            "                X X   X X X X     X       ",
            "X X X X X X X       X   X   X X           ",
            "X           X   X X   X X X X     X   X   ",
            "X   X X X   X   X   X   X     X     X     ",
            "X   X X X   X   X X X   X     X     X     ",
            "X   X X X   X   X X   X   X     X X X     ",
            "X           X       X         X X   X X   ",
            "X X X X X X X   X X   X   X     X X X     ",
    };

    private static final String[] WRITER_GS1 = {
            "X X X X X X X     X X X X   X   X   X X X X X X X ",
            "X           X   X X           X X   X           X ",
            "X   X X X   X           X X X   X   X   X X X   X ",
            "X   X X X   X     X   X     X X     X   X X X   X ",
            "X   X X X   X       X X X       X   X   X X X   X ",
            "X           X   X X   X X   X X     X           X ",
            "X X X X X X X   X   X   X   X   X   X X X X X X X ",
            "                X X   X X   X X                   ",
            "        X X X X     X X       X X   X X       X   ",
            "  X X   X X     X X X       X X X X X X X       X ",
            "    X X X X X   X X X X X   X             X X X   ",
            "X   X X X     X X X   X X X X X   X X   X X X     ",
            "  X   X     X X X X X X     X X   X           X   ",
            "X     X X X     X X       X X   X   X   X         ",
            "    X     X X X   X X   X X X   X X X   X X X X   ",
            "      X X     X     X     X X     X       X X X   ",
            "X X   X   X X   X   X       X X X X X X X         ",
            "                X X   X       X X       X X   X   ",
            "X X X X X X X   X   X   X   X X X   X   X         ",
            "X           X   X X       X   X X       X   X X   ",
            "X   X X X   X   X X X           X X X X X X     X ",
            "X   X X X   X             X X X     X X   X       ",
            "X   X X X   X       X X   X   X X X   X X     X   ",
            "X           X     X X   X X X X X   X   X X       ",
            "X X X X X X X       X         X X     X X     X X ",
    };

    private static final String[] WRITER_GS1_ECI = {
            "X X X X X X X   X   X X     X X X X X X X ",
            "X           X     X X       X           X ",
            "X   X X X   X   X X X       X   X X X   X ",
            "X   X X X   X     X   X     X   X X X   X ",
            "X   X X X   X   X   X       X   X X X   X ",
            "X           X     X X X X   X           X ",
            "X X X X X X X   X   X   X   X X X X X X X ",
            "                X   X X X                 ",
            "          X X     X X       X   X   X   X ",
            "  X   X X     X   X X X X X X   X X X   X ",
            "  X   X X X X   X X       X   X   X X     ",
            "X X X X   X   X     X   X     X X X X     ",
            "X     X     X X   X X   X   X     X     X ",
            "                X X X X X   X   X     X   ",
            "X X X X X X X       X X     X       X X   ",
            "X           X   X X         X   X X X     ",
            "X   X X X   X     X     X   X   X       X ",
            "X   X X X   X           X X X   X X X X   ",
            "X   X X X   X       X     X     X   X X X ",
            "X           X     X       X X     X X X X ",
            "X X X X X X X     X X X   X X   X     X   ",
    };

    private static QRCode encode(
            String text,
            ErrorCorrectionLevel level,
            int version,
            Map<EncodeHintType, Object> extra
    ) throws Exception {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.QR_VERSION, version);
        if (extra != null) {
            hints.putAll(extra);
        }
        return Encoder.encode(text, level, hints);
    }

    private static Bits modules(QRCode code) {
        ByteMatrix matrix = code.getMatrix();
        Bits bits = new Bits(matrix.getWidth(), matrix.getHeight());
        for (int y = 0; y < matrix.getHeight(); y++) {
            for (int x = 0; x < matrix.getWidth(); x++) {
                if (matrix.get(x, y) != 0) {
                    bits.set(x, y);
                }
            }
        }
        return bits;
    }

    /** MSB-first fields, {@code (value, width)} pairs, padded with zero bits. {@code BitArray::toBytes}. */
    private static byte[] pack(int... fields) {
        int total = 0;
        for (int i = 1; i < fields.length; i += 2) {
            total += fields[i];
        }
        byte[] out = new byte[(total + 7) / 8];
        int cursor = 0;
        for (int i = 0; i < fields.length; i += 2) {
            int value = fields[i];
            int width = fields[i + 1];
            for (int bit = width - 1; bit >= 0; bit--) {
                if (((value >> bit) & 1) != 0) {
                    out[cursor / 8] |= (byte) (1 << (7 - (cursor % 8)));
                }
                cursor++;
            }
        }
        return out;
    }

    /** {@code MASKED_TEST_FORMAT_INFO}. The second sample still contains the dark module. */
    private static final int MASKED_FORMAT = 0x2BED;
    private static final int MASKED_FORMAT_2 =
            ((0x2BED << 1) & 0b1111111000000000) | 0b100000000 | (0x2BED & 0b11111111);

    private static void assertSameFormat(QrDecoder.Format expected, QrDecoder.Format actual) {
        assertTrue(sameFormat(expected, actual));
    }

    /** {@code FormatInformation::operator==}. The model is model 1 only when that format mask won. */
    private static boolean sameFormat(QrDecoder.Format expected, QrDecoder.Format actual) {
        return expected.dataMask == actual.dataMask
                && expected.ecLevel == actual.ecLevel
                && expected.model1() == actual.model1();
    }

    /** {@code i} is the row and {@code j} is the column, as in {@code QRDataMaskTest}. */
    private static boolean officialMask(int mask, int x, int y) {
        int i = y;
        int j = x;
        return switch (mask) {
            case 0 -> (i + j) % 2 == 0;
            case 1 -> i % 2 == 0;
            case 2 -> j % 3 == 0;
            case 3 -> (i + j) % 3 == 0;
            case 4 -> (i / 2 + j / 3) % 2 == 0;
            case 5 -> (i * j) % 2 + (i * j) % 3 == 0;
            case 6 -> ((i * j) % 2 + (i * j) % 3) % 2 == 0;
            case 7 -> ((i + j) % 2 + (i * j) % 3) % 2 == 0;
            default -> false;
        };
    }

    private static byte[] raw(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }

    private static String zxingText(Bits bits) throws ReaderException {
        BitMatrix matrix = new BitMatrix(bits.width, bits.height);
        for (int y = 0; y < bits.height; y++) {
            for (int x = 0; x < bits.width; x++) {
                if (bits.get(x, y)) {
                    matrix.set(x, y);
                }
            }
        }
        return new Decoder().decode(matrix).getText();
    }
}
