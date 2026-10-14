package io.github.kamilkrzywanski.barcode.decode;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.EncodeHintType;
import com.google.zxing.NotFoundException;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.GlobalHistogramBinarizer;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.pdf417.PDF417Reader;
import com.google.zxing.pdf417.PDF417Writer;
import com.google.zxing.pdf417.encoder.Compaction;
import io.github.kamilkrzywanski.barcode.ecc.ReedSolomonException;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Pdf417DecoderTest {
    @Test
    void roundTripsTextNumericByteAndCompact() throws Exception {
        assertRoundTrip("PDF417", null);
        assertRoundTrip("Hello, PDF417!", null);
        // zxing-cpp PDF417HighLevelEncoderTest. The encoder accepts these. The text is the payload.
        assertRoundTrip("1%\u00a7s ?aG$", null);
        assertRoundTrip("asdfg\u00a7asd", null);
        assertRoundTrip("pdf417 lower", null);
        assertRoundTrip("The quick brown fox jumps over the lazy dog 0123456789", null);
        Map<EncodeHintType, Object> numeric = hints();
        numeric.put(EncodeHintType.PDF417_COMPACTION, Compaction.NUMERIC);
        assertRoundTrip("123456789012345678901234567890", numeric);
        Map<EncodeHintType, Object> bytes = hints();
        bytes.put(EncodeHintType.PDF417_COMPACTION, Compaction.BYTE);
        assertRoundTrip("Byte-Mode-42", bytes);
        Map<EncodeHintType, Object> compact = hints();
        compact.put(EncodeHintType.PDF417_COMPACT, true);
        assertRoundTrip("Compact-PDF417", compact);
        Map<EncodeHintType, Object> level0 = hints();
        level0.put(EncodeHintType.ERROR_CORRECTION, 0);
        assertRoundTrip("EC-0", level0);
        Map<EncodeHintType, Object> level5 = hints();
        level5.put(EncodeHintType.ERROR_CORRECTION, 5);
        assertRoundTrip("EC-5", level5);
        Map<EncodeHintType, Object> bare = hints();
        bare.put(EncodeHintType.MARGIN, 0);
        assertRoundTrip("NO-QUIET", bare);
        Map<EncodeHintType, Object> utf8 = hints();
        utf8.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        assertRoundTrip("Zażółć", utf8);
        assertRoundTrip("A\u0001B", null);
    }

    @Test
    void roundTripsGrowingPayloads() throws Exception {
        Map<EncodeHintType, Object> numeric = hints();
        numeric.put(EncodeHintType.PDF417_COMPACTION, Compaction.NUMERIC);
        for (int length = 1; length <= 40; length++) {
            assertRoundTrip("P".repeat(length), null);
            assertRoundTrip("9".repeat(length), numeric);
        }
    }

    @Test
    void anErrorCorrectionLevelPastEightIsAChecksumError() {
        // Row indicators give the level as a value mod 30 over 3, so a damaged one can say 9.
        int[] codewords = new int[2048];
        codewords[0] = 1500;
        assertThrows(ReedSolomonException.class, () -> Pdf417Decoder.decodeCodewordsContent(codewords, 1 << 10, new int[0]));
        assertThrows(ReedSolomonException.class, () -> Pdf417Decoder.decodeCodewordsContent(new int[20], 4, new int[6]));
    }

    @Test
    void correctsOneCodewordAndRejectsHalfTheSymbol() throws Exception {
        boolean[][] image = matrix("CODEWORD", null);
        nudgeOneCodeword(image);
        assertEquals("CODEWORD", Pdf417Decoder.decode(image));

        boolean[][] ruined = matrix("CODEWORD", null);
        eraseLowerHalf(ruined);
        assertNull(Pdf417Decoder.decode(ruined));
        assertNull(Pdf417Decoder.decode(new boolean[40][40]));
        assertNull(Pdf417Decoder.decode(new boolean[0][]));
        assertNull(Pdf417Decoder.decode(null));
    }

    /**
     * Locked to zxing-cpp {@code PDF417DecoderTest} text and validity. Macro metadata,
     * structured append and the {@code ]L} identifier stay out of the text.
     */
    @Test
    void readsTheOfficialCodewordFixtures() {
        assertEquals("PDF417 Symbology Standard", data(
                16, 453, 178, 121, 236, 858, 834, 361, 431, 426, 746, 828, 570, 393, 17, 119));
        assertEquals("PDF417 Symbology Standard", data(
                16, 453, 178, 121, 237, 807, 564, 361, 431, 426, 746, 828, 570, 393, 17, 119));
        assertEquals("ABCDEFGHIJKLMNOPQRSTUVWXYZ ", data(
                15, 1, 63, 125, 187, 249, 311, 373, 435, 497, 559, 621, 683, 745, 809));
        assertEquals("abcdefghijklmnopqrstuvwxyz ", data(
                15, 810, 32, 94, 156, 218, 280, 342, 404, 466, 528, 590, 652, 714, 776));
        assertEquals("0123456&\r\t,:#-.$/+%*=^ 789", data(
                15, 840, 32, 94, 156, 311, 373, 435, 497, 559, 621, 683, 746, 218, 299));
        assertEquals(";<>@[\\]_'~!\r\t,:\n-.$/\"|*()?{", data(
                16, 865, 1, 63, 125, 187, 849, 311, 373, 435, 497, 559, 621, 683, 745, 809));
        assertEquals("AB{}  C#+  de{}  {}F  12{}  G{}  H", data(
                27, 1, 865, 807, 896, 782, 855, 626, 807, 94, 865, 807, 896, 808, 776, 839, 176, 808, 32, 776,
                839, 806, 208, 776, 839, 806, 239));
        assertEquals("AB{}  C#+  de{}  {}F  12{}  G{}  H", data(
                25, 1, 896, 897, 806, 88, 470, 836, 783, 148, 776, 839, 806, 896, 897, 178, 806, 32, 776, 839,
                806, 209, 809, 836, 787));

        assertEquals("AAAA\u00e9AAAA", data(7, 0, 0, 913, 233, 0, 0));
        assertEquals("AAAA\u00e9AAAAA", data(8, 0, 0, 913, 233, 0, 0, 29));
        assertEquals("AAAAA\u00e9AAAA", data(8, 0, 0, 29, 913, 233, 0, 0));
        assertEquals("AAAAA\u00e9aaaaaa", data(10, 0, 0, 29, 913, 233, 810, 0, 0, 29));
        assertEquals("aaaaa\u00e9aaaaaa", data(9, 810, 0, 0, 913, 233, 0, 0, 0));
        assertEquals("aaaaaa\u00e9aaaaaa", data(10, 810, 0, 0, 29, 913, 233, 0, 0, 0));
        assertEquals("00000\u00e9000000", data(9, 840, 0, 0, 913, 233, 0, 0, 0));
        assertEquals("0000\u00e90000", data(8, 840, 0, 29, 913, 233, 0, 0));
        assertEquals(";;;;\u00e9;;;;", data(8, 865, 0, 0, 913, 233, 0, 0));
        assertEquals(";;;;;\u00e9AAAA", data(9, 865, 0, 0, 29, 913, 233, 0, 0));
        assertEquals(";;;;;\u00e9;;;;;", data(11, 865, 0, 0, 29, 913, 233, 865, 0, 0, 29));
        assertEquals(";;;;;\u00e9aaaaa", data(10, 865, 0, 0, 29, 913, 233, 810, 0, 0));
        assertEquals("\u00ffZZZZ", data(5, 913, 255, 775, 775));
        assertNull(data(6, 901, 255, 255, 913, 255));
        assertNull(data(7, 902, 171, 209, 268, 913, 255));
        assertNull(data(18, 1, 63, 125, 902, 17, 110, 836, 811, 223, 901, 127, 127, 127, 127, 913, 255, 775));
        assertNull(data(13, 1, 63, 125, 902, 17, 110, 836, 811, 223, 913, 255, 775));

        assertEquals("\u007f".repeat(11), data(12, 901, 213, 598, 413, 118, 87, 127, 127, 127, 127, 127));
        assertEquals("\u007f".repeat(12), data(12, 924, 213, 598, 413, 118, 87, 213, 598, 413, 118, 87));
        assertEquals("\u007f".repeat(11), data(13, 924, 213, 598, 413, 118, 87, 901, 127, 127, 127, 127, 127));
        assertEquals("\u007f".repeat(15), data(
                17, 924, 213, 598, 413, 118, 87, 213, 598, 413, 118, 87, 901, 127, 127, 901, 127));
        assertEquals("\u007f".repeat(11), data(15, 901, 127, 127, 127, 127, 127, 901, 127, 127, 127, 127, 901, 127, 127));
        assertEquals("\u007f".repeat(11), data(13, 901, 213, 598, 413, 118, 87, 127, 901, 127, 127, 127, 127));
        assertEquals("\u007f".repeat(11), data(13, 901, 213, 598, 413, 118, 87, 127, 127, 127, 127, 901, 127));
        assertEquals("\u007f".repeat(11), data(13, 901, 213, 598, 413, 118, 87, 127, 127, 127, 127, 127, 901));
        assertEquals("\u007f".repeat(11), data(14, 901, 213, 598, 413, 118, 87, 127, 127, 127, 127, 901, 901, 127));
        assertEquals("\u007f".repeat(11), data(15, 901, 213, 598, 413, 118, 87, 127, 127, 127, 127, 901, 901, 127, 901));
        assertEquals("\u007f".repeat(15), data(
                17, 901, 213, 598, 413, 118, 87, 127, 127, 127, 127, 127, 901, 127, 127, 127, 127));

        assertEquals("1234567890123456789012345678901234567890123", data(
                17, 902, 49, 98, 103, 675, 30, 186, 631, 467, 409, 266, 246, 677, 536, 811, 223));
        assertEquals("12345678901234567890123456789012345678901234", data(
                17, 902, 491, 81, 137, 450, 302, 67, 15, 174, 492, 862, 667, 475, 869, 12, 434));
        assertEquals("123456789012345678901234567890123456789012345", data(
                18, 902, 491, 81, 137, 450, 302, 67, 15, 174, 492, 862, 667, 475, 869, 12, 434, 15));
        assertEquals("123456789012345678901234567890123456789012345678901234567890123456789012345678901234567", data(
                32, 902, 491, 81, 137, 450, 302, 67, 15, 174, 492, 862, 667, 475, 869, 12, 434, 68, 482, 582,
                185, 641, 663, 847, 653, 803, 879, 734, 537, 34, 771, 667));
        assertEquals("1234567890123456789012345678901234567890123456789012345678901234567890123456789012345678", data(
                32, 902, 491, 81, 137, 450, 302, 67, 15, 174, 492, 862, 667, 475, 869, 12, 434, 685, 326, 422,
                57, 117, 339, 377, 238, 839, 698, 145, 870, 348, 517, 378));
        assertEquals("12345678901234567890123456789012345678901234567890123456789012345678901234567890123456789", data(
                33, 902, 491, 81, 137, 450, 302, 67, 15, 174, 492, 862, 667, 475, 869, 12, 434, 685, 326, 422,
                57, 117, 339, 377, 238, 839, 698, 145, 870, 348, 517, 378, 19));

        assertEquals("ABCDEF\u007f\u007f\u007f\u007f1234567890123VWXYZ", data(
                19, 1, 63, 125, 901, 127, 127, 127, 127, 902, 17, 110, 836, 811, 223, 900, 652, 714, 779));
        assertEquals("ABCDEF1234567890123\u007f\u007f\u007f\u007fVWXYZ", data(
                19, 1, 63, 125, 902, 17, 110, 836, 811, 223, 901, 127, 127, 127, 127, 900, 652, 714, 779));
        assertEquals("\u007f\u007f\u007fABCDEF1234567890123VWXYZ", data(
                19, 901, 127, 127, 127, 900, 1, 63, 125, 902, 17, 110, 836, 811, 223, 900, 652, 714, 779));
        assertEquals("\u007f\u007f\u007f\u007f\u007f1234567890123VWXYZ", data(
                17, 901, 127, 127, 127, 127, 127, 902, 17, 110, 836, 811, 223, 900, 652, 714, 779));
        assertEquals("1234567890123ABCDEF\u007f\u007f\u007fVWXYZ", data(
                19, 902, 17, 110, 836, 811, 223, 900, 1, 63, 125, 901, 127, 127, 127, 900, 652, 714, 779));
        assertEquals("1234567890123\u007f\u007f\u007fABCDEFVWXYZ", data(
                18, 902, 17, 110, 836, 811, 223, 901, 127, 127, 127, 900, 1, 63, 125, 652, 714, 779));

        assertEquals("A\u00e9", data(7, 927, 3, 900, 29, 913, 233));
        assertEquals("A\u00e9", data(7, 900, 927, 3, 29, 913, 233));
        assertEquals("A\u00e9", data(6, 927, 3, 29, 913, 233));
        assertEquals("A\u00e9", data(6, 927, 3, 901, 65, 233));
        assertEquals("A\u00e9", data(6, 29, 913, 927, 3, 233));
        assertEquals("A\u03b2", data(7, 927, 9, 900, 29, 913, 226));
        assertEquals("A\u03b2", data(7, 900, 927, 9, 29, 913, 226));
        assertEquals("A\u03b2", data(6, 927, 9, 29, 913, 226));
        assertEquals("A\u03b2", data(6, 927, 9, 901, 65, 226));
        assertEquals("A\u03b2", data(6, 29, 913, 927, 9, 226));
        assertEquals("AB\u03b2", data(6, 1, 913, 927, 9, 226));

        assertEquals("\u70b9\u8317", data(8, 927, 20, 901, 147, 95, 228, 170));
        assertEquals("\u70b9\u8317", data(8, 901, 927, 20, 147, 95, 228, 170));
        assertEquals("\u70b9\u8317A", data(9, 927, 20, 901, 147, 95, 228, 170, 65));
        assertEquals("\u70b9\u8317A", data(9, 901, 927, 20, 147, 95, 228, 170, 65));
        assertEquals("\u70b9\u8317\u30c6", data(9, 927, 20, 924, 246, 877, 166, 106, 797));
        assertEquals("\u70b9\u8317\u30c6", data(9, 924, 927, 20, 246, 877, 166, 106, 797));
        assertNull(data(9, 924, 246, 877, 166, 106, 927, 20, 797));
        assertNull(data(14, 901, 213, 598, 927, 20, 413, 118, 87, 127, 127, 127, 127, 127));
        assertEquals("\u70b9\u8317\u30c6A", data(10, 901, 927, 20, 246, 877, 166, 106, 797, 65));

        assertEquals("12345678901234567890123456789012345678901234", data(
                19, 902, 927, 20, 491, 81, 137, 450, 302, 67, 15, 174, 492, 862, 667, 475, 869, 12, 434));
        assertEquals("123456789012345678901234567890123456789012", data(
                19, 902, 11, 927, 20, 485, 624, 192, 160, 521, 439, 324, 737, 864, 136, 732, 282, 410, 12));
        assertEquals("1234567890123456789012345678901234567890123\u70b9\u8317", data(
                24, 902, 154, 98, 332, 101, 927, 20, 354, 63, 496, 448, 236, 148, 354, 624, 335, 600, 123,
                901, 147, 95, 228, 170));
        assertEquals("12345678901234567890123456789012\u3334\u3536\u3738\u3930\u3132A\u0100", data(
                24, 902, 322, 183, 750, 813, 535, 621, 854, 718, 783, 621, 112, 927, 25, 18, 413, 287, 712,
                901, 0, 'A', 1, 0));

        assertEquals("ABCDEFG\u03b1\u03b2\u00e1A", data(15, 1, 63, 125, 209, 927, 9, 901, 225, 226, 927, 3, 901, 225, 65));
        assertEquals("ABCDEFG\u03b1\u03b2\u00e1A", data(14, 1, 63, 125, 209, 927, 9, 901, 225, 226, 927, 3, 225, 65));
        assertEquals("ABCDEFG\u03b1\u03b2\u03b3\u03b4\u03b5\u03b6\u00e1A\u0436\u0437\u0438", data(
                24, 1, 63, 125, 209, 927, 9, 924, 378, 492, 165, 708, 390, 927, 3, 901, 225, 65, 927, 7, 901,
                214, 215, 216));
        assertEquals("AB\u03b2\u0436", data(10, 1, 913, 927, 9, 226, 913, 927, 7, 214));

        assertEquals("AB\u03b1\u03b2\u00e1", data(13, 901, 65, 66, 927, 9, 901, 225, 226, 927, 3, 901, 225));
        assertEquals("AB\u03b1\u03b2\u00e1", data(11, 901, 65, 66, 927, 9, 225, 226, 927, 3, 225));
        assertEquals("\u70b9\u8317\u03b1", data(11, 901, 927, 20, 147, 95, 228, 170, 927, 9, 225));
        assertEquals("\u70b9\u8317\u03b1", data(16, 901, 927, 20, 147, 95, 228, 170, 926, 899, 899, 927, 9, 925, 0, 225));
        assertEquals("\u70b9\u8317\u30c6\u0452", data(13, 924, 927, 20, 246, 877, 166, 106, 797, 927, 22, 901, 0x90));
        assertEquals("\u70b9\u8317\u30c6\u03b1\u0452", data(
                15, 901, 927, 20, 246, 877, 166, 106, 797, 927, 9, 225, 927, 22, 0x90));
        assertEquals("\u70b9\u8317\u30c6\u9f44\u8180\u8d67\u03b1\u0452\u0179", data(
                37, 927, 10, 901, 927, 20, 246, 877, 166, 106, 797, 927, 30, 927, 29, 415, 537, 357, 329, 194,
                927, 8, 927, 9, 225, 926, 899, 898, 927, 22, 0x90, 927, 4, 900, 913, 0xAC, 925, 1));

        assertEquals("1234567890123456789012345678901234567890123AB\u70b9", data(
                25, 902, 171, 209, 269, 12, 434, 927, 20, 404, 629, 775, 441, 213, 222, 288, 513, 400, 123,
                900, 1, 913, 147, 913, 95));
        assertEquals("123456789012345678901234567890123456789012AB\u70b9\u0398", data(
                31, 902, 190, 232, 498, 813, 782, 767, 927, 16, 259, 248, 517, 378, 927, 20, 289, 700, 317, 21,
                112, 901, 'A', 'B', 147, 95, 927, 26, 0xCE, 901, 0x98));
        assertEquals("12345678901234567890123456789\u3930\u3132\u3334\u3536\u3738\u3930\u3132A\u0100\u0141\u0398", data(
                32, 902, 289, 885, 405, 732, 212, 109, 679, 286, 885, 289, 927, 16, 927, 25, 289, 700, 317, 21,
                112, 924, 0, 382, 878, 524, 177, 927, 26, 901, 0xCE, 0x98));

        assertEquals("", data(4, 927, 901, 0));
        assertEquals("AA", data(3, 0, 927));
        assertEquals("AA", data(5, 926, 10, 10, 0));
        assertNotNull(data(4, 0, 926, 10));
        assertNotNull(data(3, 0, 926));
        assertEquals("AA", data(4, 925, 10, 0));
        assertNotNull(data(3, 0, 925));
        assertEquals("AA", data(2, 0));
        assertEquals("AA", data(3, 921, 0));
        assertNull(data(3, 0, 921));
        assertNull(data(4, 901, 65, 921));
        assertNull(data(4, 901, 921, 65));
        assertNull(data(3, 918, 0));
        assertNull(data(3, 0, 918));
        assertEquals("AA", data(3, 920, 0));
        assertNull(data(3, 0, 920));
        assertNull(data(3, 903, 0));
        assertNull(data(3, 0, 903));
    }

    /**
     * Locked to zxing-cpp {@code PDF417DecoderTest} macro samples and
     * {@code PDF417HighLevelEncoderTest}. A macro block is not part of the text.
     * The encoder rows are the codewords with the length descriptor added in front.
     * Codeword 899 is binary, read as ISO-8859-1.
     */
    @Test
    void readsTheOfficialMacroSamplesAndEncoderCodewords() {
        assertNull(data(20, 928, 111, 100, 17, 53, 923, 1, 111, 104, 923, 3, 64, 416, 34, 923, 4, 258, 446, 67,
                1000, 1000, 1000));
        assertNull(data(11, 928, 111, 103, 17, 53, 923, 1, 111, 104, 922, 1000, 1000, 1000));
        assertNull(data(7, 928, 111, 100, 100, 200, 300));
        assertEquals("P", data(23, 477, 928, 111, 100, 0, 252, 21, 86, 923, 0, 815, 251, 133, 12, 148, 537, 593,
                599, 923, 1, 111, 102, 98, 311, 355, 522, 920, 779, 40, 628, 33, 749, 267, 506, 213, 928, 465, 248,
                493, 72, 780, 699, 780, 493, 755, 84, 198, 628, 368, 156, 198, 809, 19, 113));
        assertEquals("P", data(25, 477, 928, 111, 100, 0, 252, 21, 86, 923, 2, 2, 0, 1, 0, 0, 0, 923, 5, 130,
                923, 6, 1, 500, 13));
        assertEquals("P", data(7, 477, 928, 222, 198, 0, 922));

        assertEquals("ABCD", data(8, 927, 26, 901, 'A', 'B', 'C', 'D'));
        assertEquals("ABCD", data(5, 927, 26, 1, '?'));
        assertEquals("1234", data(6, 927, 26, 902, 12, 434));
        assertEquals("abcd", data(8, 927, 26, 901, 'a', 'b', 'c', 'd'));
        assertEquals("\u00e9", data(5, 927, 899, 901, 0xe9));
    }

    /** Locked to zxing-cpp {@code PDF417ScanningDecoderTest}. A bad length descriptor is repaired. */
    @Test
    void repairsABadSymbolLengthDescriptor() throws ReedSolomonException {
        int[] high = {4, 1, 449, 394};
        assertEquals("AB", Pdf417Decoder.decodeCodewords(high, 2, new int[0]));
        assertEquals(2, high[0]);
        int[] low = {1, 1, 800, 351};
        assertEquals("AB", Pdf417Decoder.decodeCodewords(low, 2, new int[0]));
        assertEquals(2, low[0]);
        int[] zero = {0, 1, 917, 27};
        assertEquals("AB", Pdf417Decoder.decodeCodewords(zero, 2, new int[0]));
        assertEquals(2, zero[0]);
    }

    /**
     * Locked to zxing-cpp {@code PDF417WriterTest}. Margin is zero, so the grid is the symbol.
     * Each pair of characters is one module. The symbol is 103 by 32, and each of the eight
     * rows is drawn four times.
     */
    @Test
    void readsTheOfficialWriterPicture() {
        assertEquals("Hello Google", Pdf417Decoder.decode(spaced(WRITER_HELLO)));
    }

    /** Writer art uses a space between modules. Trailing spaces are part of the row. */
    private static boolean[][] spaced(String[] rows) {
        int width = rows[0].length() / 2;
        boolean[][] image = new boolean[rows.length][width];
        for (int y = 0; y < rows.length; y++) {
            if (rows[y].length() != width * 2) {
                throw new IllegalArgumentException("row " + y);
            }
            for (int x = 0; x < width; x++) {
                image[y][x] = rows[y].charAt(x * 2) == 'X';
            }
        }
        return image;
    }

    private static final String[] WRITER_HELLO = {
            "X X X X X X X X   X   X   X       X X X X X   X   X   X X X X X     X X   X   X         X X           X X X X   X X     X     X X X     X X X X   X   X   X X X X         X X X X X X X   X       X   X     X ",
            "X X X X X X X X   X   X   X       X X X X X   X   X   X X X X X     X X   X   X         X X           X X X X   X X     X     X X X     X X X X   X   X   X X X X         X X X X X X X   X       X   X     X ",
            "X X X X X X X X   X   X   X       X X X X X   X   X   X X X X X     X X   X   X         X X           X X X X   X X     X     X X X     X X X X   X   X   X X X X         X X X X X X X   X       X   X     X ",
            "X X X X X X X X   X   X   X       X X X X X   X   X   X X X X X     X X   X   X         X X           X X X X   X X     X     X X X     X X X X   X   X   X X X X         X X X X X X X   X       X   X     X ",
            "X X X X X X X X   X   X   X       X X X X X X   X   X       X X X   X X X X   X       X X   X X       X   X X     X     X X X X X X     X X X X   X   X     X             X X X X X X X   X       X   X     X ",
            "X X X X X X X X   X   X   X       X X X X X X   X   X       X X X   X X X X   X       X X   X X       X   X X     X     X X X X X X     X X X X   X   X     X             X X X X X X X   X       X   X     X ",
            "X X X X X X X X   X   X   X       X X X X X X   X   X       X X X   X X X X   X       X X   X X       X   X X     X     X X X X X X     X X X X   X   X     X             X X X X X X X   X       X   X     X ",
            "X X X X X X X X   X   X   X       X X X X X X   X   X       X X X   X X X X   X       X X   X X       X   X X     X     X X X X X X     X X X X   X   X     X             X X X X X X X   X       X   X     X ",
            "X X X X X X X X   X   X   X       X X X   X   X   X X X X X X       X     X X X X X   X X   X         X   X X   X X           X X X X   X X X   X   X       X X X X X X   X X X X X X X   X       X   X     X ",
            "X X X X X X X X   X   X   X       X X X   X   X   X X X X X X       X     X X X X X   X X   X         X   X X   X X           X X X X   X X X   X   X       X X X X X X   X X X X X X X   X       X   X     X ",
            "X X X X X X X X   X   X   X       X X X   X   X   X X X X X X       X     X X X X X   X X   X         X   X X   X X           X X X X   X X X   X   X       X X X X X X   X X X X X X X   X       X   X     X ",
            "X X X X X X X X   X   X   X       X X X   X   X   X X X X X X       X     X X X X X   X X   X         X   X X   X X           X X X X   X X X   X   X       X X X X X X   X X X X X X X   X       X   X     X ",
            "X X X X X X X X   X   X   X       X X   X   X X X X     X X X X X   X       X     X X X   X X X       X   X X         X X   X           X   X   X X X X     X X X X       X X X X X X X   X       X   X     X ",
            "X X X X X X X X   X   X   X       X X   X   X X X X     X X X X X   X       X     X X X   X X X       X   X X         X X   X           X   X   X X X X     X X X X       X X X X X X X   X       X   X     X ",
            "X X X X X X X X   X   X   X       X X   X   X X X X     X X X X X   X       X     X X X   X X X       X   X X         X X   X           X   X   X X X X     X X X X       X X X X X X X   X       X   X     X ",
            "X X X X X X X X   X   X   X       X X   X   X X X X     X X X X X   X       X     X X X   X X X       X   X X         X X   X           X   X   X X X X     X X X X       X X X X X X X   X       X   X     X ",
            "X X X X X X X X   X   X   X       X X X   X   X X X         X X     X X X X X     X     X       X X   X X X X X   X       X X   X X X   X X X X   X   X X X     X X X     X X X X X X X   X       X   X     X ",
            "X X X X X X X X   X   X   X       X X X   X   X X X         X X     X X X X X     X     X       X X   X X X X X   X       X X   X X X   X X X X   X   X X X     X X X     X X X X X X X   X       X   X     X ",
            "X X X X X X X X   X   X   X       X X X   X   X X X         X X     X X X X X     X     X       X X   X X X X X   X       X X   X X X   X X X X   X   X X X     X X X     X X X X X X X   X       X   X     X ",
            "X X X X X X X X   X   X   X       X X X   X   X X X         X X     X X X X X     X     X       X X   X X X X X   X       X X   X X X   X X X X   X   X X X     X X X     X X X X X X X   X       X   X     X ",
            "X X X X X X X X   X   X   X       X X X X   X   X X X X   X         X     X X X             X   X X   X X   X X X X X   X         X     X X X   X   X X X X X   X         X X X X X X X   X       X   X     X ",
            "X X X X X X X X   X   X   X       X X X X   X   X X X X   X         X     X X X             X   X X   X X   X X X X X   X         X     X X X   X   X X X X X   X         X X X X X X X   X       X   X     X ",
            "X X X X X X X X   X   X   X       X X X X   X   X X X X   X         X     X X X             X   X X   X X   X X X X X   X         X     X X X   X   X X X X X   X         X X X X X X X   X       X   X     X ",
            "X X X X X X X X   X   X   X       X X X X   X   X X X X   X         X     X X X             X   X X   X X   X X X X X   X         X     X X X   X   X X X X X   X         X X X X X X X   X       X   X     X ",
            "X X X X X X X X   X   X   X       X X X   X     X X X   X X X X X   X X X X   X X   X X     X X X X   X X   X X X   X X       X         X X   X     X X X   X X X X       X X X X X X X   X       X   X     X ",
            "X X X X X X X X   X   X   X       X X X   X     X X X   X X X X X   X X X X   X X   X X     X X X X   X X   X X X   X X       X         X X   X     X X X   X X X X       X X X X X X X   X       X   X     X ",
            "X X X X X X X X   X   X   X       X X X   X     X X X   X X X X X   X X X X   X X   X X     X X X X   X X   X X X   X X       X         X X   X     X X X   X X X X       X X X X X X X   X       X   X     X ",
            "X X X X X X X X   X   X   X       X X X   X     X X X   X X X X X   X X X X   X X   X X     X X X X   X X   X X X   X X       X         X X   X     X X X   X X X X       X X X X X X X   X       X   X     X ",
            "X X X X X X X X   X   X   X       X X X X X   X     X   X X         X       X   X X X X           X   X   X X X X X       X   X X X     X   X   X X X X X X   X X X       X X X X X X X   X       X   X     X ",
            "X X X X X X X X   X   X   X       X X X X X   X     X   X X         X       X   X X X X           X   X   X X X X X       X   X X X     X   X   X X X X X X   X X X       X X X X X X X   X       X   X     X ",
            "X X X X X X X X   X   X   X       X X X X X   X     X   X X         X       X   X X X X           X   X   X X X X X       X   X X X     X   X   X X X X X X   X X X       X X X X X X X   X       X   X     X ",
            "X X X X X X X X   X   X   X       X X X X X   X     X   X X         X       X   X X X X           X   X   X X X X X       X   X X X     X   X   X X X X X X   X X X       X X X X X X X   X       X   X     X ",
    };

    private static String data(int... codewords) {
        return Pdf417Decoder.decodeData(codewords);
    }

    private static void assertRoundTrip(String text, Map<EncodeHintType, ?> hints) throws Exception {
        boolean[][] image = matrix(text, hints);
        assertEquals(text, Pdf417Decoder.decode(image), text);
        assertEquals(text, zxing(image), text);
    }

    private static Map<EncodeHintType, Object> hints() {
        return new EnumMap<>(EncodeHintType.class);
    }

    private static boolean[][] matrix(String text, Map<EncodeHintType, ?> hints) throws Exception {
        BitMatrix modules = new PDF417Writer().encode(text, BarcodeFormat.PDF_417, 1, 1, hints);
        boolean[][] image = new boolean[modules.getHeight()][modules.getWidth()];
        for (int y = 0; y < modules.getHeight(); y++) {
            for (int x = 0; x < modules.getWidth(); x++) {
                image[y][x] = modules.get(x, y);
            }
        }
        return image;
    }

    private static String zxing(boolean[][] image) throws Exception {
        BitMatrix modules = new BitMatrix(image[0].length, image.length);
        for (int y = 0; y < image.length; y++) {
            for (int x = 0; x < image[0].length; x++) {
                if (image[y][x]) {
                    modules.set(x, y);
                }
            }
        }
        var buffered = MatrixToImageWriter.toBufferedImage(modules);
        var source = new BufferedImageLuminanceSource(buffered);
        try {
            return new PDF417Reader().decode(new BinaryBitmap(new GlobalHistogramBinarizer(source))).getText();
        } catch (NotFoundException missed) {
            return new PDF417Reader().decode(new BinaryBitmap(new HybridBinarizer(source))).getText();
        }
    }

    /** Moves one interior edge of the first data codeword on every scanline of that row. */
    private static void nudgeOneCodeword(boolean[][] image) {
        int left = image[0].length;
        int top = image.length;
        int bottom = -1;
        for (int y = 0; y < image.length; y++) {
            for (int x = 0; x < image[y].length; x++) {
                if (image[y][x]) {
                    left = Math.min(left, x);
                    top = Math.min(top, y);
                    bottom = Math.max(bottom, y);
                }
            }
        }
        int band = top;
        while (band + 1 <= bottom && Arrays.equals(image[top], image[band + 1])) {
            band++;
        }
        for (int x = left + 36; x < left + 48 && x + 1 < image[0].length; x++) {
            if (image[top][x] != image[top][x + 1]) {
                for (int y = top; y <= band; y++) {
                    boolean bit = image[y][x];
                    image[y][x] = image[y][x + 1];
                    image[y][x + 1] = bit;
                }
                return;
            }
        }
        throw new AssertionError("no interior edge in the first data codeword");
    }

    private static void eraseLowerHalf(boolean[][] image) {
        int top = image.length;
        int bottom = -1;
        int left = image[0].length;
        int right = -1;
        for (int y = 0; y < image.length; y++) {
            for (int x = 0; x < image[y].length; x++) {
                if (image[y][x]) {
                    top = Math.min(top, y);
                    bottom = Math.max(bottom, y);
                    left = Math.min(left, x);
                    right = Math.max(right, x);
                }
            }
        }
        for (int y = top + (bottom - top) / 2; y <= bottom; y++) {
            for (int x = left; x <= right; x++) {
                image[y][x] = false;
            }
        }
    }
}
