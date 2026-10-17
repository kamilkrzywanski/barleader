/*
 * Copyright 2008 ZXing authors
 * Copyright 2026 Kamil Krzywański
 */
// SPDX-License-Identifier: Apache-2.0
package io.github.kamilkrzywanski.barcode.qr;

/** The data codewords of one block and their error correction codewords (ZXing's {@code BlockPair}). */
record BlockPair(byte[] dataBytes, byte[] errorCorrectionBytes) {
}
