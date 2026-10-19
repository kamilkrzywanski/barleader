/*
 * Copyright 2026 Kamil Krzywański
 */
// SPDX-License-Identifier: Apache-2.0
package io.github.kamilkrzywanski.barleader;

/**
 * What to do with the 2- or 5-digit supplement printed to the right of an EAN-13, EAN-8, UPC-A or
 * UPC-E symbol, such as the price on a book or the issue number on a magazine. The modes are
 * zxing-cpp's {@code EanAddOnSymbol}. A supplement comes back in {@link BarcodeHit#addOn()}; the
 * text stays the main symbol.
 */
public enum EanAddOn {
    /** Read the main symbol only. The default. */
    IGNORE,
    /** Read the supplement when there is one, and the main symbol either way. */
    READ,
    /**
     * Return an EAN or UPC symbol only together with its supplement. Use it when every label has
     * one, so a frame that shows the main symbol before the supplement is not taken as a read.
     */
    REQUIRE
}
