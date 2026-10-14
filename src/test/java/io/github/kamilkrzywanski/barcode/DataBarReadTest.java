package io.github.kamilkrzywanski.barcode;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every DataBar image of the zxing-cpp 3.1.1 samples under {@code official-rows} reads through the
 * full reader once the DataBar symbologies are asked for. The text is the element string.
 */
class DataBarReadTest {
    private static final ReadOptions DATABAR = ReadOptions.fast()
            .symbologies(Symbology.DATABAR, Symbology.DATABAR_LIMITED, Symbology.DATABAR_EXPANDED)
            .timeBudgetMillis(Long.MAX_VALUE);

    @Test
    void readsEveryOfficialDataBarSample() throws Exception {
        Path root = Path.of(DataBarReadTest.class.getResource("/official-rows").toURI());
        List<Path> images = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(p -> p.getParent().getFileName().toString().matches("(rss|databar).*"))
                    .filter(p -> p.toString().endsWith(".png"))
                    .sorted()
                    .forEach(images::add);
        }
        assertTrue(images.size() > 200, "found " + images.size());
        BarcodeReader reader = new BarcodeReader();
        List<String> failures = new ArrayList<>();
        for (Path image : images) {
            Path label = image.resolveSibling(image.getFileName().toString().replace(".png", ".txt"));
            if (!Files.exists(label)) {
                continue;
            }
            String expected = Files.readString(label, StandardCharsets.UTF_8).strip();
            BufferedImage picture = ImageIO.read(image.toFile());
            List<BarcodeHit> hits = reader.read(picture, DATABAR);
            if (hits.size() != 1 || !hits.get(0).text().equals(expected)) {
                failures.add(root.relativize(image) + " " + hits);
            }
        }
        assertEquals(List.of(), failures);
    }

    @Test
    void dataBarStaysOffByDefault() throws Exception {
        Path image = Path.of(DataBarReadTest.class.getResource("/official-rows/rss14-1/1.png").toURI());
        List<BarcodeHit> hits = new BarcodeReader().read(ImageIO.read(image.toFile()), ReadOptions.fast());
        assertTrue(hits.stream().noneMatch(hit -> hit.symbology().name().startsWith("DATABAR")), hits.toString());
    }
}
