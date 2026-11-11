/*
 * Copyright 2026 Kamil Krzywański
 */
// SPDX-License-Identifier: Apache-2.0
package io.github.kamilkrzywanski.barleader.locate.dm;

import io.github.kamilkrzywanski.barleader.BarcodeHit;
import io.github.kamilkrzywanski.barleader.ImagePoint;
import io.github.kamilkrzywanski.barleader.Symbology;
import io.github.kamilkrzywanski.barleader.image.BlackMatrix;
import io.github.kamilkrzywanski.barleader.image.PixelMap;
import io.github.kamilkrzywanski.barleader.locate.dm.DmGeometry.Bits;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * QR, Data Matrix, Aztec, and Micro QR on the black matrix this attempt already produced.
 * The finders are not sent back through {@link DmBinarizer}. A white-on-black symbol is the
 * same matrix inverted, still under this attempt's name, matching ZXing {@code ALSO_INVERTED}.
 * Matrix hits are not confirmed on a linear strip.
 */
public final class MatrixReader {
    /**
     * Variants of the ladder that look for a symbol in one polarity. {@code inverted-luma} and the
     * originals keep both, so a white-on-black symbol still has its attempts. On the 16382 snapshot
     * images a second pass on these variants read nothing the others did not.
     */
    private static final Set<String> ONE_POLARITY = Set.of(
            "pad-4", "contrast-stretch", "illumination", "adaptive-mean", "otsu", "scale-2", "downscale-2", "downscale-4");

    /** Runs the detectors one after another on the calling thread. */
    private static final Consumer<List<Runnable>> IN_TURN = tasks -> tasks.forEach(Runnable::run);

    private MatrixReader() {
    }

    /** @param black this attempt's black matrix. It is copied, so a later reader may still invert it. */
    public static List<BarcodeHit> read(
            BlackMatrix black,
            PixelMap toSource,
            String attempt,
            int rotationDegrees,
            double confidence,
            Set<Symbology> symbologies,
            boolean findAll
    ) {
        return read(black, toSource, attempt, rotationDegrees, confidence, symbologies, findAll, null);
    }

    /**
     * {@link #read(BlackMatrix, PixelMap, String, int, double, Set, boolean)} with the detectors
     * handed to {@code runAll}, which runs them side by side, or one after another when it is null.
     * The detectors only read the matrix, and their symbols are put together in the same order as
     * one after another. Side by side, the inverted copy is read together with the matrix as it is,
     * when it would be read after a miss, and counts only as it did then.
     */
    public static List<BarcodeHit> read(
            BlackMatrix black,
            PixelMap toSource,
            String attempt,
            int rotationDegrees,
            double confidence,
            Set<Symbology> symbologies,
            boolean findAll,
            Consumer<List<Runnable>> runAll
    ) {
        if (!wants(symbologies) || black == null || black.height() < 3 || black.width() < 3) {
            return List.of();
        }
        Bits bits = Bits.of(black);
        boolean bothWays = findAll || !ONE_POLARITY.contains(attempt);
        List<BarcodeHit> normal;
        List<BarcodeHit> inverted;
        if (runAll != null && bothWays) {
            Bits flipped = bits.copy();
            flipped.flipAll();
            List<List<BarcodeHit>> asItIs = new ArrayList<>(5);
            List<List<BarcodeHit>> asInverted = new ArrayList<>(5);
            List<Runnable> detectors = new ArrayList<>(10);
            detectors.addAll(detectors(bits, toSource, attempt, rotationDegrees, confidence, symbologies, asItIs, runAll));
            detectors.addAll(detectors(flipped, toSource, attempt, rotationDegrees, confidence, symbologies, asInverted, runAll));
            runAll.accept(detectors);
            normal = joined(asItIs);
            inverted = joined(asInverted);
            if (!findAll && !normal.isEmpty()) {
                return List.of(normal.get(0));
            }
        } else {
            Consumer<List<Runnable>> run = runAll == null ? IN_TURN : runAll;
            normal = decode(bits, toSource, attempt, rotationDegrees, confidence, symbologies, run, runAll);
            if (!findAll && !normal.isEmpty()) {
                return List.of(normal.get(0));
            }
            if (!bothWays) {
                return List.of();
            }
            bits.flipAll();
            inverted = decode(bits, toSource, attempt, rotationDegrees, confidence, symbologies, run, runAll);
        }
        if (!findAll) {
            return inverted.isEmpty() ? List.of() : List.of(inverted.get(0));
        }
        if (inverted.isEmpty()) {
            return normal;
        }
        List<BarcodeHit> all = new ArrayList<>(normal.size() + inverted.size());
        all.addAll(normal);
        all.addAll(inverted);
        return all;
    }

