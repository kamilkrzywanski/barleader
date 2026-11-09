/*
 * Copyright 2026 Kamil Krzywański
 */
// SPDX-License-Identifier: Apache-2.0
package io.github.kamilkrzywanski.barleader;

import io.github.kamilkrzywanski.barleader.image.GrayU8;

import java.util.Arrays;

/**
 * Whether the frame shows no bar just past either end of a UPC-E read on a crop. The decoders take
 * the end of an image for a quiet zone, and a crop of {@link BarSegments} can end, or be padded
 * white, at the middle guard of an EAN-13 or UPC-A: its left half then reads as a UPC-E, whose end
 * guard is the middle guard and the first bar after it. Five lines along the scan line, beside it by
 * up to 8 percent of its length, are sampled from the frame, and their median is looked at from 1.5
 * to 8 modules past each end. A bar there is darker than halfway between the symbol's bars and
 * spaces, more than a third of that contrast below the light around it, and has light again within
 * 4 modules past it: the dark edge of a label, or a collage's background, stays dark. Every digit of
 * the right half puts a bar within 5 modules; a blurred one may show only the wider bars after it.
 */
final class QuietZone {
    private static final double[] OFFSETS = {-0.08, -0.04, 0, 0.04, 0.08};
    /** Modules past an end where the zone starts, clear of the blur of the last bar. */
    private static final double FROM = 1.5;
    /** Modules past an end where the zone stops. */
    private static final double TO = 8;
    /** Modules past a bar within which the frame is light again; no bar of an EAN is wider. */
    private static final double BEYOND = 4;
    /** Modules on each side of a sample over which the light around it is taken. */
    private static final double AROUND = 2;
    /** A dip this deep below the light around it, in parts of the symbol's contrast, is a bar. */
    private static final double DIP = 0.35;
    /** Below this contrast between the bars and the spaces of the symbol nothing is judged. */
    private static final double MIN_CONTRAST = 24;
    /** Samples a module. */
    private static final int SAMPLES = 4;

    private QuietZone() {
    }

    /**
     * Whether no bar lies past either end of the scan line from ({@code x0}, {@code y0}) to
     * ({@code x1}, {@code y1}) of {@code gray}, a symbol {@code modules} modules long between the
     * outer edges of its guards. True also when the line is too short or the symbol too faint
     * to tell.
     */
    static boolean clear(GrayU8 gray, double x0, double y0, double x1, double y1, int modules) {
        double length = Math.hypot(x1 - x0, y1 - y0);
        if (!(length >= 2 * modules / 3.0) || length > 8192) {
            return true;
        }
        double step = length / modules / SAMPLES;
        double ux = (x1 - x0) / length;
        double uy = (y1 - y0) / length;
        int reach = (int) Math.ceil((TO + Math.max(AROUND, BEYOND)) * SAMPLES);
        int inside = modules * SAMPLES;
        double[] profile = new double[inside + 1 + 2 * reach];
        double[] column = new double[OFFSETS.length];
        for (int i = 0; i < profile.length; i++) {
            double t = (i - reach) * step;
            int count = 0;
            for (double offset : OFFSETS) {
                double v = bilinear(gray, x0 + ux * t - uy * offset * length, y0 + uy * t + ux * offset * length);
                if (!Double.isNaN(v)) {
                    column[count++] = v;
                }
            }
            if (count == 0) {
                profile[i] = Double.NaN;
            } else {
                Arrays.sort(column, 0, count);
                profile[i] = count % 2 == 1 ? column[count / 2] : (column[count / 2 - 1] + column[count / 2]) / 2;
            }
        }
        double[] symbol = Arrays.stream(profile, reach, reach + inside + 1).filter(v -> !Double.isNaN(v)).sorted().toArray();
        if (symbol.length < inside / 2) {
            return true;
        }
        double light = symbol[symbol.length * 9 / 10];
        double dark = symbol[symbol.length / 10];
        double contrast = light - dark;
        if (contrast < MIN_CONTRAST) {
            return true;
        }
        double[] smooth = new double[profile.length];
        for (int i = 0; i < profile.length; i++) {
            double sum = 0;
            int count = 0;
            for (int k = Math.max(0, i - 1); k <= Math.min(profile.length - 1, i + 1); k++) {
                if (!Double.isNaN(profile[k])) {
                    sum += profile[k];
                    count++;
                }
            }
            smooth[i] = count == 0 ? Double.NaN : sum / count;
        }
        int from = (int) Math.round(FROM * SAMPLES);
        int to = (int) Math.round(TO * SAMPLES);
        int around = (int) Math.round(AROUND * SAMPLES);
        int beyond = (int) Math.round(BEYOND * SAMPLES);
        for (int side : new int[]{-1, 1}) {
            int edge = side < 0 ? reach : reach + inside;
            for (int d = from; d <= to; d++) {
                int i = edge + side * d;
                if (Double.isNaN(smooth[i]) || smooth[i] >= dark + contrast / 2) {
                    continue;
                }
                double envelope = smooth[i];
                for (int k = Math.max(0, i - around); k <= Math.min(profile.length - 1, i + around); k++) {
                    if (smooth[k] > envelope) {
                        envelope = smooth[k];
                    }
                }
                if (envelope - smooth[i] <= DIP * contrast) {
                    continue;
                }
                for (int k = d + 1; k <= d + beyond; k++) {
                    double v = smooth[edge + side * k];
                    if (v > dark + 0.6 * contrast) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /** The gray value at ({@code x}, {@code y}), or NaN outside the image. */
    private static double bilinear(GrayU8 g, double x, double y) {
        if (!(x >= 0 && y >= 0 && x <= g.width - 1 && y <= g.height - 1)) {
            return Double.NaN;
        }
        int ix = (int) x;
        int iy = (int) y;
        int x1 = Math.min(ix + 1, g.width - 1);
        int y1 = Math.min(iy + 1, g.height - 1);
        double fx = x - ix;
        double fy = y - iy;
        int row0 = g.startIndex + iy * g.stride;
        int row1 = g.startIndex + y1 * g.stride;
        double top = (g.data[row0 + ix] & 255) * (1 - fx) + (g.data[row0 + x1] & 255) * fx;
        double bottom = (g.data[row1 + ix] & 255) * (1 - fx) + (g.data[row1 + x1] & 255) * fx;
        return top * (1 - fy) + bottom * fy;
    }
}
