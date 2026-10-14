package io.github.kamilkrzywanski.barcode.decode;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.ChecksumException;
import com.google.zxing.FormatException;
import com.google.zxing.NotFoundException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitArray;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.oned.Code39Reader;
import com.google.zxing.oned.Code39Writer;
import io.github.kamilkrzywanski.barcode.BarcodeHit;
import io.github.kamilkrzywanski.barcode.Symbology;
import io.github.kamilkrzywanski.barcode.image.PixelMap;
import io.github.kamilkrzywanski.barcode.image.ZxingLuminance;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class Code39DecoderTest {
    @Test
    void roundTripsGrowingPayloads() throws Exception {
        for (int length = 1; length <= 30; length++) {
            assertStandard("A".repeat(length));
            assertStandard("1".repeat(length));
        }
        assertStandard("CODE39-$/+.% ");
    }

    @Test
    void roundTripsStandardTextAndScales() throws Exception {
        assertStandard("CODE39");
        assertStandard("ABC-123");
        assertStandard("AA");
        for (int scale : new int[]{1, 2, 3}) {
            boolean[] row = pixels("CODE39", scale, 0);
            assertEquals("CODE39", Code39Decoder.decode(row));
        }
    }

    @Test
    void expandsExtendedAsciiAndKeepsAValidChecksum() throws Exception {
        assertEquals("hi", Code39Decoder.decode(pixels("hi", 2, 10)));
        assertEquals("hi", extended(pixels("hi", 2, 10)));
        assertEquals("\u0001", Code39Decoder.decode(pixels("\u0001", 2, 10)));
        assertEquals("a", Code39Decoder.decode(pixels("+A", 2, 10)));
        assertEquals("a8", Code39Decoder.decode(pixels("+A8", 2, 10)));
        // 'O' is the checksum of "Q+". zxing-cpp leaves it out of the extended decode, so the pair is not consumed.
        assertEquals("Q+O", Code39Decoder.decode(pixels("Q+O", 2, 10)));
        assertEquals("Qo", extended(pixels("Q+O", 2, 10)));
    }

    @Test
    void agreesWithZxingOnAQuietRow() throws Exception {
        boolean[] row = pixels("ZX-QUIET", 2, 10);
        assertEquals("ZX-QUIET", Code39Decoder.decode(row));
        assertEquals("ZX-QUIET", standard(row));
    }

    @Test
    void rejectsADamagedModuleAndABlankRow() {
        boolean[] row = pixels("CODE39", 2, 10);
        int middle = row.length / 2;
        for (int i = middle; i < middle + 8 && i < row.length; i++) {
            row[i] = !row[i];
        }
        assertNull(Code39Decoder.decode(row));
        assertNull(Code39Decoder.decode(new boolean[40]));
        assertNull(Code39Decoder.decode(new boolean[0]));
        assertNull(Code39Decoder.decode(null));
    }


    /** Locked to zxing-cpp {@code ODCode39FullASCIITest}. Code 32 is not decoded. */
    @Test
    void readsTheOfficialExtendedAlphabet() {
        assertEquals(controls(), Code39Decoder.decode(bits(
                "00000100101101101010100100100101100101010110100100100101011010100101101001001001",
                "01010110100101101001001001010110110100101010010010010101010110010110100100100101",
                "01101011001010100100100101010110110010101001001001010101010011011010010010010101",
                "10101001101010010010010101011010011010100100100101010101100110101001001001010110",
                "10101001101001001001010101101010011010010010010101101101010010100100100101010101",
                "10100110100100100101011010110100101001001001010101101101001010010010010101010101",
                "10011010010010010101101010110010100100100101010110101100101001001001010101011011",
                "00101001001001010110010101011010010010010101001101010110100100100101011001101010",
                "10100100100101010010110101101001001001010110010110101010010010010101001101101010",
                "10100100100101101010010110101001001001010110100101101010010010010110110100101010",
                "1001001001010101100101101010010010010110101100101010010110110100000")));
        assertEquals(" !\"#$%&'()*+,-./0123456789:;<=>?", Code39Decoder.decode(bits(
                "00000100101101101010011010110101001001010010110101001011010010010100101011010010",
                "11010010010100101101101001010100100101001010101100101101001001010010110101100101",
                "01001001010010101101100101010010010100101010100110110100100101001011010100110101",
                "00100101001010110100110101001001010010101011001101010010010100101101010100110100",
                "10010100101011010100110100101011011011001010110101001001010010110101101001010100",
                "11011010110100101011010110010101101101100101010101001101011011010011010101011001",
                "10101010100101101101101001011010101100101101010010010100101001101101010101001001",
                "00101011011001010101001001001010101001101101010010010010110101001101010100100100",
                "1010110100110101010010010010101011001101010010110110100000")));
        assertEquals("@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\]^_", Code39Decoder.decode(bits(
                "00001001011011010101001001001010011010101101101010010110101101001011011011010010",
                "10101011001011011010110010101011011001010101010011011011010100110101011010011010",
                "10101100110101101010100110101101010011011011010100101010110100110110101101001010",
                "11011010010101010110011011010101100101011010110010101011011001011001010101101001",
                "10101011011001101010101001011010110110010110101010011011010101010010010010110101",
                "01001101010010010010101101010011010100100100101101101010010101001001001010101101",
                "001101010010010010110101101001010010110110100000")));
        assertEquals("`abcdefghijklmnopqrstuvwxyz{|}~\u007f", Code39Decoder.decode(bits(
                "00000100101101101010100100100101100110101010100101001001011010100101101001010010",
                "01010110100101101001010010010110110100101010010100100101010110010110100101001001",
                "01101011001010100101001001010110110010101001010010010101010011011010010100100101",
                "10101001101010010100100101011010011010100101001001010101100110101001010010010110",
                "10101001101001010010010101101010011010010100100101101101010010100101001001010101",
                "10100110100101001001011010110100101001010010010101101101001010010100100101010101",
                "10011010010100100101101010110010100101001001010110101100101001010010010101011011",
                "00101001010010010110010101011010010100100101001101010110100101001001011001101010",
                "10100101001001010010110101101001010010010110010110101010010100100101001101101010",
                "10100100100101011011010010101001001001010101011001101010010010010110101011001010",
                "1001001001010110101100101010010010010101011011001010010110110100000")));
    }

    /**
     * Locked to zxing-cpp {@code ODCode39ReaderTest} with extended Code 39 left on.
     * A checksum that matches stays in the text and is not expanded.
     * The same two extended rows are {@code +A} and {@code +A8} when extended mode is off.
     * Code 32 is not read. A zero-width gap in the C++ row is one narrow module here.
     */
    @Test
    void readsTheOfficialChecksumAndExtendedRows() {
        assertEquals("A", Code39Decoder.decode(official(2, 1, 1, 1, 1, 2, 1, 1, 2)));
        assertEquals("AA", Code39Decoder.decode(official(
                2, 1, 1, 1, 1, 2, 1, 1, 2, 0, 2, 1, 1, 1, 1, 2, 1, 1, 2)));
        assertEquals("a", Code39Decoder.decode(official(
                1, 2, 1, 1, 1, 2, 1, 2, 1, 0, 2, 1, 1, 1, 1, 2, 1, 1, 2)));
        assertEquals("a8", Code39Decoder.decode(official(
                1, 2, 1, 1, 1, 2, 1, 2, 1, 0, 2, 1, 1, 1, 1, 2, 1, 1, 2, 0,
                2, 1, 1, 2, 1, 1, 2, 1, 1)));
    }

    /**
     * Locked to zxing-cpp {@code ODCode39WriterTest}. The five zeros on each side are the
     * writer margin. The commercial-at row is the same symbol as the extended-alphabet
     * fixture, with one extra quiet module in front.
     */
    @Test
    void readsTheOfficialWriterRows() {
        assertEquals("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789", Code39Decoder.decode(bits(
                "00000100101101101011010100101101011010010110110110100101010101100101101101011001",
                "01010110110010101010100110110110101001101010110100110101010110011010110101010011",
                "01011010100110110110101001010101101001101101011010010101101101001010101011001101",
                "10101011001010110101100101010110110010110010101011010011010101101100110101010100",
                "10110101101100101101010100110110101010100110110101101001010110101100101011011011",
                "00101010101001101011011010011010101011001101010101001011011011010010110101011001",
                "01101010010110110100000"
        )));
        assertEquals("@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\]^_", Code39Decoder.decode(bits(
                "00000100101101101010100100100101001101010110110101001011010110100101101101101001",
                "01010101100101101101011001010101101100101010101001101101101010011010101101001101",
                "01010110011010110101010011010110101001101101101010010101011010011011010110100101",
                "01101101001010101011001101101010110010101101011001010101101100101100101010110100",
                "11010101101100110101010100101101011011001011010101001101101010101001001001011010",
                "10100110101001001001010110101001101010010010010110110101001010100100100101010110",
                "1001101010010010010110101101001010010110110100000"
        )));
    }

    /** The only requested format is Code 39, so ZXing's format set for this call is empty. */
    @Test
    void readsARenderedSymbolOnTheScanPath() {
        BitMatrix modules = new Code39Writer().encode("CODE39", BarcodeFormat.CODE_39, 420, 140);
        EnumSet<Symbology> requested = EnumSet.of(Symbology.CODE_39);
        List<BarcodeHit> hits = BitmapDecoder.read(
                ZxingLuminance.of(MatrixToImageWriter.toBufferedImage(modules)),
                PixelMap.IDENTITY,
                "original-hybrid",
                0,
                0.92,
                requested,
                false,
                false
        );
        assertEquals(EnumSet.of(Symbology.CODE_39), requested);
        assertEquals(1, hits.size());
        BarcodeHit hit = hits.get(0);
        assertEquals("CODE39", hit.text());
        assertEquals(Symbology.CODE_39, hit.symbology());
        assertEquals("original-hybrid", hit.attempt());
        assertEquals(-1, hit.orientationDegrees());
        assertEquals(2, hit.corners().size());
    }

    /** Start, payload, stop. Quiet zones are ten modules. An internal zero is a one-module gap. */
    private static boolean[] official(int... payload) {
        int[] guard = {0, 1, 2, 1, 1, 2, 1, 2, 1, 1, 0};
        int[] widths = new int[guard.length + payload.length + guard.length];
        System.arraycopy(guard, 0, widths, 0, guard.length);
        System.arraycopy(payload, 0, widths, guard.length, payload.length);
        System.arraycopy(guard, 0, widths, guard.length + payload.length, guard.length);
        int modules = 0;
        for (int i = 0; i < widths.length; i++) {
            if (widths[i] == 0) {
                widths[i] = (i == 0 || i == widths.length - 1) ? 10 : 1;
            }
            modules += widths[i];
        }
        boolean[] row = new boolean[modules];
        int cursor = 0;
        boolean black = false;
        for (int width : widths) {
            for (int i = 0; i < width; i++) {
                row[cursor++] = black;
            }
            black = !black;
        }
        return row;
    }

    private static String controls() {
        char[] chars = new char[32];
        for (int i = 0; i < chars.length; i++) {
            chars[i] = (char) i;
        }
        return new String(chars);
    }

    private static boolean[] bits(String... parts) {
        String row = String.join("", parts);
        boolean[] black = new boolean[row.length()];
        for (int i = 0; i < row.length(); i++) {
            black[i] = row.charAt(i) == '1';
        }
        return black;
    }

    private static void assertStandard(String payload) throws Exception {
        boolean[] row = pixels(payload, 2, 10);
        assertEquals(payload, Code39Decoder.decode(row));
        assertEquals(payload, standard(row));
    }

    private static boolean[] pixels(String text, int scale, int quiet) {
        boolean[] modules = new Code39Writer().encode(text);
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

    private static String standard(boolean[] row) throws NotFoundException, FormatException, ChecksumException {
        return new Code39Reader().decodeRow(0, bits(row), null).getText();
    }

    private static String extended(boolean[] row) throws NotFoundException, FormatException, ChecksumException {
        return new Code39Reader(false, true).decodeRow(0, bits(row), null).getText();
    }

    private static BitArray bits(boolean[] row) {
        BitArray bits = new BitArray(row.length);
        for (int i = 0; i < row.length; i++) {
            if (row[i]) {
                bits.set(i);
            }
        }
        return bits;
    }
}
