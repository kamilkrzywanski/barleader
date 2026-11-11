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

    public StripBands(GrayU8 strip) {
        this.strip = strip;
    }

    /** One band: its middle row, its profile, and the spans of bars on the profile. */
    record Band(int y, double[] profile, List<int[]> spans) {
    }

    static int count() {
        return AT.length;
    }

    /** Band {@code index}, sampled by the first fit that asks; the two fits of a strip may ask side by side. */
    synchronized Band band(int index) {
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
