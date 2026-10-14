package io.github.kamilkrzywanski.barcode.preprocess;

import io.github.kamilkrzywanski.barcode.image.BoxMean;
import io.github.kamilkrzywanski.barcode.image.GrayU8;

/**
 * Global and local thresholds. A dark pixel is 1 and a light pixel is 0, the same polarity the
 * ladder already feeds to {@code binaryToLuma}.
 */
final class Binarize {
    private static final float MEAN_SCALE = 0.95f;
    private static final float SAUVOLA_K = 0.3f;

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

    static GrayU8 localGaussian(GrayU8 gray, int window) {
        int radius = window / 2;
        int[] kernel = gaussianKernel(radius);
        GrayU8 horizontal = convolve(gray, kernel, true);
        GrayU8 blur = convolve(horizontal, kernel, false);
        GrayU8 binary = new GrayU8(gray.width, gray.height);
        for (int y = 0; y < gray.height; y++) {
            for (int x = 0; x < gray.width; x++) {
                float threshold = blur.get(x, y) * MEAN_SCALE;
                binary.set(x, y, gray.get(x, y) <= threshold ? 1 : 0);
            }
        }
        return binary;
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

    /** Odd integer Gaussian. Sigma is {@code (2 * radius + 1) / 5}, then scaled the way an integer kernel is. */
    private static int[] gaussianKernel(int radius) {
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

    private static GrayU8 convolve(GrayU8 input, int[] kernel, boolean horizontal) {
        int radius = kernel.length / 2;
        int weight = 0;
        for (int value : kernel) {
            weight += value;
        }
        GrayU8 out = new GrayU8(input.width, input.height);
        for (int y = 0; y < input.height; y++) {
            for (int x = 0; x < input.width; x++) {
                int total = 0;
                int used = 0;
                for (int k = 0; k < kernel.length; k++) {
                    int xx = x;
                    int yy = y;
                    if (horizontal) {
                        xx = x - radius + k;
                    } else {
                        yy = y - radius + k;
                    }
                    if (xx < 0 || yy < 0 || xx >= input.width || yy >= input.height) {
                        continue;
                    }
                    total += input.get(xx, yy) * kernel[k];
                    used += kernel[k];
                }
                if (used == 0) {
                    out.set(x, y, input.get(x, y));
                } else if (!horizontal && used == weight) {
                    out.set(x, y, (int) ((total + weight / 2) * (1.0 / weight)));
                } else {
                    out.set(x, y, (total + used / 2) / used);
                }
            }
        }
        return out;
    }
}
