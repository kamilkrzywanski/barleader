/*
 * Copyright 2026 Florian Nick
 * Copyright 2026 Kamil Krzywański
 */
// SPDX-License-Identifier: Apache-2.0
package io.github.kamilkrzywanski.barleader.retail;

import io.github.kamilkrzywanski.barleader.image.GrayU8;

/**
 * A crop of the frame with its edges sharpened, for a box whose rows showed the guards of a symbol
 * and read nothing. Ported from tapirscan 1.5.0 ({@code restore_with_kernel} of
 * {@code bindings/rust/src/pipeline/restoration.rs}).
 */
final class CropSharpen {
    /** The blur taken off a crop first, and the wider one tried after it. */
    static final int[] NARROW = {1, 4, 6, 4, 1};
    static final int[] WIDE = {1, 4, 11, 21, 26, 21, 11, 4, 1};

    private CropSharpen() {
    }

    /**
     * The {@code w} by {@code h} pixels of {@code frame} from ({@code x}, {@code y}), each as
     * {@code (5 * gray - 3 * blur + 1) / 2}: the pixel and one and a half times what it differs by
     * from its surroundings, blurred with {@code kernel} along the rows and the columns, the
     * edge pixels of the crop repeated beyond it. Whole numbers throughout.
     */
    static GrayU8 sharpen(GrayU8 frame, int x, int y, int w, int h, int[] kernel) {
        int center = kernel.length / 2;
        int sum = 0;
        for (int weight : kernel) {
            sum += weight;
        }
        int normalizer = sum * sum;
        int[] horizontal = new int[w * h];
        for (int row = 0; row < h; row++) {
            int from = frame.startIndex + (y + row) * frame.stride + x;
            for (int col = 0; col < w; col++) {
                int acc = 0;
                for (int i = 0; i < kernel.length; i++) {
                    int at = Math.max(0, Math.min(w - 1, col + i - center));
                    acc += kernel[i] * (frame.data[from + at] & 255);
                }
                horizontal[row * w + col] = acc;
            }
        }
        GrayU8 out = new GrayU8(w, h);
        for (int row = 0; row < h; row++) {
            for (int col = 0; col < w; col++) {
                int smooth = 0;
                for (int i = 0; i < kernel.length; i++) {
                    int at = Math.max(0, Math.min(h - 1, row + i - center));
                    smooth += kernel[i] * horizontal[at * w + col];
                }
                int gray = frame.data[frame.startIndex + (y + row) * frame.stride + x + col] & 255;
                int value = (5 * gray - 3 * ((smooth + normalizer / 2) / normalizer) + 1) / 2;
                out.data[out.startIndex + row * out.stride + col] = (byte) Math.max(0, Math.min(255, value));
            }
        }
        return out;
    }
}
