package org.krzywanski.barcode;

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
 */
public record BarcodeHit(
        String text,
        Symbology symbology,
        List<ImagePoint> corners,
        int orientationDegrees,
        double confidence,
        String attempt
) {
    public BarcodeHit {
        text = Objects.requireNonNull(text, "text");
        symbology = Objects.requireNonNull(symbology, "symbology");
        corners = corners == null ? List.of() : List.copyOf(corners);
        attempt = attempt == null ? "" : attempt;
        if (orientationDegrees < -1 || orientationDegrees > 359) {
            throw new IllegalArgumentException("orientationDegrees must be -1 or 0..359");
        }
        if (Double.isNaN(confidence) || confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be in [0, 1]");
        }
    }
}
