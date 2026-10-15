package io.github.kamilkrzywanski.barcode.cli;

import io.github.kamilkrzywanski.barcode.AztecOptions;
import io.github.kamilkrzywanski.barcode.Barcode;
import io.github.kamilkrzywanski.barcode.BarcodeType;
import io.github.kamilkrzywanski.barcode.BarcodeWriter;
import io.github.kamilkrzywanski.barcode.DataMatrixShape;
import io.github.kamilkrzywanski.barcode.Pdf417Options;
import io.github.kamilkrzywanski.barcode.QrErrorCorrection;
import io.github.kamilkrzywanski.barcode.RenderOptions;
import io.github.kamilkrzywanski.barcode.WriteOptions;

import javax.imageio.ImageIO;
import java.awt.Font;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Writes a barcode of any {@link BarcodeType}.
 *
 * <pre>
 * java -jar barcode-pro.jar write --type=TYPE [options] content
 *   --type=TYPE         CODE_128, EAN_13, DATA_MATRIX, PDF_417, AZTEC, ... (QR_CODE by default)
 *   --module=N          pixels a module in an image, 4 by default
 *   --height=N          the bars of a linear symbol in modules, 60 by default
 *   --no-text           no human-readable line under a linear symbol
 *   --font=NAME         the font of the line, SansSerif by default
 *   --font-size=N       its size in modules; fitted to the symbol by default
 *   --mm=N              millimetres a module in a PDF, 0.33 by default
 *   --quiet=N           light modules on each side, the symbology's own by default
 *   --ratio=R           wide to narrow, 2.0 to 3.0, for Code 39, Codabar, Code 11 and ITF
 *   --checksum          the optional check characters of Code 39 and Code 11
 *   --add-on=DIGITS     a 2- or 5-digit add-on after EAN and UPC
 *   --charset=NAME      the character set of text in a 2D symbol, announced with its ECI
 *   --compact           the fewest symbols or bits, where the symbology has a minimal encoder
 *   --level=L           error correction: L, M, Q or H for QR Code, 0 to 8 for PDF417, a percent for Aztec
 *   --shape=S           ANY, SQUARE or RECTANGLE for Data Matrix
 *   --columns=N         PDF417 data columns, 1 to 30
 *   --rows=N            PDF417 rows, 3 to 90
 *   --row-height=N      PDF417 row height in modules, 3 by default
 *   --truncated         truncated PDF417
 *   --layers=N          Aztec layers, -1 to -4 compact, 1 to 32 full
 *   --out=FILE          an image in the format of its extension, .svg or .pdf; SVG on standard output without it
 *   --                  the next argument is the content, even when it starts with a dash
 * </pre>
 *
 * <p>The exit code is 0 when the symbol was written, and 2 for wrong usage, content the symbology
 * cannot hold, or a file that cannot be written.
 */
