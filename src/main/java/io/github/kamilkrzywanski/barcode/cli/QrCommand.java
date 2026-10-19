/*
 * Copyright 2026 Kamil Krzywański
 */
// SPDX-License-Identifier: Apache-2.0
package io.github.kamilkrzywanski.barcode.cli;

import io.github.kamilkrzywanski.barcode.QrCode;
import io.github.kamilkrzywanski.barcode.QrErrorCorrection;
import io.github.kamilkrzywanski.barcode.QrOptions;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Writes a QR Code.
 *
 * <pre>
 * java -jar barleader.jar qr [options] text
 *   --level=L|M|Q|H     error correction, M by default
 *   --module=N          pixels a module in an image, 8 by default
 *   --quiet=N           light modules around the symbol, 4 by default
 *   --version=N         a fixed version, 1 to 40
 *   --mask=N            a fixed mask, 0 to 7
 *   --charset=NAME      the character set of the text, announced with its ECI
 *   --gs1               GS1 element strings, with FNC1 in first position
 *   --compact           segments in several modes and character sets, for a smaller symbol
 *   --out=FILE          an image in the format of its extension, or .svg; SVG on standard output without it
 *   --                  the next argument is the text, even when it starts with a dash
 * </pre>
 *
 * <p>The exit code is 0 when the symbol was written, and 2 for wrong usage, text that does not fit,
 * or a file that cannot be written.
 */
final class QrCommand {
    private QrCommand() {
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        QrOptions options = QrOptions.defaults();
        int moduleSize = 8;
        int quietZone = QrCode.QUIET_ZONE;
        Path output = null;
        String text = null;
        boolean optionsEnd = false;
        try {
            for (String arg : args) {
                if (optionsEnd || !arg.startsWith("-")) {
                    if (text != null) {
                        err.println("One text at a time; quote it when it has spaces.");
                        return 2;
                    }
                    text = arg;
                } else if (arg.equals("--")) {
                    optionsEnd = true;
                } else if (arg.equals("-h") || arg.equals("--help")) {
                    usage(out);
                    return 0;
                } else if (arg.startsWith("--level=")) {
                    options = options.errorCorrection(QrErrorCorrection.valueOf(value(arg).toUpperCase(Locale.ROOT)));
                } else if (arg.startsWith("--module=")) {
                    moduleSize = Integer.parseInt(value(arg));
                } else if (arg.startsWith("--quiet=")) {
                    quietZone = Integer.parseInt(value(arg));
                } else if (arg.startsWith("--version=")) {
                    options = options.version(Integer.parseInt(value(arg)));
                } else if (arg.startsWith("--mask=")) {
                    options = options.mask(Integer.parseInt(value(arg)));
                } else if (arg.startsWith("--charset=")) {
                    options = options.charset(Charset.forName(value(arg)));
                } else if (arg.equals("--gs1")) {
                    options = options.gs1(true);
                } else if (arg.equals("--compact")) {
                    options = options.compact(true);
                } else if (arg.startsWith("--out=")) {
                    output = Path.of(value(arg));
                } else {
                    err.println("Unknown option: " + arg);
                    usage(err);
                    return 2;
                }
            }
        } catch (IllegalArgumentException wrong) {
            err.println("Wrong option value: " + wrong.getMessage());
            return 2;
        }
        if (text == null) {
            usage(err);
            return 2;
        }
        QrCode qr;
        try {
            qr = QrCode.encode(text, options);
        } catch (IllegalArgumentException cannot) {
            err.println(cannot.getMessage());
            return 2;
        }
        try {
            if (output == null) {
                out.print(qr.toSvg(quietZone));
            } else if (output.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".svg")) {
                Files.writeString(output, qr.toSvg(quietZone), StandardCharsets.UTF_8);
            } else {
                String name = output.getFileName().toString();
                String format = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : "png";
                if (!ImageIO.write(qr.toImage(moduleSize, quietZone), format, output.toFile())) {
                    err.println("No image writer for " + format);
                    return 2;
                }
            }
        } catch (IOException | IllegalArgumentException cannot) {
            err.println("Cannot write " + output + ": " + cannot.getMessage());
            return 2;
        }
        return 0;
    }

    private static String value(String arg) {
        return arg.substring(arg.indexOf('=') + 1);
    }

    static void usage(PrintStream stream) {
        stream.println("Usage: java -jar barleader.jar qr [--level=L|M|Q|H] [--module=8] [--quiet=4] [--version=N]"
                + " [--mask=N] [--charset=NAME] [--gs1] [--compact] [--out=file.png|file.svg] [--] text");
    }
}
