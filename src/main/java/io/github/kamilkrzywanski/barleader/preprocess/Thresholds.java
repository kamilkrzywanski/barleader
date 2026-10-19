/*
 * Copyright 2026 Kamil Krzywański
 */
// SPDX-License-Identifier: Apache-2.0
package io.github.kamilkrzywanski.barleader.preprocess;

import io.github.kamilkrzywanski.barleader.image.GrayU8;

import java.util.ArrayList;
import java.util.List;

/** Global and local thresholds. Output pixels are 1 when dark and 0 when light. */
public final class Thresholds {
    private Thresholds() {
    }

    public static GrayU8 otsu(GrayU8 gray) {
        return Binarize.otsu(gray);
    }

    public static GrayU8 mean(GrayU8 gray) {
        return Binarize.localMean(gray, radius(gray, 12));
    }

    public static GrayU8 gaussian(GrayU8 gray) {
        return Binarize.localGaussian(gray, radius(gray, 12));
    }

    public static List<GrayU8> sauvola(GrayU8 gray) {
        int wide = radius(gray, 8);
        int narrow = Math.max(15, wide / 2);
        if (narrow % 2 == 0) {
            narrow++;
        }
        List<GrayU8> images = new ArrayList<>(2);
        images.add(Binarize.sauvola(gray, narrow));
        if (wide != narrow) {
            images.add(Binarize.sauvola(gray, wide));
        }
        return images;
    }

    public static List<GrayU8> morphology(GrayU8 binary) {
        GrayU8 eroded = Binarize.erode8(binary);
        GrayU8 dilated = Binarize.dilate8(binary);
        GrayU8 opened = Binarize.dilate8(eroded);
        GrayU8 closed = Binarize.erode8(dilated);
        GrayU8 denoised = Binarize.removePointNoise(binary);
        return List.of(denoised, eroded, dilated, opened, closed);
    }

    /** One 8-connected dilation. Outside the image counts as light. */
    public static GrayU8 dilate8(GrayU8 binary) {
        return Binarize.dilate8(binary);
    }

    private static int radius(GrayU8 gray, int divisor) {
        int radius = Math.max(15, Math.min(gray.width, gray.height) / divisor);
        if (radius % 2 == 0) {
            radius++;
        }
        return radius;
    }
}
