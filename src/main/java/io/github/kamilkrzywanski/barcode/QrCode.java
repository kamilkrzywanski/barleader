package io.github.kamilkrzywanski.barcode;

import io.github.kamilkrzywanski.barcode.qr.QrEncoder;

import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;

/**
 * A QR Code (model 2) made from text or bytes, as modules that can be drawn at any size.
 *
 * <pre>
 * QrCode qr = QrCode.encode("https://example.com");
 * ImageIO.write(qr.toImage(8), "png", new File("qr.png"));
 * Files.writeString(Path.of("qr.svg"), qr.toSvg());
 * </pre>
 *
 * <p>The whole text is one segment in the most compact mode that holds every character: numeric,
 * alphanumeric, byte, or Kanji when the character set is Shift_JIS. For the same options the
 * modules are the ones ZXing's {@code QRCodeWriter} produces. A printed or displayed symbol needs a
 * quiet zone of four light modules around it, which the drawing methods add.
 */
public final class QrCode {
    /** The quiet zone the standard asks for, in modules. */
    public static final int QUIET_ZONE = 4;

    private final boolean[][] modules;
    private final int version;
    private final QrErrorCorrection errorCorrection;
    private final int mask;

    private QrCode(QrEncoder.Encoded encoded) {
        this.modules = encoded.modules();
        this.version = encoded.version();
        this.errorCorrection = encoded.level();
        this.mask = encoded.mask();
    }

    /** {@code text} with the default options. */
    public static QrCode encode(String text) {
        return encode(text, QrOptions.defaults());
    }

    /**
     * {@code text} with {@code options}.
     *
     * @throws IllegalArgumentException when the text does not fit, or a character set asked for
     *                                  cannot encode it
     */
    public static QrCode encode(String text, QrOptions options) {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(options, "options");
        Charset charset = options.charset();
        if (charset == null) {
            charset = StandardCharsets.ISO_8859_1.newEncoder().canEncode(text) ? null : StandardCharsets.UTF_8;
        } else if (!charset.newEncoder().canEncode(text)) {
            throw new IllegalArgumentException("The text cannot be encoded in " + charset.name());
        }
        return new QrCode(QrEncoder.encode(text, options.errorCorrection(), charset, options.gs1(), options.version(), options.mask(),
                options.compact()));
    }

    /**
     * {@code data} in byte mode, without an ECI, so a reader gets the bytes as they are. The
     * character set of {@code options} does not apply.
     *
     * @throws IllegalArgumentException when the data does not fit
     */
    public static QrCode encode(byte[] data, QrOptions options) {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(options, "options");
        return new QrCode(QrEncoder.encode(data.clone(), options.errorCorrection(), options.gs1(), options.version(), options.mask()));
    }

    /** Modules on a side, without the quiet zone: 21 for version 1, 4 more a version. */
    public int size() {
        return modules.length;
    }

    /** Whether the module in column {@code x} and row {@code y} is dark; (0, 0) is the top left. */
    public boolean isDark(int x, int y) {
        return modules[y][x];
    }

    /** 1 to 40. */
    public int version() {
        return version;
    }

    public QrErrorCorrection errorCorrection() {
        return errorCorrection;
    }

    /** The data mask, 0 to 7. */
    public int mask() {
        return mask;
    }

    /** A black and white image, {@code moduleSize} pixels a module, with the four-module quiet zone. */
    public BufferedImage toImage(int moduleSize) {
        return toImage(moduleSize, QUIET_ZONE);
    }

    /** A black and white image, {@code moduleSize} pixels a module, with {@code quietZone} light modules around. */
    public BufferedImage toImage(int moduleSize, int quietZone) {
        if (moduleSize < 1) {
            throw new IllegalArgumentException("moduleSize must be >= 1");
        }
        if (quietZone < 0) {
            throw new IllegalArgumentException("quietZone must be >= 0");
        }
        int side = Math.multiplyExact(size() + 2 * quietZone, moduleSize);
        BufferedImage image = new BufferedImage(side, side, BufferedImage.TYPE_BYTE_BINARY);
        WritableRaster raster = image.getRaster();
        int[] light = new int[side];
        Arrays.fill(light, 1);
        for (int y = 0; y < side; y++) {
            raster.setSamples(0, y, side, 1, 0, light);
        }
        int[] dark = new int[moduleSize * moduleSize];
        for (int y = 0; y < size(); y++) {
            for (int x = 0; x < size(); x++) {
                if (modules[y][x]) {
                    raster.setSamples((x + quietZone) * moduleSize, (y + quietZone) * moduleSize, moduleSize, moduleSize, 0, dark);
                }
            }
        }
        return image;
    }

    /** An SVG drawing with the four-module quiet zone, one unit a module. */
    public String toSvg() {
        return toSvg(QUIET_ZONE);
    }

    /** An SVG drawing with {@code quietZone} light modules around, one unit a module; each run of dark modules is one rectangle of the path. */
    public String toSvg(int quietZone) {
        if (quietZone < 0) {
            throw new IllegalArgumentException("quietZone must be >= 0");
        }
        int side = size() + 2 * quietZone;
        StringBuilder path = new StringBuilder();
        for (int y = 0; y < size(); y++) {
            int x = 0;
            while (x < size()) {
                if (!modules[y][x]) {
                    x++;
                    continue;
                }
                int start = x;
                while (x < size() && modules[y][x]) {
                    x++;
                }
                path.append(String.format(Locale.ROOT, "M%d %dh%dv1h-%dz", start + quietZone, y + quietZone, x - start, x - start));
            }
        }
        return String.format(Locale.ROOT,
                "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 %d %d\" shape-rendering=\"crispEdges\">"
                        + "<rect width=\"%d\" height=\"%d\" fill=\"#fff\"/><path d=\"%s\" fill=\"#000\"/></svg>\n",
                side, side, side, side, path);
    }

    @Override
    public String toString() {
        return "QrCode[version=" + version + ", errorCorrection=" + errorCorrection + ", mask=" + mask + ", size=" + size() + "]";
    }
}
