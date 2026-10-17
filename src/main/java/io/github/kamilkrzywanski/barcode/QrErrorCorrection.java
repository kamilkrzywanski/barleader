/*
 * Copyright 2026 Kamil Krzywański
 */
// SPDX-License-Identifier: Apache-2.0
package io.github.kamilkrzywanski.barcode;

/**
 * How much of a QR Code can be damaged and still read. A higher level makes a larger symbol for the
 * same data.
 */
public enum QrErrorCorrection {
    /** About 7% of the codewords. */
    L,
    /** About 15% of the codewords; the default. */
    M,
    /** About 25% of the codewords. */
    Q,
    /** About 30% of the codewords. */
    H
}
