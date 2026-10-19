/*
 * Copyright 2026 Kamil Krzywański
 */
// SPDX-License-Identifier: Apache-2.0
package io.github.kamilkrzywanski.barleader;

/**
 * How many image variants {@link BarcodeReader} is willing to try.
 */
public enum DecodeMode {
    /**
     * Original image, a short preprocessing ladder, orthogonal rotations, and the
     * QR, Micro QR, Aztec, and Data Matrix finders.
     */
    FAST,
    /**
     * Everything in {@link #FAST}, plus Sauvola and Gaussian thresholds, morphology,
     * small-angle rotations, extra scales, and quadrilateral Data Matrix candidates.
     */
    AGGRESSIVE
}
