package io.github.kamilkrzywanski.barleader.image;

import io.github.kamilkrzywanski.barleader.image.GrayU8;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.Writer;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.datamatrix.DataMatrixWriter;
import com.google.zxing.datamatrix.encoder.SymbolShapeHint;
import com.google.zxing.oned.Code128Writer;
import com.google.zxing.oned.Code39Writer;
import com.google.zxing.oned.EAN13Writer;
import com.google.zxing.oned.EAN8Writer;
import com.google.zxing.oned.UPCAWriter;
import com.google.zxing.pdf417.PDF417Writer;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.google.zxing.qrcode.encoder.Encoder;
import com.google.zxing.qrcode.encoder.QRCode;
import io.github.kamilkrzywanski.barleader.ImagePoint;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Deterministic synthetic corpus for the phase-0 benchmark. */
public final class Samples {
    public enum Kind {
        CLEAN,
        FAST_ADVANTAGE,
        AGGRESSIVE_ADVANTAGE,
        STRESS,
        NEGATIVE
    }

    public record Sample(String name, Kind kind, BufferedImage image, List<String> expected) {
    }

    private Samples() {
    }

    public static List<Sample> all() {
        List<Sample> samples = new ArrayList<>();
        samples.add(clean("qr", qr("QR-PRO-HELLO", 240), "QR-PRO-HELLO"));
        samples.add(clean("datamatrix", dataMatrix("DM-PRO-123"), "DM-PRO-123"));
        samples.add(clean("code128", code128("BARCODE-128"), "BARCODE-128"));
        samples.add(clean("pdf417", pdf417("PDF417-PRO"), "PDF417-PRO"));
        samples.add(clean("ean13", ean13("590123412345"), ean13Text("590123412345")));
        samples.add(clean("ean8", ean8("5512345"), ean8Text("5512345")));
        samples.add(clean("upca", upca("03600029145"), upcaText("03600029145")));
        samples.add(clean("code39", code39("CODE39"), "CODE39"));

        samples.add(advantage("inverted-qr", invert(qr("QR-INVERT", 240)), "QR-INVERT"));
        samples.add(advantage("inverted-datamatrix", invert(dataMatrix("DM-INVERT")), "DM-INVERT"));
        samples.add(advantage("inverted-code128", invert(code128("INVERT-128")), "INVERT-128"));
        samples.add(advantage("perspective-qr", perspective(qr("QR-SKEW", 220)), "QR-SKEW"));
        samples.add(advantage("qr-block-phase", qr("QR-PRO-HELLO", 240, ErrorCorrectionLevel.H), "QR-PRO-HELLO"));
        samples.add(advantage("qr-glare", glare(qr("QR-GLARE", 240)), "QR-GLARE"));
        samples.add(advantage("qr-noise", noise(qr("QR-NOISE", 240), 0.02), "QR-NOISE"));
        samples.add(advantage("perspective-datamatrix", skewedDataMatrix("DM-SKEW"), "DM-SKEW"));
        samples.add(new Sample(
                "code128-rotated-30",
                Kind.AGGRESSIVE_ADVANTAGE,
                rotate(code128("ANGLE-128"), 30),
                List.of("ANGLE-128")
        ));
        samples.add(new Sample(
                "code128-rotated-45",
                Kind.AGGRESSIVE_ADVANTAGE,
                rotate(code128("ANGLE-45"), 45),
                List.of("ANGLE-45")
        ));
        samples.add(new Sample(
                "datamatrix-on-label",
                Kind.AGGRESSIVE_ADVANTAGE,
                dataMatrixOnLabel("DM-TILE"),
                List.of("DM-TILE")
        ));

        samples.add(new Sample("tiny-qr", Kind.STRESS, pad(tinyQr("TINY-QR"), 80), List.of("TINY-QR")));
        samples.add(new Sample("qr-blur", Kind.STRESS, blur(qr("QR-BLUR", 280)), List.of("QR-BLUR")));
        samples.add(new Sample(
                "qr-gradient",
                Kind.STRESS,
                gradient(qr("QR-LIGHT", 240), 0.35),
                List.of("QR-LIGHT")
        ));

        samples.add(new Sample("blank", Kind.NEGATIVE, filled(180, 140, 0xFFFFFF), List.of()));
        samples.add(new Sample("gradient", Kind.NEGATIVE, gradient(filled(180, 140, 0xFFFFFF), 0.05), List.of()));
        samples.add(new Sample("noise", Kind.NEGATIVE, noise(filled(160, 160, 0xD0D0D0), 0.08), List.of()));
        return List.copyOf(samples);
    }

