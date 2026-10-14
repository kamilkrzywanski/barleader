package io.github.kamilkrzywanski.barcode.decode;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.ChecksumException;
import com.google.zxing.EncodeHintType;
import com.google.zxing.FormatException;
import com.google.zxing.NotFoundException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitArray;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.oned.Code128Reader;
import com.google.zxing.oned.Code128Writer;
import io.github.kamilkrzywanski.barcode.BarcodeHit;
import io.github.kamilkrzywanski.barcode.Symbology;
import io.github.kamilkrzywanski.barcode.image.PixelMap;
import io.github.kamilkrzywanski.barcode.image.ZxingLuminance;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Code128DecoderTest {
    @Test
    void roundTripsCodeSetsAndScales() throws Exception {
        assertRoundTrip("CODE-128", null);
        assertRoundTrip("Hi 128", null);
        assertRoundTrip("0011223344", null);
        Map<EncodeHintType, Object> setA = new EnumMap<>(EncodeHintType.class);
        setA.put(EncodeHintType.FORCE_CODE_SET, "A");
        assertRoundTrip("CODE128A", setA);
        Map<EncodeHintType, Object> setC = new EnumMap<>(EncodeHintType.class);
        setC.put(EncodeHintType.FORCE_CODE_SET, "C");
        assertRoundTrip("1234567890", setC);
        Map<EncodeHintType, Object> compact = new EnumMap<>(EncodeHintType.class);
        compact.put(EncodeHintType.CODE128_COMPACT, true);
        assertRoundTrip("Code128-Compact-42", compact);
        for (int scale : new int[]{1, 2, 3}) {
            boolean[] row = pixels("SCALE-" + scale, null, scale, 0);
            assertEquals("SCALE-" + scale, Code128Decoder.decode(row));
        }
    }

    /**
     * Locked to zxing-cpp {@code CreateBarcodeTest::ZintISO8859_1} plain texts.
     * The symbol is the ZXing writer. The Latin-1 rows are omitted: that writer
     * rejects bytes above 127. Identifiers and the HRI spellings stay out.
     */
    @Test
    void readsTheOfficialControlPayloads() throws Exception {
        assertRoundTrip("12\u0001\u007f34", null);
        assertRoundTrip("12\u000034", null);
    }

    @Test
    void roundTripsGrowingPayloads() throws Exception {
        for (int length = 1; length <= 40; length++) {
            assertRoundTrip("A".repeat(length), null);
            assertRoundTrip("1".repeat(length), null);
        }
        assertRoundTrip("A1".repeat(30), null);
    }

    @Test
    void agreesWithZxingOnAQuietRow() throws Exception {
        boolean[] row = pixels("ZX-QUIET", null, 2, 10);
        assertEquals("ZX-QUIET", Code128Decoder.decode(row));
        assertEquals("ZX-QUIET", zxing(row));
    }

    @Test
    void readsALaterFnc1AsGroupSeparator() {
        boolean[] row = pixels("AB\u00f1CD", null, 2, 10);
        assertEquals("AB\u001dCD", Code128Decoder.decode(row));
    }

    @Test
    void rejectsADamagedModuleAndABlankRow() {
        boolean[] row = pixels("CODE-128", null, 2, 10);
        int middle = row.length / 2;
        for (int i = middle; i < middle + 4 && i < row.length; i++) {
            row[i] = !row[i];
        }
        assertNull(Code128Decoder.decode(row));
        assertNull(Code128Decoder.decode(new boolean[40]));
        assertNull(Code128Decoder.decode(new boolean[0]));
    }

    /**
     * Locked to zxing-cpp {@code ODCode128ReaderTest}. The raw text drops a leading FNC1.
     * A later FNC1 is U+001D. HRI parentheses and the {@code <GS>} spelling are not applied.
     */
    @Test
    void readsTheOfficialPatterns() {
        assertEquals("2001", Code128Decoder.decode(symbol('C',
                2, 2, 1, 2, 3, 1, 2, 2, 2, 1, 2, 2, 3, 1, 1, 2, 2, 2)));
        assertEquals("2001", Code128Decoder.decode(symbol('C',
                4, 1, 1, 1, 3, 1, 2, 2, 1, 2, 3, 1, 2, 2, 2, 1, 2, 2, 1, 3, 2, 1, 3, 1)));
        assertEquals("AB", Code128Decoder.decode(symbol('B',
                1, 1, 1, 3, 2, 3, 4, 1, 1, 1, 3, 1, 1, 3, 1, 1, 2, 3, 2, 1, 2, 3, 2, 1)));
        assertEquals("zB", Code128Decoder.decode(symbol('B',
                2, 1, 4, 1, 2, 1, 4, 1, 1, 1, 3, 1, 1, 3, 1, 1, 2, 3, 4, 2, 1, 2, 1, 1)));
        assertEquals("99A", Code128Decoder.decode(symbol('C',
                1, 1, 3, 1, 4, 1, 4, 1, 1, 1, 3, 1, 1, 1, 4, 1, 3, 1, 1, 1, 1, 3, 2, 3, 1, 2, 3, 1, 2, 2)));
        assertEquals("?\u001dB", Code128Decoder.decode(symbol('B',
                2, 1, 2, 3, 2, 1, 4, 1, 1, 1, 3, 1, 1, 3, 1, 1, 2, 3, 3, 2, 2, 2, 1, 1)));
        // The third pattern is another FNC1 (102). The checksum is 101, so zxing-cpp sets ChecksumError
        // and still returns the text. A checksum failure here is null.
        assertNull(Code128Decoder.decode(symbol('B',
                4, 1, 1, 1, 3, 1, 4, 1, 1, 1, 3, 1, 4, 1, 1, 1, 3, 1)));
        assertEquals("A\u001d", Code128Decoder.decode(symbol('B',
                1, 1, 1, 3, 2, 3, 4, 1, 1, 1, 3, 1, 4, 1, 1, 1, 3, 1, 3, 2, 2, 2, 1, 1)));

        assertEquals("92", Code128Decoder.decode(symbol('C',
                1, 1, 1, 1, 4, 3, 1, 3, 1, 1, 4, 1)));
        assertEquals("92", Code128Decoder.decode(symbol('B',
                1, 1, 4, 3, 1, 1, 1, 1, 3, 1, 4, 1, 1, 1, 1, 1, 4, 3, 3, 3, 1, 1, 2, 1)));
        assertEquals("92", Code128Decoder.decode(symbol('B',
                3, 2, 1, 1, 2, 2, 1, 1, 4, 3, 1, 1, 2, 2, 3, 2, 1, 1, 1, 2, 1, 4, 2, 1)));

        assertEquals("a\u00e9\u00a0", Code128Decoder.decode(symbol('B',
                1, 2, 1, 1, 2, 4, 1, 1, 4, 1, 3, 1, 1, 4, 2, 1, 1, 2, 1, 1, 4, 1, 3, 1, 2, 1, 2, 2, 2, 2, 2, 2, 1, 4, 1, 1)));
    }

    /**
     * Locked to zxing-cpp {@code ODCode128WriterTest}. The five zeros on each side are the
     * writer margin. A leading FNC1 is dropped. FNC2 and FNC3 add no characters. FNC4 adds
     * 128 to the next character. A later FNC1 is U+001D.
     */
    @Test
    void readsTheOfficialWriterRows() {
        assertEquals("123", Code128Decoder.decode(ink(
                "00000110100111001111010111010110011100101111011101100101110010101111000110001110",
                "101100000"
        )));
        assertEquals("123", Code128Decoder.decode(ink(
                "00000110100100001111010100010011100110110011100101100101110011100010110110001110",
                "101100000"
        )));
        assertEquals("123", Code128Decoder.decode(ink(
                "00000110100100001011110001010011100110110011100101100101110011101000110110001110",
                "101100000"
        )));
        assertEquals("\u00b123", Code128Decoder.decode(ink(
                "00000110100100001011110111010011100110110011100101100101110011100011010110001110",
                "101100000"
        )));
        assertEquals("\n\u001d\u00b1\n", Code128Decoder.decode(ink(
                "00000110100001001000011001011110101110111010111101001110011010000110010101011110",
                "00110001110101100000"
        )));
        assertEquals("\u0000ABab\u0010", Code128Decoder.decode(ink(
                "00000110100001001010000110010100011000100010110001011110111010010110000100100001",
                "10111010111101010011110011001110100110001110101100000"
        )));
        assertEquals("ab\u0000ab", Code128Decoder.decode(ink(
                "00000110100100001001011000010010000110111010111101010000110010111101110100101100",
                "001001000011011010001110110001110101100000"
        )));
    }

    /** The only requested format is Code 128, so ZXing's format set for this call is empty. */
    @Test
    void readsARenderedSymbolOnTheScanPath() throws Exception {
        BitMatrix modules = new Code128Writer().encode("CODE-128", BarcodeFormat.CODE_128, 460, 140);
        EnumSet<Symbology> requested = EnumSet.of(Symbology.CODE_128);
        List<BarcodeHit> hits = ZxingDecoder.read(
                ZxingLuminance.of(MatrixToImageWriter.toBufferedImage(modules)),
                PixelMap.IDENTITY,
                "original-hybrid",
                0,
                0.92,
                requested,
                false,
                false
        );
        assertEquals(1, hits.size());
        BarcodeHit hit = hits.get(0);
        assertEquals("CODE-128", hit.text());
        assertEquals(Symbology.CODE_128, hit.symbology());
        assertEquals("original-hybrid", hit.attempt());
        assertEquals(-1, hit.orientationDegrees());
        assertEquals(2, hit.corners().size());
        assertTrue(requested.contains(Symbology.CODE_128));
    }

    /** {@code 1} is black. */
    private static boolean[] ink(String... parts) {
        String bits = String.join("", parts);
        boolean[] row = new boolean[bits.length()];
        for (int i = 0; i < bits.length(); i++) {
            row[i] = bits.charAt(i) == '1';
        }
        return row;
    }

    /** Start pattern, payload runs, then the stop pattern. Ten white modules of quiet zone sit on each side. */
    private static boolean[] symbol(char start, int... payload) {
        int[] prefix = switch (start) {
            case 'A' -> new int[]{0, 2, 1, 1, 4, 1, 2};
            case 'B' -> new int[]{0, 2, 1, 1, 2, 1, 4};
            case 'C' -> new int[]{0, 2, 1, 1, 2, 3, 2};
            default -> throw new IllegalArgumentException(String.valueOf(start));
        };
        int[] stop = {2, 3, 3, 1, 1, 1, 2, 0};
        int[] widths = new int[prefix.length + payload.length + stop.length];
        System.arraycopy(prefix, 0, widths, 0, prefix.length);
        System.arraycopy(payload, 0, widths, prefix.length, payload.length);
        System.arraycopy(stop, 0, widths, prefix.length + payload.length, stop.length);
        int modules = 20;
        for (int width : widths) {
            modules += width;
        }
        boolean[] row = new boolean[modules];
        int cursor = 10;
        boolean black = false;
        for (int width : widths) {
            for (int i = 0; i < width; i++) {
                row[cursor++] = black;
            }
            black = !black;
        }
        return row;
    }

    private static void assertRoundTrip(String text, Map<EncodeHintType, ?> hints) throws Exception {
        boolean[] row = pixels(text, hints, 2, 10);
        assertEquals(text, Code128Decoder.decode(row));
        assertEquals(text, zxing(row));
    }

    private static boolean[] pixels(String text, Map<EncodeHintType, ?> hints, int scale, int quiet) {
        boolean[] modules = new Code128Writer().encode(text, hints);
        boolean[] row = new boolean[(modules.length + quiet * 2) * scale];
        for (int i = 0; i < modules.length; i++) {
            if (modules[i]) {
                int at = (quiet + i) * scale;
                for (int s = 0; s < scale; s++) {
                    row[at + s] = true;
                }
            }
        }
        return row;
    }

    private static String zxing(boolean[] row) throws NotFoundException, FormatException, ChecksumException {
        BitArray bits = new BitArray(row.length);
        for (int i = 0; i < row.length; i++) {
            if (row[i]) {
                bits.set(i);
            }
        }
        return new Code128Reader().decodeRow(0, bits, null).getText();
    }
}
