/*
 * Copyright 2016 Nu-book Inc.
 * Copyright 2016 ZXing authors
 * Copyright 2026 Kamil Krzywański
 */
// SPDX-License-Identifier: Apache-2.0
package io.github.kamilkrzywanski.barcode.locate.dm;

import io.github.kamilkrzywanski.barcode.image.GrayU8;
import io.github.kamilkrzywanski.barcode.locate.dm.DmGeometry.Bits;

/**
 * Matrix binarizer used by zxing-cpp 3.1.1 before {@code DMDetector}.
 * This is {@code HybridBinarizer::getBlackMatrix} with {@code USE_NEW_ALGORITHM}
 * ({@code core/src/HybridBinarizer.cpp}): an 8×8 block min/max threshold, smoothed
 * over a 5×5 block window, with the global-histogram fallback for tiny or flat images.
 * A pixel at or below the threshold is black.
 */
final class DmBinarizer {
    private static final int BLOCK = 8;
    private static final int MIN_DYNAMIC_RANGE = 24;
    /** {@code BLOCK * (1 + 2 * 2)}. Images smaller than this use the global histogram. */
    private static final int WINDOW = 40;
    private static final int BUCKETS = 32;

    private DmBinarizer() {
    }

    static Bits blackMatrix(GrayU8 gray) {
        Bits bits = new Bits(gray.width, gray.height);
        if (gray.width < WINDOW || gray.height < WINDOW) {
            global(gray, bits);
            return bits;
        }
        int subWidth = (gray.width + BLOCK - 1) / BLOCK;
        int subHeight = (gray.height + BLOCK - 1) / BLOCK;
        int[] thresholds = blockThresholds(gray, subWidth, subHeight);
        smooth(thresholds, subWidth, subHeight);
        int max = 0;
        for (int value : thresholds) {
            max = Math.max(max, value);
        }
        if (max == 0) {
            global(gray, bits);
            return bits;
        }
        apply(gray, bits, thresholds, subWidth, subHeight);
        return bits;
    }

    /** {@code (max - min > 24) ? (max + min) / 2 : 0} per block. Edge blocks share the last full block. */
    private static int[] blockThresholds(GrayU8 gray, int subWidth, int subHeight) {
        int[] thresholds = new int[subWidth * subHeight];
        for (int y = 0; y < subHeight; y++) {
            int y0 = Math.min(y * BLOCK, gray.height - BLOCK);
            for (int x = 0; x < subWidth; x++) {
                int x0 = Math.min(x * BLOCK, gray.width - BLOCK);
                int min = 255;
                int max = 0;
                for (int yy = 0; yy < BLOCK; yy++) {
                    for (int xx = 0; xx < BLOCK; xx++) {
                        int pixel = gray.get(x0 + xx, y0 + yy);
                        min = Math.min(min, pixel);
                        max = Math.max(max, pixel);
                    }
                }
                thresholds[y * subWidth + x] = (max - min > MIN_DYNAMIC_RANGE) ? (max + min) / 2 : 0;
            }
        }
        return thresholds;
    }

    /**
     * Center block counts three times, each neighbor once. Remaining zeros are filled
     * with the next non-zero threshold, and the tail with the last one.
     */
    private static void smooth(int[] values, int subWidth, int subHeight) {
        int[] out = new int[values.length];
        int radius = 2;
        for (int y = 0; y < subHeight; y++) {
            for (int x = 0; x < subWidth; x++) {
                int left = clamp(x, radius, subWidth - radius - 1);
                int top = clamp(y, radius, subHeight - radius - 1);
                int center = values[y * subWidth + x];
                int sum = center * 2;
                int count = sum > 0 ? 2 : 0;
                for (int dy = -radius; dy <= radius; dy++) {
                    for (int dx = -radius; dx <= radius; dx++) {
                        int sample = values[(top + dy) * subWidth + (left + dx)];
                        sum += sample;
                        if (sample > 0) {
                            count++;
                        }
                    }
                }
                out[y * subWidth + x] = count > 0 ? sum / count : 0;
            }
        }
        int last = -1;
        for (int i = 0; i < out.length; i++) {
            if (out[i] != 0) {
                if (last != i - 1) {
                    int fill = out[i];
                    for (int j = last + 1; j < i; j++) {
                        out[j] = fill;
                    }
                }
                last = i;
            }
        }
        int tail = last < 0 ? out[0] : out[last];
        for (int j = last + 1; j < out.length; j++) {
            out[j] = tail;
        }
        System.arraycopy(out, 0, values, 0, values.length);
    }

    private static void apply(GrayU8 gray, Bits bits, int[] thresholds, int subWidth, int subHeight) {
        for (int y = 0; y < subHeight; y++) {
            int y0 = Math.min(y * BLOCK, gray.height - BLOCK);
            for (int x = 0; x < subWidth; x++) {
                int x0 = Math.min(x * BLOCK, gray.width - BLOCK);
                int threshold = thresholds[y * subWidth + x];
                for (int yy = 0; yy < BLOCK; yy++) {
                    for (int xx = 0; xx < BLOCK; xx++) {
                        if (gray.get(x0 + xx, y0 + yy) <= threshold) {
                            bits.set(x0 + xx, y0 + yy);
                        }
                    }
                }
            }
        }
    }

    /** Four sampled rows, same valley estimate as {@code GlobalHistogramBinarizer::getBlackMatrix}. */
    private static void global(GrayU8 gray, Bits bits) {
        int[] buckets = new int[BUCKETS];
        for (int band = 1; band < 5; band++) {
            int row = gray.height * band / 5;
            int right = gray.width * 4 / 5;
            for (int x = gray.width / 5; x < right; x++) {
                buckets[gray.get(x, row) >> 3]++;
            }
        }
        int blackPoint = estimateBlackPoint(buckets);
        if (blackPoint <= 0) {
            return;
        }
        for (int y = 0; y < gray.height; y++) {
            for (int x = 0; x < gray.width; x++) {
                if (gray.get(x, y) <= blackPoint) {
                    bits.set(x, y);
                }
            }
        }
    }

    /** Valley from {@code GlobalHistogramBinarizer::EstimateBlackPoint}. Shared with a scanline. */
    static int estimateBlackPoint(int[] buckets) {
        int firstPeak = 0;
        for (int i = 1; i < buckets.length; i++) {
            if (buckets[i] > buckets[firstPeak]) {
                firstPeak = i;
            }
        }
        int maxCount = buckets[firstPeak];
        int secondPeak = 0;
        int secondScore = 0;
        for (int x = 0; x < buckets.length; x++) {
            int distance = x - firstPeak;
            int score = buckets[x] * distance * distance;
            if (score > secondScore) {
                secondPeak = x;
                secondScore = score;
            }
        }
        if (firstPeak > secondPeak) {
            int swap = firstPeak;
            firstPeak = secondPeak;
            secondPeak = swap;
        }
        if (secondPeak - firstPeak <= BUCKETS / 16) {
            return -1;
        }
        int bestValley = secondPeak - 1;
        int bestScore = -1;
        for (int x = secondPeak - 1; x > firstPeak; x--) {
            int fromFirst = x - firstPeak;
            int score = fromFirst * fromFirst * (secondPeak - x) * (maxCount - buckets[x]);
            if (score > bestScore) {
                bestValley = x;
                bestScore = score;
            }
        }
        return bestValley << 3;
    }

    private static int clamp(int value, int low, int high) {
        return Math.max(low, Math.min(value, high));
    }
}
