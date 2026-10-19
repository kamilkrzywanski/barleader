package io.github.kamilkrzywanski.barleader.decode;

import io.github.kamilkrzywanski.barleader.image.GrayU8;
import org.junit.jupiter.api.Test;
import io.github.kamilkrzywanski.barleader.image.GrayImages;
import io.github.kamilkrzywanski.barleader.locate.dm.OfficialMatrix;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * PDF417, MaxiCode, and 1D images from zxing-cpp 3.1.1 {@code test/samples} (Apache 2.0).
 * Each file is one that an off-ladder decoder already returns unchanged.
 * Stacked and 2D symbols use the matrix binarizer. A linear symbol uses one scanline from
 * {@code GlobalHistogramBinarizer::getPatternRow}. Upright rows are tried first. A mirrored
 * row, a half turn, or a quarter turn is tried only after that scan misses, so it cannot
 * replace an upright hit. A short RSS-14 symbol that the matrix misses is read from the scanline.
 * ImageIO does not read WebP, so those rasters are stored as the PNG Pillow decoded.
 * The sibling {@code .txt} is the original zxing-cpp file, including a trailing newline.
 * An EAN/UPC add-on sample asks for the add-on ({@code EanAddOnSymbol::Require}).
 * Code 32, slanted ITF, the unread UPC add-on photo, and DX Film Edge photos that do not
 * return the file text are not in this list.
 * Nothing here is added to {@code Samples.all()} or to the scan ladder.
 */
class OfficialRowSampleTest {
    @Test
    void readsTheOfficialImagesTheseDecodersAlreadyDecode() throws Exception {
        Path root = resourceRoot();
        List<String> names = Files.readAllLines(root.resolve("manifest.txt"));
        assertFalse(names.isEmpty());
        for (String name : names) {
            if (name.isBlank()) {
                continue;
            }
            BufferedImage image = ImageIO.read(root.resolve(name).toFile());
            assertNotNull(image, name);
            String expected = Files.readString(root.resolve(textName(name)), StandardCharsets.UTF_8);
            GrayU8 gray = GrayImages.from(image);
            boolean[][] bits = OfficialMatrix.from(gray);
            String text = read(name.substring(0, name.indexOf('/')), gray, bits);
            assertEquals(expected, text, name);
        }
    }

    private static Path resourceRoot() throws URISyntaxException {
        URL manifest = OfficialRowSampleTest.class.getResource("/official-rows/manifest.txt");
        assertNotNull(manifest);
        return Path.of(manifest.toURI()).getParent();
    }

    private static String textName(String image) {
        int dot = image.lastIndexOf('.');
        return image.substring(0, dot) + ".txt";
    }

    private static String read(String dir, GrayU8 gray, boolean[][] bits) {
        if (dir.startsWith("pdf417")) {
            String pure = Pdf417Decoder.decode(bits);
            if (pure != null) {
                return pure;
            }
            return Pdf417ScanningDecoder.decode(bits, true);
        }
        if (dir.startsWith("maxicode")) {
            return MaxiCodeDecoder.decode(bits);
        }
        if (dir.startsWith("dxfilm")) {
            return DxFilmEdgeDecoder.decode(bits);
        }
        if (dir.startsWith("rssexpanded")) {
            String text = DataBarExpandedReader.decode(grid(bits, 1));
            if (text != null) {
                return text;
            }
            text = DataBarExpandedReader.decode(turn(bits, true));
            if (text != null) {
                return text;
            }
            return DataBarExpandedReader.decode(turn(bits, false));
        }
        if (dir.startsWith("rss14")) {
            String text = rss(bits);
            if (text != null) {
                return text;
            }
            text = rssScan(gray, false);
            if (text != null) {
                return text;
            }
            text = rssScan(gray, true);
            if (text != null) {
                return text;
            }
            return rss(halfTurn(bits));
        }
        if (dir.startsWith("databar")) {
            for (boolean[] row : rows(bits, 1)) {
                String text = DataBarDecoder.decodeLimited(row);
                if (text != null) {
                    return text;
                }
            }
            return null;
        }
        if (!linear(dir)) {
            return null;
        }
        String text = scan(dir, gray, false);
        if (text != null) {
            return text;
        }
        return scan(dir, gray, true);
    }

    private static String scan(String dir, GrayU8 gray, boolean mirrored) {
        for (int y = 0; y < gray.height; y++) {
            boolean[] row = OfficialMatrix.row(gray, y);
            if (row == null) {
                continue;
            }
            String text = symbol(dir, mirrored ? mirror(row) : row);
            if (text != null) {
                return text;
            }
        }
        return null;
    }

