package org.krzywanski;

import org.krzywanski.barcode.BarcodeHit;
import org.krzywanski.barcode.BarcodeReader;
import org.krzywanski.barcode.DecodeMode;
import org.krzywanski.barcode.ImagePoint;
import org.krzywanski.barcode.ReadOptions;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Reads every barcode in an image file.
 * Usage: Main &lt;image&gt; [--aggressive] [--all]
 */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length == 0 || args[0].equals("-h") || args[0].equals("--help")) {
            System.err.println("Usage: Main <image> [--aggressive] [--all]");
            System.exit(2);
            return;
        }
        Path path = Path.of(args[0]);
        if (!Files.isRegularFile(path)) {
            System.err.println("Not a file: " + path);
            System.exit(2);
            return;
        }
        boolean aggressive = false;
        boolean findAll = false;
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--aggressive")) {
                aggressive = true;
            } else if (args[i].equals("--all")) {
                findAll = true;
            } else {
                System.err.println("Unknown option: " + args[i]);
                System.exit(2);
                return;
            }
        }
        BufferedImage image = ImageIO.read(path.toFile());
        if (image == null) {
            System.err.println("Cannot decode image: " + path);
            System.exit(2);
            return;
        }
        ReadOptions options = aggressive ? ReadOptions.aggressive() : ReadOptions.fast();
        if (findAll) {
            options = options.findAll(true);
        }
        if (aggressive && options.mode() != DecodeMode.AGGRESSIVE) {
            throw new IllegalStateException("aggressive options");
        }
        List<BarcodeHit> hits = new BarcodeReader().read(image, options);
        if (hits.isEmpty()) {
            System.out.println("NO READ");
            System.exit(1);
            return;
        }
        for (BarcodeHit hit : hits) {
            System.out.println(hit.symbology()
                    + "\t" + hit.text()
                    + "\t" + hit.attempt()
                    + "\t" + hit.confidence()
                    + "\t" + hit.orientationDegrees()
                    + "\t" + format(hit.corners()));
        }
    }

    private static String format(List<ImagePoint> corners) {
        if (corners.isEmpty()) {
            return "-";
        }
        StringBuilder builder = new StringBuilder();
        for (ImagePoint corner : corners) {
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(Math.round(corner.x())).append(',').append(Math.round(corner.y()));
        }
        return builder.toString();
    }
}
