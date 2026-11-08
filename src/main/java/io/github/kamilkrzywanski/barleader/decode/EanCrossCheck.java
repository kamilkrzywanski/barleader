/*
 * Copyright 2026 Kamil Krzywański
 */
// SPDX-License-Identifier: Apache-2.0
package io.github.kamilkrzywanski.barleader.decode;

import io.github.kamilkrzywanski.barleader.image.BinaryBitmap;
import io.github.kamilkrzywanski.barleader.image.GrayLuminanceSource;
import io.github.kamilkrzywanski.barleader.image.GrayU8;

import java.util.HashSet;
import java.util.Set;

/**
 * An EAN-13 or UPC-A read checked against the unmodified image, after tapirscan's source
 * contradiction. Eleven lines parallel to the read's scan line, shifted across the bars by up to a
 * quarter of its length and reaching past both ends, are sampled from the original pixels and read
 * by the EAN row decoder with both binarizers at whole and quarter pixels. A read that none of
 * them repeats while two or more read another EAN-13 was a misread of that symbol: on the snapshot
 * images every such read had a wrong left half or wrong digits, and the text the lines agreed on
 * was the labelled one.
 */
public final class EanCrossCheck {
    private static final double[] OFFSETS = {-0.24, -0.18, -0.12, -0.08, -0.04, 0, 0.04, 0.08, 0.12, 0.18, 0.24};
    private static final double EXTEND = 0.15;
    private static final int REPEAT = 3;
    private static final int MIN_LINES = 2;

    /** What the lines say about a read. */
    public enum Verdict { KEEP, REPLACE, DROP }

    /** A verdict, and for {@link Verdict#REPLACE} the 13 digits the lines agree on. */
    public record Result(Verdict verdict, String text) {
        static final Result KEEP = new Result(Verdict.KEEP, null);
    }

    private EanCrossCheck() {
    }

    /**
     * Checks {@code text} (13 digits, or 12 of a UPC-A) read along the line from
     * ({@code x0}, {@code y0}) to ({@code x1}, {@code y1}) of {@code image}.
     */
    public static Result check(GrayU8 image, double x0, double y0, double x1, double y1, String text) {
        double length = Math.hypot(x1 - x0, y1 - y0);
        if (image == null || length < 20 || !Double.isFinite(length)) {
            return Result.KEEP;
        }
        String want = text.length() == 12 ? "0" + text : text;
        double ux = (x1 - x0) / length;
        double uy = (y1 - y0) / length;
        double extend = EXTEND * length;
        int width = (int) Math.round(length + 2 * extend);
        if (width > 8192) {
            return Result.KEEP;
        }
        GrayU8 strip = new GrayU8(width, OFFSETS.length * REPEAT);
        for (int r = 0; r < OFFSETS.length; r++) {
            double off = OFFSETS[r] * length;
            for (int x = 0; x < width; x++) {
                double t = x - extend;
                byte value = (byte) Math.round(bilinear(image, x0 + ux * t - uy * off, y0 + uy * t + ux * off));
                for (int k = 0; k < REPEAT; k++) {
                    strip.data[(r * REPEAT + k) * width + x] = value;
                }
            }
        }
        boolean[] same = new boolean[OFFSETS.length];
        String[] other = new String[OFFSETS.length];
        boolean mixed = false;
        GrayLuminanceSource source = new GrayLuminanceSource(strip);
        for (BinaryBitmap bitmap : new BinaryBitmap[]{BinaryBitmap.hybrid(source), BinaryBitmap.global(source)}) {
            for (int scale : new int[]{1, 4}) {
                for (int r = 0; r < OFFSETS.length; r++) {
                    int y = r * REPEAT + REPEAT / 2;
                    for (boolean reversed : new boolean[]{false, true}) {
                        int[] runs = bitmap.runs(y, reversed, scale);
                        if (runs == null) {
                            continue;
                        }
                        UpcEanDecoder.Span span = UpcEanDecoder.decodeSpan(runs, false, false);
                        if (span == null || span.kind() != UpcEanDecoder.Kind.EAN_13) {
                            continue;
                        }
                        if (span.text().equals(want)) {
                            same[r] = true;
                        } else if (other[r] == null) {
                            other[r] = span.text();
                        } else if (!other[r].equals(span.text())) {
                            mixed = true;
                        }
                    }
                }
            }
        }
        int lines = 0;
        Set<String> texts = new HashSet<>();
        for (int r = 0; r < OFFSETS.length; r++) {
            if (same[r]) {
                return Result.KEEP;
            }
            if (other[r] != null) {
                lines++;
                texts.add(other[r]);
            }
        }
        if (lines < MIN_LINES) {
            return Result.KEEP;
        }
        if (texts.size() == 1 && !mixed) {
            return new Result(Verdict.REPLACE, texts.iterator().next());
        }
        return new Result(Verdict.DROP, null);
    }

    private static double bilinear(GrayU8 g, double x, double y) {
        if (!(x >= 0 && y >= 0 && x <= g.width - 1 && y <= g.height - 1)) {
            return 255;
        }
        int ix = (int) x;
        int iy = (int) y;
        int x1 = Math.min(ix + 1, g.width - 1);
        int y1 = Math.min(iy + 1, g.height - 1);
        double fx = x - ix;
        double fy = y - iy;
        int row0 = g.startIndex + iy * g.stride;
        int row1 = g.startIndex + y1 * g.stride;
        double top = (g.data[row0 + ix] & 255) * (1 - fx) + (g.data[row0 + x1] & 255) * fx;
        double bottom = (g.data[row1 + ix] & 255) * (1 - fx) + (g.data[row1 + x1] & 255) * fx;
        return top * (1 - fy) + bottom * fy;
    }
}
