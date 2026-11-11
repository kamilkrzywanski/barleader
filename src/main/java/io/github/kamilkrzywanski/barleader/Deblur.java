package io.github.kamilkrzywanski.barleader;

import io.github.kamilkrzywanski.barleader.image.GrayU8;
import io.github.kamilkrzywanski.barleader.image.PixelMap;

import java.util.Arrays;

/**
 * Deconvolved copies of a blurred linear crop, a Java port of {@code pipeline/deblur.rs} of
 * tapirscan 1.5.0. Motion during the exposure doubles the bars and defocus smears them; both leave
 * the symbol well located but defeat every threshold. A crop of {@link BarSegments} is resampled
 * to about three pixels a module, taking an EAN-13 box of 95 modules, and its rows are sharpened
 * along the bars three ways: a ghost 0.8 modules behind every sample removed, the same ghost ahead
 * of it, and three Van Cittert steps against a Gaussian 0.7 modules wide. A crop whose edges are
 * already steep is left alone: the sharp symbols there are not EAN-13 that blur hides.
 */
final class Deblur {
    private static final double MODULE_PIXELS = 3;
    private static final double GHOST_MODULES = 0.8;
    private static final double GHOST_AMPLITUDE = 0.5;
    /**
     * Median over the middle rows of the steepest edges against the row contrast above which a crop
     * is sharp. tapirscan's corpus medians: 0.33 for blurred codes it recovered, 0.80 for symbols
     * that are not retail.
     */
    private static final double MAX_SHARPNESS = 0.6;
    private static final double DEFOCUS_MODULES = 0.7;
    private static final int DEFOCUS_ITERATIONS = 3;
    /** {@link BarSegments} crops extend this far past each end of the box. */
    private static final int CROP_END = 20;

    private final double[] rows;
    private final int width;
    private final int height;
    private final double module;
    private final PixelMap toSource;
    private final int degrees;

    private Deblur(double[] rows, int width, int height, double module, PixelMap toSource, int degrees) {
        this.rows = rows;
        this.width = width;
        this.height = height;
        this.module = module;
        this.toSource = toSource;
        this.degrees = degrees;
    }

