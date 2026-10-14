package io.github.kamilkrzywanski.barcode;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Measured false reads on Arte-Lab Medium Barcode 1D. The photographs are EAN-13
 * frames. Three blurry Dataset2 shots used to come back as a PDF417 run of
 * {@code A}, and {@code Foto(573).jpg} used to come back as a short Data Matrix.
 * The printed Data Matrix symbols in Dataset1 are a different case and stay.
 */
class ArteLabFalsePositiveTest {
    private static final Path ROOT = Path.of("datasets", "artelab-medium-barcode-1d");

    @Test
    void blurryEanFramesDoNotInventPdf417OrDataMatrix() throws IOException {
        assertRetailOrEmpty("Dataset2/Foto(480).jpg");
        assertRetailOrEmpty("Dataset2/Foto(515).jpg");
        assertRetailOrEmpty("Dataset2/Foto(573).jpg");
    }

    /** The false PDF417 used to stop the ladder before illumination read this EAN-13. */
    @Test
    void foto542KeepsTheEanOnceTheFalsePdf417IsGone() throws IOException {
        BufferedImage image = ImageIO.read(ROOT.resolve("Dataset2/Foto(542).jpg").toFile());
        List<BarcodeHit> hits = new BarcodeReader().read(image, ReadOptions.fast());
        assertEquals(1, hits.size());
        assertEquals(Symbology.EAN_13, hits.get(0).symbology());
        assertEquals("8002205319804", hits.get(0).text());
        assertEquals("illumination", hits.get(0).attempt());
    }

    @Test
    void printedDataMatrixBesideTheEanStays() throws IOException {
        assertDataMatrix("Dataset1/05102009161.jpg", "372260C");
        assertDataMatrix("Dataset1/05102009162.jpg", "372260C");
        assertDataMatrix("Dataset1/05102009163.jpg", "372260C");
        assertDataMatrix("Dataset1/05102009132.jpg", "2A127693515");
    }

    private static void assertRetailOrEmpty(String relative) throws IOException {
        String label = Files.readString(ROOT.resolve(relative + ".txt")).trim();
        BufferedImage image = ImageIO.read(ROOT.resolve(relative).toFile());
        BarcodeReader reader = new BarcodeReader();
        List<BarcodeHit> hits = reader.read(image, ReadOptions.fast());
        if (hits.isEmpty()) {
            hits = reader.read(image, ReadOptions.aggressive());
        }
        if (hits.isEmpty()) {
            return;
        }
        assertEquals(1, hits.size(), relative);
        BarcodeHit hit = hits.get(0);
        assertTrue(
                hit.symbology() == Symbology.EAN_13 || hit.symbology() == Symbology.UPC_A,
                relative + " " + hit.symbology() + " " + hit.attempt()
        );
        boolean exact = label.equals(hit.text());
        boolean upcA = label.startsWith("0") && label.substring(1).equals(hit.text());
        assertTrue(exact || upcA, relative + " expected " + label + " got " + hit.text());
    }

    private static void assertDataMatrix(String relative, String text) throws IOException {
        BufferedImage image = ImageIO.read(ROOT.resolve(relative).toFile());
        List<BarcodeHit> hits = new BarcodeReader().read(image, ReadOptions.fast());
        assertEquals(1, hits.size(), relative);
        assertEquals(Symbology.DATA_MATRIX, hits.get(0).symbology(), relative);
        assertEquals(text, hits.get(0).text(), relative);
    }
}
