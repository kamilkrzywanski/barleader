/*
 * Copyright 2026 Kamil Krzywański
 */
// SPDX-License-Identifier: Apache-2.0
package io.github.kamilkrzywanski.barcode;

import java.util.EnumSet;
import java.util.Set;

/** Symbologies this library can search for. */
public enum Symbology {
    QR_CODE(false),
    MICRO_QR(false),
    DATA_MATRIX(false),
    AZTEC(false),
    PDF_417(false),
    CODE_128(true),
    CODE_39(true),
    CODE_93(true),
    EAN_13(true),
    EAN_8(true),
    UPC_A(true),
    UPC_E(true),
    ITF(true),
    CODABAR(true),
    /**
     * GS1 DataBar Omnidirectional, Truncated, Stacked and Stacked Omnidirectional. The text is the
     * element string, such as {@code 0104412345678909}.
     */
    DATABAR(true),
    /** GS1 DataBar Limited. */
    DATABAR_LIMITED(true),
    /** GS1 DataBar Expanded and Expanded Stacked. The text is the element string without parentheses. */
    DATABAR_EXPANDED(true);

    private static final EnumSet<Symbology> DEFAULTS = EnumSet.of(
            QR_CODE,
            MICRO_QR,
            DATA_MATRIX,
            AZTEC,
            PDF_417,
            CODE_128,
            CODE_39,
            EAN_13,
            EAN_8,
            UPC_A,
            UPC_E
    );

    private final boolean linear;

    Symbology(boolean linear) {
        this.linear = linear;
    }

    /**
     * The set {@link ReadOptions} starts with: QR, Micro QR, Data Matrix, Aztec, PDF417,
     * Code 128, Code 39, EAN-13, EAN-8, UPC-A and UPC-E. Code 93, ITF and Codabar are read
     * only when asked for, because short random bars match them more easily. The DataBar
     * family is read only when asked for too: it scans every row of every attempt.
     */
    public static Set<Symbology> defaults() {
        return EnumSet.copyOf(DEFAULTS);
    }

    /** One-dimensional symbologies. Matrix and stacked 2D codes return false. */
    public boolean linear() {
        return linear;
    }

    /** QR, Micro QR, Data Matrix, Aztec, and PDF417. */
    public boolean twoDimensional() {
        return !linear;
    }
}
