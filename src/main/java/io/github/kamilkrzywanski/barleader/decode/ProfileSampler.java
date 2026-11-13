/*
 * Copyright 2026 Florian Nick
 * Copyright 2026 Kamil Krzywański
 */
// SPDX-License-Identifier: Apache-2.0
package io.github.kamilkrzywanski.barleader.decode;

import io.github.kamilkrzywanski.barleader.image.GrayU8;

import java.util.Arrays;

/**
 * The gray profile of one line across a candidate, sampled from the frame, and its bars as runs
 * with edges between the samples. Ported from tapirscan 1.5.0 ({@code core/src/fast_profile.rs},
 * {@code Sampler}).
 *
 * <p>A threshold of a whole image or a whole row places an edge where the gray crosses one level.
 * Here each edge lies where the profile crosses the level half way between the peak and the valley
 * on its two sides ({@link #threshold}), or between the lowest and the highest value of a window
 * around it ({@link #adaptiveThreshold}), so shading, a dark half and low contrast move no edge.
 * The profile can first be sharpened ({@link #restoreContrast}), freed of slow shading
 * ({@link #restoreHighpass}) or of a ghost from camera shake ({@link #restoreDeghost}); none of
 * them moves a sample.
 */
final class ProfileSampler {
    /** A swing of the profile below this many gray levels is no bar. */
    private static final float SWING = 10f;

    private float[] values = new float[0];
    private float[] scratch = new float[0];
    private float[] blurred = new float[0];
    private float[] next = new float[0];
    private float[] widths = new float[0];
    private float[][] envelope = new float[4][0];
    private int[] extremaAt = new int[0];
    private float[] extrema = new float[0];
    private float[] edges = new float[64];
    private float[] runs = new float[64];
    private float[] speckEdges = new float[64];
    private float[] speckRuns = new float[64];
    private int runCount;
    private int speckCount;
    private int samples;
    private boolean firstBlack;

    /** How many samples the profile has. */
    int samples() {
        return samples;
    }

    /** How many runs the last threshold left; there is one edge more. */
    int runCount() {
        return runCount;
    }

    /** The widths of the runs, in samples; the array may be longer than {@link #runCount}. */
    float[] runs() {
        return runs;
    }

    /** The edges of the runs, in samples from the start of the line, the first 0 and the last {@code samples - 1}. */
    float[] edges() {
        return edges;
    }

    /** Whether the first run is dark. */
    boolean firstBlack() {
        return firstBlack;
    }

    /**
     * Samples the line from ({@code ax}, {@code ay}) to ({@code bx}, {@code by}) of {@code image},
     * {@code density} samples a pixel and between 32 and {@code limit} in all, each the bilinear
     * mean of four pixels. Outside the image a sample is white.
     */
    void sample(GrayU8 image, double ax, double ay, double bx, double by, double density, int limit) {
        double length = Math.hypot(bx - ax, by - ay);
        samples = (int) Math.max(32, Math.min(Math.min(Math.max(limit, 32), 4096), Math.ceil(length * density)));
        if (values.length < samples) {
            values = new float[samples];
        }
        int maxX = image.width - 1;
        int maxY = image.height - 1;
        byte[] data = image.data;
        for (int i = 0; i < samples; i++) {
            double t = i / (double) (samples - 1);
            double x = ax + (bx - ax) * t;
            double y = ay + (by - ay) * t;
            if (x < 0 || y < 0 || x > maxX || y > maxY) {
                values[i] = 255f;
                continue;
            }
            int x0 = (int) x;
            int y0 = (int) y;
            int x1 = Math.min(x0 + 1, maxX);
            int y1 = Math.min(y0 + 1, maxY);
            double fx = x - x0;
            double fy = y - y0;
            int row0 = image.startIndex + y0 * image.stride;
            int row1 = image.startIndex + y1 * image.stride;
            double top = (data[row0 + x0] & 255) * (1 - fx) + (data[row0 + x1] & 255) * fx;
            double bottom = (data[row1 + x0] & 255) * (1 - fx) + (data[row1 + x1] & 255) * fx;
            values[i] = (float) (top * (1 - fy) + bottom * fy);
        }
        runCount = 0;
    }

