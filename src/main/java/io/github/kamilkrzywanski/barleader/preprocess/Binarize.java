/*
 * Copyright 2026 Kamil Krzywański
 */
// SPDX-License-Identifier: Apache-2.0
package io.github.kamilkrzywanski.barleader.preprocess;

import io.github.kamilkrzywanski.barleader.image.BoxMean;
import io.github.kamilkrzywanski.barleader.image.GrayU8;

/**
 * Global and local thresholds. A dark pixel is 1 and a light pixel is 0, the same polarity the
 * ladder already feeds to {@code binaryToLuma}.
 */
final class Binarize {
    private static final float MEAN_SCALE = 0.95f;
    private static final float SAUVOLA_K = 0.3f;
    /** Frames up to this many pixels get the Gaussian kernel itself in {@link #localGaussian}. */
    private static final long EXACT_GAUSSIAN_PIXELS = 16_000_000L;

    private Binarize() {
    }

    static GrayU8 otsu(GrayU8 gray) {
        return thresholdDown(gray, otsuThreshold(gray));
    }

    static int otsuThreshold(GrayU8 gray) {
        int[] histogram = new int[256];
        int total = gray.width * gray.height;
        for (int y = 0; y < gray.height; y++) {
            for (int x = 0; x < gray.width; x++) {
                histogram[gray.get(x, y)]++;
            }
        }
        double length = 256.0;
        double sum = 0.0;
        for (int i = 0; i < 256; i++) {
            sum += (i / length) * histogram[i];
        }
        double sumB = 0.0;
        int background = 0;
        double best = 0.0;
        int threshold = 0;
        for (int i = 0; i < 256; i++) {
            background += histogram[i];
            if (background == 0) {
                continue;
            }
            int foreground = total - background;
            if (foreground == 0) {
                break;
            }
            sumB += (i / length) * histogram[i];
            double meanB = sumB / background;
            double meanF = (sum - sumB) / foreground;
            double between = (double) background * (double) foreground * (meanB - meanF) * (meanB - meanF);
            if (between > best) {
                best = between;
                threshold = i;
            }
        }
        return threshold;
    }

    /** {@code window} is the odd kernel width. The blur radius is half of that. */
    static GrayU8 localMean(GrayU8 gray, int window) {
        GrayU8 mean = new GrayU8(gray.width, gray.height);
        BoxMean.mean(gray, mean, window / 2);
        GrayU8 binary = new GrayU8(gray.width, gray.height);
        for (int y = 0; y < gray.height; y++) {
            for (int x = 0; x < gray.width; x++) {
                float threshold = mean.get(x, y) * MEAN_SCALE;
                binary.set(x, y, gray.get(x, y) <= threshold ? 1 : 0);
            }
        }
        return binary;
    }

    /**
     * A local threshold against a Gaussian blur of sigma {@code (window + 1) / 5}, a kernel of
     * integers {@code window} wide. Applied tap by tap it cost one product per tap and pixel, and
     * the window grows with the image: on a 100-megapixel frame about 1.5e11 operations, minutes in
     * one step that no budget stopped. The kernel steps down from about 22 at its middle to 1 at
     * its ends, so it is a sum of about 22 nested boxes, and each pass adds up, per box, a prefix
     * sum along the row or a running sum down the column: the same pixels at a cost that does not
     * grow with the window. Past 16 megapixels, where even that takes seconds, three box means of
     * equal width stand for the kernel, their variance three times {@code (w * w - 1) / 12}: on the
     * public tapirscan benchmark that approximation lost two labelled EAN-8 under uneven light and
     * read one wrong one.
     */
    static GrayU8 localGaussian(GrayU8 gray, int window) {
        GrayU8 blur;
        if ((long) gray.width * gray.height <= EXACT_GAUSSIAN_PIXELS) {
            int[] kernel = gaussianKernel(window / 2);
            blur = columns(rows(gray, kernel), kernel);
        } else {
            double sigma = window / 5.0;
            int box = Math.max(1, (int) Math.round((Math.sqrt(4.0 * sigma * sigma + 1.0) - 1.0) / 2.0));
            blur = new GrayU8(gray.width, gray.height);
            GrayU8 pass = new GrayU8(gray.width, gray.height);
            BoxMean.mean(gray, blur, box);
            BoxMean.mean(blur, pass, box);
            BoxMean.mean(pass, blur, box);
        }
        GrayU8 binary = new GrayU8(gray.width, gray.height);
        for (int y = 0; y < gray.height; y++) {
            for (int x = 0; x < gray.width; x++) {
                float threshold = blur.get(x, y) * MEAN_SCALE;
                binary.set(x, y, gray.get(x, y) <= threshold ? 1 : 0);
            }
        }
        return binary;
    }

