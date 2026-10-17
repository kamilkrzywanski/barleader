/*
 * Copyright 2026 Kamil Krzywański
 */
// SPDX-License-Identifier: Apache-2.0
package io.github.kamilkrzywanski.barcode;

import java.nio.charset.Charset;
import java.util.Objects;

/**
 * How {@link BarcodeWriter} writes. Immutable; every setter returns a copy. A setting that does not
 * apply to a symbology is ignored.
 */
public final class WriteOptions {
    private static final WriteOptions DEFAULTS = new WriteOptions(-1, 2.5, false, null, null, false, QrOptions.defaults(), DataMatrixShape.ANY,
            Pdf417Options.defaults(), AztecOptions.defaults());

    private final int quietZone;
    private final double wideRatio;
    private final boolean checksum;
    private final String addOn;
    private final Charset charset;
    private final boolean compact;
    private final QrOptions qr;
    private final DataMatrixShape dataMatrixShape;
    private final Pdf417Options pdf417;
    private final AztecOptions aztec;

    private WriteOptions(int quietZone, double wideRatio, boolean checksum, String addOn, Charset charset, boolean compact,
                         QrOptions qr, DataMatrixShape dataMatrixShape, Pdf417Options pdf417, AztecOptions aztec) {
        if (quietZone < -1) {
            throw new IllegalArgumentException("quietZone must be >= 0, or -1 for the symbology's own");
        }
        if (!(wideRatio >= 2.0 && wideRatio <= 3.0)) {
            throw new IllegalArgumentException("wideRatio must be from 2.0 to 3.0");
        }
        this.quietZone = quietZone;
        this.wideRatio = wideRatio;
        this.checksum = checksum;
        this.addOn = addOn;
        this.charset = charset;
        this.compact = compact;
        this.qr = Objects.requireNonNull(qr, "qr");
        this.dataMatrixShape = Objects.requireNonNull(dataMatrixShape, "dataMatrixShape");
        this.pdf417 = Objects.requireNonNull(pdf417, "pdf417");
        this.aztec = Objects.requireNonNull(aztec, "aztec");
    }

    public static WriteOptions defaults() {
        return DEFAULTS;
    }

    /**
     * The light modules on each side, -1 for the symbology's own: 11 and 7 for EAN-13, 9 for UPC-A,
     * 7 for EAN-8, 9 and 7 for UPC-E, ten narrow elements for the other linear codes, 4 for QR Code,
     * 1 for Data Matrix, 2 for PDF417 and none for Aztec, whose finder needs none.
     */
    public WriteOptions quietZone(int modules) {
        return new WriteOptions(modules, wideRatio, checksum, addOn, charset, compact, qr, dataMatrixShape, pdf417, aztec);
    }

    /**
     * The width of a wide element to a narrow one, from 2.0 to 3.0, for Code 39, PZN, Codabar,
     * Code 11 and Interleaved 2 of 5; 2.5 by default, which is two and five modules.
     */
    public WriteOptions wideRatio(double ratio) {
        return new WriteOptions(quietZone, ratio, checksum, addOn, charset, compact, qr, dataMatrixShape, pdf417, aztec);
    }

    /** The optional check characters: Code 39's modulo 43 character, Code 11's C and K. */
    public WriteOptions checksum(boolean checksum) {
        return new WriteOptions(quietZone, wideRatio, checksum, addOn, charset, compact, qr, dataMatrixShape, pdf417, aztec);
    }

    /** A 2- or 5-digit add-on after EAN-13, EAN-8, UPC-A, UPC-E, ISBN-13 or ISMN; null for none. */
    public WriteOptions addOn(String digits) {
        return new WriteOptions(quietZone, wideRatio, checksum, digits, charset, compact, qr, dataMatrixShape, pdf417, aztec);
    }

    /** The character set of text in a 2D symbol, announced with its ECI; null chooses from the text. */
    public WriteOptions charset(Charset charset) {
        return new WriteOptions(quietZone, wideRatio, checksum, addOn, charset, compact, qr, dataMatrixShape, pdf417, aztec);
    }

    /**
     * The fewest symbols or bits, where the symbology has a minimal encoder: Code 128, QR Code and
     * Data Matrix; PDF417 then splits the text among character sets for the fewest bytes.
     */
    public WriteOptions compact(boolean compact) {
        return new WriteOptions(quietZone, wideRatio, checksum, addOn, charset, compact, qr, dataMatrixShape, pdf417, aztec);
    }

    /** The QR Code settings: error correction level, version and mask. */
    public WriteOptions qr(QrOptions qr) {
        return new WriteOptions(quietZone, wideRatio, checksum, addOn, charset, compact, qr, dataMatrixShape, pdf417, aztec);
    }

    /** The shape of a Data Matrix: square, rectangular, or the smallest of either. */
    public WriteOptions dataMatrixShape(DataMatrixShape shape) {
        return new WriteOptions(quietZone, wideRatio, checksum, addOn, charset, compact, qr, shape, pdf417, aztec);
    }

    /** The PDF417 settings: error correction level, columns, rows, row height and truncation. */
    public WriteOptions pdf417(Pdf417Options pdf417) {
        return new WriteOptions(quietZone, wideRatio, checksum, addOn, charset, compact, qr, dataMatrixShape, pdf417, aztec);
    }

    /** The Aztec settings: error correction and size. */
    public WriteOptions aztec(AztecOptions aztec) {
        return new WriteOptions(quietZone, wideRatio, checksum, addOn, charset, compact, qr, dataMatrixShape, pdf417, aztec);
    }

    public int quietZone() {
        return quietZone;
    }

    public double wideRatio() {
        return wideRatio;
    }

    public boolean checksum() {
        return checksum;
    }

    /** The add-on digits, or null. */
    public String addOn() {
        return addOn;
    }

    /** The character set asked for, or null. */
    public Charset charset() {
        return charset;
    }

    public boolean compact() {
        return compact;
    }

    public QrOptions qr() {
        return qr;
    }

    public DataMatrixShape dataMatrixShape() {
        return dataMatrixShape;
    }

    public Pdf417Options pdf417() {
        return pdf417;
    }

    public AztecOptions aztec() {
        return aztec;
    }
}