    /** Whether the profile spans eight gray levels: below that no threshold makes a transition. */
    boolean hasContrast() {
        float lo = 255f;
        float hi = 0f;
        for (int i = 0; i < samples; i++) {
            lo = Math.min(lo, values[i]);
            hi = Math.max(hi, values[i]);
            if (hi - lo >= 8f) {
                return true;
            }
        }
        return false;
    }

    /** Unsharp mask with a five-sample binomial blur: {@code strength} times the detail is added back. */
    void restoreContrast(float strength) {
        float[] original = copyOfValues();
        int n = samples;
        for (int i = 0; i < n; i++) {
            float smooth = (original[Math.max(i - 2, 0)]
                    + original[Math.max(i - 1, 0)] * 4f
                    + original[i] * 6f
                    + original[Math.min(i + 1, n - 1)] * 4f
                    + original[Math.min(i + 2, n - 1)]) / 16f;
            values[i] = clamp(original[i] + strength * (original[i] - smooth));
        }
    }

    /**
     * Unsharp mask with a wide blur, three running boxes of the given half widths: what stays is
     * the profile without its slow shading.
     */
    void restoreHighpass(int[] halfWidths, float strength) {
        int n = samples;
        if (n == 0) {
            return;
        }
        float[] original = copyOfValues();
        if (blurred.length < n) {
            blurred = new float[n];
            next = new float[n];
        }
        System.arraycopy(original, 0, blurred, 0, n);
        for (int half : halfWidths) {
            float width = 2 * half + 1;
            float sum = 0f;
            for (int j = 0; j <= 2 * half; j++) {
                sum += blurred[Math.min(Math.max(j - half, 0), n - 1)];
            }
            for (int i = 0; i < n; i++) {
                next[i] = sum / width;
                sum += blurred[Math.min(Math.max(i + half + 1, 0), n - 1)] - blurred[Math.min(Math.max(i - half, 0), n - 1)];
            }
            float[] swap = blurred;
            blurred = next;
            next = swap;
        }
        for (int i = 0; i < n; i++) {
            values[i] = clamp(original[i] + strength * (original[i] - blurred[i]));
        }
    }

    /**
     * Undoes a ghost of camera shake: the profile is taken for the sharp one plus a copy of it
     * shifted by {@code lag}, a share of the profile, with {@code amplitude} of its strength. The
     * two-tap kernel is inverted along the profile, or {@code backward} against it.
     */
    void restoreDeghost(float lag, float amplitude, boolean backward) {
        int n = samples;
        int shift = (int) Math.max(1f, Math.round(lag * n));
        if (n <= shift || amplitude < 0f || amplitude >= 1f) {
            return;
        }
        if (backward) {
            reverseValues();
        }
        for (int i = shift; i < n; i++) {
            values[i] = (1f + amplitude) * values[i] - amplitude * values[i - shift];
        }
        for (int i = 0; i < n; i++) {
            values[i] = clamp(values[i]);
        }
        if (backward) {
            reverseValues();
        }
    }

