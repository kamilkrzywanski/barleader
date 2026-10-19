/*
 * Copyright 2026 Kamil Krzywański
 */
// SPDX-License-Identifier: Apache-2.0
package io.github.kamilkrzywanski.barcode.decode;

import io.github.kamilkrzywanski.barcode.image.GrayU8;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The gray profile across the bars of a strip, in quarter pixels, that {@link Code128Fit} and
 * {@link EanFit} fit their patterns to: rows averaged after undoing the shear of tilted bars, the
 * stretches between quiet zones, and the normal distribution function that blurs a module edge.
 */
final class GrayProfile {
    /** Profile samples per pixel. */
    static final int FINE = 4;
    static final double STEP = 1.0 / FINE;
    /** A quiet zone of a span is at least this many pixels of flat, light profile. */
    private static final double QUIET = 8;
    private static final double MARGIN = 6;
    static final double MIN_SPAN = 40;
    private static final double MAX_SPAN = 1200;
    private static final double[] PHI = phiTable();

    private GrayProfile() {
    }

    /** Horizontal shift of the bars per row, from the profiles {@code distance} rows above and below. */
    static double shear(GrayU8 strip, int y, int distance, int half) {
        int above = y - distance;
        int below = y + distance;
        if (above - half < 0 || below + half >= strip.height) {
            return 0;
        }
        double[] a = profile(strip, above, half, 0);
        double[] b = profile(strip, below, half, 0);
        int reach = (int) Math.ceil(Math.min(2 * distance * 0.07, 12) * FINE);
        double[] score = new double[2 * reach + 1];
        int bestShift = 0;
        for (int shift = -reach; shift <= reach; shift++) {
            int from = Math.max(0, -shift);
            int to = Math.min(a.length, b.length - shift);
            if (to - from < 2) {
                return 0;
            }
            double meanA = 0;
            double meanB = 0;
            for (int i = from; i < to; i++) {
                meanA += a[i];
                meanB += b[i + shift];
            }
            meanA /= to - from;
            meanB /= to - from;
            double covariance = 0;
            for (int i = from; i < to; i++) {
                covariance += (a[i] - meanA) * (b[i + shift] - meanB);
            }
            score[shift + reach] = covariance / (to - from);
            if (score[shift + reach] > score[bestShift + reach]) {
                bestShift = shift;
            }
        }
        double fraction = 0;
        int at = bestShift + reach;
        if (at > 0 && at < score.length - 1) {
            double curvature = score[at - 1] - 2 * score[at] + score[at + 1];
            if (curvature < 0) {
                fraction = 0.5 * (score[at - 1] - score[at + 1]) / curvature;
            }
        }
        return (bestShift + fraction) * STEP / (below - above);
    }

    /** The mean of rows {@code y - half} to {@code y + half}, each shifted by its distance times {@code slope}. */
    static double[] profile(GrayU8 strip, int y, int half, double slope) {
        int count = (strip.width - 1) * FINE;
        double[] out = new double[count];
        int top = Math.max(0, y - half);
        int bottom = Math.min(strip.height - 1, y + half);
        for (int i = 0; i < count; i++) {
            double x = i * STEP;
            double sum = 0;
            int rows = 0;
            for (int row = top; row <= bottom; row++) {
                double shifted = x + (row - y) * slope;
                int x0 = (int) Math.floor(shifted);
                if (x0 < 0 || x0 + 1 >= strip.width) {
                    continue;
                }
                double fraction = shifted - x0;
                sum += strip.get(x0, row) * (1 - fraction) + strip.get(x0 + 1, row) * fraction;
                rows++;
            }
            out[i] = rows > 0 ? sum / rows : i > 0 ? out[i - 1] : 255;
        }
        return out;
    }

    /** Stretches between quiet zones, as sample ranges with some quiet zone on each side. */
    static List<int[]> spans(double[] g) {
        int n = g.length;
        double low = Double.MAX_VALUE;
        double high = -1;
        for (double v : g) {
            low = Math.min(low, v);
            high = Math.max(high, v);
        }
        double contrast = high - low;
        List<int[]> spans = new ArrayList<>();
        if (contrast < 20) {
            return spans;
        }
        int window = (int) (QUIET * FINE);
        boolean[] quiet = new boolean[n];
        for (int i = 0; i + window <= n; i++) {
            double min = Double.MAX_VALUE;
            double max = -1;
            for (int j = i; j < i + window; j++) {
                min = Math.min(min, g[j]);
                max = Math.max(max, g[j]);
            }
            if (max - min < 0.25 * contrast && min > low + 0.5 * contrast) {
                Arrays.fill(quiet, i, i + window, true);
            }
        }
        int margin = (int) (MARGIN * FINE);
        int i = 0;
        while (i < n && !quiet[i]) {
            i++;
        }
        while (i < n) {
            while (i < n && quiet[i]) {
                i++;
            }
            int start = i;
            while (i < n && !quiet[i]) {
                i++;
            }
            if (i >= n) {
                break;
            }
            double width = (i - start) * STEP;
            if (width >= MIN_SPAN && width <= MAX_SPAN) {
                spans.add(new int[]{Math.max(0, start - margin), Math.min(n, i + margin)});
            }
        }
        return spans;
    }

    /** The profile reversed, for reading a symbol upside down. */
    static double[] reversed(double[] forward) {
        double[] backward = new double[forward.length];
        for (int i = 0; i < forward.length; i++) {
            backward[i] = forward[forward.length - 1 - i];
        }
        return backward;
    }

    /** The normal distribution function from -6 to 6 in steps of 0.01. */
    private static double[] phiTable() {
        double[] table = new double[1201];
        for (int i = 0; i < table.length; i++) {
            double z = (i - 600) / 100.0;
            double t = 1 / (1 + 0.2316419 * Math.abs(z));
            double density = 0.3989423 * Math.exp(-z * z / 2);
            double tail = density * t * (0.3193815 + t * (-0.3565638 + t * (1.781478 + t * (-1.821256 + t * 1.330274))));
            table[i] = z > 0 ? 1 - tail : tail;
        }
        return table;
    }

    static double phi(double z) {
        double at = z * 100 + 600;
        if (at <= 0) {
            return 0;
        }
        if (at >= 1200) {
            return 1;
        }
        int i = (int) at;
        return PHI[i] + (PHI[i + 1] - PHI[i]) * (at - i);
    }
}
