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
    CODABAR(true);

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
     * only when asked for, because short random bars match them more easily.
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