    /**
     * The crop at about three pixels a module, with 2×2 samples a pixel when it shrinks, or null
     * when it is too small to hold an EAN-13 or its edges are sharp.
     */
    static Deblur blurred(LinearBand.Crop crop) {
        GrayU8 image = crop.image();
        double length = image.width - 2.0 * CROP_END;
        if (length < 40) {
            return null;
        }
        double step = Math.max(1, length / 95 / MODULE_PIXELS);
        int width = (int) Math.round(image.width / step);
        int height = Math.max(8, (int) Math.round(image.height / step));
        if (width < 64) {
            return null;
        }
        double[] offsets = step > 1.25 ? new double[]{0.25, 0.75} : new double[]{0.5};
        double[] rows = new double[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double sum = 0;
                for (double dy : offsets) {
                    for (double dx : offsets) {
                        sum += bilinear(image, (x + dx) * step - 0.5, (y + dy) * step - 0.5);
                    }
                }
                rows[y * width + x] = sum / (offsets.length * offsets.length);
            }
        }
        PixelMap source = crop.toSource();
        Deblur deblur = new Deblur(rows, width, height, length / 95 / step,
                (x, y) -> source.toSource(x * step, y * step), crop.degrees());
        double sharpness = deblur.sharpness();
        return Double.isNaN(sharpness) || sharpness > MAX_SHARPNESS ? null : deblur;
    }

    PixelMap toSource() {
        return toSource;
    }

    int degrees() {
        return degrees;
    }

    /** The resampled crop itself. */
    GrayU8 original() {
        return gray(rows);
    }

    /** The ghost removed behind every sample, then ahead of it, then the defocus. */
    GrayU8[] variants() {
        double lag = GHOST_MODULES * module;
        return new GrayU8[]{gray(deghost(lag, false)), gray(deghost(lag, true)), gray(defocus(DEFOCUS_MODULES * module))};
    }

    /** {@link #variants()} built side by side by {@code runAll}. */
    GrayU8[] variants(java.util.function.Consumer<java.util.List<Runnable>> runAll) {
        double lag = GHOST_MODULES * module;
        GrayU8[] out = new GrayU8[3];
        runAll.accept(java.util.List.of(
                () -> out[0] = gray(deghost(lag, false)),
                () -> out[1] = gray(deghost(lag, true)),
                () -> out[2] = gray(defocus(DEFOCUS_MODULES * module))));
        return out;
    }

    private double sharpness() {
        int x0 = width / 5;
        int x1 = width * 4 / 5;
        if (x1 - x0 < 8) {
            return Double.NaN;
        }
        int from = height * 21 / 50;
        int to = height * 29 / 50;
        double[] scores = new double[Math.max(0, to - from)];
        int count = 0;
        double[] steps = new double[x1 - x0 - 1];
        for (int y = from; y < to; y++) {
            double[] row = Arrays.copyOfRange(rows, y * width + x0, y * width + x1);
            double[] sorted = row.clone();
            Arrays.sort(sorted);
            double range = percentile(sorted, 0.95) - percentile(sorted, 0.05);
            if (range <= 20) {
                continue;
            }
            for (int i = 0; i + 1 < row.length; i++) {
                steps[i] = Math.abs(row[i + 1] - row[i]);
            }
            Arrays.sort(steps);
            scores[count++] = percentile(steps, 0.95) / range;
        }
        if (count == 0) {
            return Double.NaN;
        }
        double[] kept = Arrays.copyOf(scores, count);
        Arrays.sort(kept);
        return percentile(kept, 0.5);
    }

    private static double percentile(double[] sorted, double f) {
        return sorted[(int) (f * (sorted.length - 1))];
    }

    /** Each row minus half a copy of itself {@code lag} pixels behind, or ahead when {@code backward}. */
    private double[] deghost(double lag, boolean backward) {
        double[] out = rows.clone();
        int whole = Math.max(1, (int) Math.floor(lag));
        double fraction = Math.max(0, lag - whole);
        double[] row = new double[width];
        for (int y = 0; y < height; y++) {
            System.arraycopy(out, y * width, row, 0, width);
            if (backward) {
                reverse(row);
            }
            for (int i = whole; i < width; i++) {
                // The first restored sample has no older neighbour to interpolate with.
                double older = row[i - whole - (i > whole ? 1 : 0)];
                double echo = (1 - fraction) * row[i - whole] + fraction * older;
                row[i] = (1 + GHOST_AMPLITUDE) * row[i] - GHOST_AMPLITUDE * echo;
            }
            if (backward) {
                reverse(row);
            }
            System.arraycopy(row, 0, out, y * width, width);
        }
        return out;
    }

    private static void reverse(double[] values) {
        for (int i = 0, j = values.length - 1; i < j; i++, j--) {
            double swap = values[i];
            values[i] = values[j];
            values[j] = swap;
        }
    }

    /** Van Cittert deconvolution of a Gaussian {@code sigma} pixels wide along each row. */
    private double[] defocus(double sigma) {
        int radius = Math.max(1, (int) Math.ceil(3 * sigma));
        double[] kernel = new double[2 * radius + 1];
        double total = 0;
        for (int i = 0; i < kernel.length; i++) {
            double x = (i - radius) / sigma;
            kernel[i] = Math.exp(-0.5 * x * x);
            total += kernel[i];
        }
        double[] estimate = rows.clone();
        double[] blurred = new double[rows.length];
        for (int iteration = 0; iteration < DEFOCUS_ITERATIONS; iteration++) {
            for (int y = 0; y < height; y++) {
                int row = y * width;
                for (int i = 0; i < width; i++) {
                    double sum = 0;
                    for (int k = 0; k < kernel.length; k++) {
                        sum += kernel[k] * estimate[row + Math.min(width - 1, Math.max(0, i + k - radius))];
                    }
                    blurred[row + i] = sum / total;
                }
            }
            for (int i = 0; i < estimate.length; i++) {
                estimate[i] += rows[i] - blurred[i];
            }
        }
        return estimate;
    }

    private GrayU8 gray(double[] values) {
        GrayU8 out = new GrayU8(width, height);
        for (int i = 0; i < values.length; i++) {
            out.data[i] = (byte) Math.max(0, Math.min(255, Math.round(values[i])));
        }
        return out;
    }

    private static double bilinear(GrayU8 image, double x, double y) {
        x = Math.max(0, Math.min(image.width - 1, x));
        y = Math.max(0, Math.min(image.height - 1, y));
        int x0 = (int) x;
        int y0 = (int) y;
        int x1 = Math.min(x0 + 1, image.width - 1);
        int y1 = Math.min(y0 + 1, image.height - 1);
        double fx = x - x0;
        double fy = y - y0;
        int row0 = image.startIndex + y0 * image.stride;
        int row1 = image.startIndex + y1 * image.stride;
        double top = (image.data[row0 + x0] & 255) * (1 - fx) + (image.data[row0 + x1] & 255) * fx;
        double bottom = (image.data[row1 + x0] & 255) * (1 - fx) + (image.data[row1 + x1] & 255) * fx;
        return top * (1 - fy) + bottom * fy;
    }
}
