/*
 * Copyright 2026 Kamil Krzywański
 */
// SPDX-License-Identifier: Apache-2.0
package io.github.kamilkrzywanski.barleader.locate.dm;

import io.github.kamilkrzywanski.barleader.image.BlackMatrix;
import io.github.kamilkrzywanski.barleader.image.GrayU8;
import io.github.kamilkrzywanski.barleader.locate.dm.DmGeometry.Bits;
import io.github.kamilkrzywanski.barleader.locate.dm.QrConcentric.Ring;

import java.util.ArrayList;
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
    /** The QR finder patterns and the Aztec centres of {@link #bits}, found once for the signs and the traces. */
    private List<Ring> rings;
    private List<Ring> centres;

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
        return bits != null && (!rings().isEmpty() || inverted(b -> !QrDetector.findFinderPatterns(b, true).isEmpty()));
    }

    /** Whether the Aztec detector finds a bullseye centre on this matrix, as it is or inverted. */
    public boolean aztecCentres() {
        return bits != null && (!centres().isEmpty() || inverted(b -> !AztecDetector.findCenters(b).isEmpty()));
    }

    /** The QR finder patterns of the matrix as it is, which the signs, the QR and the Micro QR trace all look for. */
    private synchronized List<Ring> rings() {
        if (rings == null) {
            rings = bits == null ? List.of() : QrDetector.findFinderPatterns(bits, true);
        }
        return rings;
    }

    /** The Aztec centres of the matrix as it is, which the sign and the Aztec trace both look for. */
    private synchronized List<Ring> centres() {
        if (centres == null) {
            centres = bits == null ? List.of() : AztecDetector.findCenters(bits);
        }
        return centres;
    }

    /** Whether the matrix is there and at least {@code side} modules each way, as the detectors ask. */
    private boolean atLeast(int side) {
        return bits != null && bits.width >= side && bits.height >= side;
    }

    private boolean inverted(java.util.function.Predicate<Bits> found) {
        Bits inverted = bits.copy();
        inverted.flipAll();
        return found.test(inverted);
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
        // The QR detector sorts the patterns it is given: it gets its own list.
        return width < 3 || height < 3 || !atLeast(3) ? List.of() : QrDetector.read(bits, new ArrayList<>(rings()));
    }

    /** The Micro QR codes, as {@link MicroQrDetector#read(GrayU8)} reads them. */
    public List<MicroQrDetector.Found> microQr() {
        return width < 11 || height < 11 || !atLeast(11) ? List.of() : MicroQrDetector.read(bits, new ArrayList<>(rings()));
    }

    /** The Aztec codes, as {@link AztecDetector#read(GrayU8)} reads them. */
    public List<AztecDetector.Found> aztec() {
        return width < 11 || height < 11 || !atLeast(11) ? List.of() : AztecDetector.read(bits, centres());
    }

    /** The Data Matrix symbols, as {@link DataMatrixDetector#read(GrayU8)} reads them. */
    public List<DataMatrixDetector.Found> dataMatrix() {
        return DataMatrixDetector.read(bits);
    }

    /** {@link #dataMatrix()} with the directions of the edge trace side by side when {@code runAll} is not null. */
    public List<DataMatrixDetector.Found> dataMatrix(java.util.function.Consumer<List<Runnable>> runAll) {
        return DataMatrixDetector.read(bits, runAll);
    }
}
