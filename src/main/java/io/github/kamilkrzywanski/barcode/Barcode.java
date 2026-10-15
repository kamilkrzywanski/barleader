package io.github.kamilkrzywanski.barcode;

import io.github.kamilkrzywanski.barcode.encode.UpcEanEncoder;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;

/**
 * A written barcode as modules, and the drawings of it: an image, SVG or PDF. A linear symbol is
 * one row of modules, which a drawing stretches to the bar height and writes the human-readable
 * line under; a 2D symbol is a matrix. The quiet zones are light modules around the symbol that
 * the drawings add and {@link #isDark} does not count. {@link RenderOptions} sets the bar height
 * and the line.
 *
 * <pre>
 * Barcode code = BarcodeWriter.encode(BarcodeType.CODE_128, "ABC-123");
 * ImageIO.write(code.toImage(2), "png", new File("code.png"));
 * Files.write(Path.of("code.pdf"), code.toPdf(0.33));
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
    private final RetailLabel retailLabel;

    Barcode(BarcodeType type, boolean[][] modules, String text, int quietLeft, int quietRight, int quietTop, int quietBottom) {
        this(type, modules, text, quietLeft, quietRight, quietTop, quietBottom, null);
    }

    private Barcode(BarcodeType type, boolean[][] modules, String text, int quietLeft, int quietRight, int quietTop, int quietBottom,
                    RetailLabel retailLabel) {
        this.retailLabel = retailLabel;
        this.type = type;
        this.modules = modules;
        this.text = text;
        this.quietLeft = quietLeft;
        this.quietRight = quietRight;
        this.quietTop = quietTop;
        this.quietBottom = quietBottom;
    }

    static Barcode linear(BarcodeType type, boolean[] bars, String text, int quietLeft, int quietRight) {
        return linear(type, bars, text, quietLeft, quietRight, null);
    }

    static Barcode linear(BarcodeType type, boolean[] bars, String text, int quietLeft, int quietRight, RetailLabel retailLabel) {
        return new Barcode(type, new boolean[][]{bars}, text, quietLeft, quietRight, 0, 0, retailLabel);
    }

    /**
     * The parts of an EAN or UPC line: the digits with the check digit, the add-on or null, and the
     * ISBN or ISMN shown above the symbol or null.
     */
    record RetailLabel(UpcEanEncoder.Kind kind, String digits, String addOn, String above) {
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

    /** Row {@code y} of the modules, not a copy. */
    boolean[] row(int y) {
        return modules[y];
    }

    RetailLabel retailLabel() {
        return retailLabel;
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

    /** A black and white image, {@code moduleSize} pixels a module, drawn with the {@link RenderOptions#defaults()}. */
    public BufferedImage toImage(int moduleSize) {
        return toImage(moduleSize, RenderOptions.defaults());
    }

    /**
     * An image, {@code moduleSize} pixels a module, with the quiet zones and, for a linear symbol,
     * the human-readable line. Without a line the image is black and white; with one it is grey,
     * the bars still black and white and only the edges of the glyphs smoothed.
     */
    public BufferedImage toImage(int moduleSize, RenderOptions options) {
        if (moduleSize < 1) {
            throw new IllegalArgumentException("moduleSize must be >= 1");
        }
        Drawing drawing = Drawing.of(this, options);
        int width = Math.toIntExact((long) Math.ceil(drawing.width * moduleSize - 1e-9));
        int height = Math.toIntExact((long) Math.ceil(drawing.height * moduleSize - 1e-9));
        Math.multiplyExact(width, height);
        BufferedImage image = new BufferedImage(width, height, drawing.hasText() ? BufferedImage.TYPE_BYTE_GRAY : BufferedImage.TYPE_BYTE_BINARY);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            graphics.setColor(Color.BLACK);
            graphics.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            graphics.scale(moduleSize, moduleSize);
            for (Rectangle2D.Double dark : drawing.dark) {
                graphics.fill(dark);
            }
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.fill(drawing.text);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    /** An SVG drawing, one unit a module, drawn with the {@link RenderOptions#defaults()}. */
    public String toSvg() {
        return toSvg(RenderOptions.defaults());
    }

    /**
     * An SVG drawing, one unit a module, with the quiet zones and, for a linear symbol, the
     * human-readable line as glyph outlines, so that it looks the same without the font. Each run of
     * dark modules is one rectangle of the path.
     */
    public String toSvg(RenderOptions options) {
        Drawing drawing = Drawing.of(this, options);
        StringBuilder bars = new StringBuilder();
        for (Rectangle2D.Double dark : drawing.dark) {
            bars.append('M').append(Drawing.number(dark.x)).append(' ').append(Drawing.number(dark.y))
                    .append('h').append(Drawing.number(dark.width)).append('v').append(Drawing.number(dark.height))
                    .append('h').append(Drawing.number(-dark.width)).append('z');
        }
        String width = Drawing.number(drawing.width);
        String height = Drawing.number(drawing.height);
        StringBuilder svg = new StringBuilder("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ")
                .append(width).append(' ').append(height).append("\"><rect width=\"").append(width).append("\" height=\"")
                .append(height).append("\" fill=\"#fff\"/><path d=\"").append(bars)
                .append("\" fill=\"#000\" shape-rendering=\"crispEdges\"/>");
        if (drawing.hasText()) {
            svg.append("<path d=\"").append(svgPath(drawing.text)).append("\" fill=\"#000\"/>");
        }
        return svg.append("</svg>\n").toString();
    }

    private static String svgPath(Path2D.Double path) {
        StringBuilder d = new StringBuilder();
        double[] at = new double[6];
        for (PathIterator segments = path.getPathIterator(null); !segments.isDone(); segments.next()) {
            int type = segments.currentSegment(at);
            int points = switch (type) {
                case PathIterator.SEG_MOVETO -> {
                    d.append('M');
                    yield 1;
                }
                case PathIterator.SEG_LINETO -> {
                    d.append('L');
                    yield 1;
                }
                case PathIterator.SEG_QUADTO -> {
                    d.append('Q');
                    yield 2;
                }
                case PathIterator.SEG_CUBICTO -> {
                    d.append('C');
                    yield 3;
                }
                default -> {
                    d.append('Z');
                    yield 0;
                }
            };
            for (int i = 0; i < points * 2; i++) {
                d.append(i == 0 ? "" : " ").append(Drawing.number(at[i]));
            }
        }
        return d.toString();
    }

    /** A one-page PDF, {@code moduleMillimetres} a module, drawn with the {@link RenderOptions#defaults()}. */
    public byte[] toPdf(double moduleMillimetres) {
        return toPdf(moduleMillimetres, RenderOptions.defaults());
    }

    /**
     * A one-page vector PDF the size of the drawing, {@code moduleMillimetres} a module: 0.33 is
     * the nominal module of EAN and UPC. The human-readable line is filled glyph outlines, so the
     * file needs no font.
     */
    public byte[] toPdf(double moduleMillimetres, RenderOptions options) {
        if (!(moduleMillimetres > 0) || Double.isInfinite(moduleMillimetres)) {
            throw new IllegalArgumentException("moduleMillimetres must be > 0");
        }
        return PdfDocument.write(Drawing.of(this, options), moduleMillimetres, type + " " + text);
    }

    @Override
    public String toString() {
        return "Barcode[" + type + ", " + width() + "x" + height() + ", " + text + "]";
    }
}
