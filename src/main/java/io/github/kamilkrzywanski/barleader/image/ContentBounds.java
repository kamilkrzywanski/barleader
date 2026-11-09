/*
 * Copyright 2026 Kamil Krzywański
 */
// SPDX-License-Identifier: Apache-2.0
package io.github.kamilkrzywanski.barleader.image;

/**
 * The part of a very large frame that holds anything: a photo pasted on a blank canvas, a scan
 * with wide empty margins. Blocks of {@link #BLOCK} pixels whose sampled luminance spans no more
 * than {@link #FLAT} levels are blank; the box around the others, with {@link #PAD} pixels to
 * spare, is returned when it leaves out a quarter of the frame or more. A photo has no blank
 * blocks to leave out, so it is read whole.
 */
public final class ContentBounds {
    private static final int BLOCK = 32;
    private static final int SAMPLE = 2;
    private static final int FLAT = 12;
    private static final int PAD = 64;
    private static final double MAX_SHARE = 0.75;

    private ContentBounds() {
    }

    /** {@code {x0, y0, x1, y1}} with {@code x1} and {@code y1} exclusive, or null to read the whole frame. */
    public static int[] find(GrayU8 gray) {
        int blocksX = (gray.width + BLOCK - 1) / BLOCK;
        int blocksY = (gray.height + BLOCK - 1) / BLOCK;
        int minBx = Integer.MAX_VALUE;
        int minBy = Integer.MAX_VALUE;
        int maxBx = -1;
        int maxBy = -1;
        int[] low = new int[blocksX];
        int[] high = new int[blocksX];
        for (int by = 0; by < blocksY; by++) {
            java.util.Arrays.fill(low, 255);
            java.util.Arrays.fill(high, 0);
            int yEnd = Math.min(gray.height, (by + 1) * BLOCK);
            for (int y = by * BLOCK; y < yEnd; y += SAMPLE) {
                int row = gray.startIndex + y * gray.stride;
                for (int x = 0; x < gray.width; x += SAMPLE) {
                    int v = gray.data[row + x] & 255;
                    int b = x / BLOCK;
                    if (v < low[b]) {
                        low[b] = v;
                    }
                    if (v > high[b]) {
                        high[b] = v;
                    }
                }
            }
            for (int bx = 0; bx < blocksX; bx++) {
                if (high[bx] - low[bx] > FLAT) {
                    minBx = Math.min(minBx, bx);
                    maxBx = Math.max(maxBx, bx);
                    minBy = Math.min(minBy, by);
                    maxBy = by;
                }
            }
        }
        if (maxBx < 0) {
            return null;
        }
        int x0 = Math.max(0, minBx * BLOCK - PAD);
        int y0 = Math.max(0, minBy * BLOCK - PAD);
        int x1 = Math.min(gray.width, (maxBx + 1) * BLOCK + PAD);
        int y1 = Math.min(gray.height, (maxBy + 1) * BLOCK + PAD);
        if ((double) (x1 - x0) * (y1 - y0) > MAX_SHARE * gray.width * (double) gray.height) {
            return null;
        }
        return new int[]{x0, y0, x1, y1};
    }

    /** A copy of {@code box} of {@code gray}. */
    public static GrayU8 crop(GrayU8 gray, int[] box) {
        GrayU8 out = new GrayU8(box[2] - box[0], box[3] - box[1]);
        for (int y = 0; y < out.height; y++) {
            System.arraycopy(gray.data, gray.startIndex + (box[1] + y) * gray.stride + box[0], out.data, y * out.width, out.width);
        }
        return out;
    }
}
