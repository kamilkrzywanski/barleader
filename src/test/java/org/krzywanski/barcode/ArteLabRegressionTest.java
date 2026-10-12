package org.krzywanski.barcode;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.krzywanski.barcode.eval.ArteLabScoring;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Named Arte-Lab frames. Absent from a normal {@code mvn test}; run
 * {@code mvn -Partelab test}. The photographs stay under {@code datasets/}
 * and are not copied onto the test classpath.
 *
 * <p>The contract is the text. An earlier attempt that returns the same GTIN
 * still passes. {@code Foto(725)} stays empty: a local-mean band used to emit
 * {@code 8161834788488}. {@code Foto(707)} stays empty: its only read was Otsu
 * at 2×, and that step does not fit before Sauvola inside 100 attempts.
 * {@code Foto(844)} has an empty upstream label; the printed GTIN stays.
 */
@Tag("artelab")
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class ArteLabRegressionTest {
    private final BarcodeReader reader = new BarcodeReader();

    @ParameterizedTest
    @CsvSource({
            "Dataset1/05102009081.jpg,4902030187590",
            "Dataset1/05102009103.jpg,8023222032262",
            "Dataset1/05102009105.jpg,8023222032262",
            "Dataset1/05102009123.jpg,4902520242204",
            "Dataset1/05102009125.jpg,4902030187590",
            "Dataset1/05102009129.jpg,4902030187590",
            "Dataset1/05102009154.jpg,8004020931773",
            "Dataset1/05102009160.jpg,4005808801046",
            "Dataset1/PICT0036.JPG,8011642115887",
            "Dataset1/PICT0041.JPG,8011642115887",
            "Dataset2/Foto(687).jpg,8011642115962",
            "Dataset2/Foto(731).jpg,8023222032262",
            "Dataset2/Foto(782).jpg,8005235212442",
            "Dataset2/Foto(790).jpg,8005235212442",
            "Dataset2/Foto(804).jpg,7321925005431",
            "Dataset2/Foto(726).jpg,8023222032262"
    })
    void aggressiveReadsTheLabeledGtin(String relative, String gtin) throws IOException {
        assertEquals(gtin, ArteLabScoring.labelDigits(path(relative)), relative);
        List<BarcodeHit> hits = reader.read(load(relative), ReadOptions.aggressive());
        assertEquals(1, hits.size(), relative + " " + detail(hits));
        assertEquals(Symbology.EAN_13, hits.get(0).symbology(), relative + " " + detail(hits));
        assertEquals(gtin, hits.get(0).text(), relative + " " + detail(hits));
    }

    @ParameterizedTest
    @CsvSource({
            "Dataset2/Foto(725).jpg,8023222032262",
            "Dataset2/Foto(707).jpg,8002330024451"
    })
    void aggressiveStaysEmpty(String relative, String label) throws IOException {
        assertEquals(label, ArteLabScoring.labelDigits(path(relative)), relative);
        List<BarcodeHit> hits = reader.read(load(relative), ReadOptions.aggressive());
        assertTrue(hits.isEmpty(), relative + " " + detail(hits));
    }

    @ParameterizedTest
    @CsvSource("Dataset2/Foto(844).jpg,7321925005431")
    void emptyLabelKeepsThePrintedGtin(String relative, String gtin) throws IOException {
        assertEquals("", ArteLabScoring.labelDigits(path(relative)), relative);
        List<BarcodeHit> hits = reader.read(load(relative), ReadOptions.aggressive());
        assertEquals(1, hits.size(), relative + " " + detail(hits));
        assertEquals(Symbology.EAN_13, hits.get(0).symbology(), relative + " " + detail(hits));
        assertEquals(gtin, hits.get(0).text(), relative + " " + detail(hits));
    }

    @ParameterizedTest
    @CsvSource("Dataset2/Foto(542).jpg,8002205319804")
    void fastKeepsFoto542(String relative, String gtin) throws IOException {
        assertEquals(gtin, ArteLabScoring.labelDigits(path(relative)), relative);
        List<BarcodeHit> hits = reader.read(load(relative), ReadOptions.fast());
        assertEquals(1, hits.size(), relative + " " + detail(hits));
        assertEquals(Symbology.EAN_13, hits.get(0).symbology(), relative + " " + detail(hits));
        assertEquals(gtin, hits.get(0).text(), relative + " " + detail(hits));
        assertEquals("illumination", hits.get(0).attempt(), relative + " " + detail(hits));
    }

    private static Path path(String relative) {
        return ArteLabScoring.ROOT.resolve(relative);
    }

    private static BufferedImage load(String relative) throws IOException {
        BufferedImage image = ImageIO.read(path(relative).toFile());
        if (image == null) {
            throw new IOException("Cannot read " + relative);
        }
        return image;
    }

    private static String detail(List<BarcodeHit> hits) {
        if (hits.isEmpty()) {
            return "empty";
        }
        BarcodeHit hit = hits.get(0);
        return hit.symbology() + "@" + hit.attempt() + "/" + hit.orientationDegrees() + ":" + hit.text();
    }
}
