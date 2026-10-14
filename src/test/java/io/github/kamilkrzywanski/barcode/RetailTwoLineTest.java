package io.github.kamilkrzywanski.barcode;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.imageio.ImageIO;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Small, soft retail labels from the zxing-cpp samples whose only clean rows are far from the
 * parallel strip or right next to the first row. zxing-cpp accepts them once two scan lines
 * agree, and fast mode now reads them on the original image.
 */
class RetailTwoLineTest {
    private static final ReadOptions FAST = ReadOptions.fast().timeBudgetMillis(Long.MAX_VALUE);

    @ParameterizedTest
    @CsvSource({
            "upca-2/32.png", "upca-2/39.png", "upca-2/41.png", "upca-2/49.png",
            "upca-3/11.png", "upca-3/13.png", "upca-3/16.png", "upca-4/7.png", "upca-5/34.png",
            "ean13-2/04.png", "ean13-2/09.png", "ean13-4/05.png", "ean13-4/13.png"
    })
    void readsInFastMode(String name) throws Exception {
        Path image = Path.of(RetailTwoLineTest.class.getResource("/official-rows/" + name).toURI());
        String gtin = Files.readString(image.resolveSibling(name.substring(name.indexOf('/') + 1).replace(".png", ".txt")),
                StandardCharsets.UTF_8).strip();
        List<BarcodeHit> hits = BarcodeReader.sequential().read(ImageIO.read(image.toFile()), FAST);
        assertEquals(1, hits.size(), name + " " + hits);
        BarcodeHit hit = hits.get(0);
        String text = hit.symbology() == Symbology.UPC_A ? "0" + hit.text() : hit.text();
        assertEquals(gtin, text, name);
    }
}
