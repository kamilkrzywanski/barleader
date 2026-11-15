/*
 * Copyright 2026 Florian Nick
 * Copyright 2026 Kamil Krzywański
 */
// SPDX-License-Identifier: Apache-2.0
package io.github.kamilkrzywanski.barleader.decode;

import io.github.kamilkrzywanski.barleader.image.GrayU8;
import io.github.kamilkrzywanski.barleader.retail.EanRuns;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

/**
 * Whether the frame's own pixels speak against an EAN-13 that a recovery read. Ported from
 * tapirscan 1.5.0 ({@code source_contradiction} of
 * {@code bindings/rust/src/pipeline/source_evidence.rs}).
 *
 * <p>Three lines across the outline of the read, at a fifth, a half and four fifths of its
 * height and three samples a pixel, are cut the three ways of {@link ProfileSampler} and read both
 * ways by how the 59 runs between the ends of the outline look, whatever their checksum. A text
 * other than the read's on two of the three lines contradicts it.
 */
public final class SourceCheck {
    private SourceCheck() {
    }

    /**
     * Whether two of three lines across {@code polygon}, four corners in frame pixels with the
     * first two along the symbol, read as another text than {@code text}, thirteen digits.
     */
    public static boolean contradicted(GrayU8 frame, double[] polygon, String text) {
        int[] expected = new int[13];
        for (int i = 0; i < 13; i++) {
            expected[i] = text.charAt(i) - '0';
        }
        ProfileSampler sampler = new ProfileSampler();
        List<int[]> conflicts = new ArrayList<>();
        List<int[]> counts = new ArrayList<>();
        float[] widths = new float[59];
        for (double v : new double[]{0.2, 0.5, 0.8}) {
            double[] a = point(polygon, -0.15, v);
            double[] b = point(polygon, 1.15, v);
            sampler.sample(frame, a[0], a[1], b[0], b[1], 3, 1536);
            List<int[]> row = new ArrayList<>();
            for (int method = 0; method < 3; method++) {
                if (method == 1) {
                    sampler.adaptiveThreshold();
                } else {
                    sampler.threshold(method == 0);
                }
                int count = sampler.runCount();
                float[] runs = sampler.runs();
                float total = 0f;
                for (int i = 0; i < count; i++) {
                    total += runs[i];
                }
                for (int pass = 0; pass < 2 && total > 0f; pass++) {
                    boolean reverse = pass == 1;
                    boolean black = reverse ? sampler.firstBlack() ^ (count % 2 == 0) : sampler.firstBlack();
                    float before = 0f;
                    for (int start = 0; start + 59 < count; start++) {
                        // A symbol starts with a bar, between runs of white.
                        if (start > 0 && (start % 2 == 0) == black) {
                            float symbol = 0f;
                            for (int i = 0; i < 59; i++) {
                                widths[i] = runs[reverse ? count - 1 - (start + i) : start + i];
                                symbol += widths[i];
                            }
                            float left = before / total;
                            float right = left + symbol / total;
                            float module = symbol / 95f;
                            float quietBefore = runs[reverse ? count - start : start - 1];
                            float quietAfter = runs[reverse ? count - 1 - (start + 59) : start + 59];
                            if (Math.abs(left - 0.15f / 1.3f) <= 0.12f && Math.abs(right - 1.15f / 1.3f) <= 0.12f
                                    && quietBefore >= 4f * module && quietAfter >= 4f * module) {
                                EanRuns.Evidence evidence = EanRuns.visual(widths);
                                if (evidence != null && !Arrays.equals(evidence.digits(), expected) && !contains(row, evidence.digits())) {
                                    row.add(evidence.digits());
                                }
                            }
                        }
                        before += runs[reverse ? count - 1 - start : start];
                    }
                }
            }
            for (int[] digits : row) {
                int at = -1;
                for (int i = 0; i < conflicts.size(); i++) {
                    if (Arrays.equals(conflicts.get(i), digits)) {
                        at = i;
                    }
                }
                if (at < 0) {
                    conflicts.add(digits);
                    counts.add(new int[]{1});
                } else if (++counts.get(at)[0] >= 2) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Whether the frame's own pixels look like {@code text}, thirteen digits, inside
     * {@code polygon}: three lines across it, each 256 samples with their mean and slope taken
     * out, are compared with the modules of the text drawn sharp and blurred by 0.3, 0.6 and 0.9
     * of a module, a little longer and shorter and shifted by up to a module, either way round.
     * Two lines at least two pixels apart must each follow one such drawing closely: a share of
     * 0.65 of their variance. A read whose bars were cut wrongly the same way on every row, as
     * under the ghost of camera shake, differs from the symbol in most digits and does not pass;
     * a text that differs in a digit or two does. Port of
     * {@code recovered_ean_source_agreement}.
     */
    public static boolean agrees(GrayU8 frame, double[] polygon, String text) {
        double[][] rows = new double[3][256];
        double[] norms = lines(frame, polygon, rows);
        double height = (Math.hypot(polygon[6] - polygon[0], polygon[7] - polygon[1]) + Math.hypot(polygon[4] - polygon[2], polygon[5] - polygon[3])) / 2;
        boolean eligible = false;
        for (int i = 0; i < 3; i++) {
            for (int j = i + 1; j < 3; j++) {
                eligible |= norms[i] >= 160 && norms[j] >= 160 && (j - i) * 0.3 * height >= 2;
            }
        }
        if (!eligible) {
            return false;
        }
        // Bars are dark and the drawing counts them as one: a line that follows it correlates below zero.
        Predicate<double[]> agreed = best -> {
            for (int i = 0; i < 3; i++) {
                for (int j = i + 1; j < 3; j++) {
                    if (best[i] < 0 && best[i] * best[i] >= 0.65 && best[j] < 0 && best[j] * best[j] >= 0.65 && (j - i) * 0.3 * height >= 2) {
                        return true;
                    }
                }
            }
            return false;
        };
        return agreed.test(follow(rows, norms, modules(text, 13), agreed));
    }

    /**
     * How closely the frame's pixels follow {@code text}, thirteen digits, inside {@code polygon}:
     * the share of their variance that the best drawing of the text explains, as {@link #agrees}
     * draws it, as the mean over those of its three lines that have contrast; -1 when none has.
     * Two texts read from the same bars can be told apart by it.
     */
    public static double fit(GrayU8 frame, double[] polygon, String text) {
        return fit(frame, polygon, modules(text, 13));
    }

    /** {@link #fit(GrayU8, double[], String)} for the eight digits of an EAN-8. */
    public static double fitEan8(GrayU8 frame, double[] polygon, String text) {
        return fit(frame, polygon, modules(text, 8));
    }

    /** {@link #fit(GrayU8, double[], String)} for the eight digits of a UPC-E: number system, six digits and check digit. */
    public static double fitUpcE(GrayU8 frame, double[] polygon, String text) {
        return fit(frame, polygon, modules(text, 6));
    }

    /** Which of the six digits of a UPC-E of number system 0 are of the even set, by its check digit; the first of them is the high bit. */
    private static final int[] UPC_E_PARITY = {56, 52, 50, 49, 44, 38, 35, 42, 41, 37};

    /** The modules, a bar as one, of the thirteen digits of an EAN-13, the eight of an EAN-8 ({@code digits} 8) or the eight of a UPC-E ({@code digits} 6). */
    private static float[] modules(String text, int digits) {
        int[] d = new int[text.length()];
        for (int i = 0; i < d.length; i++) {
            d[i] = text.charAt(i) - '0';
        }
        if (digits == 13) {
            return EanRuns.modules(d);
        }
        float[] out = new float[digits == 8 ? 67 : 51];
        int at = 0;
        out[at] = 1;
        out[at + 2] = 1;
        at += 3;
        for (int j = 0; j < digits; j++) {
            if (digits == 8 && j == 4) {
                out[at + 1] = 1;
                out[at + 3] = 1;
                at += 5;
            }
            boolean right = digits == 8 && j >= 4;
            boolean even = digits == 6 && ((UPC_E_PARITY[d[7]] ^ (d[0] == 0 ? 0 : 63)) >> (5 - j) & 1) == 1;
            int[] widths = EanRuns.digitWidths(d[digits == 6 ? j + 1 : j]);
            // The odd set starts with a space, the even set is it backwards, and the right set starts with a bar.
            boolean bar = right;
            for (int k = 0; k < 4; k++) {
                int width = widths[even ? 3 - k : k];
                for (int m = 0; m < width; m++) {
                    out[at++] = bar ? 1 : 0;
                }
                bar = !bar;
            }
        }
        if (digits == 8) {
            out[at] = 1;
            out[at + 2] = 1;
        } else {
            out[at + 1] = 1;
            out[at + 3] = 1;
            out[at + 5] = 1;
        }
        return out;
    }

    private static double fit(GrayU8 frame, double[] polygon, float[] bits) {
        double[][] rows = new double[3][256];
        double[] norms = lines(frame, polygon, rows);
        double[] best = follow(rows, norms, bits, null);
        double sum = 0;
        int count = 0;
        for (int j = 0; j < 3; j++) {
            if (norms[j] >= 160) {
                sum += best[j] < 0 ? best[j] * best[j] : 0;
                count++;
            }
        }
        return count == 0 ? -1 : sum / count;
    }

    /** Three lines across {@code polygon}, at 0.2, 0.5 and 0.8 of its height, each normalized; returns their lengths before that. */
    private static double[] lines(GrayU8 frame, double[] polygon, double[][] rows) {
        double[] norms = new double[3];
        for (int j = 0; j < 3; j++) {
            double v = 0.2 + 0.3 * j;
            for (int i = 0; i < 256; i++) {
                double[] p = point(polygon, -0.02 + 1.04 * i / 255, v);
                rows[j][i] = pixel(frame, p[0], p[1]);
            }
            norms[j] = normalize(rows[j]);
        }
        return norms;
    }

    /**
     * For each line with contrast its lowest correlation with a drawing of the modules {@code bits}: sharp and
     * blurred by 0.3, 0.6 and 0.9 of a module, a little longer and shorter, shifted by up to a
     * module, either way round. Stops once {@code enough} holds for what it has.
     */
    private static double[] follow(double[][] rows, double[] norms, float[] bits, Predicate<double[]> enough) {
        int count = bits.length;
        double[] best = new double[3];
        double[] raster = new double[(count + 8) * 8];
        double[] smoothed = new double[raster.length];
        double[] template = new double[256];
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < raster.length; i++) {
                int module = (int) Math.floor(i / 8.0 - 4);
                raster[i] = module >= 0 && module < count ? bits[pass == 1 ? count - 1 - module : module] : 0;
            }
            for (double sigma : new double[]{0, 0.3, 0.6, 0.9}) {
                if (sigma == 0) {
                    System.arraycopy(raster, 0, smoothed, 0, raster.length);
                } else {
                    int radius = (int) Math.ceil(sigma * 8 * 3);
                    double[] weights = new double[2 * radius + 1];
                    double sum = 0;
                    for (int k = -radius; k <= radius; k++) {
                        weights[k + radius] = Math.exp(-0.5 * (k / (sigma * 8)) * (k / (sigma * 8)));
                        sum += weights[k + radius];
                    }
                    for (int i = 0; i < raster.length; i++) {
                        double value = 0;
                        for (int k = -radius; k <= radius; k++) {
                            int index = i + k;
                            if (index >= 0 && index < raster.length) {
                                value += raster[index] * weights[k + radius];
                            }
                        }
                        smoothed[i] = value / sum;
                    }
                }
                for (double pitch : new double[]{0.98, 1, 1.02}) {
                    for (double offset : new double[]{-1, -0.5, 0, 0.5, 1}) {
                        for (int i = 0; i < 256; i++) {
                            double u = -0.02 + 1.04 * i / 255;
                            double index = ((count * u - offset) / pitch + 4) * 8;
                            if (index < 0 || index >= smoothed.length - 1) {
                                template[i] = 0;
                            } else {
                                int k = (int) index;
                                template[i] = smoothed[k] * (1 - (index - k)) + smoothed[k + 1] * (index - k);
                            }
                        }
                        normalize(template);
                        for (int j = 0; j < 3; j++) {
                            if (norms[j] < 160) {
                                continue;
                            }
                            double correlation = 0;
                            for (int i = 0; i < 256; i++) {
                                correlation += template[i] * rows[j][i];
                            }
                            best[j] = Math.min(best[j], correlation);
                        }
                        if (enough != null && enough.test(best)) {
                            return best;
                        }
                    }
                }
            }
        }
        return best;
    }

    /** Takes the mean and the slope out of 256 values and scales them to unit length; returns their length before that. */
    private static double normalize(double[] values) {
        double mean = 0;
        for (double v : values) {
            mean += v;
        }
        mean /= 256;
        double slope = 0;
        double spread = 0;
        for (int i = 0; i < 256; i++) {
            slope += (i - 127.5) * (values[i] - mean);
            spread += (i - 127.5) * (i - 127.5);
        }
        slope /= spread;
        double norm = 0;
        for (int i = 0; i < 256; i++) {
            values[i] -= mean + slope * (i - 127.5);
            norm += values[i] * values[i];
        }
        norm = Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < 256; i++) {
                values[i] /= norm;
            }
        }
        return norm;
    }

    /** The frame at ({@code x}, {@code y}) between its four pixels around, white outside it. */
    private static double pixel(GrayU8 frame, double x, double y) {
        if (x < 0 || y < 0 || x > frame.width - 1 || y > frame.height - 1) {
            return 255;
        }
        int x0 = (int) x;
        int y0 = (int) y;
        int x1 = Math.min(x0 + 1, frame.width - 1);
        int y1 = Math.min(y0 + 1, frame.height - 1);
        double fx = x - x0;
        double fy = y - y0;
        int row0 = frame.startIndex + y0 * frame.stride;
        int row1 = frame.startIndex + y1 * frame.stride;
        double top = (frame.data[row0 + x0] & 255) * (1 - fx) + (frame.data[row0 + x1] & 255) * fx;
        double bottom = (frame.data[row1 + x0] & 255) * (1 - fx) + (frame.data[row1 + x1] & 255) * fx;
        return top * (1 - fy) + bottom * fy;
    }

    private static boolean contains(List<int[]> row, int[] digits) {
        for (int[] other : row) {
            if (Arrays.equals(other, digits)) {
                return true;
            }
        }
        return false;
    }

    /** The frame pixel at {@code u} along the outline and {@code v} across it. */
    private static double[] point(double[] q, double u, double v) {
        double ax = q[0] + (q[2] - q[0]) * u;
        double ay = q[1] + (q[3] - q[1]) * u;
        double bx = q[6] + (q[4] - q[6]) * u;
        double by = q[7] + (q[5] - q[7]) * u;
        return new double[]{ax + (bx - ax) * v, ay + (by - ay) * v};
    }
}
