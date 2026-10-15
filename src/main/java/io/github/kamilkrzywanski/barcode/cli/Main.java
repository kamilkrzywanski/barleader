package io.github.kamilkrzywanski.barcode.cli;

import io.github.kamilkrzywanski.barcode.BarcodeHit;
import io.github.kamilkrzywanski.barcode.BarcodeReader;
import io.github.kamilkrzywanski.barcode.EanAddOn;
import io.github.kamilkrzywanski.barcode.ImagePoint;
import io.github.kamilkrzywanski.barcode.ReadOptions;
import io.github.kamilkrzywanski.barcode.Symbology;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

/**
 * Reads the barcodes in image files and prints one line per symbol, or with {@code write} as the
 * first argument writes a barcode ({@link WriteCommand}), and with {@code qr} a QR Code
 * ({@link QrCommand}).
 *
 * <pre>
 * java -jar barcode-pro.jar [options] image...
 *   --aggressive        the full ladder: small-angle rotations, band search, thresholds
 *   --all               every symbol, not only the first
 *   --formats=A,B,...   only these symbologies, for example QR_CODE,EAN_13
 *   --addon=read        also read EAN/UPC supplements; --addon=require keeps only symbols with one
 *   --json              one JSON object per symbol
 * </pre>
 *
 * <p>Plain output is tab-separated: file, symbology, text, attempt, orientation, corners, and the
 * EAN/UPC supplement or {@code -}.
 * Tabs, line breaks and backslashes in the text are escaped as {@code \t}, {@code \n},
 * {@code \r} and {@code \\}. The exit code is 0 when every image had a symbol, 1 when one
 * had none, and 2 for wrong usage or an unreadable file.
 */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        System.exit(run(args, new PrintStream(System.out, true, StandardCharsets.UTF_8), System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length > 0 && args[0].equals("qr")) {
            return QrCommand.run(Arrays.copyOfRange(args, 1, args.length), out, err);
        }
        if (args.length > 0 && args[0].equals("write")) {
            return WriteCommand.run(Arrays.copyOfRange(args, 1, args.length), out, err);
        }
        boolean aggressive = false;
        boolean findAll = false;
        boolean json = false;
        EnumSet<Symbology> formats = null;
        EanAddOn addOn = EanAddOn.IGNORE;
        List<Path> files = new ArrayList<>();
        for (String arg : args) {
            if (arg.equals("-h") || arg.equals("--help")) {
                usage(out);
                return 0;
            } else if (arg.equals("--aggressive")) {
                aggressive = true;
            } else if (arg.equals("--all")) {
                findAll = true;
            } else if (arg.equals("--json")) {
                json = true;
            } else if (arg.startsWith("--addon=")) {
                try {
                    addOn = EanAddOn.valueOf(arg.substring("--addon=".length()).toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException unknown) {
                    err.println("Unknown add-on mode: " + arg + ". Known: ignore, read, require");
                    return 2;
                }
            } else if (arg.startsWith("--formats=")) {
                formats = EnumSet.noneOf(Symbology.class);
                for (String name : arg.substring("--formats=".length()).split(",")) {
                    try {
                        formats.add(Symbology.valueOf(name.trim().toUpperCase(Locale.ROOT)));
                    } catch (IllegalArgumentException unknown) {
                        err.println("Unknown symbology: " + name + ". Known: " + EnumSet.allOf(Symbology.class));
                        return 2;
                    }
                }
            } else if (arg.startsWith("-")) {
                err.println("Unknown option: " + arg);
                usage(err);
                return 2;
            } else {
                files.add(Path.of(arg));
            }
        }
        if (files.isEmpty()) {
            usage(err);
            return 2;
        }
        ReadOptions options = aggressive ? ReadOptions.aggressive() : ReadOptions.fast();
        options = options.findAll(findAll).eanAddOn(addOn);
        if (formats != null && !formats.isEmpty()) {
            options = options.symbologies(formats);
        }
        BarcodeReader reader = new BarcodeReader();
        int status = 0;
        for (Path file : files) {
            BufferedImage image;
            try {
                image = Files.isRegularFile(file) ? ImageIO.read(file.toFile()) : null;
            } catch (IOException exception) {
                image = null;
            }
            if (image == null) {
                err.println("Cannot read an image from " + file);
                status = 2;
                continue;
            }
            List<BarcodeHit> hits = reader.read(image, options);
            if (hits.isEmpty()) {
                if (!json) {
                    out.println(file + "\tNO READ");
                }
                status = Math.max(status, 1);
            }
            for (BarcodeHit hit : hits) {
                out.println(json ? json(file, hit) : tsv(file, hit));
            }
        }
        return status;
    }

    private static void usage(PrintStream stream) {
        stream.println("Usage: java -jar barcode-pro.jar [--aggressive] [--all] [--formats=QR_CODE,EAN_13,...]"
                + " [--addon=read|require] [--json] image...");
        WriteCommand.usage(stream);
        QrCommand.usage(stream);
    }

    private static String tsv(Path file, BarcodeHit hit) {
        return file
                + "\t" + hit.symbology()
                + "\t" + escape(hit.text())
                + "\t" + hit.attempt()
                + "\t" + hit.orientationDegrees()
                + "\t" + corners(hit.corners())
                + "\t" + (hit.addOn().isEmpty() ? "-" : hit.addOn());
    }

    private static String json(Path file, BarcodeHit hit) {
        StringBuilder corners = new StringBuilder("[");
        for (ImagePoint corner : hit.corners()) {
            if (corners.length() > 1) {
                corners.append(',');
            }
            corners.append('[').append(round(corner.x())).append(',').append(round(corner.y())).append(']');
        }
        corners.append(']');
        return "{\"file\":" + quote(file.toString())
                + ",\"symbology\":" + quote(hit.symbology().name())
                + ",\"text\":" + quote(hit.text())
                + (hit.addOn().isEmpty() ? "" : ",\"addOn\":" + quote(hit.addOn()))
                + ",\"attempt\":" + quote(hit.attempt())
                + ",\"orientation\":" + hit.orientationDegrees()
                + ",\"corners\":" + corners
                + "}";
    }

    private static String corners(List<ImagePoint> corners) {
        if (corners.isEmpty()) {
            return "-";
        }
        StringBuilder builder = new StringBuilder();
        for (ImagePoint corner : corners) {
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(round(corner.x())).append(',').append(round(corner.y()));
        }
        return builder.toString();
    }

    private static long round(double value) {
        return Math.round(value);
    }

    private static String escape(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '\t' -> out.append("\\t");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                default -> {
                    if (c < 0x20 || c == 0x7F) {
                        out.append(String.format("\\x%02x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    private static String quote(String text) {
        StringBuilder out = new StringBuilder(text.length() + 2).append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
