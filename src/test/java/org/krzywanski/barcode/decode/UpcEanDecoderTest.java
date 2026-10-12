package org.krzywanski.barcode.decode;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.ChecksumException;
import com.google.zxing.FormatException;
import com.google.zxing.NotFoundException;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitArray;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.oned.EAN13Reader;
import com.google.zxing.oned.EAN13Writer;
import com.google.zxing.oned.EAN8Reader;
import com.google.zxing.oned.EAN8Writer;
import com.google.zxing.oned.UPCEReader;
import com.google.zxing.oned.UPCEWriter;
import org.junit.jupiter.api.Test;
import org.krzywanski.barcode.BarcodeHit;
import org.krzywanski.barcode.Symbology;
import org.krzywanski.barcode.image.PixelMap;

import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class UpcEanDecoderTest {
    @Test
    void roundTripsEanUpcAndScales() throws Exception {
        assertEan13("400638133393");
        assertEan13("012345678905");
        assertEan8("9638507");
        assertUpce("0123456");
        for (int scale : new int[]{1, 2, 3}) {
            boolean[] row = pixels(new EAN13Writer().encode("400638133393"), scale, 0);
            assertEquals(ean13(pixels(new EAN13Writer().encode("400638133393"), 1, 10)), UpcEanDecoder.decode(row));
        }
    }

    @Test
    void agreesWithZxingOnAQuietRow() throws Exception {
        boolean[] row = pixels(new EAN8Writer().encode("5512345"), 2, 10);
        assertEquals(ean8(row), UpcEanDecoder.decode(row));
    }

    @Test
    void rejectsADamagedModuleAndABlankRow() {
        boolean[] row = pixels(new EAN13Writer().encode("400638133393"), 2, 10);
        int middle = row.length / 2;
        for (int i = middle; i < middle + 12 && i < row.length; i++) {
            row[i] = !row[i];
        }
        assertNull(UpcEanDecoder.decode(row));
        assertNull(UpcEanDecoder.decode(new boolean[40]));
        assertNull(UpcEanDecoder.decode(new boolean[0]));
        assertNull(UpcEanDecoder.decode(null));
    }

    /**
     * Locked to zxing-cpp {@code ODEAN13WriterTest}, {@code ODEAN8WriterTest},
     * {@code ODUPCAWriterTest} and {@code ODUPCEWriterTest}. The five zeros on each side
     * are the writer margin. A checksum supplied by the writer is part of these same rows.
     * UPC-A is returned as EAN-13 with a leading zero. UPC-E is {@code 0} plus its UPC-A expansion.
     */
    @Test
    void readsTheOfficialWriterRows() {
        assertEquals("5901234123457", UpcEanDecoder.decode(ink(
                "00001010001011010011101100110010011011110100111010101011001101101100100001010111",
                "001001110100010010100000"
        )));
        assertEquals("96385074", UpcEanDecoder.decode(ink("0000101000101101011110111101011011101010100111011100101000100101110010100000")));
        assertEquals("0485963095124", UpcEanDecoder.decode(ink(
                "00001010100011011011101100010001011010111101111010101011100101110100100111011001",
                "101101100101110010100000"
        )));
        assertEquals("0123456789012", UpcEanDecoder.decode(ink(
                "00001010011001001001101111010100011011000101011110101010001001001000111010011100",
                "101100110110110010100000"
        )));
        assertEquals("0050968000093", UpcEanDecoder.decode(ink("000010101110010100111000101101011110110111001011101010100000")));
        assertEquals("0123456000070", UpcEanDecoder.decode(ink("000010100100110111101010001101110010000101001000101010100000")));
    }

    /**
     * Locked to zxing-cpp {@code CreateBarcodeTest::ZintASCII} plain texts.
     * EAN-8 is the eight-digit result of {@code 123456}. UPC-A is the thirteen-digit
     * result of {@code 1234}. Identifiers stay out.
     */
    @Test
    void readsTheOfficialShortPayloads() {
        assertEquals("01234565", UpcEanDecoder.decode(new EAN8Writer().encode("01234565")));
        assertEquals("1234567890128", UpcEanDecoder.decode(new EAN13Writer().encode("1234567890128")));
        assertEquals("0000000012348", UpcEanDecoder.decode(new EAN13Writer().encode("0000000012348")));
    }

    /**
     * The only requested retail formats are the ones under test, so ZXing's format set for
     * this call does not include them. UPC-A drops the leading zero only when it was requested.
     * UPC-E on this path is eight digits, while {@link UpcEanDecoder#decode(boolean[])} still expands it.
     */
    @Test
    void readsARenderedSymbolOnTheScanPath() throws Exception {
        assertRetail(
                new EAN13Writer().encode("5901234123457", BarcodeFormat.EAN_13, 360, 160),
                EnumSet.of(Symbology.EAN_13),
                "5901234123457",
                Symbology.EAN_13);
        assertRetail(
                new EAN8Writer().encode("55123457", BarcodeFormat.EAN_8, 280, 150),
                EnumSet.of(Symbology.EAN_8),
                "55123457",
                Symbology.EAN_8);
        BitMatrix upc = new EAN13Writer().encode("0036000291452", BarcodeFormat.EAN_13, 360, 160);
        assertRetail(upc, EnumSet.of(Symbology.UPC_A), "036000291452", Symbology.UPC_A);
        assertRetail(upc, EnumSet.of(Symbology.EAN_13), "0036000291452", Symbology.EAN_13);
        assertRetail(
                upc,
                EnumSet.of(Symbology.EAN_13, Symbology.UPC_A),
                "036000291452",
                Symbology.UPC_A);

        boolean[] quiet = pixels(new UPCEWriter().encode("0123456"), 2, 10);
        String eight = upce(quiet);
        assertEquals(8, eight.length());
        assertEquals("0" + UPCEReader.convertUPCEtoUPCA(eight), UpcEanDecoder.decode(quiet));
        assertRetail(
                new UPCEWriter().encode("0123456", BarcodeFormat.UPC_E, 240, 120),
                EnumSet.of(Symbology.UPC_E),
                eight,
                Symbology.UPC_E);
    }

    private static void assertRetail(BitMatrix modules, EnumSet<Symbology> requested, String text, Symbology symbology) {
        EnumSet<Symbology> before = EnumSet.copyOf(requested);
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
        assertEquals(before, requested);
        assertEquals(1, hits.size());
        BarcodeHit hit = hits.get(0);
        assertEquals(text, hit.text());
        assertEquals(symbology, hit.symbology());
        assertEquals("original-hybrid", hit.attempt());
        assertEquals(-1, hit.orientationDegrees());
        assertEquals(2, hit.corners().size());
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

    private static void assertEan13(String payload) throws Exception {
        boolean[] row = pixels(new EAN13Writer().encode(payload), 2, 10);
        String text = ean13(row);
        assertEquals(13, text.length());
        assertEquals(text, UpcEanDecoder.decode(row));
    }

    private static void assertEan8(String payload) throws Exception {
        boolean[] row = pixels(new EAN8Writer().encode(payload), 2, 10);
        assertEquals(ean8(row), UpcEanDecoder.decode(row));
    }

    private static void assertUpce(String payload) throws Exception {
        boolean[] row = pixels(new UPCEWriter().encode(payload), 2, 10);
        String eight = upce(row);
        assertEquals(8, eight.length());
        assertEquals("0" + UPCEReader.convertUPCEtoUPCA(eight), UpcEanDecoder.decode(row));
    }

    private static boolean[] pixels(boolean[] modules, int scale, int quiet) {
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

    private static String ean13(boolean[] row) throws NotFoundException, FormatException, ChecksumException {
        return new EAN13Reader().decodeRow(0, bits(row), null).getText();
    }

    private static String ean8(boolean[] row) throws NotFoundException, FormatException, ChecksumException {
        return new EAN8Reader().decodeRow(0, bits(row), null).getText();
    }

    private static String upce(boolean[] row) throws NotFoundException, FormatException, ChecksumException {
        return new UPCEReader().decodeRow(0, bits(row), null).getText();
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
