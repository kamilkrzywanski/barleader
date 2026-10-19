/*
 * Copyright 2026 Kamil Krzywański
 */
// SPDX-License-Identifier: Apache-2.0
package io.github.kamilkrzywanski.barleader;

import java.awt.Font;

/**
 * How {@link Barcode} draws an image, SVG or PDF. Immutable; every setter returns a copy.
 *
 * <p>By default the bars of a linear symbol are {@value Barcode#DEFAULT_BAR_HEIGHT} modules high
 * with the human-readable line under them, in the logical font {@code SansSerif}. The line of
 * EAN and UPC is laid out as on a retail label: the digits in groups under the halves of the
 * symbol, the first and last beside it, the guard bars reaching down between the groups, an
 * add-on's digits above its bars, and the ISBN or ISMN above the symbol. A 2D symbol has no line.
 * Drawing the line needs a font from AWT; a headless JDK without fonts fails on it, and
 * {@code humanReadable(false)} leaves it out.
 */
public final class RenderOptions {
    private static final RenderOptions DEFAULTS = new RenderOptions(Barcode.DEFAULT_BAR_HEIGHT, true, null, 0);

    private final int barHeight;
    private final boolean humanReadable;
    private final Font font;
    private final double fontSize;

    private RenderOptions(int barHeight, boolean humanReadable, Font font, double fontSize) {
        if (barHeight < 1) {
            throw new IllegalArgumentException("barHeight must be >= 1");
        }
        if (!(fontSize >= 0) || Double.isInfinite(fontSize)) {
            throw new IllegalArgumentException("fontSize must be > 0, or 0 to fit the symbol");
        }
        this.barHeight = barHeight;
        this.humanReadable = humanReadable;
        this.font = font;
        this.fontSize = fontSize;
    }

    public static RenderOptions defaults() {
        return DEFAULTS;
    }

    /** The height of the bars of a linear symbol in modules, the line under them not counted. */
    public RenderOptions barHeight(int modules) {
        return new RenderOptions(modules, humanReadable, font, fontSize);
    }

    /** Whether a linear symbol has its human-readable line. */
    public RenderOptions humanReadable(boolean humanReadable) {
        return new RenderOptions(barHeight, humanReadable, font, fontSize);
    }

    /** The face of the human-readable line, its size ignored; null for {@code SansSerif}. */
    public RenderOptions font(Font font) {
        return new RenderOptions(barHeight, humanReadable, font, fontSize);
    }

    /**
     * The size of the human-readable font in modules, or 0 to fit it: EAN and UPC digits seven
     * modules wide, as their digit groups are, and other lines 10 modules, smaller where the line
     * would be wider than the symbol.
     */
    public RenderOptions fontSize(double modules) {
        return new RenderOptions(barHeight, humanReadable, font, modules);
    }

    public int barHeight() {
        return barHeight;
    }

    public boolean humanReadable() {
        return humanReadable;
    }

    /** The face asked for, or null. */
    public Font font() {
        return font;
    }

    public double fontSize() {
        return fontSize;
    }
}
