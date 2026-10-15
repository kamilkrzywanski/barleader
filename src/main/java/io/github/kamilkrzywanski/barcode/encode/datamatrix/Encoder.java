package io.github.kamilkrzywanski.barcode.encode.datamatrix;

/**
 * Ported from ZXing 3.5.4 {@code com.google.zxing.datamatrix.encoder.Encoder} (Apache License 2.0, the ZXing authors).
 */
interface Encoder {

    int getEncodingMode();

    void encode(EncoderContext context);

}
