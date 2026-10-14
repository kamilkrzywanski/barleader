package io.github.kamilkrzywanski.barcode;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import io.github.kamilkrzywanski.barcode.eval.ArteLabScoring;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Named Arte-Lab frames. Absent from a normal {@code mvn test}; run
 * {@code mvn -Partelab test}. The photographs stay under {@code datasets/}
 * and are not copied onto the test classpath.
 *
 * <p>The contract is the text. An earlier attempt that returns the same GTIN
 * still passes. {@code Foto(725)} used to stay empty because a local-mean band
 * emitted {@code 8161834788488}; the 35th percentile now returns the labeled GTIN.
 * {@code Foto(707)} is the one tight crop whose nearest 2× Otsu
 * reads the label. {@code Foto(503)} and {@code Foto(543)} need the horizontal
 * unsharp on the first crop. {@code Foto(481)} and {@code Foto(728)} need the 35th
 * percentile of the averaged bar rows; {@code Foto(573)} needs the 45th.
 * {@code Foto(556)} needs the median. {@code Foto(817)} and {@code Foto(820)} need the
 * local mean of the sharpest rows. {@code Foto(501)} needs a radius-4 unsharp of the
 * averaged row, cut at that median. {@code Foto(765)}, {@code Foto(776)} and
 * {@code Foto(779)} need the sharp-row median on the third crop, and {@code Foto(778)}
 * needs it on the first; the label is EAN-13 and the read is the same digits as UPC-A.
 * {@code Foto(483)} needs that average cut at 40. {@code Foto(406)} needs the per-column
 * median of those rows, and {@code Foto(738)} the darkest pixel. {@code Foto(504)} needs
 * a radius-2 unsharp of the sharp-row average on the second crop. {@code Foto(480)},
 * {@code Foto(676)} and {@code Foto(829)} need radius 4 amount 4 then Otsu on the
 * first crop, in the slot after half scale. {@code 05102009191} is a tall crop whose
 * mean binarizer matches on two glare-free rows. {@code Foto(844)} and
 * {@code Foto(849)} have an empty upstream label; the printed GTIN stays.
 * {@code Foto(849)} needs radius 4 amount 4 after a −2° correction of the soft
 * wide crop. {@code Foto(427)}, {@code Foto(677)}, {@code Foto(846)} and
 * {@code Foto(851)} need that correction too. {@code Foto(489)}, {@code Foto(722)}
 * and {@code Foto(675)} need it on a later crop, as do {@code Foto(683)} and
 * {@code Foto(678)}. {@code Foto(767)} needs the same correction and returns UPC-A.
 * The soft crop uses radius 3: {@code Foto(516)}, {@code Foto(837)} and
 * {@code Foto(842)} stay blank at radius 4. {@code Foto(698)} needs the same
 * mask on crop 1 after a −1° correction. {@code Foto(515)} and {@code Foto(824)}
 * need crop 0 after a +2° correction. {@code Foto(823)} needs crop 2 after
 * +0.5°, and {@code Foto(828)} and {@code Foto(830)} need crop 3 after +1°
 * and +2°. {@code Foto(484)} needs the sharp-row median of crop 2 after −1°.
 * {@code Foto(496)} needs crop 3 keystoned after +1°. {@code Foto(761)} needs
 * the sharp-row unsharp of crop 0 after −1° and returns UPC-A.
 * {@code Foto(312)} and {@code Foto(827)} need the digit correlator on the
 * sharpest rows. {@code Foto(760)} and {@code Foto(762)} need it too and return UPC-A.
 * {@code Foto(751)} and {@code Foto(771)} need the coarser grid in that same
 * attempt, after the guarded grid misses every wide crop. {@code Foto(771)} returns UPC-A.
 * {@code Foto(714)} needs the module step of 0.1 after that coarser grid also misses.
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
            "Dataset2/Foto(726).jpg,8023222032262",
            "Dataset2/Foto(503).jpg,8002330097875",
            "Dataset2/Foto(543).jpg,8002205319804",
            "Dataset2/Foto(707).jpg,8002330024451",
            "Dataset2/Foto(725).jpg,8023222032262",
            "Dataset2/Foto(481).jpg,8011642111896",
            "Dataset2/Foto(573).jpg,4902030132293",
            "Dataset2/Foto(728).jpg,8023222032262",
            "Dataset2/Foto(501).jpg,8002330097875",
            "Dataset2/Foto(556).jpg,5050083170188",
            "Dataset2/Foto(817).jpg,8011642111896",
            "Dataset2/Foto(820).jpg,8011642111896",
            "Dataset2/Foto(406).jpg,8011642115887",
            "Dataset2/Foto(483).jpg,8011642115962",
            "Dataset2/Foto(738).jpg,8011642115887",
            "Dataset2/Foto(504).jpg,8002330097875",
            "Dataset2/Foto(480).jpg,8011642111896",
            "Dataset2/Foto(676).jpg,4902030187590",
            "Dataset2/Foto(829).jpg,8011642111896",
            "Dataset2/Foto(427).jpg,8002330002534",
            "Dataset2/Foto(677).jpg,4902030187590",
            "Dataset2/Foto(846).jpg,7321925005431",
            "Dataset2/Foto(851).jpg,7321925005431",
            "Dataset2/Foto(489).jpg,4902030187590",
            "Dataset2/Foto(675).jpg,4902030187590",
            "Dataset2/Foto(678).jpg,4902030187590",
            "Dataset2/Foto(683).jpg,8011642115962",
            "Dataset2/Foto(722).jpg,4902030187590",
            "Dataset2/Foto(516).jpg,5030930017491",
            "Dataset2/Foto(837).jpg,7321925005431",
            "Dataset2/Foto(842).jpg,7321925005431",
            "Dataset2/Foto(698).jpg,4902030195397",
            "Dataset2/Foto(515).jpg,5030930017491",
            "Dataset2/Foto(824).jpg,8011642111896",
            "Dataset2/Foto(823).jpg,8011642111896",
            "Dataset2/Foto(828).jpg,8011642111896",
            "Dataset2/Foto(830).jpg,8011642111896",
            "Dataset2/Foto(484).jpg,8011642115962",
            "Dataset2/Foto(496).jpg,4902030187590",
            "Dataset2/Foto(312).jpg,8011642115887",
            "Dataset2/Foto(827).jpg,8011642111896",
            "Dataset2/Foto(751).jpg,4902520242204",
            "Dataset2/Foto(714).jpg,8001410088710",
            "Dataset1/05102009191.jpg,8002330112752"
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
            "Dataset2/Foto(765).jpg,051122414831",
            "Dataset2/Foto(776).jpg,051122414831",
            "Dataset2/Foto(778).jpg,051122414831",
            "Dataset2/Foto(779).jpg,051122414831",
            "Dataset2/Foto(763).jpg,051122414831",
            "Dataset2/Foto(766).jpg,051122414831",
            "Dataset2/Foto(769).jpg,051122414831",
            "Dataset2/Foto(770).jpg,051122414831",
            "Dataset2/Foto(772).jpg,051122414831",
            "Dataset2/Foto(767).jpg,051122414831",
            "Dataset2/Foto(761).jpg,051122414831",
            "Dataset2/Foto(760).jpg,051122414831",
            "Dataset2/Foto(762).jpg,051122414831",
            "Dataset2/Foto(771).jpg,051122414831"
    })
    void aggressiveReadsTheLabeledUpca(String relative, String upca) throws IOException {
        assertEquals("0051122414831", ArteLabScoring.labelDigits(path(relative)), relative);
        List<BarcodeHit> hits = reader.read(load(relative), ReadOptions.aggressive());
        assertEquals(1, hits.size(), relative + " " + detail(hits));
        assertEquals(Symbology.UPC_A, hits.get(0).symbology(), relative + " " + detail(hits));
        assertEquals(upca, hits.get(0).text(), relative + " " + detail(hits));
    }

    @ParameterizedTest
    @CsvSource({
            "Dataset2/Foto(844).jpg,7321925005431",
            "Dataset2/Foto(849).jpg,7321925005431"
    })
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
