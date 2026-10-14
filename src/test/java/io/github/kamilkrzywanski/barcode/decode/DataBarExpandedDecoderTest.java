package io.github.kamilkrzywanski.barcode.decode;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DataBarExpandedDecoderTest {
    private static final char GS = 29;

    @Test
    void keepsANumericLatchImpliedByFnc1() {
        // HRI (10)12A(422)123. The second stream has a redundant numeric latch after FNC1.
        String text = "1012A" + GS + "422123";
        assertEquals(text, decode("0000000100110010101000010000001111011011000111110100001000000100"));
        assertEquals(text, decode("0000000100110010101000010000001111000011011000111110100001000000100"));
        // HRI (10)12((422)123. The "(" is payload, not a parenthesis added by the reader.
        String iso = "1012(" + GS + "422123";
        assertEquals(iso, decode("0001000100110010101000000100111011010111101101100011111010000100000010000100"));
        assertEquals(iso, decode("0001000100110010101000000100111011010111100001101100011111010000100000010000100"));
    }

    @Test
    void readsPriceAisAfterTheGtin() {
        assertEquals("019001234567890839201" + GS + "2001",
                decode("00110000000000000100111010101000110111110111101000001110100111100001001"));
        assertEquals("019001234567890839227955" + GS + "2001",
                decode("0011000000000000010011101010100011011111011110101010111101000100111100000010000010"));
        assertEquals("0190012345678908392912345678901234" + GS + "2001",
                decode("0100100100000000010011101010100011011111011110100110010010011100101010101101100010110111011101011001"
                        + "01010101101111100000010000011101"));
    }

    @Test
    void readsCurrencyAisAfterTheGtin() {
        assertEquals("019001234567890839300121" + GS + "200110AB1",
                decode("0011010000000000010011101010100011011111011110100000000011000011101001111000010010010011000010000010"
                        + "000100110"));
        assertEquals("019001234567890839320081234" + GS + "200110AB1",
                decode("0011010000000000010011101010100011011111011110101000000010000010101010110111110000001000001010000000"
                        + "010110000010000100110"));
        assertEquals("01900123456789083933997123456789012345" + GS + "200110AB1",
                decode("0011010000000000010011101010100011011111011110101111111001010010101010110110001011011101110101100101"
                        + "0101011011001001001111000010010010011000010000010000100110"));
    }

    @Test
    void rejectsAShortStreamAndANull() {
        assertNull(DataBarExpandedDecoder.decode(null));
        assertNull(DataBarExpandedDecoder.decode(new boolean[0]));
        assertNull(decode("000"));
        assertNull(decode("11111111111111111111111111111111"));
    }

    private static String decode(String bits) {
        boolean[] row = new boolean[bits.length()];
        for (int i = 0; i < bits.length(); i++) {
            row[i] = bits.charAt(i) == '1';
        }
        return DataBarExpandedDecoder.decode(row);
    }
}
