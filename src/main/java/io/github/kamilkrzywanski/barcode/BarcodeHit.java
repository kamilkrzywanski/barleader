/*
 * Copyright 2026 Kamil Krzywański
 */
// SPDX-License-Identifier: Apache-2.0
package io.github.kamilkrzywanski.barcode;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * One decoded barcode.
 *
 * @param text                decoded payload
 * @param symbology           symbology that produced {@code text}
 * @param corners             polygon in the source image, possibly empty when the decoder
 *                            did not return points
 * @param orientationDegrees  clockwise degrees with Y pointing down, or {@code -1} when unknown
 * @param confidence          heuristic in {@code [0, 1]}, higher for cheaper successful attempts
 * @param attempt             diagnostic name of the attempt that produced this hit
 * @param bytes               the content as the symbol stores it, before any character set is
 *                            applied: for QR Code, Micro QR, Data Matrix, Aztec and PDF417 the
 *                            data bytes without ECI designators, so binary content survives; for
 *                            the other symbologies the characters of {@code text}. Never null.
 * @param addOn               the 2- or 5-digit EAN/UPC supplement when
 *                            {@link ReadOptions#eanAddOn} asked for it and one was read, else empty
 */
public record BarcodeHit(
        String text,
        Symbology symbology,
        List<ImagePoint> corners,
        int orientationDegrees,
        double confidence,
        String attempt,
        byte[] bytes,
        String addOn
) {
    public BarcodeHit {
        text = Objects.requireNonNull(text, "text");
        symbology = Objects.requireNonNull(symbology, "symbology");
        corners = corners == null ? List.of() : List.copyOf(corners);
        attempt = attempt == null ? "" : attempt;
        bytes = bytes == null ? textBytes(text) : bytes.clone();
        addOn = addOn == null ? "" : addOn;
        if (orientationDegrees < -1 || orientationDegrees > 359) {
            throw new IllegalArgumentException("orientationDegrees must be -1 or 0..359");
        }
        if (Double.isNaN(confidence) || confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be in [0, 1]");
        }
    }

    /** A hit whose bytes are the characters of {@code text} and that has no add-on. */
    public BarcodeHit(
            String text,
            Symbology symbology,
            List<ImagePoint> corners,
            int orientationDegrees,
            double confidence,
            String attempt
    ) {
        this(text, symbology, corners, orientationDegrees, confidence, attempt, null, "");
    }

    /** A copy of the content bytes. */
    @Override
    public byte[] bytes() {
        return bytes.clone();
    }

    /** Equal when every component is, the bytes by content. */
    @Override
    public boolean equals(Object other) {
        return other instanceof BarcodeHit hit
                && text.equals(hit.text)
                && symbology == hit.symbology
                && corners.equals(hit.corners)
                && orientationDegrees == hit.orientationDegrees
                && Double.compare(confidence, hit.confidence) == 0
                && attempt.equals(hit.attempt)
                && Arrays.equals(bytes, hit.bytes)
                && addOn.equals(hit.addOn);
    }

    @Override
    public int hashCode() {
        return Objects.hash(text, symbology, corners, orientationDegrees, confidence, attempt, Arrays.hashCode(bytes), addOn);
    }

    @Override
    public String toString() {
        return "BarcodeHit[text=" + text
                + ", symbology=" + symbology
                + ", corners=" + corners
                + ", orientationDegrees=" + orientationDegrees
                + ", confidence=" + confidence
                + ", attempt=" + attempt
                + ", bytes=" + bytes.length
                + (addOn.isEmpty() ? "" : ", addOn=" + addOn)
                + "]";
    }

    /** ISO-8859-1 when every character fits in a byte, which holds for every linear reader; else UTF-8. */
    private static byte[] textBytes(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) > 0xFF) {
                return text.getBytes(StandardCharsets.UTF_8);
            }
        }
        return text.getBytes(StandardCharsets.ISO_8859_1);
    }
}