    public static Sample qrAndCode128() {
        BufferedImage matrix = qr("QR-AND", 200);
        BufferedImage linear = code128("AND-128");
        int width = matrix.getWidth() + linear.getWidth() + 80;
        int height = Math.max(matrix.getHeight(), linear.getHeight()) + 40;
        BufferedImage canvas = filled(width, height, 0xFFFFFF);
        Graphics2D graphics = canvas.createGraphics();
        graphics.drawImage(matrix, 20, 20, null);
        graphics.drawImage(linear, matrix.getWidth() + 50, 30, null);
        graphics.dispose();
        return new Sample("qr-and-code128", Kind.FAST_ADVANTAGE, canvas, List.of("QR-AND", "AND-128"));
    }

    public static Sample twoQrCodes() {
        BufferedImage left = qr("QR-LEFT", 200);
        BufferedImage right = qr("QR-RIGHT", 200);
        BufferedImage canvas = filled(left.getWidth() + right.getWidth() + 60, Math.max(left.getHeight(), right.getHeight()) + 40, 0xFFFFFF);
        Graphics2D graphics = canvas.createGraphics();
        graphics.drawImage(left, 20, 20, null);
        graphics.drawImage(right, left.getWidth() + 40, 20, null);
        graphics.dispose();
        return new Sample("two-qr", Kind.FAST_ADVANTAGE, canvas, List.of("QR-LEFT", "QR-RIGHT"));
    }

    private static Sample clean(String name, BufferedImage image, String text) {
        return new Sample(name, Kind.CLEAN, image, List.of(text));
    }

    private static Sample advantage(String name, BufferedImage image, String text) {
        return new Sample(name, Kind.FAST_ADVANTAGE, image, List.of(text));
    }

    private static BufferedImage qr(String text, int side) {
        return qr(text, side, ErrorCorrectionLevel.Q);
    }

