/*
 * Copyright 2026 Kamil Krzywański
 */
// SPDX-License-Identifier: Apache-2.0
package io.github.kamilkrzywanski.barleader.locate.dm;

import io.github.kamilkrzywanski.barleader.image.BlackMatrix;
import io.github.kamilkrzywanski.barleader.image.GrayU8;
import io.github.kamilkrzywanski.barleader.locate.dm.DmGeometry.Bits;

import java.util.List;

/**
 * The local-average matrix of zxing-cpp that the QR, Micro QR, Aztec and Data Matrix traces of one
 * frame read, built once for all four: none of them writes to it. It also tells whether a frame
 * shows what those detectors start from, a QR finder ring or an Aztec bullseye, in either polarity,
 * which {@link #qrFinders(BlackMatrix)} and {@link #aztecCentres(BlackMatrix)} tell for any black matrix.
 */
public final class TraceMatrix {
    final Bits bits;
    private final int width;
    private final int height;

    private TraceMatrix(Bits bits, int width, int height) {
        this.bits = bits;
        this.width = width;
        this.height = height;
    }

    public static TraceMatrix of(GrayU8 gray) {
        return new TraceMatrix(DmBinarizer.blackMatrix(gray), gray.width, gray.height);
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /** Whether the QR finder search finds a ring on this matrix, as it is or inverted. */
    public boolean qrFinders() {
        return qrFinders(bits);
    }

    /** Whether the Aztec detector finds a bullseye centre on this matrix, as it is or inverted. */
    public boolean aztecCentres() {
        return aztecCentres(bits);
    }

    /** {@link #qrFinders()} of {@code matrix}, which stays as it is. */
    public static boolean qrFinders(BlackMatrix matrix) {
        return matrix != null && matrix.width() >= 3 && matrix.height() >= 3 && qrFinders(Bits.of(matrix));
    }

    /** {@link #aztecCentres()} of {@code matrix}, which stays as it is. */
    public static boolean aztecCentres(BlackMatrix matrix) {
        return matrix != null && matrix.width() >= 11 && matrix.height() >= 11 && aztecCentres(Bits.of(matrix));
    }

    private static boolean qrFinders(Bits bits) {
        return either(bits, b -> !QrDetector.findFinderPatterns(b, true).isEmpty());
    }

    private static boolean aztecCentres(Bits bits) {
        return either(bits, b -> !AztecDetector.findCenters(b).isEmpty());
    }

    private static boolean either(Bits bits, java.util.function.Predicate<Bits> found) {
        if (bits == null) {
            return false;
        }
        if (found.test(bits)) {
            return true;
        }
        Bits inverted = bits.copy();
        inverted.flipAll();
        return found.test(inverted);
    }

    /** The QR codes of the trace, as {@link QrDetector#read(GrayU8)} reads them. */
    public List<QrDetector.Found> qr() {
        return width < 3 || height < 3 ? List.of() : QrDetector.read(bits);
    }

    /** The Micro QR codes, as {@link MicroQrDetector#read(GrayU8)} reads them. */
    public List<MicroQrDetector.Found> microQr() {
        return width < 11 || height < 11 ? List.of() : MicroQrDetector.read(bits);
    }

    /** The Aztec codes, as {@link AztecDetector#read(GrayU8)} reads them. */
    public List<AztecDetector.Found> aztec() {
        return width < 11 || height < 11 ? List.of() : AztecDetector.read(bits);
    }

    /** The Data Matrix symbols, as {@link DataMatrixDetector#read(GrayU8)} reads them. */
    public List<DataMatrixDetector.Found> dataMatrix() {
        return DataMatrixDetector.read(bits);
    }
}
