package io.github.kamilkrzywanski.barcode;

import com.google.zxing.BarcodeFormat;

import java.util.EnumSet;
import java.util.Set;

/**
 * Symbologies this library will search for. The ZXing format is an implementation detail:
 * Micro QR has no ZXing format. The ported reader decodes it.
 */
public enum Symbology {
    QR_CODE(BarcodeFormat.QR_CODE, false),
    MICRO_QR(null, false),
    DATA_MATRIX(BarcodeFormat.DATA_MATRIX, false),
    AZTEC(BarcodeFormat.AZTEC, false),
    PDF_417(BarcodeFormat.PDF_417, false),
    CODE_128(BarcodeFormat.CODE_128, true),
    CODE_39(BarcodeFormat.CODE_39, true),
    CODE_93(BarcodeFormat.CODE_93, true),
    EAN_13(BarcodeFormat.EAN_13, true),
    EAN_8(BarcodeFormat.EAN_8, true),
    UPC_A(BarcodeFormat.UPC_A, true),
    UPC_E(BarcodeFormat.UPC_E, true),
    ITF(BarcodeFormat.ITF, true),
    CODABAR(BarcodeFormat.CODABAR, true);

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

    private final BarcodeFormat zxing;
    private final boolean linear;

    Symbology(BarcodeFormat zxing, boolean linear) {
        this.zxing = zxing;
        this.linear = linear;
    }

    /**
     * Priority set from the spec: QR, Data Matrix, Code 128, PDF417, EAN/UPC, Code 39,
     * plus Aztec and Micro QR.
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

    public BarcodeFormat zxing() {
        return zxing;
    }

    public static Symbology fromZxing(BarcodeFormat format) {
        for (Symbology symbology : values()) {
            if (symbology.zxing == format) {
                return symbology;
            }
        }
        return null;
    }
}
