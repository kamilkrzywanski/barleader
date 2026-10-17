/*
 * Copyright 2026 Kamil Krzywański
 */
// SPDX-License-Identifier: Apache-2.0
package io.github.kamilkrzywanski.barcode;

/**
 * How {@link BarcodeWriter} writes an Aztec Code. Immutable; every setter returns a copy.
 *
 * <p>By default 23% of the symbol and three codewords are error correction, as ISO/IEC 24778
 * recommends, and the symbol is the smallest that holds the data, compact where it can be.
 */
public final class AztecOptions {
    private static final AztecOptions DEFAULTS = new AztecOptions(23, 0);

    private final int errorCorrection;
    private final int layers;

    private AztecOptions(int errorCorrection, int layers) {
        if (errorCorrection < 5 || errorCorrection > 95) {
            throw new IllegalArgumentException("errorCorrection must be 5 to 95 percent");
        }
        if (layers < -4 || layers > 32) {
            throw new IllegalArgumentException("layers must be -1 to -4 for compact, 1 to 32 for full, or 0 to choose");
        }
        this.errorCorrection = errorCorrection;
        this.layers = layers;
    }

    public static AztecOptions defaults() {
        return DEFAULTS;
    }

    /** The share of the symbol for error correction, 5 to 95 percent; ZXing writes 33. */
    public AztecOptions errorCorrection(int percent) {
        return new AztecOptions(percent, layers);
    }

    /**
     * A fixed size: -1 to -4 for the compact symbols of 15 to 27 modules, 1 to 32 for the full ones
     * of 19 to 151; data that does not fit throws. 0 is the smallest that fits.
     */
    public AztecOptions layers(int layers) {
        return new AztecOptions(errorCorrection, layers);
    }

    public int errorCorrection() {
        return errorCorrection;
    }

    public int layers() {
        return layers;
    }
}
