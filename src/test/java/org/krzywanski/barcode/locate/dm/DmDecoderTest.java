package org.krzywanski.barcode.locate.dm;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.datamatrix.DataMatrixWriter;
import com.google.zxing.datamatrix.decoder.Decoder;
import com.google.zxing.datamatrix.encoder.SymbolShapeHint;
import org.junit.jupiter.api.Test;
import org.krzywanski.barcode.locate.dm.DmGeometry.Bits;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DmDecoderTest {
    @Test
    void roundTripsGrowingPayloads() throws Exception {
        int previous = 0;
        int sizes = 0;
        for (int length = 1; length <= 120; length++) {
            BitMatrix matrix = assertRoundTrip("B".repeat(length), null);
            int key = matrix.getHeight() * 1000 + matrix.getWidth();
            if (key != previous) {
                sizes++;
                previous = key;
            }
        }
        assertTrue(sizes >= 8, "sizes " + sizes);
    }

    /**
     * Payloads from zxing-cpp {@code DMEncodeDecodeTest}. Rectangle prefixes and the
     * EDIFACT samples are encoded by the Java writer; both decoders must return the text.
     */
    @Test
    void roundTripsOfficialSquareRectangleAndEdifactPayloads() throws Exception {
        String[] square = {
                "Abc123!",
                "Lorem ipsum. http://test/",
                "AAAANAAAANAAAANAAAANAAAANAAAANAAAANAAAANAAAANAAAAN",
                "http://test/~!@#*^%&)__ ;:'\"[]{}\\|-+-=`1029384",
                "http://test/~!@#*^%&)__ ;:'\"[]{}\\|-+-=`1029384756<>/?abc"
                        + "Four score and seven our forefathers brought forth",
                LOREM,
                LOREM + LOREM
        };
        Map<EncodeHintType, Object> squareHint = new EnumMap<>(EncodeHintType.class);
        squareHint.put(EncodeHintType.DATA_MATRIX_SHAPE, SymbolShapeHint.FORCE_SQUARE);
        for (String text : square) {
            BitMatrix matrix = assertRoundTrip(text, squareHint);
            assertEquals(matrix.getWidth(), matrix.getHeight(), text.length() + " chars");
        }
        String[] rectangle = {
                "Abc123!",
                "Lorem ipsum. http://test/",
                "3i0QnD^RcZO[\\#!]1,9zIJ{1z3qrvsq",
                "AAAANAAAANAAAANAAAANAAAANAAAANAAAANAAAANAAAANAAAAN",
                "http://test/~!@#*^%&)__ ;:'\"[]{}\\|-+-=`1029384",
                "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        };
        Map<EncodeHintType, Object> rectangleHint = new EnumMap<>(EncodeHintType.class);
        rectangleHint.put(EncodeHintType.DATA_MATRIX_SHAPE, SymbolShapeHint.FORCE_RECTANGLE);
        for (String text : rectangle) {
            for (int length = 1; length <= text.length(); length++) {
                BitMatrix matrix = assertRoundTrip(text.substring(0, length), rectangleHint);
                assertNotEquals(matrix.getWidth(), matrix.getHeight(), "prefix " + length);
            }
        }
        String[] edifact = {
                "https://test~[******]_",
                "abc<->ABCDE",
                "<ABCDEFG><ABCDEFGK>",
                "*CH/GN1/022/00"
        };
        for (String text : edifact) {
            assertRoundTrip(text, null);
            assertRoundTrip(text, squareHint);
            BitMatrix matrix = assertRoundTrip(text, rectangleHint);
            assertNotEquals(matrix.getWidth(), matrix.getHeight(), text);
        }
    }

    private static final String LOREM =
            "In ut magna vel mauris malesuada dictum. Nulla ullamcorper metus quis diam"
                    + " cursus facilisis. Sed mollis quam id justo rutrum sagittis. Donec laoreet rutrum"
                    + " est, nec convallis mauris condimentum sit amet. Phasellus gravida, justo et congue"
                    + " auctor, nisi ipsum viverra erat, eget hendrerit felis turpis nec lorem. Nulla"
                    + " ultrices, elit pellentesque aliquet laoreet, justo erat pulvinar nisi, id"
                    + " elementum sapien dolor et diam.";

    @Test
    void roundTripsSquareRectangleC40AndLatin1() throws Exception {
        assertRoundTrip("DM-PRO-123", null);
        assertRoundTrip("hello datamatrix", null);
        assertRoundTrip("caf\u00e9", null);
        assertRoundTrip("SHIP>QTY*12", null);
        Map<EncodeHintType, Object> c40 = new EnumMap<>(EncodeHintType.class);
        c40.put(EncodeHintType.FORCE_C40, true);
        assertRoundTrip("C40-PRO-HELLO", c40);
        Map<EncodeHintType, Object> rectangle = new EnumMap<>(EncodeHintType.class);
        rectangle.put(EncodeHintType.DATA_MATRIX_SHAPE, SymbolShapeHint.FORCE_RECTANGLE);
        BitMatrix rect = assertRoundTrip("RECT-DM-42", rectangle);
        assertNotEquals(rect.getWidth(), rect.getHeight());
    }

    @Test
    void roundTripsAMultiBlockSymbol() throws Exception {
        BitMatrix matrix = assertRoundTrip("B".repeat(400), null);
        assertTrue(matrix.getWidth() >= 52 && matrix.getWidth() == matrix.getHeight(), matrix.getWidth() + "x" + matrix.getHeight());
    }

    @Test
    void roundTripsCompactBase256AndUtf8Eci() throws Exception {
        Map<EncodeHintType, Object> compact = new EnumMap<>(EncodeHintType.class);
        compact.put(EncodeHintType.DATA_MATRIX_COMPACT, true);
        assertRoundTrip("\u00FE".repeat(30), compact);
        Map<EncodeHintType, Object> utf8 = new EnumMap<>(EncodeHintType.class);
        utf8.put(EncodeHintType.DATA_MATRIX_COMPACT, true);
        utf8.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        assertRoundTrip("za\u017c\u00f3\u0142\u0107", utf8);
    }

    @Test
    void readsAFlippedFinderOnSquareAndRectangle() throws Exception {
        assertEquals("DM-MIRROR", DmDecoder.decode(flippedL(modules(encode("DM-MIRROR", null)))));
        Map<EncodeHintType, Object> rectangle = new EnumMap<>(EncodeHintType.class);
        rectangle.put(EncodeHintType.DATA_MATRIX_SHAPE, SymbolShapeHint.FORCE_RECTANGLE);
        BitMatrix matrix = encode("RECT-MIRROR", rectangle);
        assertNotEquals(matrix.getWidth(), matrix.getHeight());
        assertEquals("RECT-MIRROR", DmDecoder.decode(flippedL(modules(matrix))));
    }

    @Test
    void rejectsABrokenGridAndAnOddSize() throws Exception {
        Bits modules = modules(encode("DM-PRO-123", null));
        Bits broken = new Bits(modules.width, modules.height);
        for (int y = 0; y < modules.height; y++) {
            for (int x = 0; x < modules.width; x++) {
                boolean value = modules.get(x, y);
                if (x >= 1 && y >= 1) {
                    value = !value;
                }
                if (value) {
                    broken.set(x, y);
                }
            }
        }
        assertNull(DmDecoder.decode(broken));
        assertNull(DmDecoder.decode(new Bits(9, 9)));
    }

    /** Finder modules are not codewords, so error correction still accepts a nearly intact border. */
    @Test
    void keepsASymbolWhenOneClockModuleIsWrong() throws Exception {
        Bits modules = modules(encode("A", null));
        assertEquals("A", DmDecoder.decode(copy(modules, 1)));
    }

    @Test
    void rejectsASampleWhoseFinderIsMostlyMissing() throws Exception {
        Bits modules = modules(encode("A", null));
        assertTrue(modules.width <= 16 && modules.height <= 16);
        assertNull(DmDecoder.decode(copy(modules, 6)));
    }

    /** Locked to zxing-cpp {@code DMDecodedBitStreamParserTest}. Identifiers and structured-append fields stay out of the text. */
    @Test
    void readsTheOfficialCodewordFixtures() {
        assertEquals("abcABC", stream(98, 99, 100, 66, 67, 68));
        assertEquals("00019899", stream(130, 131, 228, 229));
        assertNull(stream(66, 250, 68));
        assertNull(stream(66, 67, 68, 250));
        assertEquals("ABC", stream(66, 67, 68, 254));

        assertEquals("AIMAIMAIM", stream(230, 91, 11, 91, 11, 91, 11, 254));
        assertEquals("AIMAIAb", stream(66, 74, 78, 66, 74, 66, 99, 129));
        assertEquals("AIMAIMAIM\u00cb", stream(230, 91, 11, 91, 11, 91, 11, 254, 235, 76));
        assertEquals("AIMAIMAIM\u00eb", stream(230, 91, 11, 91, 11, 91, 11, 254, 235, 108));
        assertEquals("A1B2C3D4E5F6G7H8I9J0K1L2", stream(230, 88, 88, 40, 8, 107, 147, 59, 67, 126, 206, 78, 126, 144, 121, 35, 47, 254));
        assertEquals("AIMAIMAIMAIMAIMAIM", stream(230, 91, 11, 91, 11, 91, 11, 91, 11, 91, 11, 91, 11));
        assertEquals("AIMAIMAIMAIMAIMAI", stream(230, 91, 11, 91, 11, 91, 11, 91, 11, 91, 11, 90, 241));
        assertEquals("AIMAIMAIMAIMAIMA", stream(230, 91, 11, 91, 11, 91, 11, 91, 11, 91, 11, 254, 66));
        assertEquals("AIMAIMAIMAIMAIMAI", stream(230, 91, 11, 91, 11, 91, 11, 91, 11, 91, 11, 254, 66, 74, 129, 237));
        assertEquals("AIMAIMAIMA", stream(230, 91, 11, 91, 11, 91, 11, 66));
        assertEquals("AIMAIMAIMAIMAIMAIMAI", stream(230, 91, 11, 91, 11, 91, 11, 91, 11, 91, 11, 91, 11, 254, 66, 74));

        assertEquals("aimaimaim", stream(239, 91, 11, 91, 11, 91, 11, 254));
        assertEquals("aimaimaim'", stream(239, 91, 11, 91, 11, 91, 11, 254, 40, 129));
        assertEquals("aimaimaIm", stream(239, 91, 11, 91, 11, 87, 218, 110));
        assertEquals("aimaimaimB", stream(239, 91, 11, 91, 11, 91, 11, 254, 67, 129));
        assertEquals("aimaimaim{txt}\u0004", stream(239, 91, 11, 91, 11, 91, 11, 16, 218, 236, 107, 181, 69, 254, 129, 237));

        assertEquals("\u00a0", stream(230, 0x0a, 0xf4));
        assertEquals("\u00a0", stream(239, 0x0a, 0xf4));

        assertEquals("ABC>ABC123>AB", stream(238, 89, 233, 14, 192, 100, 207, 44, 31, 67));
        assertEquals("ABC>ABC123>ABC", stream(238, 89, 233, 14, 192, 100, 207, 44, 31, 254, 67, 68));
        assertEquals("ABC>ABC123>ABCD", stream(238, 89, 233, 14, 192, 100, 207, 44, 31, 96, 82, 254));
        assertEquals("ABC>ABC123>ABCDE", stream(238, 89, 233, 14, 192, 100, 207, 44, 31, 96, 82, 70));
        assertEquals("ABC>ABC123>ABCDEF", stream(238, 89, 233, 14, 192, 100, 207, 44, 31, 96, 82, 254, 70, 71, 129, 237));

        assertEquals("1", stream(50));
        assertEquals("2001", stream(232, 150, 131));
        assertEquals("\u001d2001", stream(230, 0x0A, 0x79, 254, 150, 131));
        assertEquals("AB", stream(66, 232, 67));
        assertEquals("aB", stream(98, 232, 67));
        assertEquals("9\u001dA", stream(58, 232, 66));
        assertEquals("99A", stream(229, 232, 66));
        assertEquals("?\u001dA", stream(64, 232, 66));
        assertEquals("A\u001dB", stream(230, 0x57, 0xC4, 254, 67));
        assertEquals("99\u001dA", stream(58, 58, 232, 66));
        assertEquals("2001", stream(233, 42, 1, 1, 232, 150, 131));
        assertEquals("AB", stream(233, 42, 1, 1, 66, 232, 67));

        assertNull(stream(50, 233, 42, 1, 1));
        assertEquals("1", stream(233, 42, 1, 1, 50));
        assertEquals("1", stream(233, 0, 1, 1, 50));
        assertEquals("1", stream(233, 1, 1, 1, 50));
        assertEquals("1", stream(233, 0x81, 1, 1, 50));
        assertEquals("1", stream(233, 0xFF, 1, 1, 50));
        assertEquals("1", stream(233, 0xF1, 1, 1, 50));
        assertEquals("1", stream(233, 42, 1, 12, 50));
        assertEquals("1", stream(233, 42, 12, 34, 50));
        assertEquals("1", stream(233, 42, 12, 123, 50));
        assertEquals("1", stream(233, 42, 254, 254, 50));
        assertEquals("1", stream(233, 42, 0, 0, 50));
        assertEquals("1", stream(233, 42, 0, 255, 50));
        assertEquals("1", stream(233, 42, 255, 0, 50));
        assertEquals("1", stream(233, 42, 255, 255, 50));
        assertNull(stream(50, 234));
        assertEquals("1", stream(234, 50));
        assertNull(stream(235, 234, 50));
        assertNull(stream(233, 42, 1, 1, 234, 50));
    }

    /**
     * Locked to zxing-cpp {@code DMHighLevelEncodeTest}. Each row is the encoder's codeword
     * list and the text it was built from. Pad codewords after the latch stay out of the text.
     * The partial 276- and 277-byte visualizations are not repeated here.
     */
    @Test
    void readsTheOfficialHighLevelCodewords() {
        assertEquals("123456", stream(142, 164, 186));
        assertEquals("123456\u00a3", stream(142, 164, 186, 235, 36));
        assertEquals("30Q324343430794<OQQ", stream(160, 82, 162, 173, 173, 173, 137, 224, 61, 80, 82, 82));
        assertEquals("AIMAIAB", stream(230, 91, 11, 90, 255, 254, 67, 129));
        assertEquals("AIMAIMAIMAIMaimaimaim", stream(
                230, 91, 11, 91, 11, 91, 11, 254, 66, 74, 78, 239, 91, 11, 91, 11, 91, 11));
        assertEquals("aimaimaimaim12345678", stream(
                239, 91, 11, 91, 11, 91, 11, 91, 11, 254, 142, 164, 186, 208, 129, 237));
        assertEquals("Hello World!", stream(73, 239, 116, 130, 175, 123, 148, 64, 158, 233, 254, 34));
        assertEquals("*DTCP01", stream(238, 9, 10, 104, 141, 254, 50, 129));
        assertEquals("*DTCP0", stream(238, 9, 10, 104, 141));
        assertEquals("fiykmj*Rh2`,e6", stream(239, 122, 87, 154, 40, 7, 171, 115, 207, 12, 130, 71, 155, 254, 129, 237));
        assertEquals("[)>\u001e05\u001d5555\u001c6666\u001e\u0004", stream(236, 185, 185, 29, 196, 196, 129, 56));
        assertEquals("*MEMANT-1F-MESTECH", stream(238, 10, 99, 164, 204, 254, 240, 82, 220, 70, 180, 209, 83, 80, 80, 200));

        assertEquals(".A.C1.3.DATA.123DATA.123DATA", stream(
                240, 184, 27, 131, 198, 236, 238, 16, 21, 1, 187, 28, 179, 16, 21, 1, 187, 28, 179, 16, 21, 1));
        assertEquals(".A.C1.3.X.X2..", stream(240, 184, 27, 131, 198, 236, 238, 98, 230, 50, 47, 47));
        assertEquals(".A.C1.3.X.X2.", stream(240, 184, 27, 131, 198, 236, 238, 98, 230, 50, 47, 129));
        assertEquals(".A.C1.3.X.X2", stream(240, 184, 27, 131, 198, 236, 238, 98, 230, 50));
        assertEquals(".A.C1.3.X.X", stream(240, 184, 27, 131, 198, 236, 238, 98, 230, 31));
        assertEquals(".A.C1.3.X.", stream(240, 184, 27, 131, 198, 236, 238, 98, 231, 192));
        assertEquals(".A.C1.3.X", stream(240, 184, 27, 131, 198, 236, 238, 89));
        assertEquals(".XXX.XXX.XXX.XXX.XXX.XXX.\u00fcXX.XXX.XXX.XXX.XXX.XXX.XXX", stream(
                240, 185, 134, 24, 185, 134, 24, 185, 134, 24, 185, 134, 24, 185, 134, 24, 185, 134, 24,
                124, 47, 235, 125, 240, 97, 139, 152, 97, 139, 152, 97, 139, 152, 97, 139, 152,
                97, 139, 152, 97, 139, 152, 89, 89));
        assertEquals("abc<->ABCDE", stream(98, 99, 100, 240, 242, 223, 129, 8, 49, 5, 129, 147));
        assertEquals("CREX-TAN:h", stream(240, 13, 33, 88, 181, 64, 78, 124, 59, 105));
        assertEquals("CREX-TAN:hh", stream(240, 13, 33, 88, 181, 64, 78, 124, 59, 105, 105, 129));
        assertEquals("CREX-TAN:hhh", stream(240, 13, 33, 88, 181, 64, 78, 124, 59, 105, 105, 105));

        assertEquals("\u00ab\u00e4\u00f6\u00fc\u00e9\u00bb", stream(231, 44, 108, 59, 226, 126, 1, 104));
        assertEquals("\u00ab\u00e4\u00f6\u00fc\u00e9\u00e0\u00bb", stream(231, 51, 108, 59, 226, 126, 1, 141, 254, 129));
        assertEquals("\u00ab\u00e4\u00f6\u00fc\u00e9\u00e0\u00e1\u00bb", stream(231, 44, 108, 59, 226, 126, 1, 141, 36, 147));
        assertEquals(" 23\u00a3", stream(33, 153, 235, 36, 129));
        assertEquals("\u00ab\u00e4\u00f6\u00fc\u00e9\u00bb 234", stream(231, 51, 108, 59, 226, 126, 1, 104, 99, 153, 53, 129));
        assertEquals("\u00ab\u00e4\u00f6\u00fc\u00e9\u00bb 23\u00a3 1234567890123456789", stream(
                231, 55, 108, 59, 226, 126, 1, 104, 99, 10, 161, 167, 185, 142, 164, 186, 208,
                220, 142, 164, 186, 208, 58, 129, 59, 209, 104, 254, 150, 45));
        assertEquals(binaryMessage(20), stream(
                231, 44, 108, 59, 226, 126, 1, 141, 36, 5, 37, 187, 80, 230, 123, 17, 166, 60, 210, 103, 253, 150));
        assertEquals(binaryMessage(19), stream(
                231, 63, 108, 59, 226, 126, 1, 141, 36, 5, 37, 187, 80, 230, 123, 17, 166, 60, 210, 103, 1, 129));
    }

    /** {@code CreateBinaryMessage}: eight lead bytes, {@code len - 9} middle dots, one trailing byte. */
    private static String binaryMessage(int length) {
        return "\u00ab\u00e4\u00f6\u00fc\u00e9\u00e0\u00e1-" + "\u00b7".repeat(length - 9) + "\u00bb";
    }

    /**
     * Locked to zxing-cpp {@code DMPlacementTest}. The picture is the data region of an encoded
     * {@code AIMAIM}. Reading it back is the inverse of {@code BitMatrixFromCodewords}.
     */
    @Test
    void readsTheOfficialPlacement() {
        Bits data = modules("""
                011100001111
                001010101000
                010001010100
                001010100010
                000111000100
                011000010100
                000100001101
                011000010000
                001100001101
                100010010111
                011101011010
                001011001010
                """);
        assertArrayEquals(new int[] {
                66, 74, 78, 66, 74, 78, 129, 56, 35, 102, 192, 96, 226, 100, 156, 1, 107, 221
        }, DmDecoder.readCodewords(data, 18));
    }


    /**
     * Locked to zxing-cpp {@code DMWriterTest}. Margin is zero, so the grid is the symbol.
     * Each pair of characters is one module. The rectangle is 18 by 8. The large
     * square is 52 by 52 and holds thirty copies of {@code 123456789-}.
     */
    @Test
    void readsTheOfficialWriterPictures() {
        assertEquals("0", DmDecoder.decode(spaced(WRITER_SQUARE)));
        assertEquals("abcde", DmDecoder.decode(spaced(WRITER_RECTANGLE)));
        assertEquals("123456789-".repeat(30), DmDecoder.decode(spaced(WRITER_LARGE)));
    }

    /**
     * Locked to zxing-cpp {@code DMWriterTest} size rows. Margin is zero. {@code Hello Me}
     * requested as 14 is a 14 by 14 square. {@code Hello Google} is the natural square and
     * fits in 64; a scaled 64 canvas is not the symbol. The URL does not fit in 8.
     * Natural sizes other than 14 are not asserted.
     */
    @Test
    void readsTheOfficialWriterSizeRows() throws Exception {
        Map<EncodeHintType, Object> square = new EnumMap<>(EncodeHintType.class);
        square.put(EncodeHintType.DATA_MATRIX_SHAPE, SymbolShapeHint.FORCE_SQUARE);
        square.put(EncodeHintType.MARGIN, 0);

        BitMatrix hello = encode("Hello Me", 14, 14, square);
        assertEquals(14, hello.getWidth());
        assertEquals(14, hello.getHeight());
        assertEquals("Hello Me", DmDecoder.decode(modules(hello)));

        BitMatrix google = encode("Hello Google", 0, 0, square);
        assertTrue(google.getWidth() <= 64 && google.getHeight() <= 64, google.getWidth() + "x" + google.getHeight());
        assertEquals("Hello Google", DmDecoder.decode(modules(google)));

        Map<EncodeHintType, Object> margin = new EnumMap<>(EncodeHintType.class);
        margin.put(EncodeHintType.MARGIN, 0);
        BitMatrix url = encode("http://www.google.com/", 8, 8, margin);
        assertTrue(url.getWidth() > 8 && url.getHeight() > 8, url.getWidth() + "x" + url.getHeight());
        assertEquals("http://www.google.com/", DmDecoder.decode(modules(url)));
    }

    private static final String[] WRITER_SQUARE = {
        "X   X   X   X   X   ",
        "X X   X X     X   X ",
        "X       X X     X   ",
        "X     X           X ",
        "X     X   X X X X   ",
        "X X X X X X       X ",
        "X       X   X       ",
        "X X     X X X   X X ",
        "X   X       X       ",
        "X X X X X X X X X X ",
    };

    private static final String[] WRITER_RECTANGLE = {
        "X   X   X   X   X   X   X   X   X   ",
        "X   X X     X     X     X   X X   X ",
        "X X       X X   X     X   X X       ",
        "X   X X X     X     X X   X X   X X ",
        "X     X X X   X X X X X X X X X     ",
        "X   X X     X     X X X X       X X ",
        "X X   X X X       X X X X X   X X   ",
        "X X X X X X X X X X X X X X X X X X ",
    };

    private static final String[] WRITER_LARGE = {
        "X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   ",
        "X X     X     X   X   X     X X X X     X     X   X X     X X       X X X   X   X   X     X X       X X ",
        "X X                   X X X   X   X                 X X X X X   X X     X   X   X X   X X X X   X X     ",
        "X X     X X X     X X     X   X   X     X X X X X X X   X   X     X             X X X   X   X     X   X ",
        "X X       X   X   X               X       X     X   X   X   X     X X X X   X X     X   X   X           ",
        "X   X   X     X X   X X X     X X   X   X X       X X   X X X X     X   X     X         X X X   X X X X ",
        "X   X X   X X X       X   X   X X   X X   X X X X   X X   X   X     X   X     X X X X X   X       X X   ",
        "X   X X X   X   X   X     X X       X X     X     X X X   X   X     X X X X     X     X   X           X ",
        "X X     X   X   X X   X X X X   X X   X             X         X X X   X   X               X X   X X X   ",
        "X X             X X X   X   X     X   X     X X X X X     X X     X   X   X     X X X X       X   X X X ",
        "X X X X X   X X     X   X   X         X       X     X X   X               X       X           X X X     ",
        "X   X   X     X         X X X     X X   X   X     X X X X   X X X     X X   X   X         X X X   X   X ",
        "X   X   X     X X X X X   X   X   X X   X X   X X   X X       X   X   X X   X X   X   X     X   X X     ",
        "X   X X X X     X     X       X X       X X X   X X X   X   X     X X       X     X X       X X X X   X ",
        "X X   X   X               X X X X   X X     X   X   X   X X   X X X X   X X   X X X X   X   X     X X   ",
        "X X   X   X     X X X X X   X   X     X           X X   X X X   X   X     X X   X   X     X X   X X   X ",
        "X         X       X     X   X   X     X X X X   X   X X     X   X   X       X         X X   X X   X     ",
        "X     X X   X   X X         X X X X     X   X     X X X         X X X X         X X X     X X   X X   X ",
        "X X   X X   X X   X X X X X   X   X     X   X       X X X X X X   X   X X X X X   X   X     X X X   X   ",
        "X X X       X X     X     X   X   X     X X X X   X X   X     X   X     X     X     X X     X   X     X ",
        "X X X   X X   X                   X X X   X   X     X             X       X       X   X   X X X X X X   ",
        "X   X     X   X     X X X     X X     X   X   X   X X   X X X X   X     X             X     X X X   X X ",
        "X   X         X       X   X   X               X     X     X       X X X X X X   X   X   X X X   X X X   ",
        "X X X     X X   X   X     X X   X X X     X X   X X X   X     X X   X X X             X   X       X X X ",
        "X X   X   X X   X X   X X X       X   X   X X   X   X X   X   X X   X   X     X X X X X X     X   X     ",
        "X X X X X X X X X X X X X X X X X X X X X X X X X X X X X X X X X X X X X X X X X X X X X X X X X X X X ",
        "X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   ",
        "X     X X       X X X   X   X   X     X X       X X X     X X     X   X X X X X X   X X   X X       X X ",
        "X X X X X   X X     X   X   X X   X X X X   X X     X X X X     X   X   X X           X       X X   X   ",
        "X   X   X     X             X X X   X   X     X X X X   X   X   X       X X           X X         X X X ",
        "X   X   X     X X X X   X X     X   X   X       X   X   X X X               X     X X X X   X   X   X   ",
        "X   X X X X     X   X     X         X X X X       X X         X     X   X     X X     X   X X   X     X ",
        "X X   X   X     X   X     X X X X X   X   X X X     X X   X   X         X X X   X X     X X     X   X   ",
        "X X   X   X     X X X X     X     X   X     X     X X X X X         X X   X   X     X X   X X X X     X ",
        "X         X X X   X   X               X     X X     X   X       X X X     X       X   X X   X     X     ",
        "X     X X     X   X   X     X X X X   X     X   X X X   X X     X   X       X X   X   X   X       X X X ",
        "X X   X               X       X       X X   X X X   X   X       X       X X X   X   X X     X X   X X   ",
        "X X X   X X X     X X   X   X     X X           X X X X     X X X X X   X     X X X   X     X         X ",
        "X X       X   X   X X   X X   X   X     X X X       X X   X X       X X X   X X     X   X     X   X X   ",
        "X   X   X     X X       X     X X     X X     X   X X X   X   X X   X           X X       X X X X   X X ",
        "X   X X   X X X X   X X   X X X X   X X   X X X     X     X X X           X       X X X X X X       X   ",
        "X   X X X   X   X     X X   X   X X   X         X X X           X X     X X X X     X X   X X         X ",
        "X X     X   X   X       X     X X       X X     X   X X   X     X X         X   X X X X X X   X     X   ",
        "X X         X X X X             X X X     X X X X X X         X X X   X X X X X X X X X X X       X   X ",
        "X X X X X X   X   X X X X X   X X     X X X   X     X       X     X X       X X X       X X   X     X   ",
        "X   X     X   X     X   X     X X X X     X X     X X     X X X   X   X X X X X X   X   X     X   X   X ",
        "X             X       X     X   X   X               X   X X   X X   X     X X           X X   X X X     ",
        "X   X X X X   X     X X X X   X     X     X   X X X X   X   X X X     X X   X             X X X   X   X ",
        "X     X       X           X     X X     X     X X   X     X X X       X   X       X   X   X     X       ",
        "X   X X   X X     X   X   X X   X   X X       X   X X         X X X X   X           X   X           X X ",
        "X X   X X X X X       X     X         X X X X X     X     X   X X         X X X     X     X   X X   X   ",
        "X X X X X X X X X X X X X X X X X X X X X X X X X X X X X X X X X X X X X X X X X X X X X X X X X X X X ",
    };

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

    private static Bits modules(String art) {
        String[] lines = art.strip().split("\n");
        int width = lines[0].length();
        Bits bits = new Bits(width, lines.length);
        for (int y = 0; y < lines.length; y++) {
            if (lines[y].length() != width) {
                throw new IllegalArgumentException(lines[y]);
            }
            for (int x = 0; x < width; x++) {
                if (lines[y].charAt(x) == '1') {
                    bits.set(x, y);
                }
            }
        }
        return bits;
    }

    private static String stream(int... codewords) {
        byte[] raw = new byte[codewords.length];
        for (int i = 0; i < codewords.length; i++) {
            raw[i] = (byte) codewords[i];
        }
        return DmDecoder.decodeStream(raw);
    }

    private static BitMatrix assertRoundTrip(String text, Map<EncodeHintType, ?> hints) throws Exception {
        BitMatrix matrix = encode(text, hints);
        assertEquals(text, DmDecoder.decode(modules(matrix)));
        assertEquals(text, new Decoder().decode(matrix).getText());
        return matrix;
    }

    private static BitMatrix encode(String text, Map<EncodeHintType, ?> hints) {
        return encode(text, 0, 0, hints);
    }

    private static BitMatrix encode(String text, int width, int height, Map<EncodeHintType, ?> hints) {
        return new DataMatrixWriter().encode(text, BarcodeFormat.DATA_MATRIX, width, height, hints);
    }

    private static Bits modules(BitMatrix matrix) {
        Bits bits = new Bits(matrix.getWidth(), matrix.getHeight());
        for (int y = 0; y < matrix.getHeight(); y++) {
            for (int x = 0; x < matrix.getWidth(); x++) {
                if (matrix.get(x, y)) {
                    bits.set(x, y);
                }
            }
        }
        return bits;
    }

    /** Flips the first {@code count} modules of the top clock track. Those modules are not codewords. */
    private static Bits copy(Bits modules, int flipped) {
        Bits out = new Bits(modules.width, modules.height);
        for (int y = 0; y < modules.height; y++) {
            for (int x = 0; x < modules.width; x++) {
                boolean value = modules.get(x, y);
                if (y == 0 && x < flipped) {
                    value = !value;
                }
                if (value) {
                    out.set(x, y);
                }
            }
        }
        return out;
    }

    /** Same mapping as {@code FlippedL}: {@code (x, y)} reads {@code (width-1-y, height-1-x)}. */
    private static Bits flippedL(Bits bits) {
        Bits out = new Bits(bits.height, bits.width);
        for (int y = 0; y < out.height; y++) {
            for (int x = 0; x < out.width; x++) {
                if (bits.get(bits.width - 1 - y, bits.height - 1 - x)) {
                    out.set(x, y);
                }
            }
        }
        return out;
    }
}
