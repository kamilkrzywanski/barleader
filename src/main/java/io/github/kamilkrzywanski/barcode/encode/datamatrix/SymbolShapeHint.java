package io.github.kamilkrzywanski.barcode.encode.datamatrix;

/**
 * Enumeration for DataMatrix symbol shape hint. It can be used to force square or rectangular
 * symbols.
 *
 * <p>Ported from ZXing 3.5.4 {@code com.google.zxing.datamatrix.encoder.SymbolShapeHint} (Apache License 2.0, the ZXing authors).
 */
public enum SymbolShapeHint {

    FORCE_NONE,
    FORCE_SQUARE,
    FORCE_RECTANGLE,

}
