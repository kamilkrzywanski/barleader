package org.krzywanski.barcode.locate.dm;

import org.krzywanski.barcode.image.GrayU8;
import org.junit.jupiter.api.Test;
import org.krzywanski.barcode.image.GrayImages;
import org.krzywanski.barcode.locate.dm.DmGeometry.Bits;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Images and expected text from zxing-cpp 3.1.1 {@code test/samples} (Apache 2.0).
 * Each file is one that the current detector already returns unchanged.
 * ImageIO does not read WebP, so those rasters are stored as the PNG Pillow decoded.
 * The sibling {@code .txt} is the original zxing-cpp file when one exists for that image,
 * including a trailing newline when the file has one.
 * {@code qrcode-7} publishes one file for the whole sequence, so each image locks its own payload.
 * An empty upright read is tried again on the same bits after {@code BitMatrix::flipAll}.
 * That is {@code tryInvert} for QR, Data Matrix, and Aztec. Micro QR is not inverted.
 * The flipped matrix is not used by {@code Scan}. Model 1 QR, rMQR, and samples that need
 * {@code GuessTextEncoding} are not in this list. Nothing here is added to {@code Samples.all()}.
 * The sequence index is not part of the text.
 */
class OfficialSampleTest {
    @Test
    void readsTheOfficialImagesTheseDetectorsAlreadyDecode() throws Exception {
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
            List<String> texts = read(name, gray);
            assertTrue(texts.contains(expected), name);
        }
    }

    /**
     * zxing-cpp {@code test/samples/qrcode-7}. Four symbols, one sequence.
     * File order is the only order whose payloads join to {@code 01.txt}.
     */
    @Test
    void joinsOfficialStructuredAppendQrInFileOrder() throws Exception {
        Path root = resourceRoot();
        List<String> parts = new ArrayList<>();
        for (int index = 1; index <= 4; index++) {
            String name = "qrcode-7/01-0" + index + ".png";
            BufferedImage image = ImageIO.read(root.resolve(name).toFile());
            assertNotNull(image, name);
            List<String> texts = read(name, GrayImages.from(image));
            assertEquals(1, texts.size(), name);
            String part = Files.readString(root.resolve(textName(name)), StandardCharsets.UTF_8);
            assertEquals(part, texts.get(0), name);
            parts.add(texts.get(0));
        }
        String merged = Files.readString(root.resolve("qrcode-7/01.txt"), StandardCharsets.UTF_8);
        assertEquals(merged, String.join("", parts));
        assertEquals(1, joins(parts, new boolean[parts.size()], new StringBuilder(), merged, 0));
    }

    /** Orders of {@code parts} that concatenate to {@code merged}. */
    private static int joins(List<String> parts, boolean[] used, StringBuilder path, String merged, int placed) {
        if (placed == parts.size()) {
            return path.toString().equals(merged) ? 1 : 0;
        }
        int matches = 0;
        for (int i = 0; i < parts.size(); i++) {
            if (used[i]) {
                continue;
            }
            used[i] = true;
            int mark = path.length();
            path.append(parts.get(i));
            if (merged.startsWith(path.toString())) {
                matches += joins(parts, used, path, merged, placed + 1);
            }
            path.setLength(mark);
            used[i] = false;
        }
        return matches;
    }

    /**
     * zxing-cpp {@code falsepositives-1} rasters that ImageIO can read.
     * The four detectors return an empty list.
     */
    @Test
    void leavesTheOfficialFalsePositiveImagesUnread() throws Exception {
        Path root = resourceRoot();
        String[] names = {
                "falsepositives/#245.png",
                "falsepositives/#570.png",
                "falsepositives/MQR-falsepositive.jpg"
        };
        for (String name : names) {
            BufferedImage image = ImageIO.read(root.resolve(name).toFile());
            assertNotNull(image, name);
            GrayU8 gray = GrayImages.from(image);
            assertTrue(QrDetector.read(gray).isEmpty(), name);
            assertTrue(MicroQrDetector.read(gray).isEmpty(), name);
            assertTrue(AztecDetector.read(gray).isEmpty(), name);
            assertTrue(DataMatrixDetector.read(gray).isEmpty(), name);
        }
    }

    private static Path resourceRoot() throws URISyntaxException {
        URL manifest = OfficialSampleTest.class.getResource("/official/manifest.txt");
        assertNotNull(manifest);
        return Path.of(manifest.toURI()).getParent();
    }

    private static String textName(String image) {
        int dot = image.lastIndexOf('.');
        return image.substring(0, dot) + ".txt";
    }

    private static List<String> read(String name, GrayU8 gray) {
        String folder = name.substring(0, name.indexOf('/'));
        if (folder.startsWith("microqr")) {
            return MicroQrDetector.read(gray).stream().map(MicroQrDetector.Found::text).toList();
        }
        Bits bits = DmBinarizer.blackMatrix(gray);
        List<String> texts = decode(folder, bits);
        if (!texts.isEmpty()) {
            return texts;
        }
        bits.flipAll();
        return decode(folder, bits);
    }

    private static List<String> decode(String folder, Bits bits) {
        if (folder.startsWith("aztec")) {
            return AztecDetector.read(bits).stream().map(AztecDetector.Found::text).toList();
        }
        if (folder.startsWith("datamatrix")) {
            return DataMatrixDetector.read(bits).stream().map(DataMatrixDetector.Found::text).toList();
        }
        return QrDetector.read(bits).stream().map(QrDetector.Found::text).toList();
    }
}
