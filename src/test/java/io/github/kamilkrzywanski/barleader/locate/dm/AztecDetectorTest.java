package io.github.kamilkrzywanski.barleader.locate.dm;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.aztec.AztecWriter;
import com.google.zxing.aztec.encoder.AztecCode;
import com.google.zxing.aztec.encoder.Encoder;
import com.google.zxing.common.BitMatrix;
import io.github.kamilkrzywanski.barleader.BarcodeHit;
import io.github.kamilkrzywanski.barleader.Symbology;
import io.github.kamilkrzywanski.barleader.decode.BitmapDecoder;
import io.github.kamilkrzywanski.barleader.image.GrayImages;
import io.github.kamilkrzywanski.barleader.image.PixelMap;
import io.github.kamilkrzywanski.barleader.image.Samples;
import io.github.kamilkrzywanski.barleader.image.ZxingLuminance;
import io.github.kamilkrzywanski.barleader.locate.dm.DmGeometry.Bits;
import io.github.kamilkrzywanski.barleader.locate.dm.DmGeometry.P;
import io.github.kamilkrzywanski.barleader.locate.dm.DmGeometry.Warp;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AztecDetectorTest {
    @Test
    void readsFlatAztec() {
        assertReads(symbol("AZTEC-FLAT"), "AZTEC-FLAT");
    }

    /** The attempt binarizer is ZXing hybrid, not the local-average trace. */
    @Test
    void readsARenderedSymbolOnTheScanPath() {
        EnumSet<Symbology> requested = EnumSet.of(Symbology.AZTEC);
        List<BarcodeHit> hits = BitmapDecoder.read(
                ZxingLuminance.of(symbol("AZTEC-PRO")),
                PixelMap.IDENTITY,
                "original-hybrid",
                0,
                0.92,
                requested,
                false,
                false
        );
        assertEquals(1, hits.size(), hits.toString());
        assertEquals("AZTEC-PRO", hits.get(0).text());
        assertEquals(Symbology.AZTEC, hits.get(0).symbology());
        assertEquals("original-hybrid", hits.get(0).attempt());
        assertEquals(EnumSet.of(Symbology.AZTEC), requested);
    }

    @Test
    void readsPerspectiveAztec() {
        assertReads(Samples.perspective(symbol("AZTEC-SKEW")), "AZTEC-SKEW");
    }

    /**
     * Full-range layer 5 is at least 35 modules, so zxing-cpp samples it with {@code LocalGrid}.
     * The flat symbol has to decode, and each corner reference cross has to land on that module.
     */
    @Test
    void readsFlatAztecThatNeedsTimingCrosses() {
        AztecCode code = Encoder.encode("A", 33, 5);
        BitMatrix modules = code.getMatrix();
        assertNotNull(modules);
        int dimension = modules.getWidth();
        assertTrue(dimension >= 35 && dimension == modules.getHeight(), "dimension " + dimension);
        assertCrosses(modules);
        assertReads(symbol("A", 5, 480), "A");
        // Layer 12 is 67 modules: two timing rings, so the warp is rebuilt between them.
        AztecCode wider = Encoder.encode("A", 33, 12);
        assertTrue(wider.getMatrix().getWidth() >= 64, "dimension " + wider.getMatrix().getWidth());
        assertCrosses(wider.getMatrix());
        assertReads(symbol("A", 12, 800), "A");
        Bits blank = new Bits(80, 80);
        Warp warp = Warp.between(
                new P[]{new P(0, 0), new P(20, 0), new P(20, 20), new P(0, 20)},
                new P[]{new P(8, 8), new P(48, 8), new P(48, 48), new P(8, 48)}
        );
        assertNull(new LocalGrid(blank, warp, 10, 10, 20, 20, 0, 0).findTimingPatternCross(true, 4));
    }

    /**
     * Compact layer 1 whose mode message is all zeros, from zxing-cpp
     * {@code AZDetectorTest::ErrorInModeMessageZero}. Quarter-turns and the
     * {@code BitMatrix::mirror} transpose are scaled and read by the bullseye finder.
     * {@code DetectPure} is not used.
     */
    @Test
    void readsTheAllZeroModeMessage() {
        for (int turn = 0; turn < 4; turn++) {
            assertReads(render(turned(ZERO_MODE, turn)), "X");
            assertReads(render(turned(mirrored(ZERO_MODE), turn)), "X");
        }
    }

    /**
     * One flipped orientation module on the unrotated all-zero picture, including its mirror.
     * The twelve points are zxing-cpp {@code GetOrientationPoints} for a compact 15-module symbol.
     */
    @Test
    void readsTheAllZeroModeMessageWithOneOrientationFlip() {
        for (int[] point : ZERO_MODE_ORIENTATION) {
            String[] broken = flipped(ZERO_MODE, point[0], point[1]);
            assertReads(render(broken), "X");
            assertReads(render(flipped(mirrored(ZERO_MODE), point[0], point[1])), "X");
        }
    }

    /**
     * Two flipped orientation modules on the unrotated all-zero picture.
     * zxing-cpp checks these pairs when the symbol is not mirrored.
     */
    @Test
    void readsTheAllZeroModeMessageWithTwoOrientationFlips() {
        int[][] points = ZERO_MODE_ORIENTATION;
        for (int i = 0; i < points.length; i++) {
            for (int j = i + 1; j < points.length; j++) {
                String[] broken = flipped(
                        flipped(ZERO_MODE, points[i][0], points[i][1]),
                        points[j][0],
                        points[j][1]);
                assertReads(render(broken), "X");
            }
        }
    }

    /**
     * Compact layer 3 from zxing-cpp {@code AZDetectorTest::ErrorInOrientationBitsCompact}.
     * Dimension 23 stays on the single warp. Quarter-turns and the mirror transpose
     * go through the bullseye finder. {@code DetectPure} is not used.
     */
    @Test
    void readsTheOfficialCompactPictureWithOneOrientationFlip() {
        assertOneOrientationFlip(COMPACT_WIKI, true, "This is an example Aztec symbol for Wikipedia.");
    }

    @Test
    void readsTheOfficialCompactPicture() {
        String text = "This is an example Aztec symbol for Wikipedia.";
        for (int turn = 0; turn < 4; turn++) {
            assertReads(render(turned(COMPACT_WIKI, turn)), text);
            assertReads(render(turned(mirrored(COMPACT_WIKI), turn)), text);
        }
    }

    /**
     * Full layer 6 from zxing-cpp {@code AZDetectorTest::ErrorInOrientationBitsNotCompact}.
     * Dimension 41 is sampled through {@code LocalGrid}. Quarter-turns and the mirror
     * transpose use that path. {@code DetectPure} is not used.
     */
    @Test
    void readsTheOfficialFullLayerSixPictureWithOneOrientationFlip() {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYabcdefghijklmnopqrstuvwxyz";
        assertOneOrientationFlip(FULL_LAYER_6, false, alphabet + alphabet + alphabet);
    }

    @Test
    void readsTheOfficialFullLayerSixPicture() {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYabcdefghijklmnopqrstuvwxyz";
        String text = alphabet + alphabet + alphabet;
        for (int turn = 0; turn < 4; turn++) {
            assertReads(render(turned(FULL_LAYER_6, turn)), text);
            assertReads(render(turned(mirrored(FULL_LAYER_6), turn)), text);
        }
    }

    /**
     * Official rune pictures from zxing-cpp {@code AZDetectorTest::Rune}.
     * Each character pair is one module. The text is the three-digit rune value.
     * The last picture is a compact core and is not a rune.
     */
    @Test
    void readsOfficialRunes() {
        for (int turn = 0; turn < 4; turn++) {
            assertReads(render(turned(RUNE_0, turn)), "000");
            assertReads(render(turned(RUNE_25, turn)), "025");
            List<AztecDetector.Found> invalid = AztecDetector.read(GrayImages.from(render(turned(NOT_A_RUNE, turn))));
            assertTrue(invalid.isEmpty(), invalid.toString());
        }
    }

    /**
     * Module grids from zxing-cpp {@code AZEncoderTest}. Compact layer 3 is the Wikipedia
     * sentence. Full layer 6 is the public-domain description. Rune 255 is the second
     * encoder picture. Each character pair is one module. Quarter-turns use the bullseye finder.
     */
    @Test
    void readsTheOfficialEncoderPictures() {
        String wikipedia = "This is an example Aztec symbol for Wikipedia.";
        String description = "Aztec Code is a public domain 2D matrix barcode symbology"
                + " of nominally square symbols built on a square grid with a "
                + "distinctive square bullseye pattern at their center.";
        for (int turn = 0; turn < 4; turn++) {
            assertReads(render(turned(ENCODER_WIKIPEDIA, turn)), wikipedia);
            assertReads(render(turned(ENCODER_PUBLIC_DOMAIN, turn)), description);
            assertReads(render(turned(RUNE_255, turn)), "255");
        }
    }

    /**
     * Reader-init pictures from zxing-cpp {@code AZDetectorTest::ReaderInitFull2Layers},
     * {@code ReaderInitFull22Layers}, and {@code ReaderInitCompact}, plus {@code PureBlankEdge}.
     * The second picture of each pair has the artificial reader-init bit set.
     * That bit is cleared before the block count is read and is not on {@code Found}.
     * The 22-layer picture and both smaller pairs still decode to {@code $I}.
     * Layer 22 is 109 modules, so it is sampled from the reference-grid crosses. The blank-edge symbol is compact,
     * its top row is white, and its payload is the bytes {@code C3 95 AB} under
     * ECI 899, read as ISO-8859-1. {@code DetectPure} is not used: the bullseye
     * finder reads the module grid. Quarter-turns use that same finder.
     */
    @Test
    void readsOfficialReaderInitPictures() {
        String binary = "\u00c3\u0095\u00ab";
        for (int turn = 0; turn < 4; turn++) {
            assertReads(render(turned(READER_INIT_FULL_22, turn)), "$I");
            assertReads(render(turned(READER_INIT_FULL_CLEAR, turn)), "$I");
            assertReads(render(turned(READER_INIT_FULL_SET, turn)), "$I");
            assertReads(render(turned(READER_INIT_COMPACT_CLEAR, turn)), "$I");
            assertReads(render(turned(READER_INIT_COMPACT_SET, turn)), "$I");
            assertReads(render(turned(BLANK_EDGE, turn)), binary);
        }
    }

    /**
     * Transpose of a stride-2 module picture. Matches zxing-cpp {@code BitMatrix::mirror},
     * which swaps {@code (x, y)} with {@code (y, x)}.
     */
    private static String[] mirrored(String[] rows) {
        int n = rows.length;
        String[] next = new String[n];
        for (int y = 0; y < n; y++) {
            StringBuilder line = new StringBuilder(n * 2);
            for (int x = 0; x < n; x++) {
                line.append(rows[x].charAt(y * 2)).append(' ');
            }
            next[y] = line.toString();
        }
        return next;
    }

    /** One flipped orientation module on the unrotated picture. {@code DetectPure} is not used. */
    private static void assertOneOrientationFlip(String[] rows, boolean compact, String text) {
        int center = rows.length / 2;
        int offset = compact ? 5 : 7;
        for (int xSign : new int[]{-1, 1}) {
            for (int ySign : new int[]{-1, 1}) {
                int x = center + xSign * offset;
                int y = center + ySign * offset;
                assertReads(render(flipped(rows, x, y)), text);
                assertReads(render(flipped(rows, center + xSign * (offset - 1), y)), text);
                assertReads(render(flipped(rows, x, center + ySign * (offset - 1))), text);
            }
        }
    }

    /** Toggle one module. The source rows are not modified. */
    private static String[] flipped(String[] rows, int moduleX, int moduleY) {
        String[] copy = rows.clone();
        char[] line = copy[moduleY].toCharArray();
        int index = moduleX * 2;
        line[index] = line[index] == 'X' ? ' ' : 'X';
        copy[moduleY] = new String(line);
        return copy;
    }

    /** Clockwise quarter-turns of a stride-2 module picture. Y grows downward. */
    private static String[] turned(String[] rows, int quarters) {
        String[] current = rows;
        for (int turn = 0; turn < quarters; turn++) {
            int n = current.length;
            char[][] modules = new char[n][n];
            for (int y = 0; y < n; y++) {
                for (int x = 0; x < n; x++) {
                    modules[x][n - 1 - y] = current[y].charAt(x * 2);
                }
            }
            String[] next = new String[n];
            for (int y = 0; y < n; y++) {
                StringBuilder line = new StringBuilder(n * 2);
                for (int x = 0; x < n; x++) {
                    line.append(modules[y][x]).append(' ');
                }
                next[y] = line.toString();
            }
            current = next;
        }
        return current;
    }

    private static void assertReads(BufferedImage image, String text) {
        List<AztecDetector.Found> found = AztecDetector.read(GrayImages.from(image));
        assertTrue(
                found.stream().anyMatch(hit -> text.equals(hit.text())),
                found.toString()
        );
    }

    private static final int[][] ZERO_MODE_ORIENTATION = {
            {2, 2}, {3, 2}, {2, 3},
            {2, 12}, {3, 12}, {2, 11},
            {12, 2}, {11, 2}, {12, 3},
            {12, 12}, {11, 12}, {12, 11},
    };

    private static final String[] ZERO_MODE = {
            "    X X X X X X X   X X X X X ",
            "X X X X   X     X X         X ",
            "    X X                 X   X ",
            "X X X X X X X X X X X X X   X ",
            "X X   X               X     X ",
            "X X   X   X X X X X   X     X ",
            "X X   X   X       X   X   X X ",
            "      X   X   X   X   X     X ",
            "X X   X   X       X   X   X X ",
            "      X   X X X X X   X     X ",
            "X     X               X     X ",
            "  X   X X X X X X X X X X X   ",
            "  X                         X ",
            "X     X X X X   X     X       ",
            "X   X     X X X X       X     ",
    };

    private static final String[] COMPACT_WIKI = {
            "X     X X       X     X X     X     X         ",
            "X         X     X X     X   X X   X X       X ",
            "X X   X X X X X   X X X                 X     ",
            "X X                 X X   X       X X X X X X ",
            "    X X X   X   X     X X X X         X X     ",
            "  X X X   X X X X   X     X   X     X X   X   ",
            "        X X X X X     X X X X   X   X     X   ",
            "X       X   X X X X X X X X X X X     X   X X ",
            "X   X     X X X               X X X X   X X   ",
            "X     X X   X X   X X X X X   X X   X   X X X ",
            "X   X         X   X       X   X X X X       X ",
            "X       X     X   X   X   X   X   X X   X     ",
            "      X   X X X   X       X   X     X X X     ",
            "    X X X X X X   X X X X X   X X X X X X   X ",
            "  X X   X   X X               X X X   X X X X ",
            "  X   X       X X X X X X X X X X X X   X X   ",
            "  X X   X       X X X   X X X       X X       ",
            "  X               X   X X     X     X X X     ",
            "  X   X X X   X X   X   X X X X   X   X X X X ",
            "    X   X   X X X   X   X   X X X X     X     ",
            "        X               X                 X   ",
            "        X X     X   X X   X   X   X       X X ",
            "  X   X   X X       X   X         X X X     X ",
    };

    private static final String[] FULL_LAYER_6 = {
            "    X   X     X     X     X   X X X X   X   X   X     X X     X X       X X X X   ",
            "  X         X   X         X X X X X   X   X X X   X   X X X X X   X X X       X   ",
            "    X   X       X X X X X   X X X X   X X   X X X X X   X X X     X   X X X   X   ",
            "      X     X     X   X X X X     X   X       X X     X X       X X X         X   ",
            "X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X ",
            "X X X               X X X       X           X X X   X     X   X   X X     X X   X ",
            "        X X X X X X     X   X X   X   X X     X X   X X X X     X X     X     X   ",
            "X   X X       X   X X X X     X X X X     X X X X   X X X X X       X       X     ",
            "    X   X X   X X       X     X     X   X   X     X X   X     X X   X   X     X   ",
            "  X X           X X   X   X       X X       X X X X     X     X X   X             ",
            "  X     X   X   X X X     X X         X X   X X X X     X X X X X     X X X X   X ",
            "      X     X X X X X X X X X X   X       X   X X   X     X   X           X X X X ",
            "X X     X     X X     X   X   X     X   X X X X X X       X X   X       X X   X X ",
            "    X     X X       X X X X X     X   X           X   X         X   X       X     ",
            "  X X   X       X         X X X X X X X X X X X X X X X X     X     X X X X X X X ",
            "X X X       X X   X X X X   X                       X X X   X     X X       X X   ",
            "  X   X X X X   X   X X   X X   X X X X X X X X X   X         X   X     X   X X   ",
            "      X     X X X           X   X               X   X     X       X X X   X   X X ",
            "    X   X       X X     X   X   X   X X X X X   X   X   X X X X   X     X         ",
            "X   X X         X X X X   X X   X   X       X   X   X X X X   X X X X     X X   X ",
            "X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X ",
            "  X       X   X   X X   X   X   X   X       X   X   X X   X X   X X X       X X   ",
            "  X   X X   X X X X     X X X   X   X X X X X   X   X   X   X   X X     X X   X X ",
            "  X X       X X X         X X   X               X   X X     X   X X   X   X     X ",
            "    X   X   X   X X X     X X   X X X X X X X X X   X   X X X X X X     X   X     ",
            "X   X X           X     X   X                       X   X X   X   X X X     X X   ",
            "X X X   X X   X     X   X   X X X X X X X X X X X X X X   X   X X X     X   X X   ",
            "  X   X   X X X               X   X   X     X     X     X   X   X             X   ",
            "X   X X X   X X     X X       X   X X X X   X X X X X   X X X X X   X   X X     X ",
            "    X X   X         X X X     X           X       X X   X         X               ",
            "X X     X     X X     X X     X         X     X X X       X   X X       X   X     ",
            "  X       X X   X X X     X     X X       X X   X X X     X X       X X     X X   ",
            "  X X   X   X X X X X       X X       X X X   X X X X   X X X   X X X   X X X X X ",
            "X X         X X X X   X   X         X X   X X   X     X           X X         X   ",
            "    X X X X   X X     X   X   X X   X   X   X X X   X X X X X   X   X X X   X     ",
            "X X       X   X X X         X       X X   X       X X     X X     X X     X   X X ",
            "X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X ",
            "X X       X X X       X X     X X     X     X     X           X   X         X     ",
            "X   X X X   X     X X X   X X X X X   X X   X X X X X     X     X       X   X X   ",
            "  X   X     X X   X     X X X   X X X X   X   X   X X X X X     X     X       X   ",
            "        X X       X X X       X X     X X X     X   X     X           X X   X     ",
    };

    private static final String[] RUNE_0 = {
            "X X X   X   X   X   X ",
            "X X X X X X X X X X X ",
            "  X               X   ",
            "X X   X X X X X   X X ",
            "  X   X       X   X   ",
            "X X   X   X   X   X X ",
            "  X   X       X   X   ",
            "X X   X X X X X   X X ",
            "  X               X   ",
            "  X X X X X X X X X X ",
            "    X   X   X   X     ",
    };

    private static final String[] RUNE_25 = {
            "X X X   X X     X   X ",
            "X X X X X X X X X X X ",
            "  X               X X ",
            "  X   X X X X X   X X ",
            "  X   X       X   X   ",
            "X X   X   X   X   X X ",
            "X X   X       X   X X ",
            "X X   X X X X X   X   ",
            "X X               X X ",
            "  X X X X X X X X X X ",
            "    X     X           ",
    };

    private static final String[] ENCODER_WIKIPEDIA = {
            "X     X X       X     X X     X     X         ",
            "X         X     X X     X   X X   X X       X ",
            "X X   X X X X X   X X X                 X     ",
            "X X                 X X   X       X X X X X X ",
            "    X X X   X   X     X X X X         X X     ",
            "  X X X   X X X X   X     X   X     X X   X   ",
            "        X X X X X     X X X X   X   X     X   ",
            "X       X   X X X X X X X X X X X     X   X X ",
            "X   X     X X X               X X X X   X X   ",
            "X     X X   X X   X X X X X   X X   X   X X X ",
            "X   X         X   X       X   X X X X       X ",
            "X       X     X   X   X   X   X   X X   X     ",
            "      X   X X X   X       X   X     X X X     ",
            "    X X X X X X   X X X X X   X X X X X X   X ",
            "  X X   X   X X               X X X   X X X X ",
            "  X   X       X X X X X X X X X X X X   X X   ",
            "  X X   X       X X X   X X X       X X       ",
            "  X               X   X X     X     X X X     ",
            "  X   X X X   X X   X   X X X X   X   X X X X ",
            "    X   X   X X X   X   X   X X X X     X     ",
            "        X               X                 X   ",
            "        X X     X   X X   X   X   X       X X ",
            "  X   X   X X       X   X         X X X     X ",
    };

    private static final String[] ENCODER_PUBLIC_DOMAIN = {
            "        X X     X X     X     X     X   X X X         X   X         X   X X       ",
            "  X       X X     X   X X   X X       X             X     X   X X   X           X ",
            "  X   X X X     X   X   X X     X X X   X   X X               X X       X X     X ",
            "X X X             X   X         X         X     X     X   X     X X       X   X   ",
            "X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X ",
            "    X X   X   X   X X X               X       X       X X     X X   X X       X   ",
            "X X     X       X       X X X X   X   X X       X   X X   X       X X   X X   X   ",
            "  X       X   X     X X   X   X X   X X   X X X X X X   X X           X   X   X X ",
            "X X   X X   X   X X X X   X X X X X X X X   X   X       X X   X X X X   X X X     ",
            "  X       X   X     X       X X     X X   X   X   X     X X   X X X   X     X X X ",
            "  X   X X X   X X       X X X         X X           X   X   X   X X X   X X     X ",
            "    X     X   X X     X X X X     X   X     X X X X   X X   X X   X X X     X   X ",
            "X X X   X             X         X X X X X   X   X X   X   X   X X   X   X   X   X ",
            "          X       X X X   X X     X   X           X   X X X X   X X               ",
            "  X     X X   X   X       X X X X X X X X X X X X X X X   X   X X   X   X X X     ",
            "    X X                 X   X                       X X   X       X         X X X ",
            "        X   X X   X X X X X X   X X X X X X X X X   X     X X           X X X X   ",
            "          X X X   X     X   X   X               X   X X     X X X   X X           ",
            "X X     X     X   X   X   X X   X   X X X X X   X   X X X X X X X       X   X X X ",
            "X X X X       X       X   X X   X   X       X   X   X     X X X     X X       X X ",
            "X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X ",
            "    X     X       X         X   X   X       X   X   X     X   X X                 ",
            "        X X     X X X X X   X   X   X X X X X   X   X X X     X X X X   X         ",
            "X     X   X   X         X   X   X               X   X X   X X   X X X     X   X   ",
            "  X   X X X   X   X X   X X X   X X X X X X X X X   X X         X X     X X X X   ",
            "    X X   X   X   X X X     X                       X X X   X X   X   X     X     ",
            "    X X X X   X         X   X X X X X X X X X X X X X X   X       X X   X X   X X ",
            "            X   X   X X       X X X X X     X X X       X       X X X         X   ",
            "X       X         X   X X X X   X     X X     X X     X X           X   X       X ",
            "X     X       X X X X X     X   X X X X   X X X     X       X X X X   X   X X   X ",
            "  X X X X X               X     X X X   X       X X   X X   X X X X     X X       ",
            "X             X         X   X X   X X     X     X     X   X   X X X X             ",
            "    X   X X       X     X       X   X X X X X X   X X   X X X X X X X X X   X   X ",
            "    X         X X   X       X     X   X   X       X     X X X     X       X X X X ",
            "X     X X     X X X X X X             X X X   X               X   X     X     X X ",
            "X   X X     X               X X X X X     X X     X X X X X X X X     X   X   X X ",
            "X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X ",
            "X           X     X X X X     X     X         X         X   X       X X   X X X   ",
            "X   X   X X   X X X   X         X X     X X X X     X X   X   X     X   X       X ",
            "      X     X     X     X X     X   X X   X X   X         X X       X       X   X ",
            "X       X           X   X   X     X X   X               X     X     X X X         ",
    };

    private static final String[] RUNE_255 = {
            "X X   X   X   X     X ",
            "X X X X X X X X X X X ",
            "  X               X X ",
            "X X   X X X X X   X X ",
            "X X   X       X   X X ",
            "  X   X   X   X   X X ",
            "  X   X       X   X   ",
            "X X   X X X X X   X X ",
            "X X               X   ",
            "  X X X X X X X X X X ",
            "    X X     X X X     ",
    };

    private static final String[] NOT_A_RUNE = {
            "X X             X   X ",
            "X X X X X X X X X X X ",
            "  X               X   ",
            "X X   X X X X X   X   ",
            "  X   X       X   X X ",
            "  X   X   X   X   X   ",
            "  X   X       X   X X ",
            "  X   X X X X X   X X ",
            "X X               X   ",
            "  X X X X X X X X X X ",
            "          X X         ",
    };

    private static final String[] READER_INIT_FULL_22 = {
            "            X X   X   X X X     X       X   X     X         X   X     X X   X   X       X X   X X X         X       X   X     X   X         X X X X X X   X   X   X   X X   X X     X     X X X X     X   X X X X   X X   ",
            "        X X     X X   X   X X X X   X X   X           X     X   X   X X   X   X X X X X X X       X   X         X X X   X X     X X   X             X         X   X X   X X   X   X   X   X X     X X X X     X     X   X ",
            "    X   X   X   X       X           X X     X       X       X   X X       X X X     X   X   X X     X X   X X X X         X X X   X     X X X   X   X X     X       X X X   X   X     X X   X X X           X X   X X     ",
            "      X X       X X     X X X     X       X     X   X   X       X     X       X             X   X     X X         X   X   X X   X       X     X X   X           X X X X   X   X X     X     X     X X         X   X   X   ",
            "        X   X       X X   X       X X X X   X X X X X X X X X   X X X   X X X   X   X X X X X X X   X X X X X     X       X   X     X       X X X     X         X X X   X X X X X           X   X X X X X X X X       X   ",
            "    X     X   X     X   X X         X X X X     X     X X X   X X     X       X X   X X     X X X X X X X X   X   X   X     X     X X   X           X           X     X         X   X   X X   X     X X X X   X X         ",
            "X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X ",
            "          X     X X     X X X X       X   X     X       X X           X X X               X   X     X X X X   X X X   X         X X X         X X   X X X   X   X X                     X         X X X   X         X X X ",
            "X   X X X X X         X X X   X   X X       X X   X X X   X X X             X   X X       X   X       X     X X       X   X   X X   X X X X X   X   X   X       X X         X X     X X X     X X X X       X X       X   ",
            "  X X         X X X     X X X X   X X X X X   X         X       X   X X   X     X X X X X X   X X   X X   X   X       X   X   X X X     X X   X     X   X X   X X X X X   X     X     X X     X X     X X X       X   X   ",
            "  X   X     X X           X X   X X         X   X   X X X   X   X X X X X X X X X       X   X   X X X X     X     X   X X X X X             X X X X X X     X     X     X   X   X     X           X     X X X   X X X   X ",
            "  X X X X X   X X     X   X     X X X X   X         X X X X X X               X X X   X     X X X X     X X   X X           X X   X X X   X   X X   X X           X X X X X       X     X         X X X X X   X     X X X ",
            "  X     X X X X       X X     X       X   X X X X X X X X   X X X   X X X X X     X X   X     X   X   X X   X X             X X X X   X X   X X   X X   X     X     X       X X         X X X X X X X       X X       X   ",
            "X X X             X X   X X   X X X   X   X     X   X   X X X X   X   X X         X         X       X     X     X       X X         X         X   X X   X     X X       X     X   X X X X   X X     X X X X   X   X X X   ",
            "X X   X   X X     X   X X X     X X   X     X   X X X   X X X     X         X X     X X X X X X X X         X X     X X X X       X X X     X X X X X X   X X     X     X X X X         X X X   X   X   X X X       X   X ",
            "  X X X   X     X X   X X         X X X X X   X X       X   X   X X       X       X X X     X X     X X X           X X   X X X X             X X X     X X   X X X       X   X X     X             X X   X     X X   X X ",
            "X X     X   X     X     X X X X X X     X X X     X X X   X   X X X     X X X   X X X X X   X       X X X   X X     X X X   X   X X   X X   X   X X X     X   X X X       X X X     X           X   X X     X     X X     ",
            "      X         X     X X   X         X X X         X   X             X   X   X X   X       X   X   X   X                 X   X   X X   X     X X       X X X X X       X       X X         X   X X             X   X X   ",
            "  X X   X   X   X X     X       X   X X X X X   X X   X       X X           X X   X X   X X     X X X X     X X   X     X X     X   X X     X X   X X   X   X X X X     X X X X X       X X X     X     X X X     X       ",
            "X X X X       X X X   X       X     X X   X     X X           X   X X X   X         X         X       X       X     X X     X X     X   X X     X X       X   X X       X     X         X   X     X X X X X     X       X ",
            "    X   X   X X X X X     X X X X   X   X   X X       X   X   X         X   X X   X     X X       X X       X   X     X           X   X     X     X X   X       X   X X     X X X X   X X X       X X X     X X           ",
            "  X   X       X X   X X X       X X X   X     X X   X       X             X       X     X X X X X   X X       X X         X X   X X   X X             X     X   X X   X X     X   X     X       X X X X X     X   X X   X ",
            "X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X ",
            "      X X     X X   X X X X X     X X     X   X     X X X X     X   X X       X   X X X       X X     X X X     X   X X         X     X X     X       X       X X X   X         X   X   X X     X   X   X X   X   X   X X ",
            "          X X     X X       X X     X     X X       X     X X X X X   X     X         X   X X X   X         X   X   X     X X   X X X       X   X X   X       X X   X   X X X X X   X X   X X X   X     X   X   X X X     ",
            "X   X X X           X X X X   X     X   X X   X   X           X X X   X X     X X   X         X X         X   X         X   X X X     X X     X   X   X X       X X X X       X             X           X             X   ",
            "X X   X     X   X   X X X X X     X X     X X   X X X   X   X   X   X       X     X   X X X X X X       X   X X         X   X     X X       X   X X   X         X   X     X X X X     X   X X           X X X         X X ",
            "  X       X           X X X X X X   X X X     X   X   X   X X   X     X   X         X X   X   X                 X X   X X   X X     X         X X X     X X     X     X   X   X   X X X     X   X X X X                 X ",
            "  X     X X X     X X X     X     X   X   X X X X X   X   X           X   X X   X       X X   X   X   X X X X X   X X       X X X   X   X   X X   X X X X X X X X   X X X   X X               X   X     X   X       X X   ",
            "  X     X     X X     X X X           X X X   X X   X X   X X X   X   X       X X X X     X         X X   X   X           X X X X X   X   X       X X X       X X         X   X X X X     X       X X   X X   X   X X X X ",
            "    X X   X X   X               X X   X X X X X X X           X   X   X X   X X     X     X X         X   X X X X X   X X     X   X   X X   X X X   X X X X   X     X X X   X X     X   X   X   X X     X   X   X   X X X ",
            "X   X X   X   X         X     X X   X X         X X     X         X   X   X     X     X X X   X   X     X     X         X   X X X     X   X   X X     X X X X X X   X         X X       X   X   X     X   X     X     X X ",
            "  X         X X   X     X   X     X X   X   X   X   X X   X     X X X X   X X     X   X X X X     X X       X   X   X X X X X X       X   X X     X   X     X X X X X X   X X X X       X   X X   X   X   X X X     X   X ",
            "X                 X   X   X X   X       X X               X X   X X   X   X   X   X X   X X X   X     X   X   X X     X X     X   X     X X   X X     X   X X X X     X X     X X     X X       X X X         X   X X     ",
            "X         X X X X   X X X X     X   X   X   X   X       X   X X         X X X X         X   X X X X X       X X X X X X X     X X     X   X X     X         X   X X   X X X X X X X X X   X X X   X     X   X   X X X X   ",
            "  X   X   X       X     X X X X   X X X X     X   X X X X X   X   X X X   X     X   X   X     X         X X   X X X   X X         X X X X X     X   X       X     X       X   X X         X   X X X     X         X X   X ",
            "X   X     X X     X       X   X   X         X X X X   X X     X   X   X   X X           X         X   X     X               X X     X       X       X X X X   X X X X X     X X   X   X     X     X X X     X     X X   X ",
            "  X X   X     X                 X             X X X     X       X   X X X X   X   X     X X X   X X X X   X     X   X   X   X X X   X X X     X X X X   X X       X     X       X             X   X X X X X     X X X     ",
            "X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X ",
            "      X   X     X X   X X       X X   X   X             X   X X X     X   X     X X   X   X   X   X     X X             X X X       X X   X   X   X     X     X X X   X X           X   X X   X     X   X X     X X X   X ",
            "X X X   X   X X     X     X   X X       X   X X     X     X     X           X X         X   X   X X X     X X   X   X X X     X X   X       X X X X   X     X X             X X   X X X     X     X X X     X X   X     X ",
            "X     X   X       X X               X X   X           X   X       X     X X   X           X   X   X   X       X             X   X X   X         X X X X X     X X X X                     X   X X X X X       X X X X     ",
            "    X   X X X     X X X X X X X X X X X X X X X X   X   X     X X           X     X   X X   X X   X         X     X   X X X     X   X X     X X X   X   X X   X   X         X   X X X   X X X X X X     X   X X     X X   ",
            "  X X   X     X   X X       X X               X           X X   X X X X X     X         X     X X   X X X X         X     X     X     X   X   X   X X   X   X   X     X   X     X   X X X X X     X     X X   X X X X X X ",
            "X X X   X X X X   X X X   X X X X X     X X X       X           X           X X             X X X   X X   X X X X   X X X X X   X X     X X X X X X     X                   X X     X   X X X   X X     X   X X X         ",
            "X X X   X     X   X X     X     X       X     X           X       X X   X       X X   X X     X X     X X X   X X X   X X       X X X     X   X   X   X X   X X X     X X     X X X X   X   X X X   X X X X   X X   X   X ",
            "  X   X X X X   X   X       X X   X         X       X     X   X             X X   X         X X         X X X   X         X X   X   X X X   X   X   X X       X X     X   X X X X X                   X   X X X X X     X ",
            "X         X   X   X X X X X   X X   X   X X   X   X X   X X X X   X   X           X     X     X X X   X   X   X           X X X         X X   X X X X X     X X     X     X   X     X X       X   X     X X   X X X X   X ",
            "      X X X X X X       X   X     X X X     X   X   X           X X X   X X X X X X X   X X   X X X X X X X X X X X X X X X X         X X X X       X X   X X       X       X       X   X X       X X     X X     X X X   ",
            "X   X   X X   X     X X X X   X     X     X       X     X   X   X X                   X     X   X                       X       X X     X       X                 X X   X         X X         X       X X           X     ",
            "            X X   X X X               X X X X   X       X X   X   X   X     X X X X   X X X     X   X X X X X X X X X   X     X X X   X     X   X   X   X X   X   X     X X X     X   X   X X X     X   X X X X X   X   X ",
            "X X     X X         X   X     X X X X     X   X         X   X   X   X X X X   X X X X   X X   X X   X               X   X     X     X X X     X             X X   X X     X   X X   X   X X   X         X X   X X         ",
            "X X   X X X X X       X X X X X     X X   X X   X X X   X       X         X X X X   X   X X     X   X   X X X X X   X   X         X X   X X X   X X   X X     X     X X X   X   X   X X         X X X   X X X X X X       ",
            "X X   X X X   X     X   X     X X X X   X X       X   X X   X X   X           X   X X X     X   X   X   X       X   X   X     X   X X     X   X X   X       X   X   X     X     X   X X X   X   X X   X   X   X X X X   X ",
            "X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X ",
            "X             X X X X   X   X             X           X X   X         X       X X X X   X X   X X   X   X       X   X   X X       X X   X     X X X   X X   X X X X   X X X       X     X     X         X X         X X   ",
            "X     X     X   X     X X X     X X     X X X X   X X X   X X     X     X X X X   X X     X     X   X   X X X X X   X   X X   X X     X   X X   X     X           X X   X   X     X X     X         X       X     X   X   ",
            "X X   X X           X   X X X X       X X X   X   X   X     X   X X     X     X   X   X X   X   X   X               X   X   X   X X     X X   X X X   X   X         X     X     X X       X             X X   X   X       ",
            "X     X   X X X     X   X X   X   X     X   X X       X       X X     X X X X   X               X   X X X X X X X X X   X       X   X       X X       X     X     X     X X X         X X   X   X     X     X X X X X X X ",
            "  X             X   X               X   X X   X     X X X X X X X             X       X X   X X X                       X   X X   X   X       X X X X X X         X X           X   X X         X   X   X     X X   X X   ",
            "X       X X X X   X       X   X X X X       X   X X X   X   X X X   X   X   X   X X       X     X X X X X X X X X X X X X X           X X   X X     X     X X X X   X       X     X X X X X     X X X     X X X       X   ",
            "X   X X X     X   X X X X X   X X     X   X         X   X   X         X X X     X X X   X X         X         X   X X       X X   X     X     X       X         X X X X X     X   X X X X X   X   X       X   X X X X   X ",
            "X X X X X   X         X     X     X X   X X X X   X     X X X   X X   X X   X   X   X X X X         X X     X   X X                   X   X X     X   X   X X   X X         X   X     X X         X X     X X   X X   X X ",
            "      X X X   X     X X X X X   X X   X X     X   X X X X         X X   X     X X X   X X       X   X X X X     X X X X   X X   X             X X X         X X   X X X   X     X   X   X X     X X       X       X       ",
            "    X X     X X X           X X     X X   X X X X X X X X X X X   X       X X X X     X X   X X X   X   X X X       X X     X X X X X X X   X X   X     X X         X X     X X X   X   X   X X   X   X   X X   X   X     ",
            "  X X   X X   X X       X     X X       X     X       X X X   X X X           X X   X X             X   X X         X   X X X   X X   X X X   X X X       X X X     X X X X   X X X   X X X     X         X     X       X ",
            "  X X X     X     X         X X   X   X X X X X   X   X     X       X   X   X     X               X   X X   X X   X X     X X X           X X     X X         X       X     X     X   X X   X X           X X   X X X     ",
            "X   X   X     X X     X   X X   X   X X X         X       X X                       X   X       X X X   X       X       X   X     X   X   X     X     X X   X   X   X X X X   X X X X X     X X X X X X X     X X   X X   ",
            "X X   X X   X       X X     X     X X X   X X   X X X X     X X   X X X     X   X     X   X X X   X   X   X X   X   X X X X     X X   X     X X     X X   X       X         X       X   X   X X     X   X   X         X X ",
            "X X X   X       X X   X     X X   X   X   X   X X X   X   X   X   X   X X                                 X   X     X     X     X   X X   X   X     X   X X         X   X X     X X   X X   X X X X           X   X       ",
            "X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X ",
            "X   X   X       X   X   X   X   X   X   X X       X X       X     X   X   X     X X     X     X X X X   X X       X   X     X   X X X     X   X X X X   X X   X X         X       X X X X X X X     X     X   X   X     X ",
            "X X X X     X X X X   X       X X   X X   X X X X   X   X       X X   X     X X   X X     X X   X X X     X X   X X X   X   X X   X X   X X X X X X   X X X X X X X X   X X X   X X X       X   X   X     X X X X X X     ",
            "  X     X         X X X       X   X     X     X X X   X X   X   X X           X   X X X   X X     X     X X   X X X   X X   X X       X X       X   X X   X   X     X     X     X   X       X     X X     X   X         X ",
            "X   X   X X X       X   X     X     X X X X X   X   X   X     X   X   X   X X X   X       X X   X X X X X   X       X         X   X X X X X X X X X X           X   X   X X X   X X   X X             X   X X X X X     X ",
            "    X X X     X X   X X   X X X       X   X     X       X     X X                     X   X X   X       X X       X X   X X X X X   X   X     X     X   X     X   X   X X     X X X X   X     X X       X X     X X     X ",
            "X X X   X   X     X       X       X X X X X X X X       X   X     X     X X X X       X   X X   X   X X     X X X   X   X   X     X     X X X   X   X X X         X         X       X X X     X     X X X X X   X X X X   ",
            "X X     X X         X   X X X X X   X   X       X     X           X           X X   X   X   X X   X   X X     X X X       X X X X X   X   X   X     X     X X X   X   X X           X X X   X X X       X         X X     ",
            "X       X X X X X X X     X     X     X X   X   X X X   X X       X     X X X X   X         X X   X X   X   X X X   X X   X   X X   X   X X X X           X   X     X X X   X       X     X           X X   X X X         ",
            "    X X X                 X X     X   X X X   X   X X X   X       X X     X         X   X X X X       X       X X X X X     X X   X X X   X   X X X X   X X       X   X X X     X X X   X       X X     X X   X     X X   ",
            "  X X X   X X X X X X X X   X       X X     X                 X   X       X X X       X   X X   X X X X   X X   X   X             X         X X X   X X       X X X X       X X   X X   X X X     X X X     X X X     X   ",
            "X       X X   X X X X   X X   X     X X   X           X X   X X   X X X   X   X X       X X X   X X X X   X   X     X   X   X X   X     X X     X     X X   X   X X     X X     X       X X   X               X           ",
            "X           X   X X X X X X   X     X     X X X       X X   X     X X     X X X   X   X X   X X X X X X   X X X X       X X X   X X         X     X X X X       X X X   X X X   X X X X   X   X     X X     X X X   X     ",
            "  X           X X     X   X X X       X       X X X     X X X X X X X     X           X     X   X   X   X     X   X   X X X   X         X X   X     X X X X X X   X X X         X X X X X     X       X X X       X     X ",
            "    X   X   X   X     X X X X X   X X X X   X X X X X X   X   X X   X X X X X X X X   X   X   X   X   X X   X X     X X     X     X   X X X X X   X X X   X X X X         X X X   X X   X X X X   X   X     X X       X   ",
            "X X   X X     X   X     X X       X   X X X   X X X X     X     X   X     X       X   X   X X   X       X X     X   X X   X X X X X X X             X X X X     X X X X X X       X X   X X     X     X X X           X X ",
            "X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X ",
            "    X X X           X   X X X       X X X     X     X X   X X   X X X         X     X     X X X X X   X X X   X X X   X       X       X X X     X     X       X     X X       X                       X X           X     ",
            "X X X   X X X     X   X X   X X X X   X     X X X X   X X X   X   X X X X   X     X X     X   X   X X X     X X   X X       X X     X     X X X   X X   X X X X   X       X X   X X   X X X X X       X X   X   X X       ",
            "    X     X   X     X   X X X X       X           X       X X X X   X X   X     X X         X X X             X X X X           X X X   X X             X X X   X   X     X   X X X   X X     X X X     X X   X X X X X   ",
            "      X X X X X   X X     X X X X     X X   X X     X           X           X X     X X X X X       X     X X X X   X       X   X   X X X X X   X   X X X   X   X X X X   X X   X X X     X   X   X       X X X X   X X X ",
            "X X X   X         X X     X     X       X     X   X         X X X X   X   X     X         X       X   X X X       X X X X X   X X   X X   X       X X       X X X   X X       X X   X X       X X X       X           X X ",
            "X X     X X X   X                 X X X     X X X   X X X X X   X X X X   X X   X   X X X   X X   X   X   X X   X     X X X   X         X   X X       X     X X       X     X X       X     X X X X   X     X X   X X   X ",
            "  X X   X     X   X   X X   X   X   X           X     X       X   X   X   X   X X X   X X   X X   X X X   X   X   X X X X X       X X X       X     X   X   X X X     X       X X X X   X X X   X   X   X X     X X X   X ",
            "X X   X X X X X X   X   X X   X   X         X X X X   X       X X           X   X X     X     X X     X X X X         X     X X   X     X   X X X X       X         X X   X X X X X     X X   X X     X     X X X     X X ",
            "X X     X X   X X   X X X X   X       X   X     X   X X         X   X               X     X X X   X X X X X       X   X X   X X     X   X         X X X   X   X X   X   X     X X X X X X     X X X X X X X   X X       X ",
            "    X   X X X X   X   X   X           X X X X   X   X     X X   X   X X X   X X X     X X X X X     X X X   X X X X X       X X   X X       X X X X X             X   X X   X X X           X X X         X X X   X   X X ",
            "X     X   X   X   X   X X   X   X   X         X X X         X   X   X X       X         X     X X X   X   X   X   X X X X X X         X X     X X   X X   X   X       X X       X X     X X X       X X       X   X     X ",
            "X X X X     X X X   X X X     X X X     X   X X X     X       X X X   X   X X X X       X X     X X X X X   X         X   X X X X X X X   X X X X X X X   X     X X   X   X X     X X X     X X       X X   X X X     X X ",
            "  X   X   X             X X X   X       X X   X X   X   X         X   X X X       X     X               X X   X X X X X X         X   X X X     X X X   X   X     X X         X X   X X X   X     X   X X X       X X X X ",
            "X       X   X X   X X   X X X   X   X X X   X   X X X     X X X X X X   X X X X X     X     X X X X   X X   X       X X X X   X X       X X X   X X X X X X X       X X X X X X X X           X X       X X X         X   ",
            "X     X X       X X X         X           X     X       X X     X X X     X   X X X   X   X       X     X     X X X X X X X   X   X X X             X   X X X     X X   X X   X X X X X X   X     X X X       X           ",
            "X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X   X ",
            "X     X X     X X X   X X         X X X X X       X X       X     X   X   X         X   X   X       X X   X   X     X X X X   X     X           X     X       X X X X     X     X       X X X   X X X X   X   X   X X X   ",
            "  X X X X   X   X   X X       X   X     X   X   X X X X     X     X         X     X     X   X   X   X     X X   X X     X   X   X           X X                     X X     X   X   X   X   X X   X X     X X X X   X X   ",
            "X X   X X X   X   X   X X X X       X X         X         X   X X X X     X       X X X X   X   X     X X             X X X X X   X   X       X X X X X     X X X X X X X X     X X X X   X X X X     X X X   X X X   X X ",
            "X X X     X X   X   X   X X X   X X X     X X X X   X   X X X   X   X X   X X       X             X X X     X   X X   X X     X X           X X X             X X     X   X X       X X X X X X         X   X   X       X ",
            "  X   X   X   X   X   X X X X     X X X X     X X       X X X   X X X     X     X X X X X X X X       X X     X X   X   X       X         X   X X   X X X X   X   X   X X     X     X X   X X   X X X X   X           X   ",
            "X X X X X   X X X X   X X X   X X X         X X       X       X       X X X X X X X     X   X X X         X X       X X X X X   X     X   X X X X X   X   X     X           X X X   X X X X X X       X X X X       X X   ",
    };

    private static final String[] READER_INIT_FULL_CLEAR = {
            "      X X X   X   X X   X X X X   X X X X X   ",
            "    X   X X X X   X X X     X X X         X   ",
            "  X X   X X   X   X X   X X X   X X X     X X ",
            "  X X X   X X X   X X X X   X   X   X   X   X ",
            "    X X X X         X               X X X   X ",
            "X       X X X X X X X X X X X X X X X     X X ",
            "X     X X X                       X     X   X ",
            "X   X X X X   X X X X X X X X X   X           ",
            "X X X   X X   X               X   X       X X ",
            "X   X X   X   X   X X X X X   X   X   X X   X ",
            "X   X     X   X   X       X   X   X X X   X X ",
            "  X   X   X   X   X   X   X   X   X   X   X   ",
            "  X X X X X   X   X       X   X   X         X ",
            "X   X   X X   X   X X X X X   X   X X     X   ",
            "X       X X   X               X   X X X X   X ",
            "  X   X   X   X X X X X X X X X   X   X X   X ",
            "X     X X X                       X X         ",
            "X X   X   X X X X X X X X X X X X X X X X   X ",
            "X X         X   X       X   X X       X X     ",
            "    X     X         X X X X   X X X     X X   ",
            "    X     X X X         X   X X X     X       ",
            "    X X X         X   X   X X   X       X   X ",
            "  X   X X X             X   X   X       X     ",
    };

    private static final String[] READER_INIT_FULL_SET = {
            "      X X X   X   X X   X X X X   X X X X X   ",
            "    X   X X X X   X X X     X X X         X   ",
            "  X X   X X   X   X X   X X X   X X X     X X ",
            "  X X X   X X X   X X X X   X   X   X   X   X ",
            "    X X X X         X   X           X X X   X ",
            "X       X X X X X X X X X X X X X X X     X X ",
            "X     X X X                       X     X   X ",
            "X   X X   X   X X X X X X X X X   X           ",
            "X X X     X   X               X   X       X X ",
            "X   X X   X   X   X X X X X   X   X   X X   X ",
            "X   X   X X   X   X       X   X   X X X   X X ",
            "  X   X   X   X   X   X   X   X   X   X   X   ",
            "  X X X X X   X   X       X   X   X         X ",
            "X   X     X   X   X X X X X   X   X       X   ",
            "X         X   X               X   X X X X   X ",
            "  X   X X X   X X X X X X X X X   X   X X   X ",
            "X     X   X                       X X         ",
            "X X   X   X X X X X X X X X X X X X X X X   X ",
            "X X           X X X X         X X     X X     ",
            "    X     X         X X X X   X X X     X X   ",
            "    X     X X X         X   X X X     X       ",
            "    X X X         X   X   X X   X       X   X ",
            "  X   X X X             X   X   X       X     ",
    };

    private static final String[] READER_INIT_COMPACT_CLEAR = {
            "            X X   X         X ",
            "    X X X X   X X X   X     X ",
            "    X X             X   X     ",
            "  X X X X X X X X X X X X     ",
            "  X   X               X       ",
            "    X X   X X X X X   X     X ",
            "X     X   X       X   X X     ",
            "X     X   X   X   X   X     X ",
            "X     X   X       X   X X X   ",
            "X X   X   X X X X X   X X X X ",
            "    X X               X   X   ",
            "      X X X X X X X X X X X   ",
            "X             X X         X X ",
            "X         X   X     X X       ",
            "X X X X     X X X         X X ",
    };

    private static final String[] READER_INIT_COMPACT_SET = {
            "            X X   X         X ",
            "    X X X X   X X X   X     X ",
            "    X X     X       X   X     ",
            "  X X X X X X X X X X X X     ",
            "  X X X               X       ",
            "    X X   X X X X X   X X   X ",
            "X   X X   X       X   X X     ",
            "X     X   X   X   X   X     X ",
            "X     X   X       X   X   X   ",
            "X X   X   X X X X X   X   X X ",
            "    X X               X   X   ",
            "      X X X X X X X X X X X   ",
            "X       X X   X   X X     X X ",
            "X         X   X     X X       ",
            "X X X X     X X X         X X ",
    };

    private static final String[] BLANK_EDGE = {
            "                              ",
            "    X X X X X X X   X X X X X ",
            "    X X         X   X   X X X ",
            "  X X X X X X X X X X X X   X ",
            "      X               X   X X ",
            "    X X   X X X X X   X   X   ",
            "  X   X   X       X   X   X   ",
            "  X X X   X   X   X   X X   X ",
            "X X X X   X       X   X   X X ",
            "  X X X   X X X X X   X X X X ",
            "  X X X               X X X   ",
            "  X   X X X X X X X X X X X   ",
            "X X       X     X   X     X   ",
            "X X X X   X X     X X     X X ",
            "  X X X     X X     X   X   X ",
    };

    private static BufferedImage render(String[] rows) {
        int modules = rows.length;
        int quiet = 4;
        int scale = 8;
        int span = modules + quiet * 2;
        int size = span * scale;
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                image.setRGB(x, y, 0xFFFFFF);
            }
        }
        for (int y = 0; y < modules; y++) {
            for (int x = 0; x < modules; x++) {
                if (rows[y].charAt(x * 2) != 'X') {
                    continue;
                }
                int originX = (x + quiet) * scale;
                int originY = (y + quiet) * scale;
                for (int dy = 0; dy < scale; dy++) {
                    for (int dx = 0; dx < scale; dx++) {
                        image.setRGB(originX + dx, originY + dy, 0x000000);
                    }
                }
            }
        }
        return image;
    }

    /**
     * Perfect modules, scale 8. Each reference cross that sits at least two modules
     * inside the symbol must land on that module. The outermost ring can fall back
     * to the warp: {@code SampleGrid} does that when the cross search returns nothing.
     */
    private static void assertCrosses(BitMatrix modules) {
        int dimension = modules.getWidth();
        int scale = 8;
        int quiet = 4;
        Bits image = scaledModules(modules, scale, quiet);
        Warp warp = moduleWarp(dimension, scale, quiet);
        int half = dimension / 2;
        int rings = half / 16;
        int first = half - rings * 16;
        List<Integer> positions = new ArrayList<>();
        for (int module = first; module < dimension; module += 16) {
            positions.add(module);
        }
        int mid = positions.size() / 2;
        int checked = 0;
        for (int reach = 1; reach <= rings; reach++) {
            int low = positions.get(mid - reach);
            int high = positions.get(mid + reach);
            if (low < 2 || high > dimension - 3) {
                continue;
            }
            int[][] corners = {{low, low}, {high, low}, {high, high}, {low, high}};
            for (int[] corner : corners) {
                P found = new LocalGrid(image, warp, corner[0], corner[1], dimension, dimension, 0, 0)
                        .findTimingPatternCross(true, 4);
                double expectedX = (quiet + corner[0] + 0.5) * scale;
                double expectedY = (quiet + corner[1] + 0.5) * scale;
                assertNotNull(found, "missing cross at " + corner[0] + "," + corner[1]);
                double error = Math.hypot(found.x - expectedX, found.y - expectedY);
                assertTrue(error <= scale * 0.35,
                        "cross " + corner[0] + "," + corner[1] + " at " + found.x + "," + found.y
                                + " expected " + expectedX + "," + expectedY);
                checked++;
            }
        }
        assertTrue(checked >= 4, "no interior reference cross in dimension " + dimension);
    }

    private static Bits scaledModules(BitMatrix modules, int scale, int quiet) {
        int dimension = modules.getWidth();
        int span = dimension + quiet * 2;
        Bits bits = new Bits(span * scale, span * scale);
        for (int y = 0; y < modules.getHeight(); y++) {
            for (int x = 0; x < dimension; x++) {
                if (!modules.get(x, y)) {
                    continue;
                }
                int x0 = (x + quiet) * scale;
                int y0 = (y + quiet) * scale;
                for (int dy = 0; dy < scale; dy++) {
                    for (int dx = 0; dx < scale; dx++) {
                        bits.set(x0 + dx, y0 + dy);
                    }
                }
            }
        }
        return bits;
    }

    /** Module coordinate to pixel, with the quiet zone outside module 0. */
    private static Warp moduleWarp(int dimension, int scale, int quiet) {
        double origin = quiet * (double) scale;
        double far = origin + dimension * (double) scale;
        return Warp.between(
                new P[]{new P(0, 0), new P(dimension, 0), new P(dimension, dimension), new P(0, dimension)},
                new P[]{new P(origin, origin), new P(far, origin), new P(far, far), new P(origin, far)}
        );
    }

    private static BufferedImage symbol(String text, int layers, int size) {
        BitMatrix matrix = new AztecWriter().encode(
                text,
                BarcodeFormat.AZTEC,
                size,
                size,
                Map.of(EncodeHintType.AZTEC_LAYERS, layers)
        );
        return image(matrix);
    }

    private static BufferedImage symbol(String text) {
        return image(new AztecWriter().encode(text, BarcodeFormat.AZTEC, 200, 200));
    }

    private static BufferedImage image(BitMatrix matrix) {
        BufferedImage image = new BufferedImage(matrix.getWidth(), matrix.getHeight(), BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < matrix.getHeight(); y++) {
            for (int x = 0; x < matrix.getWidth(); x++) {
                image.setRGB(x, y, matrix.get(x, y) ? 0x000000 : 0xFFFFFF);
            }
        }
        return image;
    }
}
