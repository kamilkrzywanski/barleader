package io.github.kamilkrzywanski.barcode.decode;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.ChecksumException;
import com.google.zxing.FormatException;
import com.google.zxing.NotFoundException;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitArray;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.oned.Code93Reader;
import com.google.zxing.oned.Code93Writer;
import org.junit.jupiter.api.Test;
import io.github.kamilkrzywanski.barcode.BarcodeHit;
import io.github.kamilkrzywanski.barcode.Symbology;
import io.github.kamilkrzywanski.barcode.image.PixelMap;

import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class Code93DecoderTest {
    @Test
    void roundTripsGrowingPayloads() throws Exception {
        for (int length = 1; length <= 30; length++) {
            assertRoundTrip("A".repeat(length));
            assertRoundTrip("1".repeat(length));
        }
        assertRoundTrip("CODE93/$+%. -");
    }

    @Test
    void roundTripsStandardExtendedAndScales() throws Exception {
        assertRoundTrip("CODE93");
        assertRoundTrip("A");
        assertRoundTrip("hi");
        assertRoundTrip("Code-93");
        assertRoundTrip("\u0001");
        for (int scale : new int[]{1, 2, 3}) {
            boolean[] row = pixels("CODE93", scale, 0);
            assertEquals("CODE93", Code93Decoder.decode(row));
        }
    }

    @Test
    void agreesWithZxingOnAQuietRow() throws Exception {
        boolean[] row = pixels("ZX-QUIET", 2, 10);
        assertEquals("ZX-QUIET", Code93Decoder.decode(row));
        assertEquals("ZX-QUIET", zxing(row));
    }

    @Test
    void rejectsADamagedModuleAndABlankRow() {
        boolean[] row = pixels("CODE93", 2, 10);
        int middle = row.length / 2;
        for (int i = middle; i < middle + 8 && i < row.length; i++) {
            row[i] = !row[i];
        }
        assertNull(Code93Decoder.decode(row));
        assertNull(Code93Decoder.decode(new boolean[40]));
        assertNull(Code93Decoder.decode(new boolean[0]));
        assertNull(Code93Decoder.decode(null));
    }


    /**
     * The only requested format is Code 93, so ZXing's format set for this call is empty.
     * Code 93 stays out of {@link Symbology#defaults()}.
     */
    @Test
    void readsARenderedSymbolOnTheScanPath() {
        BitMatrix modules = new Code93Writer().encode("CODE93", BarcodeFormat.CODE_93, 420, 140);
        EnumSet<Symbology> requested = EnumSet.of(Symbology.CODE_93);
        List<BarcodeHit> hits = ZxingDecoder.read(
                new BufferedImageLuminanceSource(MatrixToImageWriter.toBufferedImage(modules)),
                PixelMap.IDENTITY,
                "original-hybrid",
                0,
                0.92,
                requested,
                false,
                false
        );
        assertEquals(EnumSet.of(Symbology.CODE_93), requested);
        assertEquals(1, hits.size());
        BarcodeHit hit = hits.get(0);
        assertEquals("CODE93", hit.text());
        assertEquals(Symbology.CODE_93, hit.symbology());
        assertEquals("original-hybrid", hit.attempt());
        assertEquals(-1, hit.orientationDegrees());
        assertEquals(2, hit.corners().size());
    }

    /** Locked to zxing-cpp {@code ODCode93ReaderTest}. Both checksum characters are removed. */
    @Test
    void readsTheOfficialFullAsciiRow() {
        assertEquals("Code93!\n$%/+ :\u001b;[{\u007f\u0000@`\u007f\u007f\u007f", Code93Decoder.decode(bits(
                "00000010101111011010001010011001010010110010011001011001010010011001011001001010",
                "00010101010000101110101101101010001001001101001101001110010101101011101011011101",
                "01110110111010010111010110100111010111011010110101000111011010110001010111011010",
                "10001101011101101010001011011101101011010011011101101011001011011101101011001101",
                "01110110101011011001110110101011001101110110101001101101110110101001110101001100",
                "10110101000101011110100000")));
    }

    /**
     * Locked to zxing-cpp {@code ODCode93WriterTest}. The five zeros on each side are the
     * writer margin. Both checksum characters are removed. Extended shifts are expanded.
     */
    @Test
    void readsTheOfficialWriterRows() {
        assertEquals("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789", Code93Decoder.decode(bits(
                "00000101011110110101000110100100110100010110010100110010010110001010101101000101",
                "10010010110001010011010010001101010101100010100110010100011010010110010001011011",
                "01101001101100101101011001101001101100101101100110101011011001011001101001101101",
                "00111010100010100101001000101000100101000010100101000100100100100100010101010000",
                "100010010100001010100111010101000010101011110100000"
        )));
        assertEquals("\u0000\u0001\u001a\u001b\u001f $%+!,09:;@AZ[_`az{\u007f", Code93Decoder.decode(bits(
                "00000101011110111011010110010110100100110110101000100100110100111010111011010110",
                "10100011101101011001001011101001011100101011010111010111011011101011011010100011",
                "10101101010110001000101001000010101110101101001110101110110101100010101110110101",
                "10011010110101000100111010111011010100011010111011010100101100111011010101101100",
                "10011001011010100010011001010011101011101101010001011011101101011010011011010001",
                "0110101100101011110100000"
        )));
    }

    private static boolean[] bits(String... parts) {
        String row = String.join("", parts);
        boolean[] black = new boolean[row.length()];
        for (int i = 0; i < row.length(); i++) {
            black[i] = row.charAt(i) == '1';
        }
        return black;
    }

    private static void assertRoundTrip(String text) throws Exception {
        boolean[] row = pixels(text, 2, 10);
        assertEquals(text, Code93Decoder.decode(row));
        assertEquals(text, zxing(row));
    }

    private static boolean[] pixels(String text, int scale, int quiet) {
        boolean[] modules = new Code93Writer().encode(text);
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
        return new Code93Reader().decodeRow(0, bits, null).getText();
    }
}