    private static String symbol(String dir, boolean[] row) {
        return switch (dir) {
            case "code128-1", "code128-2", "code128-3" -> Code128Decoder.decode(row);
            case "code39-1", "code39-2", "code39-3" -> Code39Decoder.decode(row);
            case "code93-1" -> Code93Decoder.decode(row);
            case "codabar-1", "codabar-2" -> CodabarDecoder.decode(row);
            case "itf-1", "itf-2" -> ItfDecoder.decode(row);
            case "ean8-1", "ean13-1", "ean13-2", "ean13-3", "ean13-4",
                    "upce-1", "upce-2", "upce-3",
                    "upca-1", "upca-2", "upca-3", "upca-4", "upca-5" -> UpcEanDecoder.decode(row);
            case "ean13-extension-1", "upca-extension-1" -> UpcEanDecoder.decode(row, true, true);
            case "telepen-1" -> telepen(row);
            default -> null;
        };
    }

    private static boolean[] mirror(boolean[] row) {
        boolean[] out = new boolean[row.length];
        for (int i = 0; i < row.length; i++) {
            out[i] = row[row.length - 1 - i];
        }
        return out;
    }

    private static String rss(boolean[][] bits) {
        if (bits.length == 0) {
            return null;
        }
        for (boolean[] row : rows(bits, 2)) {
            String omni = DataBarDecoder.decodeOmni(row);
            if (omni != null) {
                return omni;
            }
        }
        return DataBarDecoder.decode(grid(bits, 1));
    }

    /** {@code getPatternRow} plus {@code decodeOmni}, for a symbol the matrix path missed. */
    private static String rssScan(GrayU8 gray, boolean mirrored) {
        for (int y = 0; y < gray.height; y++) {
            boolean[] row = OfficialMatrix.row(gray, y);
            if (row == null) {
                continue;
            }
            String text = DataBarDecoder.decodeOmni(mirrored ? mirror(row) : row);
            if (text != null) {
                return text;
            }
        }
        return null;
    }

    /** Clockwise when {@code clockwise} is true. */
    private static boolean[][] turn(boolean[][] bits, boolean clockwise) {
        if (bits.length == 0 || bits[0].length == 0) {
            return bits;
        }
        int height = bits.length;
        int width = bits[0].length;
        boolean[][] out = new boolean[width][height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (clockwise) {
                    out[x][height - 1 - y] = bits[y][x];
                } else {
                    out[width - 1 - x][y] = bits[y][x];
                }
            }
        }
        return out;
    }

    private static boolean[][] halfTurn(boolean[][] bits) {
        if (bits.length == 0 || bits[0].length == 0) {
            return bits;
        }
        int height = bits.length;
        int width = bits[0].length;
        boolean[][] out = new boolean[height][width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                out[height - 1 - y][width - 1 - x] = bits[y][x];
            }
        }
        return out;
    }

    /** Symbols whose cpp reader consumes {@code getPatternRow}, not a matrix. */
    private static boolean linear(String dir) {
        return switch (dir) {
            case "code128-1", "code128-2", "code128-3",
                    "code39-1", "code39-2", "code39-3", "code93-1",
                    "codabar-1", "codabar-2", "itf-1", "itf-2",
                    "ean8-1", "ean13-1", "ean13-2", "ean13-3", "ean13-4", "ean13-extension-1",
                    "upce-1", "upce-2", "upce-3",
                    "upca-1", "upca-2", "upca-3", "upca-4", "upca-5", "upca-extension-1",
                    "telepen-1" -> true;
            default -> false;
        };
    }

    private static String telepen(boolean[] row) {
        TelepenDecoder.Symbol symbol = TelepenDecoder.decode(row);
        return symbol == null ? null : symbol.text;
    }

    private static boolean[][] grid(boolean[][] bits, int stride) {
        return rows(bits, stride).toArray(boolean[][]::new);
    }

    private static List<boolean[]> rows(boolean[][] bits, int stride) {
        List<boolean[]> rows = new ArrayList<>();
        if (bits.length == 0) {
            return rows;
        }
        int step = Math.max(1, stride);
        for (int y = 0; y < bits.length; y += step) {
            rows.add(bits[y]);
        }
        if ((bits.length - 1) % step != 0) {
            rows.add(bits[bits.length - 1]);
        }
        return rows;
    }
}
