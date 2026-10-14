package io.github.kamilkrzywanski.barcode.decode;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.ChecksumException;
import com.google.zxing.DecodeHintType;
import com.google.zxing.FormatException;
import com.google.zxing.NotFoundException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitArray;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.oned.CodaBarReader;
import com.google.zxing.oned.CodaBarWriter;
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

class CodabarDecoderTest {
    @Test
    void roundTripsGuardsAndScales() throws Exception {
        assertRoundTrip("A123B");
        assertRoundTrip("A12A");
        assertRoundTrip("C4567D");
        assertRoundTrip("A12-$:/.+B");
        // zxing-cpp ODCodaBarWriterTest::FullCircle.
        assertRoundTrip("A0123456789-$:/.+A");
        // zxing-cpp ODCodaBarWriterTest::AltStartEnd. T is the same pattern as A.
        assertEquals("A123456789-$A", CodabarDecoder.decode(pixels("T123456789-$T", 2, 10)));
        assertEquals("A123456789-$A", zxing(pixels("T123456789-$T", 2, 10)));
        boolean[] added = pixels("12", 2, 10);
        assertEquals("A12A", CodabarDecoder.decode(added));
        assertEquals("A12A", zxing(added));
        for (int scale : new int[]{1, 2, 3}) {
            assertEquals("A123B", CodabarDecoder.decode(pixels("A123B", scale, 0)));
        }
    }

    @Test
    void agreesWithZxingOnAQuietRow() throws Exception {
        boolean[] row = pixels("B98-76A", 2, 10);
        assertEquals("B98-76A", CodabarDecoder.decode(row));
        assertEquals("B98-76A", zxing(row));
    }

    @Test
    void rejectsADamagedModuleAndABlankRow() {
        boolean[] row = pixels("A123B", 2, 10);
        int middle = row.length / 2;
        for (int i = middle; i < middle + 8 && i < row.length; i++) {
            row[i] = !row[i];
        }
        assertNull(CodabarDecoder.decode(row));
        assertNull(CodabarDecoder.decode(new boolean[40]));
        assertNull(CodabarDecoder.decode(new boolean[0]));
        assertNull(CodabarDecoder.decode(null));
        // Writer wraps a bare digit as A + digit + A, three characters. cpp rejects below four.
        assertNull(CodabarDecoder.decode(pixels("1", 2, 10)));
    }

    /**
     * The only requested format is Codabar, so ZXing's format set for this call is empty.
     * Start and stop letters stay in the text. Codabar stays out of {@link Symbology#defaults()}.
     */
    @Test
    void readsARenderedSymbolOnTheScanPath() {
        BitMatrix modules = new CodaBarWriter().encode("A123B", BarcodeFormat.CODABAR, 360, 140);
        EnumSet<Symbology> requested = EnumSet.of(Symbology.CODABAR);
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
        assertEquals(EnumSet.of(Symbology.CODABAR), requested);
        assertEquals(1, hits.size());
        BarcodeHit hit = hits.get(0);
        assertEquals("A123B", hit.text());
        assertEquals(Symbology.CODABAR, hit.symbology());
        assertEquals("original-hybrid", hit.attempt());
        assertEquals(-1, hit.orientationDegrees());
        assertEquals(2, hit.corners().size());
    }

    /** Locked to zxing-cpp {@code ODCodaBarWriterTest}. T is the same pattern as A. */
    @Test
    void readsTheOfficialWriterRows() {
        assertEquals("B515-3/B", CodabarDecoder.decode(ink(
                "00000"
                        + "1001001011" + "0110101001" + "0101011001" + "0110101001" + "0101001101"
                        + "0110010101" + "01101101011" + "01001001011"
                        + "00000")));
        assertEquals("A123A", CodabarDecoder.decode(ink(
                "00000"
                        + "1011001001" + "0101011001" + "0101001011" + "0110010101" + "01011001001"
                        + "00000")));
    }

    private static void assertRoundTrip(String text) throws Exception {
        boolean[] row = pixels(text, 2, 10);
        assertEquals(text, CodabarDecoder.decode(row));
        assertEquals(text, zxing(row));
    }

    /** {@code 1} is black. The five zeros on each side are the writer margin. */
    private static boolean[] ink(String bits) {
        boolean[] row = new boolean[bits.length()];
        for (int i = 0; i < bits.length(); i++) {
            row[i] = bits.charAt(i) == '1';
        }
        return row;
    }

    private static boolean[] pixels(String text, int scale, int quiet) {
        boolean[] modules = new CodaBarWriter().encode(text);
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
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.RETURN_CODABAR_START_END, Boolean.TRUE);
        return new CodaBarReader().decodeRow(0, bits, hints).getText();
    }
}
