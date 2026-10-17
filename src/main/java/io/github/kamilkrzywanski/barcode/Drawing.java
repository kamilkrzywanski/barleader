/*
 * Copyright (c) 2025 Viktor Wedel
 * Copyright 2026 Kamil Krzywański
 */
// SPDX-License-Identifier: Apache-2.0
package io.github.kamilkrzywanski.barcode;

import io.github.kamilkrzywanski.barcode.encode.UpcEanEncoder.Kind;

import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;

/**
 * A barcode as filled shapes in modules, y down, with the quiet zones: the bars or the dark modules
 * as rectangles and the human-readable line as glyph outlines. The image, the SVG and the PDF all
 * fill these, so the three show the same text.
 *
 * <p>The line is laid out as Barcode-Lib4J 3.2.0 lays it out ({@code de.vwsoft.barcodelib4j.oned.Barcode}
 * and {@code UPCEANFamily}, Apache License 2.0, Copyright (c) 2025 Viktor Wedel): centred under
 * the symbol, or for EAN and UPC in digit groups seven modules a digit, the guard bars five modules
 * longer, an add-on's digits above its shortened bars, and the ISBN or ISMN above the symbol.
 */
final class Drawing {
    /** The font size of a line other than EAN and UPC digits, in modules. */
    static final double DEFAULT_FONT_SIZE = 10;
    private static final FontRenderContext CONTEXT = new FontRenderContext(null, true, true);
    /** How far an EAN or UPC guard bar reaches below the others, in modules. */
    private static final double GUARD_EXTENSION = 5;
    /** The space under EAN and UPC digits, in modules; the font's own descent is not used. */
    private static final double DIGIT_DESCENT = 1.5;
    /** How far the ISBN or ISMN line reaches beyond the EAN-13 symbol on each side, in modules. */
    private static final double ABOVE_OVERHANG = 5;

    /** Width and height in modules, with the quiet zones and the human-readable line. */
    final double width;
    final double height;
    final List<Rectangle2D.Double> dark = new ArrayList<>();
    final Path2D.Double text = new Path2D.Double(Path2D.WIND_NON_ZERO);

    private Drawing(double width, double height) {
        this.width = width;
        this.height = height;
    }

    boolean hasText() {
        return !text.getPathIterator(null).isDone();
    }

    static Drawing of(Barcode code, RenderOptions options) {
        if (!code.type().isLinear()) {
            return matrix(code);
        }
        boolean[] bars = code.row(0);
        double width = code.quietZoneLeft() + bars.length + code.quietZoneRight();
        int barHeight = options.barHeight();
        if (options.humanReadable() && code.retailLabel() != null) {
            return retail(code, options);
        }
        String line = options.humanReadable() ? printable(code.text()) : "";
        if (line.isBlank()) {
            Drawing drawing = new Drawing(width, barHeight);
            drawing.bars(bars, code.quietZoneLeft(), 0, barHeight);
            return drawing;
        }
        Font face = face(options);
        double size = options.fontSize() > 0 ? options.fontSize()
                : Math.min(DEFAULT_FONT_SIZE, bars.length / advance(face, line, 1));
        Font font = face.deriveFont((float) size);
        var metrics = font.getLineMetrics(line, CONTEXT);
        double baseline = barHeight + metrics.getAscent();
        Drawing drawing = new Drawing(width, baseline + metrics.getDescent());
        drawing.bars(bars, code.quietZoneLeft(), 0, barHeight);
        drawing.text(font, line, code.quietZoneLeft() + (bars.length - advance(font, line)) / 2, baseline);
        return drawing;
    }

    private static Drawing matrix(Barcode code) {
        Drawing drawing = new Drawing(code.quietZoneLeft() + code.width() + code.quietZoneRight(),
                code.quietZoneTop() + code.height() + code.quietZoneBottom());
        for (int y = 0; y < code.height(); y++) {
            drawing.bars(code.row(y), code.quietZoneLeft(), code.quietZoneTop() + y, 1);
        }
        return drawing;
    }

