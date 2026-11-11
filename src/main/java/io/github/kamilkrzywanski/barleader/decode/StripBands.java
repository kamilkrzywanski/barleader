/*
 * Copyright 2026 Kamil Krzywański
 */
// SPDX-License-Identifier: Apache-2.0
package io.github.kamilkrzywanski.barleader.decode;

import io.github.kamilkrzywanski.barleader.image.GrayU8;

import java.util.List;

/**
 * The gray profiles that {@link Code128Fit} and {@link EanFit} fit on a strip of bars: a band of
 * rows at half its height, then at three and seven tenths, each averaged along the slant of the
 * bars, with the spans of bars on it. Both fits read the same bands of the same strips, so each
 * band is sampled once, when the first fit asks for it.
 */
public final class StripBands {
    private static final double[] AT = {0.5, 0.3, 0.7};

    final GrayU8 strip;
    private final Band[] bands = new Band[AT.length];
    /** One lock a band, so that the bands of one strip are sampled side by side. */
    private final Object[] locks = {new Object(), new Object(), new Object()};

    public StripBands(GrayU8 strip) {
        this.strip = strip;
    }

    /** One band: its middle row, its profile, and the spans of bars on the profile. */
    record Band(int y, double[] profile, List<int[]> spans) {
    }

    static int count() {
        return AT.length;
    }

    /** Band {@code index}, sampled by the first fit that asks; the two fits of a strip, and its bands, may ask side by side. */
    Band band(int index) {
        synchronized (locks[index]) {
            Band band = bands[index];
            if (band == null) {
                int y = (int) (strip.height * AT[index]);
                int half = Math.max(2, strip.height / 10);
                double slope = GrayProfile.shear(strip, y, Math.max(3, strip.height / 5), half);
                double[] profile = GrayProfile.profile(strip, y, half, slope);
                band = new Band(y, profile, GrayProfile.spans(profile));
                bands[index] = band;
            }
            return band;
        }
    }

    /** Bands {@code from} to {@code to}, sampled side by side by {@code runAll}. */
    Band[] bands(int from, int to, java.util.function.Consumer<List<Runnable>> runAll) {
        Band[] out = new Band[to - from];
        List<Runnable> sampled = new java.util.ArrayList<>(out.length);
        for (int index = from; index < to; index++) {
            int band = index;
            sampled.add(() -> out[band - from] = band(band));
        }
        runAll.accept(sampled);
        return out;
    }

    /**
     * Both directions of every span of {@code bands}, searched side by side by {@code runAll}:
     * per span, in order, the profile it covers and the two results of {@code search}.
     */
    static <F> List<Searched<F>> searched(Band[] bands, java.util.function.Function<double[], F> search,
                                          java.util.function.Consumer<List<Runnable>> runAll) {
        List<Searched<F>> spans = new java.util.ArrayList<>();
        List<Runnable> searches = new java.util.ArrayList<>();
        for (Band band : bands) {
            for (int[] span : band.spans()) {
                double[] forward = java.util.Arrays.copyOfRange(band.profile(), span[0], span[1]);
                Searched<F> searched = new Searched<>(band, span, forward);
                spans.add(searched);
                searches.add(() -> searched.ahead = search.apply(forward));
                searches.add(() -> searched.behind = search.apply(GrayProfile.reversed(forward)));
            }
        }
        runAll.accept(searches);
        return spans;
    }

    /** A span of a band, its profile, and what the search found reading it each way round. */
    static final class Searched<F> {
        final Band band;
        final int[] span;
        final double[] forward;
        F ahead;
        F behind;

        Searched(Band band, int[] span, double[] forward) {
            this.band = band;
            this.span = span;
            this.forward = forward;
        }
    }
}