    private static BufferedImage qr(String text, int side, ErrorCorrectionLevel level) {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.ERROR_CORRECTION, level);
        hints.put(EncodeHintType.MARGIN, 4);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        return encode(new QRCodeWriter(), BarcodeFormat.QR_CODE, text, side, side, hints, 1);
    }

    private static BufferedImage tinyQr(String text) {
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            QRCode code = Encoder.encode(text, ErrorCorrectionLevel.M, hints);
            int modules = code.getMatrix().getWidth();
            int quiet = 4;
            int side = modules + quiet * 2;
            BufferedImage image = filled(side, side, 0xFFFFFF);
            for (int y = 0; y < modules; y++) {
                for (int x = 0; x < modules; x++) {
                    if (code.getMatrix().get(x, y) == 1) {
                        image.setRGB(x + quiet, y + quiet, 0x000000);
                    }
                }
            }
            return image;
        } catch (WriterException exception) {
            throw new IllegalStateException(text, exception);
        }
    }

    private static BufferedImage dataMatrix(String text) {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.MARGIN, 4);
        BufferedImage image = encode(new DataMatrixWriter(), BarcodeFormat.DATA_MATRIX, text, 160, 160, hints, 1);
        return pad(image, 16);
    }

    private static BufferedImage skewedDataMatrix(String text) {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.MARGIN, 4);
        hints.put(EncodeHintType.DATA_MATRIX_SHAPE, SymbolShapeHint.FORCE_SQUARE);
        return perspective(rotate(encode(new DataMatrixWriter(), BarcodeFormat.DATA_MATRIX, text, 200, 200, hints, 1), 25));
    }

    /** Crisp Data Matrix sitting in one corner of a large, otherwise empty label. */
    private static BufferedImage dataMatrixOnLabel(String text) {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.MARGIN, 2);
        BufferedImage symbol = encode(new DataMatrixWriter(), BarcodeFormat.DATA_MATRIX, text, 96, 96, hints, 1);
        int side = 960;
        BufferedImage canvas = filled(side, side, 0xFFFFFF);
        Graphics2D graphics = canvas.createGraphics();
        graphics.setColor(new Color(0x404040));
        graphics.fillRoundRect(48, 40, 260, 120, 16, 16);
        graphics.drawImage(symbol, 620, 640, null);
        graphics.dispose();
        return canvas;
    }

    private static BufferedImage code128(String text) {
        return pad(encode(new Code128Writer(), BarcodeFormat.CODE_128, text, 460, 140, Map.of(), 1), 24);
    }

    private static BufferedImage code39(String text) {
        return pad(encode(new Code39Writer(), BarcodeFormat.CODE_39, text, 420, 140, Map.of(), 1), 24);
    }

    private static BufferedImage pdf417(String text) {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.MARGIN, 12);
        hints.put(EncodeHintType.ERROR_CORRECTION, 4);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        return encode(new PDF417Writer(), BarcodeFormat.PDF_417, text, 420, 180, hints, 1);
    }

    private static BufferedImage ean13(String twelveDigits) {
        return pad(encode(new EAN13Writer(), BarcodeFormat.EAN_13, ean13Text(twelveDigits), 360, 160, Map.of(), 1), 20);
    }

    private static BufferedImage ean8(String sevenDigits) {
        return pad(encode(new EAN8Writer(), BarcodeFormat.EAN_8, ean8Text(sevenDigits), 280, 150, Map.of(), 1), 20);
    }

    private static BufferedImage upca(String elevenDigits) {
        return pad(encode(new UPCAWriter(), BarcodeFormat.UPC_A, upcaText(elevenDigits), 360, 160, Map.of(), 1), 20);
    }

    static String ean13Text(String twelveDigits) {
        return twelveDigits + checkDigit(twelveDigits, false);
    }

    static String ean8Text(String sevenDigits) {
        return sevenDigits + checkDigit(sevenDigits, true);
    }

    static String upcaText(String elevenDigits) {
        return elevenDigits + checkDigit(elevenDigits, true);
    }

    private static int checkDigit(String digits, boolean leadingWeightThree) {
        int sum = 0;
        for (int i = 0; i < digits.length(); i++) {
            int digit = digits.charAt(i) - '0';
            boolean triple = leadingWeightThree == (i % 2 == 0);
            sum += triple ? digit * 3 : digit;
        }
        return (10 - (sum % 10)) % 10;
    }

    private static BufferedImage encode(
            Writer writer,
            BarcodeFormat format,
            String text,
            int width,
            int height,
            Map<EncodeHintType, ?> hints,
            int scale
    ) {
        WriterException last = null;
        int[] widths = {width, Math.max(width * 2, 320), 640};
        int[] heights = {height, Math.max(height, 180), 280};
        for (int i = 0; i < widths.length; i++) {
            try {
                BitMatrix matrix = writer.encode(text, format, widths[i], heights[i], hints);
                return draw(matrix, scale);
            } catch (WriterException exception) {
                last = exception;
            }
        }
        throw new IllegalStateException("cannot encode " + format + " " + text, last);
    }

    private static BufferedImage draw(BitMatrix matrix, int scale) {
        int width = matrix.getWidth() * scale;
        int height = matrix.getHeight() * scale;
        BufferedImage image = filled(width, height, 0xFFFFFF);
        for (int y = 0; y < matrix.getHeight(); y++) {
            for (int x = 0; x < matrix.getWidth(); x++) {
                if (matrix.get(x, y)) {
                    for (int dy = 0; dy < scale; dy++) {
                        for (int dx = 0; dx < scale; dx++) {
                            image.setRGB(x * scale + dx, y * scale + dy, 0x000000);
                        }
                    }
                }
            }
        }
        return image;
    }

    public static BufferedImage perspective(BufferedImage source) {
        GrayU8 gray = GrayImages.from(source);
        int width = source.getWidth() + 80;
        int height = source.getHeight() + 80;
        ImagePoint[] quad = new ImagePoint[]{
                new ImagePoint(width * 0.28, 24),
                new ImagePoint(width - 20.0, 36),
                new ImagePoint(width - 8.0, height - 16.0),
                new ImagePoint(18, height * 0.72)
        };
        return toBuffered(Geometry.warpToQuad(gray, width, height, quad));
    }

    private static BufferedImage toBuffered(GrayU8 gray) {
        BufferedImage image = filled(gray.width, gray.height, 0xFFFFFF);
        for (int y = 0; y < gray.height; y++) {
            for (int x = 0; x < gray.width; x++) {
                int value = gray.get(x, y);
                image.setRGB(x, y, (value << 16) | (value << 8) | value);
            }
        }
        return image;
    }

    static BufferedImage invert(BufferedImage source) {
        BufferedImage image = filled(source.getWidth(), source.getHeight(), 0);
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                int rgb = source.getRGB(x, y);
                int r = 255 - ((rgb >> 16) & 0xFF);
                int g = 255 - ((rgb >> 8) & 0xFF);
                int b = 255 - (rgb & 0xFF);
                image.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }
        return image;
    }

    static BufferedImage gradient(BufferedImage source, double floor) {
        BufferedImage image = filled(source.getWidth(), source.getHeight(), 0);
        int width = source.getWidth();
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < width; x++) {
                double factor = floor + (1.0 - floor) * (x / (double) Math.max(1, width - 1));
                image.setRGB(x, y, scale(source.getRGB(x, y), factor));
            }
        }
        return image;
    }

    static BufferedImage glare(BufferedImage source) {
        BufferedImage image = copy(source);
        Graphics2D graphics = image.createGraphics();
        // 180 keeps stock HybridBinarizer from reading the symbol. 190 washes it out past illumination flatten.
        graphics.setColor(new Color(255, 255, 255, 180));
        int w = image.getWidth();
        int h = image.getHeight();
        graphics.fillOval(w / 5, h / 4, w / 2, h / 2);
        graphics.dispose();
        return image;
    }

    static BufferedImage blur(BufferedImage source) {
        float[] kernel = new float[25];
        for (int i = 0; i < kernel.length; i++) {
            kernel[i] = 1f / 25f;
        }
        ConvolveOp op = new ConvolveOp(new Kernel(5, 5, kernel), ConvolveOp.EDGE_NO_OP, null);
        BufferedImage image = filled(source.getWidth(), source.getHeight(), 0xFFFFFF);
        op.filter(source, image);
        return image;
    }

    static BufferedImage noise(BufferedImage source, double probability) {
        BufferedImage image = copy(source);
        Random random = new Random(1);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if (random.nextDouble() < probability) {
                    image.setRGB(x, y, random.nextBoolean() ? 0xFFFFFF : 0x000000);
                }
            }
        }
        return image;
    }

    public static BufferedImage rotate(BufferedImage source, double degrees) {
        double radians = Math.toRadians(degrees);
        double sin = Math.abs(Math.sin(radians));
        double cos = Math.abs(Math.cos(radians));
        int width = (int) Math.ceil(source.getWidth() * cos + source.getHeight() * sin);
        int height = (int) Math.ceil(source.getWidth() * sin + source.getHeight() * cos);
        BufferedImage image = filled(width, height, 0xFFFFFF);
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        AffineTransform transform = new AffineTransform();
        transform.translate(width / 2.0, height / 2.0);
        transform.rotate(radians);
        transform.translate(-source.getWidth() / 2.0, -source.getHeight() / 2.0);
        graphics.drawImage(source, transform, null);
        graphics.dispose();
        return image;
    }

    static BufferedImage pad(BufferedImage source, int margin) {
        BufferedImage image = filled(source.getWidth() + margin * 2, source.getHeight() + margin * 2, 0xFFFFFF);
        Graphics2D graphics = image.createGraphics();
        graphics.drawImage(source, margin, margin, null);
        graphics.dispose();
        return image;
    }

    private static BufferedImage copy(BufferedImage source) {
        BufferedImage image = filled(source.getWidth(), source.getHeight(), 0xFFFFFF);
        Graphics2D graphics = image.createGraphics();
        graphics.drawImage(source, 0, 0, null);
        graphics.dispose();
        return image;
    }

    private static BufferedImage filled(int width, int height, int rgb) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(new Color(rgb));
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        return image;
    }

    private static int scale(int rgb, double factor) {
        int r = clamp((int) Math.round(((rgb >> 16) & 0xFF) * factor));
        int g = clamp((int) Math.round(((rgb >> 8) & 0xFF) * factor));
        int b = clamp((int) Math.round((rgb & 0xFF) * factor));
        return (r << 16) | (g << 8) | b;
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }
}