    /** The groups of digits of EAN-13, EAN-8, UPC-A and UPC-E, and an add-on and an ISBN line if there are. */
    private static Drawing retail(Barcode code, RenderOptions options) {
        Barcode.RetailLabel label = code.retailLabel();
        Kind kind = label.kind();
        String digits = label.digits();
        // the parts beside the symbol, under its left half, under its right half, and after it
        String part1;
        String part2;
        String part3;
        String part4;
        int mainLength;
        double part2At;
        double part3At;
        int[] guards;
        switch (kind) {
            case EAN_13 -> {
                part1 = digits.substring(0, 1);
                part2 = digits.substring(1, 7);
                part3 = digits.substring(7, 13);
                part4 = null;
                mainLength = 95;
                part2At = 3.5;
                part3At = 49.5;
                guards = new int[]{0, 3, 45, 50, 92, 95};
            }
            case EAN_8 -> {
                part1 = null;
                part2 = digits.substring(0, 4);
                part3 = digits.substring(4, 8);
                part4 = null;
                mainLength = 67;
                part2At = 3.5;
                part3At = 35.5;
                guards = new int[]{0, 3, 31, 36, 64, 67};
            }
            case UPC_A -> {
                part1 = digits.substring(0, 1);
                part2 = digits.substring(1, 6);
                part3 = digits.substring(6, 11);
                part4 = digits.substring(11, 12);
                mainLength = 95;
                part2At = 10.5;
                part3At = 49.5;
                guards = new int[]{0, 10, 45, 50, 85, 95};
            }
            default -> {
                part1 = digits.substring(0, 1);
                part2 = digits.substring(1, 7);
                part3 = null;
                part4 = digits.substring(7, 8);
                mainLength = 51;
                part2At = 3.5;
                part3At = 0;
                guards = new int[]{0, 3, 45, 51};
            }
        }
        Face faces = new Face(options);
        int quietLeft = code.quietZoneLeft();
        boolean[] bars = code.row(0);
        int barHeight = options.barHeight();

        // the ISBN or ISMN line above, fitted to the symbol and its overhang
        double top = 0;
        String above = label.above();
        Font aboveFont = null;
        double aboveBaseline = 0;
        if (above != null) {
            Font sans = new Font(Font.SANS_SERIF, Font.PLAIN, 1);
            double fitted = steppedFit(sans, above, mainLength + 2 * ABOVE_OVERHANG);
            aboveFont = sans.deriveFont((float) fitted);
            var metrics = aboveFont.getLineMetrics(above, CONTEXT);
            aboveBaseline = metrics.getAscent();
            top = metrics.getAscent() + metrics.getDescent();
        }

        Font font = faces.digits(part2);
        double ascent = font.getLineMetrics(part2, CONTEXT).getAscent();
        double fontHeight = ascent + DIGIT_DESCENT;
        double baseline = top + barHeight + ascent;
        double width = quietLeft + bars.length + code.quietZoneRight();
        Drawing drawing = new Drawing(width, top + barHeight + fontHeight);
        if (above != null) {
            drawing.text(aboveFont, above, quietLeft - ABOVE_OVERHANG
                    + (mainLength + 2 * ABOVE_OVERHANG - advance(aboveFont, above)) / 2, aboveBaseline);
        }

        // the end of the symbol's right quiet zone, where an add-on starts
        int mainEnd = quietLeft + mainLength + (label.addOn() != null ? kind.quietRight : code.quietZoneRight());
        if (label.addOn() != null) {
            int addOnLength = label.addOn().length() == 2 ? 20 : 47;
            drawing.text(font, label.addOn(), mainEnd + (addOnLength - advance(font, label.addOn())) / 2, top + ascent);
        }
        if (part1 != null) {
            drawing.text(font, part1, 0, baseline);
        }
        drawing.text(font, part2, quietLeft + part2At + (part2.length() * 7 - advance(font, part2)) / 2, baseline);
        if (part3 != null) {
            drawing.text(font, part3, quietLeft + part3At + (part3.length() * 7 - advance(font, part3)) / 2, baseline);
        }
        if (part4 != null) {
            drawing.text(font, part4, mainEnd - advance(font, part4), baseline);
        }

        double guardHeight = barHeight + GUARD_EXTENSION;
        double addOnHeight = Math.max(guardHeight - fontHeight, 0);
        for (int x = 0; x < bars.length; ) {
            if (!bars[x]) {
                x++;
                continue;
            }
            int start = x;
            while (x < bars.length && bars[x]) {
                x++;
            }
            double y = top;
            double height = barHeight;
            if (start >= mainLength) {
                y = top + fontHeight;
                height = addOnHeight;
            } else if (inGuard(guards, start)) {
                height = guardHeight;
            }
            drawing.dark.add(new Rectangle2D.Double(quietLeft + start, y, x - start, height));
        }
        return drawing;
    }

