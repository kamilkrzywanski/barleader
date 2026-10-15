package io.github.kamilkrzywanski.barcode;

import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.util.Arrays;
import java.util.Locale;

/**
 * A written barcode as modules, and the drawings of it. A linear symbol is one row of modules, which
 * a drawing stretches to the bar height; a 2D symbol is a matrix. The quiet zones are light modules
 * around the symbol that the drawings add and {@link #isDark} does not count.
 *
 * <pre>
 * Barcode code = BarcodeWriter.encode(BarcodeType.CODE_128, "ABC-123");
 * ImageIO.write(code.toImage(2), "png", new File("code.png"));
 * </pre>
 */
public final class Barcode {
    /** The bar height in modules of a drawing that does not give one. */
    public static final int DEFAULT_BAR_HEIGHT = 60;

    private final BarcodeType type;
    private final boolean[][] modules;
    private final String text;
    private final int quietLeft;
    private final int quietRight;
    private final int quietTop;
    private final int quietBottom;

    Barcode(BarcodeType type, boolean[][] modules, String text, int quietLeft, int quietRight, int quietTop, int quietBottom) {
        this.type = type;
        this.modules = modules;
        this.text = text;
        this.quietLeft = quietLeft;
        this.quietRight = quietRight;
        this.quietTop = quietTop;
        this.quietBottom = quietBottom;
    }

    static Barcode linear(BarcodeType type, boolean[] bars, String text, int quietLeft, int quietRight) {
        return new Barcode(type, new boolean[][]{bars}, text, quietLeft, quietRight, 0, 0);
    }

    public BarcodeType type() {
        return type;
    }

    /** The human-readable text: what a printed label shows under or beside the symbol. */
    public String text() {
        return text;
    }

    /** Modules across, without the quiet zones. */
    public int width() {
        return modules[0].length;
    }

    /** Modules down, without the quiet zones; 1 for a linear symbol. */
    public int height() {
        return modules.length;
    }

    /** Whether the module in column {@code x} and row {@code y} is dark; (0, 0) is the top left. */
    public boolean isDark(int x, int y) {
        return modules[y][x];
    }

    public int quietZoneLeft() {
        return quietLeft;
    }

    public int quietZoneRight() {
        return quietRight;
    }

    public int quietZoneTop() {
        return quietTop;
    }

    public int quietZoneBottom() {
        return quietBottom;
    }

    /**
     * A black and white image, {@code moduleSize} pixels a module, with the quiet zones; the bars of
     * a linear symbol {@link #DEFAULT_BAR_HEIGHT} modules high.
     */
    public BufferedImage toImage(int moduleSize) {
        return toImage(moduleSize, DEFAULT_BAR_HEIGHT * moduleSize);
    }

    /**
     * A black and white image, {@code moduleSize} pixels a module, with the quiet zones; the bars of
     * a linear symbol {@code barHeight} pixels high. A 2D symbol has square modules and ignores it.
     */
    public BufferedImage toImage(int moduleSize, int barHeight) {
        if (moduleSize < 1) {
            throw new IllegalArgumentException("moduleSize must be >= 1");
        }
        if (barHeight < 1) {
            throw new IllegalArgumentException("barHeight must be >= 1");
        }
        int width = Math.multiplyExact(width() + quietLeft + quietRight, moduleSize);
        int height = type.isLinear() ? barHeight : Math.multiplyExact(height() + quietTop + quietBottom, moduleSize);
        int rowHeight = type.isLinear() ? barHeight : moduleSize;
        int top = type.isLinear() ? 0 : quietTop * moduleSize;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_BINARY);
        WritableRaster raster = image.getRaster();
        int[] light = new int[width];
        Arrays.fill(light, 1);
        for (int y = 0; y < height; y++) {
            raster.setSamples(0, y, width, 1, 0, light);
        }
        int[] dark = new int[Math.multiplyExact(moduleSize, rowHeight)];
        for (int y = 0; y < height(); y++) {
            for (int x = 0; x < width(); x++) {
                if (modules[y][x]) {
                    raster.setSamples((x + quietLeft) * moduleSize, top + y * rowHeight, moduleSize, rowHeight, 0, dark);
                }
            }
        }
        return image;
    }

    /** An SVG drawing, one unit a module, with the quiet zones; the bars of a linear symbol {@link #DEFAULT_BAR_HEIGHT} high. */
    public String toSvg() {
        return toSvg(DEFAULT_BAR_HEIGHT);
    }

    /**
     * An SVG drawing, one unit a module, with the quiet zones; the bars of a linear symbol
     * {@code barHeight} units high. Each run of dark modules in a row is one rectangle of the path.
     */
    public String toSvg(int barHeight) {
        if (barHeight < 1) {
            throw new IllegalArgumentException("barHeight must be >= 1");
        }
        int width = width() + quietLeft + quietRight;
        int rowHeight = type.isLinear() ? barHeight : 1;
        int height = type.isLinear() ? barHeight : height() + quietTop + quietBottom;
        int top = type.isLinear() ? 0 : quietTop;
        StringBuilder path = new StringBuilder();
        for (int y = 0; y < height(); y++) {
            int x = 0;
            while (x < width()) {
                if (!modules[y][x]) {
                    x++;
                    continue;
                }
                int start = x;
                while (x < width() && modules[y][x]) {
                    x++;
                }
                path.append(String.format(Locale.ROOT, "M%d %dh%dv%dh-%dz", start + quietLeft, top + y * rowHeight, x - start, rowHeight, x - start));
            }
        }
        return String.format(Locale.ROOT,
                "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 %d %d\" shape-rendering=\"crispEdges\">"
                        + "<rect width=\"%d\" height=\"%d\" fill=\"#fff\"/><path d=\"%s\" fill=\"#000\"/></svg>\n",
                width, height, width, height, path);
    }

    @Override
    public String toString() {
        return "Barcode[" + type + ", " + width() + "x" + height() + ", " + text + "]";
    }
}
