/*
 * Copyright 2016 Nu-book Inc.
 * Copyright 2016 ZXing authors
 * Copyright 2026 Kamil Krzywański
 */
// SPDX-License-Identifier: Apache-2.0
package io.github.kamilkrzywanski.barleader.locate.dm;

import io.github.kamilkrzywanski.barleader.image.GrayU8;

/**
 * One scanline from zxing-cpp 3.1.1 {@code GlobalHistogramBinarizer::getPatternRow}.
 *
 * <p>The histogram is the whole line, not the four-row sample {@code getBlackMatrix} uses.
 * The threshold is that valley minus one. Interior pixels are compared after
 * {@code (-left + 4 * center - right) / 2}. The four-way histogram in the C++ file
 * sums to the same buckets. A line shorter than 3, or a flat line, returns null.
 * Rotation 0 is an upright row. Rotation 90 follows {@code ImageView::rotated}:
 * the chosen column, read from the bottom up. Not used by the scan ladder.
 */
final class RowHistogram {
    private static final int BUCKETS = 32;

    private RowHistogram() {
    }

    /** {@code true} is black. Rotation 0 only. */
    static boolean[] bits(GrayU8 gray, int y) {
        return bits(gray, y, 0);
    }

    /**
     * {@code rotation} is 0 or 90, the same angles {@code OneD::Reader} asks for.
     * Any other angle returns null.
     */
    static boolean[] bits(GrayU8 gray, int index, int rotation) {
        if (gray == null) {
            return null;
        }
        int length;
        if (rotation == 0) {
            if (index < 0 || index >= gray.height || gray.width < 3) {
                return null;
            }
            length = gray.width;
        } else if (rotation == 90) {
            if (index < 0 || index >= gray.width || gray.height < 3) {
                return null;
            }
            length = gray.height;
        } else {
            return null;
        }
        int[] buckets = new int[BUCKETS];
        int[] luminance = new int[length];
        for (int i = 0; i < length; i++) {
            int value = rotation == 0
                    ? gray.get(i, index)
                    : gray.get(index, gray.height - 1 - i);
            luminance[i] = value;
            buckets[value >> 3]++;
        }
        int threshold = DmBinarizer.estimateBlackPoint(buckets) - 1;
        if (threshold <= 0) {
            return null;
        }
        boolean[] black = new boolean[length];
        black[0] = luminance[0] <= threshold;
        for (int i = 1; i < length - 1; i++) {
            int sharpened = (-luminance[i - 1] + (luminance[i] << 2) - luminance[i + 1]) / 2;
            black[i] = sharpened <= threshold;
        }
        black[length - 1] = luminance[length - 1] <= threshold;
        return black;
    }
}