    /** Odd integer Gaussian. Sigma is {@code (2 * radius + 1) / 5}, then scaled the way an integer kernel is. */
    static int[] gaussianKernel(int radius) {
        double sigma = (radius * 2.0 + 1.0) / 5.0;
        int width = radius * 2 + 1;
        double[] pdf = new double[width];
        double max = 0.0;
        for (int i = 0; i < width; i++) {
            double sample = radius - i;
            pdf[i] = Math.exp(-sample * sample / (2.0 * sigma * sigma)) / (sigma * Math.sqrt(2.0 * Math.PI));
            max = Math.max(max, pdf[i]);
        }
        double floor = max * 0.01;
        double min = Double.MAX_VALUE;
        for (double value : pdf) {
            if (value < min && value >= floor) {
                min = value;
            }
        }
        int[] kernel = new int[width];
        for (int i = 0; i < width; i++) {
            kernel[i] = (int) (pdf[i] / min);
        }
        return kernel;
    }

    /**
     * The nested boxes of a symmetric kernel that falls off from its middle: {@code [0]} the half
     * widths, {@code [1]} what each adds, so that the tap at offset {@code t} is the sum of the
     * additions of the boxes at least {@code |t|} wide.
     */
    private static int[][] boxes(int[] kernel) {
        int radius = kernel.length / 2;
        int[] half = new int[kernel.length];
        int[] step = new int[kernel.length];
        int count = 0;
        int previous = 0;
        for (int offset = radius; offset >= 0; offset--) {
            int value = kernel[radius + offset];
            if (value > previous) {
                half[count] = offset;
                step[count] = value - previous;
                count++;
                previous = value;
            }
        }
        return new int[][]{java.util.Arrays.copyOf(half, count), java.util.Arrays.copyOf(step, count)};
    }

    /** The kernel along each row; near the ends only the taps inside the row count. */
    private static GrayU8 rows(GrayU8 input, int[] kernel) {
        int[][] boxes = boxes(kernel);
        int[] half = boxes[0];
        int[] step = boxes[1];
        int width = input.width;
        int reach = half.length == 0 ? 0 : half[0];
        int weight = 0;
        for (int b = 0; b < half.length; b++) {
            weight += step[b] * (2 * half[b] + 1);
        }
        GrayU8 out = new GrayU8(width, input.height);
        int[] prefix = new int[width + 1];
        for (int y = 0; y < input.height; y++) {
            for (int x = 0; x < width; x++) {
                prefix[x + 1] = prefix[x] + input.get(x, y);
            }
            for (int x = 0; x < width; x++) {
                int total = 0;
                int used = 0;
                if (x >= reach && x + reach < width) {
                    for (int b = 0; b < half.length; b++) {
                        total += step[b] * (prefix[x + half[b] + 1] - prefix[x - half[b]]);
                    }
                    used = weight;
                } else {
                    for (int b = 0; b < half.length; b++) {
                        int from = Math.max(0, x - half[b]);
                        int to = Math.min(width, x + half[b] + 1);
                        total += step[b] * (prefix[to] - prefix[from]);
                        used += step[b] * (to - from);
                    }
                }
                out.set(x, y, (total + used / 2) / used);
            }
        }
        return out;
    }

    /**
     * The kernel down each column, from one running sum of rows per box; a column the whole kernel
     * covers is divided as the tap by tap pass did.
     */
    private static GrayU8 columns(GrayU8 input, int[] kernel) {
        int[][] boxes = boxes(kernel);
        int[] half = boxes[0];
        int[] step = boxes[1];
        int weight = 0;
        for (int value : kernel) {
            weight += value;
        }
        int width = input.width;
        int height = input.height;
        int[][] sums = new int[half.length][width];
        int[] low = new int[half.length];
        int[] high = new int[half.length];
        for (int b = 0; b < half.length; b++) {
            high[b] = Math.min(height - 1, half[b]);
            for (int r = 0; r <= high[b]; r++) {
                addRow(input, r, sums[b], 1);
            }
        }
        GrayU8 out = new GrayU8(width, height);
        int[] total = new int[width];
        for (int y = 0; y < height; y++) {
            int used = 0;
            java.util.Arrays.fill(total, 0);
            for (int b = 0; b < half.length; b++) {
                if (y > 0) {
                    int from = Math.max(0, y - half[b]);
                    if (from > low[b]) {
                        addRow(input, low[b], sums[b], -1);
                        low[b] = from;
                    }
                    int to = Math.min(height - 1, y + half[b]);
                    if (to > high[b]) {
                        addRow(input, to, sums[b], 1);
                        high[b] = to;
                    }
                }
                used += step[b] * (high[b] - low[b] + 1);
                int[] sum = sums[b];
                int add = step[b];
                for (int x = 0; x < width; x++) {
                    total[x] += add * sum[x];
                }
            }
            for (int x = 0; x < width; x++) {
                int value = used == weight
                        ? (int) ((total[x] + weight / 2) * (1.0 / weight))
                        : (total[x] + used / 2) / used;
                out.set(x, y, value);
            }
        }
        return out;
    }

