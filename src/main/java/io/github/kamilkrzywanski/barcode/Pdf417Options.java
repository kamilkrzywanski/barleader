/*
 * Copyright 2026 Kamil Krzywański
 */
// SPDX-License-Identifier: Apache-2.0
package io.github.kamilkrzywanski.barcode;

/**
 * How {@link BarcodeWriter} writes a PDF417. Immutable; every setter returns a copy.
 *
 * <p>By default the error correction level is 2, the columns and rows those of the shape nearest
 * three to one that holds the data, each row three modules high, and the symbol not truncated.
 */
public final class Pdf417Options {
    private static final Pdf417Options DEFAULTS = new Pdf417Options(2, 0, 0, 3, false);

    private final int errorCorrection;
    private final int columns;
    private final int rows;
    private final int rowHeight;
    private final boolean truncated;

    private Pdf417Options(int errorCorrection, int columns, int rows, int rowHeight, boolean truncated) {
        if (errorCorrection < 0 || errorCorrection > 8) {
            throw new IllegalArgumentException("errorCorrection must be 0 to 8");
        }
        if (columns != 0 && (columns < 1 || columns > 30)) {
            throw new IllegalArgumentException("columns must be 1 to 30, or 0 to choose");
        }
        if (rows != 0 && (rows < 3 || rows > 90)) {
            throw new IllegalArgumentException("rows must be 3 to 90, or 0 to choose");
        }
        if (rowHeight < 1) {
            throw new IllegalArgumentException("rowHeight must be at least 1");
        }
        this.errorCorrection = errorCorrection;
        this.columns = columns;
        this.rows = rows;
        this.rowHeight = rowHeight;
        this.truncated = truncated;
    }

    public static Pdf417Options defaults() {
        return DEFAULTS;
    }

    /** The error correction level, 0 to 8: 2 to 512 error correction codewords. */
    public Pdf417Options errorCorrection(int level) {
        return new Pdf417Options(level, columns, rows, rowHeight, truncated);
    }

    /** The data columns, 1 to 30, or 0 to choose them with the rows. */
    public Pdf417Options columns(int columns) {
        return new Pdf417Options(errorCorrection, columns, rows, rowHeight, truncated);
    }

    /** The rows, 3 to 90, or 0 to choose them with the columns. */
    public Pdf417Options rows(int rows) {
        return new Pdf417Options(errorCorrection, columns, rows, rowHeight, truncated);
    }

    /**
     * The height of a row in modules. ISO/IEC 15438 asks for at least 3, which is the default;
     * ZXing writes 4.
     */
    public Pdf417Options rowHeight(int modules) {
        return new Pdf417Options(errorCorrection, columns, rows, modules, truncated);
    }

    /** Truncated PDF417: the right row indicators and the stop pattern left out for a narrower symbol. */
    public Pdf417Options truncated(boolean truncated) {
        return new Pdf417Options(errorCorrection, columns, rows, rowHeight, truncated);
    }

    public int errorCorrection() {
        return errorCorrection;
    }

    public int columns() {
        return columns;
    }

    public int rows() {
        return rows;
    }

    public int rowHeight() {
        return rowHeight;
    }

    public boolean truncated() {
        return truncated;
    }
}