    /**
     * The runs of the profile. With {@code local}, between each peak and the valley after it, and
     * each valley and the peak after it, an edge lies where the profile crosses the level half way
     * between the two; a peak or a valley is one the profile leaves by {@value #SWING} gray levels.
     * Without it, one level cuts the whole profile: half way between its 10th and its 65th
     * percentile, which leaves out a bright surface around a label of lower contrast.
     */
    void threshold(boolean local) {
        int n = samples;
        int count = 0;
        ensureEdges(n + 2);
        edges[count++] = 0f;
        if (n < 2) {
            firstBlack = false;
            runCount = 0;
            return;
        }
        float[] v = values;
        if (local) {
            if (extrema.length < n + 1) {
                extrema = new float[n + 1];
                extremaAt = new int[n + 1];
            }
            int found = 0;
            float low = v[0];
            float high = v[0];
            int lowAt = 0;
            int highAt = 0;
            int direction = 0;
            for (int i = 0; i < n; i++) {
                float value = v[i];
                if (direction >= 0) {
                    if (value >= high) {
                        high = value;
                        highAt = i;
                    }
                    if (high - value >= SWING) {
                        extremaAt[found] = highAt;
                        extrema[found++] = high;
                        direction = -1;
                        low = value;
                        lowAt = i;
                    }
                }
                if (direction <= 0) {
                    if (value <= low) {
                        low = value;
                        lowAt = i;
                    }
                    if (value - low >= SWING) {
                        extremaAt[found] = lowAt;
                        extrema[found++] = low;
                        direction = 1;
                        high = value;
                        highAt = i;
                    }
                }
            }
            extremaAt[found] = direction == 1 ? highAt : lowAt;
            extrema[found++] = direction == 1 ? high : low;
            firstBlack = found > 1 && extrema[0] < extrema[1];
            for (int p = 0; p + 1 < found; p++) {
                float cut = (extrema[p] + extrema[p + 1]) / 2f;
                for (int i = extremaAt[p] + 1; i <= extremaAt[p + 1]; i++) {
                    if ((v[i - 1] >= cut) != (v[i] >= cut)) {
                        edges[count++] = (i - 1) + (cut - v[i - 1]) / (v[i] - v[i - 1]);
                        break;
                    }
                }
            }
        } else {
            int[] histogram = new int[256];
            for (int i = 0; i < n; i++) {
                histogram[Math.min((int) v[i], 255)]++;
            }
            float lo = quantile(histogram, n * 10 / 100);
            float hi = quantile(histogram, n * 65 / 100);
            float cut = (lo + hi) / 2f;
            firstBlack = v[0] < cut;
            if (hi - lo >= 16f) {
                for (int i = 1; i < n; i++) {
                    if ((v[i - 1] >= cut) != (v[i] >= cut)) {
                        edges[count++] = (i - 1) + (cut - v[i - 1]) / (v[i] - v[i - 1]);
                    }
                }
            }
        }
        edges[count++] = n - 1;
        finish(count);
    }

    /**
     * The runs of the profile cut where it crosses the level half way between the lowest and the
     * highest value of a window around each sample, so the level follows the lighting. The window
     * reaches six of the narrower runs of the last threshold to either side, or a 32nd of the
     * profile without one. A window that spans fewer than eight gray levels has no edge.
     */
    void adaptiveThreshold() {
        int n = samples;
        if (n < 2) {
            runCount = 0;
            firstBlack = false;
            return;
        }
        float quartile = lowerQuartileWidth();
        int radius = quartile < 0f
                ? Math.min(Math.max(n / 32, 6), 128)
                : Math.min(Math.max((int) (quartile * 6f), 6), 256);
        int block = 2 * radius + 1;
        for (int k = 0; k < 4; k++) {
            if (envelope[k].length < n) {
                envelope[k] = new float[n];
            }
        }
        float[] v = values;
        // The lowest and highest value from the start of a block up to each sample, and from each
        // sample to the end of its block: a window of one block across two blocks is their union.
        for (int start = 0; start < n; start += block) {
            int end = Math.min(start + block, n);
            float lo = v[start];
            float hi = v[start];
            for (int i = start; i < end; i++) {
                lo = Math.min(lo, v[i]);
                hi = Math.max(hi, v[i]);
                envelope[0][i] = lo;
                envelope[1][i] = hi;
            }
            lo = v[end - 1];
            hi = v[end - 1];
            for (int i = end - 1; i >= start; i--) {
                lo = Math.min(lo, v[i]);
                hi = Math.max(hi, v[i]);
                envelope[2][i] = lo;
                envelope[3][i] = hi;
            }
        }
        ensureEdges(n + 2);
        int count = 0;
        edges[count++] = 0f;
        float a = normalized(0, radius, n);
        firstBlack = a < 0f;
        for (int i = 1; i < n; i++) {
            float b = normalized(i, radius, n);
            if ((a < 0f) != (b < 0f)) {
                edges[count++] = (i - 1) - a / (b - a);
            }
            a = b;
        }
        edges[count++] = n - 1;
        finish(count);
    }

