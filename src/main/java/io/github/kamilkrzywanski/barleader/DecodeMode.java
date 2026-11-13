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
     * The original image, then the linear symbols the locators found, read from lines and rows of
     * the frame and from their crops, and nothing after that: no variant of the whole frame, no
     * finder trace and none of the steps after a miss. An image without a barcode costs about a
     * third of {@link #FAST}. What only those later steps read stays unread: matrix codes the
     * image as it is does not show clearly, and linear symbols too blurred or too small for the
     * lines, rows and crops.
     */
    QUICK,
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