    private static boolean inGuard(int[] guards, int module) {
        for (int i = 0; i < guards.length; i += 2) {
            if (module >= guards[i] && module < guards[i + 1]) {
                return true;
            }
        }
        return false;
    }

    /** The user's face, or SansSerif, sized for EAN and UPC digit groups. */
    private record Face(Font face, double size) {
        Face(RenderOptions options) {
            this(Drawing.face(options), options.fontSize());
        }

        /** Barcode-Lib4J's fitting: the largest size in steps of 0.1 at which the group is narrower than seven modules a digit. */
        Font digits(String group) {
            return face.deriveFont((float) (size > 0 ? size : steppedFit(face, group, group.length() * 7)));
        }
    }

    /** The largest size in steps of 0.1 at which {@code line} is narrower than {@code width}, as Barcode-Lib4J fits it. */
    private static double steppedFit(Font face, String line, double width) {
        double fit = width / advance(face, line, 1);
        return Math.max((Math.ceil(fit * 10 - 1e-9) - 1) / 10, 0.1);
    }

    private static Font face(RenderOptions options) {
        return options.font() != null ? options.font() : new Font(Font.SANS_SERIF, Font.PLAIN, 1);
    }

    /** The advance of {@code line} at {@code size}, measured at size 100 where the metrics are finest. */
    private static double advance(Font face, String line, double size) {
        return advance(face.deriveFont(100f), line) * size / 100;
    }

    private static double advance(Font font, String line) {
        return font.createGlyphVector(CONTEXT, line).getLogicalBounds().getWidth();
    }

    private void text(Font font, String line, double x, double baseline) {
        GlyphVector glyphs = font.createGlyphVector(CONTEXT, line);
        text.append(glyphs.getOutline((float) x, (float) baseline), false);
    }

    /** The runs of dark modules of one row as rectangles {@code height} high. */
    private void bars(boolean[] row, int left, double y, double height) {
        for (int x = 0; x < row.length; ) {
            if (!row[x]) {
                x++;
                continue;
            }
            int start = x;
            while (x < row.length && row[x]) {
                x++;
            }
            dark.add(new Rectangle2D.Double(left + start, y, x - start, height));
        }
    }

    /** The text with control characters as spaces and Code 128's function characters left out. */
    static String printable(String text) {
        StringBuilder line = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 'ñ' && c <= 'ô') {
                continue;
            }
            line.append(c < ' ' || c == '\u007f' ? ' ' : c);
        }
        return line.toString().strip();
    }

    /** Numbers of a drawing in SVG and PDF: integers plain, the rest with up to three decimals. */
    static String number(double value) {
        long rounded = Math.round(value * 1000);
        if (rounded % 1000 == 0) {
            return Long.toString(rounded / 1000);
        }
        String text = String.format(java.util.Locale.ROOT, "%.3f", rounded / 1000.0);
        int end = text.length();
        while (text.charAt(end - 1) == '0') {
            end--;
        }
        return text.substring(0, end);
    }
}
