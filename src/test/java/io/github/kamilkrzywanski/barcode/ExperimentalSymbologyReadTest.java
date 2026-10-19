package io.github.kamilkrzywanski.barcode;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * MaxiCode, Telepen, DX film edge, rMQR and MicroPDF417, the symbologies read only when asked for,
 * read every labelled zxing-cpp 3.1.1 sample of theirs under {@code official-rows} once they are
 * asked for, and stay off by default.
 */
class ExperimentalSymbologyReadTest {
    @Test
    void readsEveryOfficialSampleWhenAskedFor() throws Exception {
        assertReadsAll("maxicode-1", Symbology.MAXICODE, 8);
        assertReadsAll("telepen-1", Symbology.TELEPEN, 4);
        assertReadsAll("dxfilmedge-1", Symbology.DX_FILM_EDGE, 1);
        assertReadsAll("rmqrcode-1", Symbology.RMQR, 3);
        assertReadsAll("micropdf417-1", Symbology.MICRO_PDF417, 7);
    }

    @Test
    void staysOffByDefault() throws Exception {
        BarcodeReader reader = new BarcodeReader();
        Set<Symbology> optional = EnumSet.of(Symbology.MAXICODE, Symbology.TELEPEN, Symbology.DX_FILM_EDGE,
                Symbology.RMQR, Symbology.MICRO_PDF417);
        for (String folder : List.of("maxicode-1", "telepen-1", "dxfilmedge-1", "rmqrcode-1", "micropdf417-1")) {
            for (Path image : images(folder)) {
                for (BarcodeHit hit : reader.read(ImageIO.read(image.toFile()), ReadOptions.fast())) {
                    assertFalse(optional.contains(hit.symbology()), image + " " + hit);
                }
            }
        }
    }

    private static void assertReadsAll(String folder, Symbology symbology, int expected) throws Exception {
        ReadOptions options = ReadOptions.fast().symbologies(symbology).timeBudgetMillis(Long.MAX_VALUE);
        BarcodeReader reader = new BarcodeReader();
        List<String> failures = new ArrayList<>();
        int labelled = 0;
        for (Path image : images(folder)) {
            Path label = image.resolveSibling(image.getFileName().toString().replace(".png", ".txt"));
            if (!Files.exists(label)) {
                continue;
            }
            labelled++;
            String text = Files.readString(label, StandardCharsets.UTF_8);
            BufferedImage picture = ImageIO.read(image.toFile());
            List<BarcodeHit> hits = reader.read(picture, options);
            if (hits.isEmpty() || hits.get(0).symbology() != symbology
                    || !(hits.get(0).text().equals(text) || hits.get(0).text().equals(text.strip()))) {
                failures.add(image.getFileName() + " " + hits);
            }
        }
        assertEquals(expected, labelled, folder);
        assertEquals(List.of(), failures, folder);
    }

    private static List<Path> images(String folder) throws Exception {
        Path root = Path.of(ExperimentalSymbologyReadTest.class.getResource("/official-rows/" + folder).toURI());
        try (Stream<Path> files = Files.list(root)) {
            return files.filter(p -> p.toString().endsWith(".png")).sorted().toList();
        }
    }
}
