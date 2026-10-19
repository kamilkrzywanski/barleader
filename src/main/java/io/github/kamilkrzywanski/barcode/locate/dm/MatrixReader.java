/*
 * Copyright 2026 Kamil Krzywański
 */
// SPDX-License-Identifier: Apache-2.0
package io.github.kamilkrzywanski.barcode.locate.dm;

import io.github.kamilkrzywanski.barcode.BarcodeHit;
import io.github.kamilkrzywanski.barcode.ImagePoint;
import io.github.kamilkrzywanski.barcode.Symbology;
import io.github.kamilkrzywanski.barcode.image.BlackMatrix;
import io.github.kamilkrzywanski.barcode.image.PixelMap;
import io.github.kamilkrzywanski.barcode.locate.dm.DmGeometry.Bits;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * QR, Data Matrix, Aztec, and Micro QR on the black matrix this attempt already produced.
 * The finders are not sent back through {@link DmBinarizer}. A white-on-black symbol is the
 * same matrix inverted, still under this attempt's name, matching ZXing {@code ALSO_INVERTED}.
 * Matrix hits are not confirmed on a linear strip.
 */
public final class MatrixReader {
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
        if (!wants(symbologies) || black == null || black.height() < 3 || black.width() < 3) {
            return List.of();
        }
        Bits bits = Bits.of(black);
        List<BarcodeHit> normal = decode(bits, toSource, attempt, rotationDegrees, confidence, symbologies);
        if (!findAll && !normal.isEmpty()) {
            return List.of(normal.get(0));
        }
        bits.flipAll();
        List<BarcodeHit> inverted = decode(bits, toSource, attempt, rotationDegrees, confidence, symbologies);
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
            Set<Symbology> symbologies
    ) {
        List<BarcodeHit> hits = new ArrayList<>();
        if (symbologies.contains(Symbology.QR_CODE)) {
            for (QrDetector.Found found : QrDetector.read(bits)) {
                add(hits, found.text(), found.bytes(), Symbology.QR_CODE, found.corners(), found.orientationDegrees(),
                        toSource, attempt, rotationDegrees, confidence);
            }
        }
        if (symbologies.contains(Symbology.DATA_MATRIX)) {
            for (DataMatrixDetector.Found found : DataMatrixDetector.read(bits)) {
                add(hits, found.text(), found.bytes(), Symbology.DATA_MATRIX, found.corners(), found.orientationDegrees(),
                        toSource, attempt, rotationDegrees, confidence);
            }
        }
        if (symbologies.contains(Symbology.AZTEC)) {
            for (AztecDetector.Found found : AztecDetector.read(bits)) {
                add(hits, found.text(), found.bytes(), Symbology.AZTEC, found.corners(), found.orientationDegrees(),
                        toSource, attempt, rotationDegrees, confidence);
            }
        }
        if (symbologies.contains(Symbology.MICRO_QR)) {
            for (MicroQrDetector.Found found : MicroQrDetector.read(bits)) {
                add(hits, found.text(), found.bytes(), Symbology.MICRO_QR, found.corners(), found.orientationDegrees(),
                        toSource, attempt, rotationDegrees, confidence);
            }
        }
        if (symbologies.contains(Symbology.RMQR)) {
            for (RmqrDetector.Found found : RmqrDetector.read(bits)) {
                add(hits, found.text(), found.bytes(), Symbology.RMQR, found.corners(), found.orientationDegrees(),
                        toSource, attempt, rotationDegrees, confidence);
            }
        }
        return hits;
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