    /**
     * Takes out specks, runs narrower than {@code fraction} of the lower quartile of the run
     * widths: noise on a blurred profile sampled finely crosses the level inside a wide bar and
     * splits it. A speck and the run after it join the run before. The runs as they were are kept
     * for {@link #restoreSpecks}. False, with nothing changed, when there are none.
     */
    boolean removeSpecks(float fraction) {
        int n = runCount;
        if (n < 20) {
            return false;
        }
        // Noise cannot split a run below one sample; a profile whose mean run is narrower is too coarse for specks.
        if (fraction * (edges[n] - edges[0]) < n) {
            return false;
        }
        float width = lowerQuartileWidth();
        if (width < 0f) {
            return false;
        }
        float speck = fraction * width;
        boolean any = false;
        for (int i = 1; i < n - 1 && !any; i++) {
            any = runs[i] < speck;
        }
        if (speck < 1f || !any) {
            return false;
        }
        float[] swapRuns = speckRuns;
        float[] swapEdges = speckEdges;
        speckRuns = runs;
        speckEdges = edges;
        speckCount = n;
        runs = swapRuns.length >= n ? swapRuns : new float[speckRuns.length];
        edges = swapEdges.length >= n + 1 ? swapEdges : new float[speckEdges.length];
        int count = 0;
        runs[count] = speckRuns[0];
        edges[count++] = speckEdges[0];
        int i = 1;
        while (i < n) {
            if (i + 1 < n && speckRuns[i] < speck) {
                runs[count - 1] += speckRuns[i] + speckRuns[i + 1];
                i += 2;
            } else {
                edges[count] = speckEdges[i];
                runs[count++] = speckRuns[i];
                i++;
            }
        }
        edges[count] = speckEdges[n];
        runCount = count;
        return true;
    }

    /** Puts back the runs a successful {@link #removeSpecks} set aside. */
    void restoreSpecks() {
        float[] swapRuns = speckRuns;
        float[] swapEdges = speckEdges;
        speckRuns = runs;
        speckEdges = edges;
        runs = swapRuns;
        edges = swapEdges;
        runCount = speckCount;
    }

    private float normalized(int i, int radius, int n) {
        int left = Math.max(i - radius, 0);
        int right = Math.min(i + radius, n - 1);
        float lo = Math.min(envelope[2][left], envelope[0][right]);
        float hi = Math.max(envelope[3][left], envelope[1][right]);
        return hi - lo >= 8f ? values[i] - (lo + hi) / 2f : 0f;
    }

    /** The lower quartile of the widths of the runs at least a sample wide, or -1 with fewer than twenty. */
    private float lowerQuartileWidth() {
        if (widths.length < runCount) {
            widths = new float[runCount];
        }
        int count = 0;
        for (int i = 0; i < runCount; i++) {
            if (runs[i] >= 1f) {
                widths[count++] = runs[i];
            }
        }
        if (count < 20) {
            return -1f;
        }
        Arrays.sort(widths, 0, count);
        return widths[count / 4];
    }

    private static float quantile(int[] histogram, int target) {
        int count = 0;
        for (int i = 0; i < histogram.length; i++) {
            count += histogram[i];
            if (count > target) {
                return i;
            }
        }
        return 255f;
    }

    private void finish(int edgeCount) {
        runCount = edgeCount - 1;
        if (runs.length < runCount) {
            runs = new float[edges.length];
        }
        for (int i = 0; i < runCount; i++) {
            runs[i] = edges[i + 1] - edges[i];
        }
    }

    private void ensureEdges(int size) {
        if (edges.length < size) {
            edges = new float[size];
        }
        if (runs.length < size) {
            runs = new float[size];
        }
    }

    private float[] copyOfValues() {
        if (scratch.length < samples) {
            scratch = new float[samples];
        }
        System.arraycopy(values, 0, scratch, 0, samples);
        return scratch;
    }

    private void reverseValues() {
        for (int i = 0, j = samples - 1; i < j; i++, j--) {
            float swap = values[i];
            values[i] = values[j];
            values[j] = swap;
        }
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(255f, value));
    }
}