    private static void addRow(GrayU8 input, int y, int[] sum, int sign) {
        for (int x = 0; x < input.width; x++) {
            sum[x] += sign * input.get(x, y);
        }
    }

    static GrayU8 sauvola(GrayU8 gray, int window) {
        int radius = window / 2;
        float[] mean = BoxMean.meanFloat(gray, radius);
        float[] squares = meanOfSquares(gray, radius);
        float maxStdev = 0.0f;
        float[] stdev = new float[mean.length];
        for (int i = 0; i < mean.length; i++) {
            float variance = squares[i] - mean[i] * mean[i];
            float deviation = (float) Math.sqrt(variance);
            stdev[i] = deviation;
            if (deviation > maxStdev) {
                maxStdev = deviation;
            }
        }
        GrayU8 binary = new GrayU8(gray.width, gray.height);
        int i = 0;
        for (int y = 0; y < gray.height; y++) {
            for (int x = 0; x < gray.width; x++, i++) {
                float ratio = maxStdev == 0.0f ? 0.0f : stdev[i] / maxStdev;
                float threshold = mean[i] * (1.0f + SAUVOLA_K * (ratio - 1.0f));
                binary.set(x, y, gray.get(x, y) <= threshold ? 1 : 0);
            }
        }
        return binary;
    }

    static GrayU8 erode8(GrayU8 binary) {
        GrayU8 out = new GrayU8(binary.width, binary.height);
        int width = binary.width;
        int height = binary.height;
        for (int y = 1; y < height - 1; y++) {
            for (int x = 1; x < width - 1; x++) {
                int sum = binary.get(x, y) + binary.get(x - 1, y) + binary.get(x + 1, y)
                        + binary.get(x - 1, y - 1) + binary.get(x, y - 1) + binary.get(x + 1, y - 1)
                        + binary.get(x - 1, y + 1) + binary.get(x, y + 1) + binary.get(x + 1, y + 1);
                out.set(x, y, sum == 9 ? 1 : 0);
            }
        }
        for (int x = 0; x < width; x++) {
            int top = at(binary, x, 0, 1) + at(binary, x - 1, 0, 1) + at(binary, x + 1, 0, 1)
                    + at(binary, x - 1, 1, 1) + at(binary, x, 1, 1) + at(binary, x + 1, 1, 1);
            out.set(x, 0, top == 6 ? 1 : 0);
            int bottomY = height - 1;
            int bottom = at(binary, x, bottomY, 1) + at(binary, x - 1, bottomY, 1) + at(binary, x + 1, bottomY, 1)
                    + at(binary, x - 1, bottomY - 1, 1) + at(binary, x, bottomY - 1, 1) + at(binary, x + 1, bottomY - 1, 1);
            out.set(x, bottomY, bottom == 6 ? 1 : 0);
        }
        for (int y = 0; y < height; y++) {
            int left = at(binary, 0, y, 1) + at(binary, 1, y, 1) + at(binary, 0, y - 1, 1)
                    + at(binary, 1, y - 1, 1) + at(binary, 0, y + 1, 1) + at(binary, 1, y + 1, 1);
            out.set(0, y, left == 6 ? 1 : 0);
            int rightX = width - 1;
            int right = at(binary, rightX - 1, y, 1) + at(binary, rightX, y, 1) + at(binary, rightX - 1, y - 1, 1)
                    + at(binary, rightX, y - 1, 1) + at(binary, rightX - 1, y + 1, 1) + at(binary, rightX, y + 1, 1);
            out.set(rightX, y, right == 6 ? 1 : 0);
        }
        return out;
    }