    public static boolean wants(Set<Symbology> symbologies) {
        return symbologies.contains(Symbology.QR_CODE)
                || symbologies.contains(Symbology.DATA_MATRIX)
                || symbologies.contains(Symbology.AZTEC)
                || symbologies.contains(Symbology.MICRO_QR)
                || symbologies.contains(Symbology.RMQR);
    }

    private static List<BarcodeHit> decode(
            Bits bits,
            PixelMap toSource,
            String attempt,
            int rotationDegrees,
            double confidence,
            Set<Symbology> symbologies,
            Consumer<List<Runnable>> runAll,
            Consumer<List<Runnable>> within
    ) {
        List<List<BarcodeHit>> found = new ArrayList<>(5);
        runAll.accept(detectors(bits, toSource, attempt, rotationDegrees, confidence, symbologies, found, within));
        return joined(found);
    }

    /**
     * The detectors asked for, each putting its symbols in a list of its own, in order, in
     * {@code found}; {@code within}, when not null, runs the directions of the Data Matrix trace side by side.
     */
    private static List<Runnable> detectors(
            Bits bits,
            PixelMap toSource,
            String attempt,
            int rotationDegrees,
            double confidence,
            Set<Symbology> symbologies,
            List<List<BarcodeHit>> found,
            Consumer<List<Runnable>> within
    ) {
        List<Runnable> detectors = new ArrayList<>(5);
        if (symbologies.contains(Symbology.QR_CODE)) {
            detectors.add(detector(found, hits -> {
                for (QrDetector.Found symbol : QrDetector.read(bits)) {
                    add(hits, symbol.text(), symbol.bytes(), Symbology.QR_CODE, symbol.corners(), symbol.orientationDegrees(),
                            toSource, attempt, rotationDegrees, confidence);
                }
            }));
        }
        if (symbologies.contains(Symbology.DATA_MATRIX)) {
            detectors.add(detector(found, hits -> {
                for (DataMatrixDetector.Found symbol : DataMatrixDetector.read(bits, within)) {
                    add(hits, symbol.text(), symbol.bytes(), Symbology.DATA_MATRIX, symbol.corners(), symbol.orientationDegrees(),
                            toSource, attempt, rotationDegrees, confidence);
                }
            }));
        }
        if (symbologies.contains(Symbology.AZTEC)) {
            detectors.add(detector(found, hits -> {
                for (AztecDetector.Found symbol : AztecDetector.read(bits)) {
                    add(hits, symbol.text(), symbol.bytes(), Symbology.AZTEC, symbol.corners(), symbol.orientationDegrees(),
                            toSource, attempt, rotationDegrees, confidence);
                }
            }));
        }
        if (symbologies.contains(Symbology.MICRO_QR)) {
            detectors.add(detector(found, hits -> {
                for (MicroQrDetector.Found symbol : MicroQrDetector.read(bits)) {
                    add(hits, symbol.text(), symbol.bytes(), Symbology.MICRO_QR, symbol.corners(), symbol.orientationDegrees(),
                            toSource, attempt, rotationDegrees, confidence);
                }
            }));
        }
        if (symbologies.contains(Symbology.RMQR)) {
            detectors.add(detector(found, hits -> {
                for (RmqrDetector.Found symbol : RmqrDetector.read(bits)) {
                    add(hits, symbol.text(), symbol.bytes(), Symbology.RMQR, symbol.corners(), symbol.orientationDegrees(),
                            toSource, attempt, rotationDegrees, confidence);
                }
            }));
        }
        return detectors;
    }

    private static List<BarcodeHit> joined(List<List<BarcodeHit>> found) {
        List<BarcodeHit> hits = new ArrayList<>();
        for (List<BarcodeHit> part : found) {
            hits.addAll(part);
        }
        return hits;
    }

    /** A detector that puts its symbols in a list of its own, at its place in {@code found}. */
    private static Runnable detector(List<List<BarcodeHit>> found, Consumer<List<BarcodeHit>> read) {
        List<BarcodeHit> hits = new ArrayList<>();
        found.add(hits);
        return () -> read.accept(hits);
    }

    private static void add(
            List<BarcodeHit> hits,
            String text,
            byte[] bytes,
            Symbology symbology,
            List<ImagePoint> corners,
            int orientationDegrees,
            PixelMap toSource,
            String attempt,
            int rotationDegrees,
            double confidence
    ) {
        if (text == null || text.isBlank()) {
            return;
        }
        List<ImagePoint> mapped = new ArrayList<>(corners.size());
        for (ImagePoint corner : corners) {
            ImagePoint source = toSource.toSource(corner.x(), corner.y());
            if (source != null && Double.isFinite(source.x()) && Double.isFinite(source.y())) {
                mapped.add(source);
            }
        }
        int orientation = rotationDegrees == 0
                ? orientationDegrees
                : Math.floorMod(orientationDegrees + rotationDegrees, 360);
        hits.add(new BarcodeHit(text, symbology, mapped, orientation, confidence, attempt, bytes, ""));
    }
}
