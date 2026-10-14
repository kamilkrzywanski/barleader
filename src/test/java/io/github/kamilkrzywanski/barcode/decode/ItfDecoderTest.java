package io.github.kamilkrzywanski.barcode.decode;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.ChecksumException;
import com.google.zxing.FormatException;
import com.google.zxing.NotFoundException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitArray;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.oned.ITFReader;
import com.google.zxing.oned.ITFWriter;
import io.github.kamilkrzywanski.barcode.BarcodeHit;
import io.github.kamilkrzywanski.barcode.Symbology;
import io.github.kamilkrzywanski.barcode.image.PixelMap;
import io.github.kamilkrzywanski.barcode.image.ZxingLuminance;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ItfDecoderTest {
    @Test
    void roundTripsGrowingEvenLengths() throws Exception {
        // Length 4 is below ZXing's default minimum and is locked separately.
        for (int length = 6; length <= 20; length += 2) {
            assertRoundTrip("1".repeat(length));
        }
    }

    @Test
    void roundTripsDigitsAndScales() throws Exception {
        assertRoundTrip("123456");
        assertRoundTrip("0123456789");
        assertRoundTrip("090807");
        assertRoundTrip("12345678901234");
        // Four digits are below the default minimum of 6, but a symbol that fills the row uses half of that.
        assertEquals("1234", ItfDecoder.decode(pixels("1234", 2, 10)));
        assertNull(ItfDecoder.decode(pixels("12", 2, 10)));
        for (int scale : new int[]{1, 2, 3}) {
            assertEquals("123456", ItfDecoder.decode(pixels("123456", scale, 0)));
        }
    }

    /**
     * The only requested format is ITF, so ZXing's format set for this call is empty.
     * ITF stays out of {@link Symbology#defaults()}.
     */
    @Test
    void readsARenderedSymbolOnTheScanPath() {
        BitMatrix modules = new ITFWriter().encode("123456", BarcodeFormat.ITF, 360, 140);
        EnumSet<Symbology> requested = EnumSet.of(Symbology.ITF);
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
        assertEquals(EnumSet.of(Symbology.ITF), requested);
        assertEquals(1, hits.size());
        BarcodeHit hit = hits.get(0);
        assertEquals("123456", hit.text());
        assertEquals(Symbology.ITF, hit.symbology());
        assertEquals("original-hybrid", hit.attempt());
        assertEquals(-1, hit.orientationDegrees());
        assertEquals(2, hit.corners().size());
    }

    @Test
    void keepsTheCheckDigitAndAgreesWithZxingOnAQuietRow() throws Exception {
        boolean[] row = pixels("123456", 2, 10);
        assertEquals("123456", ItfDecoder.decode(row));
        assertEquals("123456", zxing(row));
        assertEquals("123456", ItfDecoder.decode(pixels("123456", 1, 10)));
    }

    @Test
    void rejectsAShortSymbolThatDoesNotFillTheRow() {
        boolean[] row = pixels("1234", 2, 10);
        boolean[] longer = new boolean[row.length + 40];
        System.arraycopy(row, 0, longer, 0, row.length);
        for (int i = row.length + 20; i < row.length + 30; i++) {
            longer[i] = true;
        }
        assertNull(ItfDecoder.decode(longer));
        assertEquals("123456", ItfDecoder.decode(tail("123456")));
    }

    @Test
    void rejectsADamagedModuleAndABlankRow() {
        boolean[] row = pixels("123456", 2, 10);
        int middle = row.length / 2;
        for (int i = middle; i < middle + 8 && i < row.length; i++) {
            row[i] = !row[i];
        }
        assertNull(ItfDecoder.decode(row));
        assertNull(ItfDecoder.decode(new boolean[40]));
        assertNull(ItfDecoder.decode(new boolean[0]));
        assertNull(ItfDecoder.decode(null));
    }

    /** Locked to zxing-cpp {@code ODITFWriterTest}. The five zeros on each side are the writer margin. */
    @Test
    void readsTheOfficialWriterRow() {
        assertEquals("00123456789012", ItfDecoder.decode(ink(
                "0000010101010111000111000101110100010101110001110111010001010001110100011"
                        + "100010101000101011100011101011101000111000101110100010101110001110100000")));
    }

    private static void assertRoundTrip(String text) throws Exception {
        boolean[] row = pixels(text, 2, 10);
        assertEquals(text, ItfDecoder.decode(row));
        assertEquals(text, zxing(row));
    }

    private static boolean[] tail(String text) {
        boolean[] row = pixels(text, 2, 10);
        boolean[] longer = new boolean[row.length + 40];
        System.arraycopy(row, 0, longer, 0, row.length);
        for (int i = row.length + 20; i < row.length + 30; i++) {
            longer[i] = true;
        }
        return longer;
    }

    /** {@code 1} is black. */
    private static boolean[] ink(String bits) {
        boolean[] row = new boolean[bits.length()];
        for (int i = 0; i < bits.length(); i++) {
            row[i] = bits.charAt(i) == '1';
        }
        return row;
    }

    private static boolean[] pixels(String text, int scale, int quiet) {
        boolean[] modules = new ITFWriter().encode(text);
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
        return new ITFReader().decodeRow(0, bits, null).getText();
    }
}