final class WriteCommand {
    private WriteCommand() {
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        BarcodeType type = BarcodeType.QR_CODE;
        WriteOptions options = WriteOptions.defaults();
        Pdf417Options pdf417 = Pdf417Options.defaults();
        AztecOptions aztec = AztecOptions.defaults();
        String level = null;
        int moduleSize = 4;
        RenderOptions render = RenderOptions.defaults();
        double moduleMillimetres = 0.33;
        Path output = null;
        String content = null;
        boolean optionsEnd = false;
        try {
            for (String arg : args) {
                if (optionsEnd || !arg.startsWith("-")) {
                    if (content != null) {
                        err.println("One content at a time; quote it when it has spaces.");
                        return 2;
                    }
                    content = arg;
                } else if (arg.equals("--")) {
                    optionsEnd = true;
                } else if (arg.equals("-h") || arg.equals("--help")) {
                    usage(out);
                    return 0;
                } else if (arg.startsWith("--type=")) {
                    type = BarcodeType.valueOf(value(arg).toUpperCase(Locale.ROOT).replace('-', '_'));
                } else if (arg.startsWith("--module=")) {
                    moduleSize = Integer.parseInt(value(arg));
                } else if (arg.startsWith("--height=")) {
                    render = render.barHeight(Integer.parseInt(value(arg)));
                } else if (arg.equals("--no-text")) {
                    render = render.humanReadable(false);
                } else if (arg.startsWith("--font=")) {
                    render = render.font(new Font(value(arg), Font.PLAIN, 1));
                } else if (arg.startsWith("--font-size=")) {
                    render = render.fontSize(Double.parseDouble(value(arg)));
                } else if (arg.startsWith("--mm=")) {
                    moduleMillimetres = Double.parseDouble(value(arg));
                    if (!(moduleMillimetres > 0) || Double.isInfinite(moduleMillimetres)) {
                        throw new IllegalArgumentException("--mm must be > 0");
                    }
                } else if (arg.startsWith("--quiet=")) {
                    options = options.quietZone(Integer.parseInt(value(arg)));
                } else if (arg.startsWith("--ratio=")) {
                    options = options.wideRatio(Double.parseDouble(value(arg)));
                } else if (arg.equals("--checksum")) {
                    options = options.checksum(true);
                } else if (arg.startsWith("--add-on=")) {
                    options = options.addOn(value(arg));
                } else if (arg.startsWith("--charset=")) {
                    options = options.charset(Charset.forName(value(arg)));
                } else if (arg.equals("--compact")) {
                    options = options.compact(true);
                } else if (arg.startsWith("--level=")) {
                    level = value(arg);
                } else if (arg.startsWith("--shape=")) {
                    options = options.dataMatrixShape(DataMatrixShape.valueOf(value(arg).toUpperCase(Locale.ROOT)));
                } else if (arg.startsWith("--columns=")) {
                    pdf417 = pdf417.columns(Integer.parseInt(value(arg)));
                } else if (arg.startsWith("--rows=")) {
                    pdf417 = pdf417.rows(Integer.parseInt(value(arg)));
                } else if (arg.startsWith("--row-height=")) {
                    pdf417 = pdf417.rowHeight(Integer.parseInt(value(arg)));
                } else if (arg.equals("--truncated")) {
                    pdf417 = pdf417.truncated(true);
                } else if (arg.startsWith("--layers=")) {
                    aztec = aztec.layers(Integer.parseInt(value(arg)));
                } else if (arg.startsWith("--out=")) {
                    output = Path.of(value(arg));
                } else {
                    err.println("Unknown option: " + arg);
                    usage(err);
                    return 2;
                }
            }
            if (level != null) {
                switch (type) {
                    case QR_CODE, GS1_QR_CODE ->
                            options = options.qr(options.qr().errorCorrection(QrErrorCorrection.valueOf(level.toUpperCase(Locale.ROOT))));
                    case PDF_417 -> pdf417 = pdf417.errorCorrection(Integer.parseInt(level));
                    case AZTEC -> aztec = aztec.errorCorrection(Integer.parseInt(level));
                    default -> {
                        err.println(type + " has no error correction level.");
                        return 2;
                    }
                }
            }
        } catch (IllegalArgumentException wrong) {
            err.println("Wrong option value: " + wrong.getMessage());
            return 2;
        }
        if (content == null) {
            usage(err);
            return 2;
        }
        Barcode code;
        try {
            code = BarcodeWriter.encode(type, content, options.pdf417(pdf417).aztec(aztec));
        } catch (IllegalArgumentException cannot) {
            err.println(cannot.getMessage());
            return 2;
        }
        try {
            String lower = output == null ? "" : output.getFileName().toString().toLowerCase(Locale.ROOT);
            if (output == null) {
                out.print(code.toSvg(render));
            } else if (lower.endsWith(".svg")) {
                Files.writeString(output, code.toSvg(render), StandardCharsets.UTF_8);
            } else if (lower.endsWith(".pdf")) {
                Files.write(output, code.toPdf(moduleMillimetres, render));
            } else {
                String name = output.getFileName().toString();
                String format = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : "png";
                if (!ImageIO.write(code.toImage(moduleSize, render), format, output.toFile())) {
                    err.println("No image writer for " + format);
                    return 2;
                }
            }
        } catch (IOException | IllegalArgumentException | ArithmeticException cannot) {
            err.println("Cannot write " + output + ": " + cannot.getMessage());
            return 2;
        }
        return 0;
    }

    private static String value(String arg) {
        return arg.substring(arg.indexOf('=') + 1);
    }

    static void usage(PrintStream stream) {
        stream.println("Usage: java -jar barcode-pro.jar write [--type=CODE_128|EAN_13|DATA_MATRIX|PDF_417|AZTEC|...]"
                + " [--module=4] [--height=60] [--no-text] [--font=NAME] [--font-size=N] [--mm=0.33] [--quiet=N] [--ratio=2.5] [--checksum] [--add-on=DIGITS] [--charset=NAME]"
                + " [--compact] [--level=L] [--shape=ANY|SQUARE|RECTANGLE] [--columns=N] [--rows=N] [--row-height=3]"
                + " [--truncated] [--layers=N] [--out=file.png|file.svg|file.pdf] [--] content");
    }
}