    static GrayU8 dilate8(GrayU8 binary) {
        GrayU8 out = new GrayU8(binary.width, binary.height);
        int width = binary.width;
        int height = binary.height;
        for (int y = 1; y < height - 1; y++) {
            for (int x = 1; x < width - 1; x++) {
                int sum = binary.get(x, y) + binary.get(x - 1, y) + binary.get(x + 1, y)
                        + binary.get(x - 1, y - 1) + binary.get(x, y - 1) + binary.get(x + 1, y - 1)
                        + binary.get(x - 1, y + 1) + binary.get(x, y + 1) + binary.get(x + 1, y + 1);
                out.set(x, y, sum > 0 ? 1 : 0);
            }
        }
        for (int x = 0; x < width; x++) {
            int top = at(binary, x, 0, 0) + at(binary, x - 1, 0, 0) + at(binary, x + 1, 0, 0)
                    + at(binary, x - 1, 1, 0) + at(binary, x, 1, 0) + at(binary, x + 1, 1, 0);
            out.set(x, 0, top > 0 ? 1 : 0);
            int bottomY = height - 1;
            int bottom = at(binary, x, bottomY, 0) + at(binary, x - 1, bottomY, 0) + at(binary, x + 1, bottomY, 0)
                    + at(binary, x - 1, bottomY - 1, 0) + at(binary, x, bottomY - 1, 0) + at(binary, x + 1, bottomY - 1, 0);
            out.set(x, bottomY, bottom > 0 ? 1 : 0);
        }
        for (int y = 0; y < height; y++) {
            int left = at(binary, 0, y, 0) + at(binary, 1, y, 0) + at(binary, 0, y - 1, 0)
                    + at(binary, 1, y - 1, 0) + at(binary, 0, y + 1, 0) + at(binary, 1, y + 1, 0);
            out.set(0, y, left > 0 ? 1 : 0);
            int rightX = width - 1;
            int right = at(binary, rightX - 1, y, 0) + at(binary, rightX, y, 0) + at(binary, rightX - 1, y - 1, 0)
                    + at(binary, rightX, y - 1, 0) + at(binary, rightX - 1, y + 1, 0) + at(binary, rightX, y + 1, 0);
            out.set(rightX, y, right > 0 ? 1 : 0);
        }
        return out;
    }

    static GrayU8 removePointNoise(GrayU8 binary) {
        GrayU8 out = new GrayU8(binary.width, binary.height);
        int width = binary.width;
        int height = binary.height;
        for (int y = 1; y < height - 1; y++) {
            for (int x = 1; x < width - 1; x++) {
                int total = binary.get(x - 1, y) + binary.get(x + 1, y)
                        + binary.get(x - 1, y - 1) + binary.get(x, y - 1) + binary.get(x + 1, y - 1)
                        + binary.get(x - 1, y + 1) + binary.get(x, y + 1) + binary.get(x + 1, y + 1);
                if (total < 2) {
                    out.set(x, y, 0);
                } else if (total > 6) {
                    out.set(x, y, 1);
                } else {
                    out.set(x, y, binary.get(x, y));
                }
            }
        }
        for (int x = 0; x < width; x++) {
            int top = at(binary, x - 1, 0, 0) + at(binary, x + 1, 0, 0)
                    + at(binary, x - 1, 1, 0) + at(binary, x, 1, 0) + at(binary, x + 1, 1, 0);
            out.set(x, 0, top < 2 ? 0 : binary.get(x, 0));
            int bottomY = height - 1;
            int bottom = at(binary, x - 1, bottomY, 0) + at(binary, x + 1, bottomY, 0)
                    + at(binary, x - 1, bottomY - 1, 0) + at(binary, x, bottomY - 1, 0) + at(binary, x + 1, bottomY - 1, 0);
            out.set(x, bottomY, bottom < 2 ? 0 : binary.get(x, bottomY));
        }
        for (int y = 0; y < height; y++) {
            int left = at(binary, 1, y, 0) + at(binary, 0, y - 1, 0)
                    + at(binary, 1, y - 1, 0) + at(binary, 0, y + 1, 0) + at(binary, 1, y + 1, 0);
            out.set(0, y, left < 2 ? 0 : binary.get(0, y));
            int rightX = width - 1;
            int right = at(binary, rightX - 1, y, 0) + at(binary, rightX - 1, y - 1, 0)
                    + at(binary, rightX, y - 1, 0) + at(binary, rightX - 1, y + 1, 0) + at(binary, rightX, y + 1, 0);
            out.set(rightX, y, right < 2 ? 0 : binary.get(rightX, y));
        }
        return out;
    }

    private static float[] meanOfSquares(GrayU8 gray, int radius) {
        int width = gray.width;
        int height = gray.height;
        float[] src = new float[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                float value = gray.get(x, y);
                src[y * width + x] = value * value;
            }
        }
        return BoxMean.meanFloat(src, width, height, radius);
    }

    private static GrayU8 thresholdDown(GrayU8 gray, int threshold) {
        GrayU8 binary = new GrayU8(gray.width, gray.height);
        for (int y = 0; y < gray.height; y++) {
            for (int x = 0; x < gray.width; x++) {
                binary.set(x, y, gray.get(x, y) <= threshold ? 1 : 0);
            }
        }
        return binary;
    }

    private static int at(GrayU8 image, int x, int y, int outside) {
        if (x < 0 || y < 0 || x >= image.width || y >= image.height) {
            return outside;
        }
        return image.get(x, y);
    }

}
