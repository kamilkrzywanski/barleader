package io.github.kamilkrzywanski.barcode.qr;

/** The data codewords of one block and their error correction codewords (ZXing's {@code BlockPair}). */
record BlockPair(byte[] dataBytes, byte[] errorCorrectionBytes) {
}
