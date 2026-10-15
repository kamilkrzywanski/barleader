package io.github.kamilkrzywanski.barcode;

import com.google.zxing.EncodeHintType;
import com.google.zxing.oned.CodaBarWriter;
import com.google.zxing.oned.Code128Writer;
import com.google.zxing.oned.Code39Writer;
import com.google.zxing.oned.Code93Writer;
import com.google.zxing.oned.EAN13Writer;
import com.google.zxing.oned.EAN8Writer;
import com.google.zxing.oned.ITFWriter;
import com.google.zxing.oned.UPCEWriter;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BarcodeWriterTest {
    private static boolean[] bars(Barcode code) {
        assertEquals(1, code.height());
        boolean[] bars = new boolean[code.width()];
        for (int x = 0; x < bars.length; x++) {
            bars[x] = code.isDark(x, 0);
        }
        return bars;
    }

    private static boolean[] bars(BarcodeType type, String content, WriteOptions options) {
        return bars(BarcodeWriter.encode(type, content, options));
    }

    private static String modules(boolean[] bars) {
        StringBuilder out = new StringBuilder(bars.length);
        for (boolean bar : bars) {
            out.append(bar ? '1' : '0');
        }
        return out.toString();
    }

    /** Barcode-Lib4J's modules of a symbol, its quiet zones stripped. */
    private static String barcodeLib4j(de.vwsoft.barcodelib4j.oned.BarcodeType type, String content, boolean checksum, String addOn)
            throws Exception {
        de.vwsoft.barcodelib4j.oned.Barcode code = de.vwsoft.barcodelib4j.oned.Barcode.newInstance(type, content, true, checksum);
        if (addOn != null) {
            code.setAddOn(addOn);
        }
        Method encode = de.vwsoft.barcodelib4j.oned.Barcode.class.getDeclaredMethod("encode");
        encode.setAccessible(true);
        String modules = encode.invoke(code).toString();
        int first = modules.indexOf('1');
        int last = modules.lastIndexOf('1');
        return modules.substring(first, last + 1);
    }

    private static List<String> asciiSamples() {
        Random random = new Random(128);
        List<String> samples = new java.util.ArrayList<>(List.of("A", "12", "123", "1234", "ABC-123", "Hello, World!", "0123456789012345",
                "a1b2c3d4", "\u0001\u0002ABC\u001f", "9999x8888", "x12345678901"));
        for (int i = 0; i < 300; i++) {
            StringBuilder text = new StringBuilder();
            int length = 1 + random.nextInt(30);
            for (int j = 0; j < length; j++) {
                text.append(random.nextInt(4) == 0 ? (char) ('0' + random.nextInt(10)) : (char) random.nextInt(128));
            }
            samples.add(text.toString());
        }
        return samples;
    }

    @Test
    void code128MatchesZxingInEveryMode() {
        Code128Writer zxing = new Code128Writer();
        WriteOptions compact = WriteOptions.defaults().compact(true);
        for (String text : asciiSamples()) {
            assertArrayEquals(zxing.encode(text), bars(BarcodeType.CODE_128, text, WriteOptions.defaults()), text);
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.CODE128_COMPACT, true);
            assertArrayEquals(zxing.encode(text, hints), bars(BarcodeType.CODE_128, text, compact), text);
            for (String set : new String[]{"A", "B"}) {
                Map<EncodeHintType, Object> forced = new EnumMap<>(EncodeHintType.class);
                forced.put(EncodeHintType.FORCE_CODE_SET, set);
                boolean[] expected;
                try {
                    expected = zxing.encode(text, forced);
                } catch (IllegalArgumentException cannot) {
                    BarcodeType type = set.equals("A") ? BarcodeType.CODE_128_A : BarcodeType.CODE_128_B;
                    assertThrows(IllegalArgumentException.class, () -> bars(type, text, WriteOptions.defaults()), text);
                    continue;
                }
                assertArrayEquals(expected, bars(set.equals("A") ? BarcodeType.CODE_128_A : BarcodeType.CODE_128_B, text, WriteOptions.defaults()));
            }
        }
        Map<EncodeHintType, Object> forcedC = new EnumMap<>(EncodeHintType.class);
        forcedC.put(EncodeHintType.FORCE_CODE_SET, "C");
        assertArrayEquals(zxing.encode("01234567", forcedC), bars(BarcodeType.CODE_128_C, "01234567", WriteOptions.defaults()));
        assertArrayEquals(zxing.encode("01234567", forcedC), bars(BarcodeType.CODE_128_C, "1234567", WriteOptions.defaults()));
    }

    @Test
    void theWideAndNarrowSymbologiesMatchZxingAtItsRatios() {
        WriteOptions two = WriteOptions.defaults().wideRatio(2.0);
        WriteOptions three = WriteOptions.defaults().wideRatio(3.0);
        for (String text : List.of("A", "CODE-39", "HELLO WORLD $/+%", "0123456789")) {
            assertArrayEquals(new Code39Writer().encode(text), bars(BarcodeType.CODE_39, text, two), text);
        }
        for (String text : asciiSamples()) {
            assertArrayEquals(new Code39Writer().encode(text), bars(BarcodeType.CODE_39_EXTENDED, text, two), text);
            assertArrayEquals(new Code93Writer().encode(text), bars(BarcodeType.CODE_93_EXTENDED, text, WriteOptions.defaults()), text);
        }
        assertArrayEquals(new Code93Writer().encode("CODE 93"), bars(BarcodeType.CODE_93, "CODE 93", WriteOptions.defaults()));
        for (String text : List.of("0123456789", "A1234B", "T-$:/.+N", "1", "")) {
            assertArrayEquals(new CodaBarWriter().encode(text), bars(BarcodeType.CODABAR, text, two), text);
        }
        for (String text : List.of("00", "0123456789", "12345678901231")) {
            assertArrayEquals(new ITFWriter().encode(text), bars(BarcodeType.ITF, text, three), text);
        }
    }

    @Test
    void eanAndUpcMatchZxing() {
        for (String text : List.of("400638133393", "4006381333931", "590123412345", "978316148410")) {
            assertArrayEquals(new EAN13Writer().encode(text), bars(BarcodeType.EAN_13, text, WriteOptions.defaults()), text);
        }
        assertArrayEquals(new EAN8Writer().encode("9638507"), bars(BarcodeType.EAN_8, "9638507", WriteOptions.defaults()));
        assertArrayEquals(new EAN13Writer().encode("003600029145"), bars(BarcodeType.UPC_A, "03600029145", WriteOptions.defaults()));
        for (String text : List.of("0123456", "01234565", "1654321")) {
            assertArrayEquals(new UPCEWriter().encode(text), bars(BarcodeType.UPC_E, text, WriteOptions.defaults()), text);
        }
        assertThrows(IllegalArgumentException.class, () -> BarcodeWriter.encode(BarcodeType.EAN_13, "4006381333932"));
    }

    @Test
    void theRestMatchBarcodeLib4j() throws Exception {
        WriteOptions defaults = WriteOptions.defaults();
        WriteOptions checksum = defaults.checksum(true);
        assertEquals(barcodeLib4j(de.vwsoft.barcodelib4j.oned.BarcodeType.CODE11, "1234-5678", false, null),
                modules(bars(BarcodeType.CODE_11, "1234-5678", defaults)));
        assertEquals(barcodeLib4j(de.vwsoft.barcodelib4j.oned.BarcodeType.CODE11, "1234-5678", true, null),
                modules(bars(BarcodeType.CODE_11, "1234-5678", checksum)));
        assertEquals(barcodeLib4j(de.vwsoft.barcodelib4j.oned.BarcodeType.CODE39, "CODE39", false, null),
                modules(bars(BarcodeType.CODE_39, "CODE39", defaults)));
        assertEquals(barcodeLib4j(de.vwsoft.barcodelib4j.oned.BarcodeType.CODE39, "CODE39", true, null),
                modules(bars(BarcodeType.CODE_39, "CODE39", checksum)));
        assertEquals(barcodeLib4j(de.vwsoft.barcodelib4j.oned.BarcodeType.CODE39E, "Code 39e!", false, null),
                modules(bars(BarcodeType.CODE_39_EXTENDED, "Code 39e!", defaults)));
        assertEquals(barcodeLib4j(de.vwsoft.barcodelib4j.oned.BarcodeType.CODE93E, "Code 93e!", false, null),
                modules(bars(BarcodeType.CODE_93_EXTENDED, "Code 93e!", defaults)));
        assertEquals(barcodeLib4j(de.vwsoft.barcodelib4j.oned.BarcodeType.ITF, "12345678", false, null),
                modules(bars(BarcodeType.ITF, "12345678", defaults)));
        assertEquals(barcodeLib4j(de.vwsoft.barcodelib4j.oned.BarcodeType.CODABAR, "A1234-5678B", false, null),
                modules(bars(BarcodeType.CODABAR, "A1234-5678B", defaults)));
        assertEquals(barcodeLib4j(de.vwsoft.barcodelib4j.oned.BarcodeType.PZN, "123456", false, null),
                modules(bars(BarcodeType.PZN, "123456", defaults)));
        assertEquals(barcodeLib4j(de.vwsoft.barcodelib4j.oned.BarcodeType.PZN8, "1234567", false, null),
                modules(bars(BarcodeType.PZN_8, "1234567", defaults)));
        assertEquals(barcodeLib4j(de.vwsoft.barcodelib4j.oned.BarcodeType.ISBN13, "978-3-16-148410", false, null),
                modules(bars(BarcodeType.ISBN_13, "978-3-16-148410", defaults)));
        assertEquals(barcodeLib4j(de.vwsoft.barcodelib4j.oned.BarcodeType.ISMN, "979-0-2600-0043", false, null),
                modules(bars(BarcodeType.ISMN, "979-0-2600-0043", defaults)));
        assertEquals(barcodeLib4j(de.vwsoft.barcodelib4j.oned.BarcodeType.EAN13, "590123412345", false, "12"),
                modules(bars(BarcodeType.EAN_13, "590123412345", defaults.addOn("12"))));
        assertEquals(barcodeLib4j(de.vwsoft.barcodelib4j.oned.BarcodeType.UPCA, "03600029145", false, "51299"),
                modules(bars(BarcodeType.UPC_A, "03600029145", defaults.addOn("51299"))));
        assertEquals(barcodeLib4j(de.vwsoft.barcodelib4j.oned.BarcodeType.EAN8, "9638507", false, "52495"),
                modules(bars(BarcodeType.EAN_8, "9638507", defaults.addOn("52495"))));
        assertEquals(barcodeLib4j(de.vwsoft.barcodelib4j.oned.BarcodeType.UPCE, "0123456", false, "07"),
                modules(bars(BarcodeType.UPC_E, "0123456", defaults.addOn("07"))));
        String gs1 = "(01)09501101530003(17)250630(10)AB-123";
        assertEquals(barcodeLib4j(de.vwsoft.barcodelib4j.oned.BarcodeType.EAN128, gs1, false, null),
                modules(bars(BarcodeType.GS1_128, gs1, defaults)));
        assertEquals(barcodeLib4j(de.vwsoft.barcodelib4j.oned.BarcodeType.EAN14, "0950110153000", false, null),
                modules(bars(BarcodeType.EAN_14, "0950110153000", defaults)));
        assertEquals(barcodeLib4j(de.vwsoft.barcodelib4j.oned.BarcodeType.SSCC18, "00950110153000001", false, null),
                modules(bars(BarcodeType.SSCC_18, "00950110153000001", defaults)));
        assertEquals(barcodeLib4j(de.vwsoft.barcodelib4j.oned.BarcodeType.CODE128C, "12345678", false, null),
                modules(bars(BarcodeType.CODE_128_C, "12345678", defaults)));
    }

    @Test
    void theReaderReadsWhatTheWriterWrites() {
        BarcodeReader reader = BarcodeReader.sequential();
        ReadOptions all = ReadOptions.fast().symbologies(Symbology.values());
        record Case(BarcodeType type, String content, Symbology symbology, String text) {
        }
        for (Case c : List.of(
                new Case(BarcodeType.CODE_128, "Hello, World!", Symbology.CODE_128, "Hello, World!"),
                new Case(BarcodeType.CODE_128_C, "12345678", Symbology.CODE_128, "12345678"),
                new Case(BarcodeType.GS1_128, "(01)09501101530003(10)AB-123", Symbology.CODE_128, "010950110153000310AB-123"),
                new Case(BarcodeType.GS1_128, "(10)AB-123\u001D(17)250630", Symbology.CODE_128, "10AB-123\u001D17250630"),
                new Case(BarcodeType.CODE_39, "CODE-39", Symbology.CODE_39, "CODE-39"),
                new Case(BarcodeType.PZN, "123456", Symbology.CODE_39, "-1234562"),
                new Case(BarcodeType.CODE_93, "CODE 93", Symbology.CODE_93, "CODE 93"),
                new Case(BarcodeType.CODE_93_EXTENDED, "Code 93!", Symbology.CODE_93, "Code 93!"),
                new Case(BarcodeType.CODABAR, "A1234-5678B", Symbology.CODABAR, "A1234-5678B"),
                new Case(BarcodeType.ITF, "12345678", Symbology.ITF, "12345678"),
                new Case(BarcodeType.EAN_13, "590123412345", Symbology.EAN_13, "5901234123457"),
                new Case(BarcodeType.EAN_8, "9638507", Symbology.EAN_8, "96385074"),
                new Case(BarcodeType.UPC_A, "03600029145", Symbology.UPC_A, "036000291452"),
                new Case(BarcodeType.UPC_E, "0123456", Symbology.UPC_E, "01234565"),
                new Case(BarcodeType.ISBN_13, "978-3-16-148410", Symbology.EAN_13, "9783161484100"),
                new Case(BarcodeType.QR_CODE, "Zażółć", Symbology.QR_CODE, "Zażółć"),
                new Case(BarcodeType.GS1_QR_CODE, "(01)09501101530003(10)AB-123", Symbology.QR_CODE, "010950110153000310AB-123"))) {
            Barcode code = BarcodeWriter.encode(c.type(), c.content());
            List<BarcodeHit> hits = reader.read(code.toImage(3), all);
            assertEquals(1, hits.size(), c.toString());
            assertEquals(c.symbology(), hits.get(0).symbology(), c.toString());
            assertEquals(c.text(), hits.get(0).text(), c.toString());
        }
    }

    @Test
    void textsAndQuietZonesFollowTheSymbology() {
        Barcode isbn = BarcodeWriter.encode(BarcodeType.ISBN_13, "978-3-16-148410");
        assertEquals("ISBN 978-3-16-148410-0", isbn.text());
        assertEquals(11, isbn.quietZoneLeft());
        assertEquals(7, isbn.quietZoneRight());
        Barcode pzn = BarcodeWriter.encode(BarcodeType.PZN, "123456");
        assertEquals("PZN -1234562", pzn.text());
        assertEquals(20, pzn.quietZoneLeft());
        Barcode gs1 = BarcodeWriter.encode(BarcodeType.GS1_128, "0109501101530003");
        assertEquals("(01)09501101530003", gs1.text());
        Barcode withAddOn = BarcodeWriter.encode(BarcodeType.EAN_13, "590123412345", WriteOptions.defaults().addOn("12"));
        assertEquals("5901234123457 12", withAddOn.text());
        assertEquals(5, withAddOn.quietZoneRight());
        assertTrue(BarcodeWriter.encode(BarcodeType.CODE_128, "A").toSvg().contains("viewBox=\"0 0 "));
        assertEquals((BarcodeWriter.encode(BarcodeType.EAN_8, "9638507").width() + 14) * 2,
                BarcodeWriter.encode(BarcodeType.EAN_8, "9638507").toImage(2).getWidth());
        assertThrows(IllegalArgumentException.class, () -> BarcodeWriter.encode(BarcodeType.CODE_39, "lowercase"));
        assertThrows(IllegalArgumentException.class, () -> BarcodeWriter.encode(BarcodeType.GS1_128, "(01)09501101530004"));
        assertThrows(IllegalArgumentException.class, () -> BarcodeWriter.encode(BarcodeType.ITF, "123"));
    }
}
