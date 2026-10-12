package org.krzywanski.barcode;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Arte-Lab Medium Barcode 1D, kept beside the sources so a real-photo EAN-13
 * reference stays with the project. Dataset1 is autofocus, Dataset2 is not.
 * Each JPEG has a sibling text file. 427 of those files are the 13-digit ground
 * truth. Three files in the upstream archive are empty.
 *
 * <p>The photographs live under {@code datasets/artelab-medium-barcode-1d/},
 * not on the test classpath, so a normal {@code mvn test} does not copy 216 MB
 * into {@code target/}. They are not part of {@code Samples.all()} and this
 * test does not decode them. A full read of all 430 frames is
 * {@code org.krzywanski.barcode.eval.ArteLabReport}, not this test. A miss there
 * is not a contract failure of the 22-sample suite. The named frames that are
 * pinned run under {@code mvn -Partelab test}.
 */
class ArteLabCorpusTest {
    /** Empty {@code .jpg.txt} files shipped in the upstream archive. */
    private static final Set<String> UNLABELED = Set.of(
            "Dataset1/05102009199.jpg",
            "Dataset2/Foto(844).jpg",
            "Dataset2/Foto(849).jpg"
    );

    @Test
    void mediumBarcode1dIsPresentAndLabeled() throws IOException {
        Path root = Path.of("datasets", "artelab-medium-barcode-1d");
        assertTrue(Files.isDirectory(root), root.toAbsolutePath().toString());
        String readme = Files.readString(root.resolve("Readme.txt"), StandardCharsets.ISO_8859_1);
        assertTrue(readme.contains("Creative Commons Attribution 3.0"), readme);
        assertTrue(readme.contains("EAN-13"), readme);

        int photos = 0;
        for (String set : List.of("Dataset1", "Dataset2")) {
            List<Path> images = jpegFiles(root.resolve(set));
            assertEquals(215, images.size(), set);
            for (Path image : images) {
                String key = set + "/" + image.getFileName();
                String raw = Files.readString(
                        image.resolveSibling(image.getFileName() + ".txt"),
                        StandardCharsets.ISO_8859_1
                );
                int length = digits(raw).length();
                if (UNLABELED.contains(key)) {
                    assertEquals(0, length, key);
                } else {
                    assertEquals(13, length, key);
                }
                photos++;
            }
        }
        assertEquals(430, photos);
        assertEquals(3, UNLABELED.size());
    }

    private static List<Path> jpegFiles(Path directory) throws IOException {
        try (Stream<Path> walk = Files.list(directory)) {
            List<Path> images = new ArrayList<>();
            for (Path path : walk.toList()) {
                String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
                    images.add(path);
                }
            }
            return images;
        }
    }

    private static String digits(String raw) {
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c >= '0' && c <= '9') {
                digits.append(c);
            }
        }
        return digits.toString();
    }
}
