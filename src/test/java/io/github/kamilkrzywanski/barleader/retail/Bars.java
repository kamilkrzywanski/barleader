package io.github.kamilkrzywanski.barleader.retail;

import com.google.zxing.oned.EAN13Writer;
import io.github.kamilkrzywanski.barleader.image.GrayU8;

/** An EAN-13 as modules, as the 59 widths of its runs, as a normalized row and as an image. */
final class Bars {
    static final String TEXT = "5901234123457";
    static final int[] DIGITS = {5, 9, 0, 1, 2, 3, 4, 1, 2, 3, 4, 5, 7};

    private Bars() {
    }

    /** The 95 modules of {@code text}, bars true. */
    static boolean[] modules(String text) {
        boolean[] encoded = new EAN13Writer().encode(text.substring(0, 12));
        int from = 0;
        while (!encoded[from]) {
            from++;
        }
        int to = encoded.length;
        while (!encoded[to - 1]) {
            to--;
        }
        return java.util.Arrays.copyOfRange(encoded, from, to);
    }

    /** The widths of the 59 runs, {@code module} a module, with every bar {@code wider} wider at the cost of the spaces. */
    static float[] widths(boolean[] modules, float module, float wider) {
        float[] out = new float[59];
        int count = 0;
        int start = 0;
        for (int i = 1; i <= modules.length; i++) {
            if (i == modules.length || modules[i] != modules[start]) {
                out[count++] = (i - start) * module + (modules[start] ? wider : -wider);
                start = i;
            }
        }
        return out;
    }

    /** A row of {@code length} samples, dark as one: {@code quiet} modules of white, the symbol at {@code module} samples a module, and white to the end. */
    static float[] row(boolean[] modules, int length, double module, double quiet) {
        float[] out = new float[length];
        for (int i = 0; i < length; i++) {
            double m = (i + 0.5) / module - quiet;
            out[i] = m >= 0 && m < modules.length && modules[(int) m] ? 1f : 0f;
        }
        return out;
    }

    /**
     * The symbol on {@code rows} rows with {@code margin} pixels of light gray around it, a module
     * {@code module} pixels wide and each pixel the mean of what it covers.
     */
    static GrayU8 image(boolean[] modules, double module, int rows, int margin) {
        int width = (int) Math.ceil(modules.length * module) + 2 * margin;
        GrayU8 image = new GrayU8(width, rows + 2 * margin);
        for (int x = 0; x < width; x++) {
            double ink = 0;
            for (int m = 0; m < modules.length; m++) {
                double cover = Math.min(x + 1, margin + (m + 1) * module) - Math.max(x, margin + m * module);
                if (cover > 0 && modules[m]) {
                    ink += cover;
                }
            }
            for (int y = 0; y < image.height; y++) {
                boolean inside = y >= margin && y < margin + rows;
                image.data[y * image.stride + x] = (byte) Math.round(inside ? 200 - 160 * ink : 200);
            }
        }
        return image;
    }
}
