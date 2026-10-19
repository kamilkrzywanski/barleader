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
    /**
     * rMQR, the rectangular Micro QR of ISO/IEC 23941. Experimental and lightly tested: read only
     * when asked for, tried on zxing-cpp's three samples and on rotated, scaled and blurred copies
     * of them.
     */
    RMQR(false),
    DATA_MATRIX(false),
    AZTEC(false),
    PDF_417(false),
    /**
     * MicroPDF417, ISO/IEC 24728. Experimental and lightly tested: read only when asked for, tried
     * on zxing-cpp's ten samples.
     */
    MICRO_PDF417(false),
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
    DATABAR_EXPANDED(true),
    /**
     * MaxiCode. Experimental and lightly tested: read only from an image that holds the symbol
     * alone, upright and filling it, as zxing-cpp reads it; there is no detector for photos, and
     * the corners of a hit are those of the whole image.
     */
    MAXICODE(false),
    /**
     * Telepen, full ASCII and compressed numeric. Experimental and lightly tested: tried on a
     * handful of samples, and the corners of a hit are those of the whole image.
     */
    TELEPEN(true),
    /**
     * DX film edge, the DX number on the edge of 35 mm film, such as {@code 115-10/11A}.
     * Experimental and lightly tested: tried on three samples, one of which reads a different
     * frame number than zxing-cpp's test expects; the corners of a hit are those of the whole image.
     */
    DX_FILM_EDGE(true);

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
     * family is read only when asked for too: it scans every row of every attempt. MaxiCode,
     * Telepen, DX film edge, rMQR and MicroPDF417 are experimental and read only when asked for.
     */
    public static Set<Symbology> defaults() {
        return EnumSet.copyOf(DEFAULTS);
    }

    /** One-dimensional symbologies. Matrix and stacked 2D codes return false. */
    public boolean linear() {
        return linear;
    }

    /** QR, Micro QR, rMQR, Data Matrix, Aztec, PDF417, MicroPDF417, and MaxiCode. */
    public boolean twoDimensional() {
        return !linear;
    }
}
