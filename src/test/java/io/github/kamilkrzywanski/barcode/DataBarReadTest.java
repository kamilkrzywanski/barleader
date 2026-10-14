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
 * full reader once the DataBar symbologies are asked for. The text is the element string, and the
 * corners lie on the symbol.
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
            } else if (!plausibleCorners(hits.get(0), picture)) {
                failures.add(root.relativize(image) + " corners " + hits.get(0).corners());
            }
        }
        assertEquals(List.of(), failures);
    }

    /** Two or four corners inside the image, spanning at least a fifth of its width or height. */
    private static boolean plausibleCorners(BarcodeHit hit, BufferedImage image) {
        List<ImagePoint> corners = hit.corners();
        if (corners.size() != 2 && corners.size() != 4) {
            return false;
        }
        double minX = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (ImagePoint corner : corners) {
            if (corner.x() < -2 || corner.y() < -2 || corner.x() > image.getWidth() + 1 || corner.y() > image.getHeight() + 1) {
                return false;
            }
            minX = Math.min(minX, corner.x());
            maxX = Math.max(maxX, corner.x());
            minY = Math.min(minY, corner.y());
            maxY = Math.max(maxY, corner.y());
        }
        return maxX - minX >= image.getWidth() / 5.0 || maxY - minY >= image.getHeight() / 5.0;
    }

    @Test
    void dataBarStaysOffByDefault() throws Exception {
        Path image = Path.of(DataBarReadTest.class.getResource("/official-rows/rss14-1/1.png").toURI());
        List<BarcodeHit> hits = new BarcodeReader().read(ImageIO.read(image.toFile()), ReadOptions.fast());
        assertTrue(hits.stream().noneMatch(hit -> hit.symbology().name().startsWith("DATABAR")), hits.toString());
    }
}
