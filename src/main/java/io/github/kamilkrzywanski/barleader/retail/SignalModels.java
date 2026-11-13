/*
 * Copyright 2026 Florian Nick
 * Copyright 2026 Kamil Krzywański
 */
// SPDX-License-Identifier: Apache-2.0
package io.github.kamilkrzywanski.barleader.retail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * More ways to cut one normalized profile into runs, each read by {@link RunWindows}: without the
 * clusters of specks beside an edge, with the levels following the profile, with the edges
 * between samples, without weak crossings of the level, and with one edge between each two turns
 * of the profile. Ported from tapirscan 1.5.0 ({@code core/src/transition.rs}:
 * {@code decode_clustered_runs}; {@code core/src/local_signal.rs};
 * {@code core/src/multi_profile.rs}: {@code decode_local_variants_validated},
 * {@code weak_excursions}, {@code decode_prepared_short}; {@code core/src/extrema_runs.rs}).
 *
 * <p>None of them adds an edge the profile does not show or looks at digits: they are other
 * readings of the same samples, and what they read still refuses a different reading of the same
 * stretch when the readings are merged.
 */
final class SignalModels {
    /** A turn of the profile counts from this height. */
    private static final float PROMINENCE = 0.04f;
    /** The most samples the extrema look at. */
    private static final int MAX_EXAMINED = 8192;

    /** The runs of the profile with its levels following it, kept for the short quiet zones. */
    static final class Local {
        float[] normalized = new float[512];
        final RunWindows.Runs integer = new RunWindows.Runs();
        final RunWindows.Runs fractional = new RunWindows.Runs();
    }

    private SignalModels() {
    }

    /**
     * The readings of {@code runs} with each cluster of up to four runs under a fifth of the run
     * width around it (its lower quartile over 33 runs) made one edge: between runs of one colour
     * the cluster takes that colour, between a dark and a light run the edge goes where the dark
     * of the cluster's samples puts it. {@code raw}, the readings of the runs as they are, comes
     * back where there is no such cluster.
     */
    static RunWindows.Reads clustered(float[] p, int length, RunWindows.Runs runs, RunWindows.Reads raw) {
        int n = runs.count;
        int[] start = new int[n];
        int[] end = new int[n];
        for (int i = 0; i < n; i++) {
            start[i] = (int) runs.start[i];
            end[i] = (int) runs.end[i];
        }
        List<int[]> changes = new ArrayList<>();
        int[] widths = new int[33];
        int i = 0;
        while (i + 2 < n) {
            int w = end[i + 1] - start[i + 1];
            if (w * 3 >= end[i] - start[i]) {
                i++;
                continue;
            }
            int scale = lowerQuartile(start, end, Math.max(0, i - 15), Math.min(i + 18, n), widths);
            int j = i + 1;
            while (j < n && j <= i + 4 && (end[j] - start[j]) * 5 < scale) {
                j++;
            }
            if (j == i + 1 || j == n) {
                i++;
                continue;
            }
            int left = start[i + 1];
            int right = start[j];
            if ((right - left) * 100 >= 35 * scale) {
                i++;
                continue;
            }
            boolean same = runs.black[i] == runs.black[j];
            if (end[j] - start[j] < (same ? 3 * (right - left) : scale / 2)) {
                i++;
                continue;
            }
            if (same) {
                changes.add(new int[]{left, right, runs.black[i] ? 1 : 0});
            } else {
                double mass = 0;
                for (int k = left; k < right; k++) {
                    mass += p[k];
                }
                int edge = (int) Math.max(left, Math.min(right, Math.round(runs.black[i] ? left + mass : right - mass)));
                changes.add(new int[]{left, edge, runs.black[i] ? 1 : 0});
                changes.add(new int[]{edge, right, runs.black[j] ? 1 : 0});
            }
            i = j;
        }
        if (changes.isEmpty()) {
            return raw.copy();
        }
        boolean[] binary = new boolean[length];
        for (int k = 0; k < length; k++) {
            binary[k] = p[k] >= 0.5f;
        }
        for (int[] change : changes) {
            Arrays.fill(binary, change[0], change[1], change[2] == 1);
        }
        RunWindows.Runs cleaned = new RunWindows.Runs();
        int from = 0;
        boolean value = binary[0];
        for (int b = 1; b <= length; b++) {
            boolean next = b < length && binary[b];
            if (b == length || next != value) {
                cleaned.add(from, b, value);
                from = b;
                value = next;
            }
        }
        return RunWindows.decode(cleaned, false);
    }

    private static int lowerQuartile(int[] start, int[] end, int from, int to, int[] widths) {
        int count = to - from;
        for (int k = 0; k < count; k++) {
            widths[k] = end[from + k] - start[from + k];
        }
        Arrays.sort(widths, 0, count);
        return widths[count / 4];
    }

    /**
     * The readings of {@code p} with its levels following it: each sample set between the least
     * and the greatest within six run widths of it, where those differ by a quarter, then cut at
     * a half with the edges at whole samples and between them, with and without {@code guardBias},
     * and without its weak crossings; and the readings of the edges between the turns of
     * {@code p} itself. The runs stay in {@code scratch} for {@link #preparedShort}.
     */
    static RunWindows.Reads local(float[] p, int length, boolean guardBias, Local scratch) {
        prepare(p, length, scratch);
        float[] q = scratch.normalized;
        RunWindows.Runs integer = scratch.integer;
        RunWindows.Runs fractional = scratch.fractional;
        integer.count = 0;
        fractional.count = 0;
        int start = 0;
        double edge = 0;
        boolean black = q[0] >= 0.5f;
        for (int i = 1; i <= length; i++) {
            boolean next = i < length && q[i] >= 0.5f;
            if (i == length || next != black) {
                double end = i == length ? i : i - 0.5 + (0.5 - (double) q[i - 1]) / ((double) q[i] - (double) q[i - 1]);
                integer.add(start, i, black);
                fractional.add(edge, end, black);
                start = i;
                edge = end;
                black = next;
            }
        }
        RunWindows.Reads reads = RunWindows.merge(RunWindows.decode(fractional, false), RunWindows.decode(integer, false));
        if (guardBias) {
            reads = RunWindows.merge(reads, RunWindows.decode(integer, true));
            reads = RunWindows.merge(reads, RunWindows.decode(fractional, true));
        }
        RunWindows.Runs cleaned = weakExcursions(q, integer);
        if (cleaned != null) {
            reads = RunWindows.merge(reads, both(cleaned, guardBias));
        }
        return RunWindows.merge(reads, extrema(p, length, guardBias));
    }

    /** The symbols between short quiet zones on the runs {@link #local} left in {@code scratch}. */
    static RunWindows.Reads preparedShort(Local scratch, boolean guardBias) {
        RunWindows.Reads reads = RunWindows.merge(RunWindows.decode(scratch.integer, false, true),
                RunWindows.decode(scratch.fractional, false, true));
        if (!guardBias) {
            return reads;
        }
        return RunWindows.merge(reads, RunWindows.merge(RunWindows.decode(scratch.integer, true, true),
                RunWindows.decode(scratch.fractional, true, true)));
    }

    private static RunWindows.Reads both(RunWindows.Runs runs, boolean guardBias) {
        RunWindows.Reads reads = RunWindows.decode(runs, false);
        return guardBias ? RunWindows.merge(reads, RunWindows.decode(runs, true)) : reads;
    }

    /** Sets {@code scratch.normalized}: {@code p} as it is under twenty edges, else with the levels following it. */
    private static void prepare(float[] p, int length, Local scratch) {
        if (scratch.normalized.length < length) {
            scratch.normalized = new float[length];
        }
        float[] out = scratch.normalized;
        int[] histogram = new int[44];
        int runCount = 0;
        int start = 0;
        boolean dark = p[0] >= 0.5f;
        for (int i = 1; i < length; i++) {
            boolean next = p[i] >= 0.5f;
            if (next != dark) {
                histogram[Math.min(i - start, 43)]++;
                runCount++;
                start = i;
                dark = next;
            }
        }
        if (runCount < 20) {
            System.arraycopy(p, 0, out, 0, length);
            return;
        }
        int radius = 6;
        int seen = 0;
        for (int width = 0; width < 44; width++) {
            seen += histogram[width];
            if (seen > runCount / 4) {
                radius = Math.max(6, Math.min(256, width * 6));
                break;
            }
        }
        // The least and greatest of each window of 2 * radius + 1 samples, from those of whole blocks of that length.
        int window = radius * 2 + 1;
        float[] loBefore = new float[length];
        float[] hiBefore = new float[length];
        float[] loAfter = new float[length];
        float[] hiAfter = new float[length];
        for (int begin = 0; begin < length; begin += window) {
            int end = Math.min(begin + window, length);
            float lo = p[begin];
            float hi = p[begin];
            for (int j = begin; j < end; j++) {
                lo = Math.min(lo, p[j]);
                hi = Math.max(hi, p[j]);
                loBefore[j] = lo;
                hiBefore[j] = hi;
            }
            lo = p[end - 1];
            hi = p[end - 1];
            for (int j = end - 1; j >= begin; j--) {
                lo = Math.min(lo, p[j]);
                hi = Math.max(hi, p[j]);
                loAfter[j] = lo;
                hiAfter[j] = hi;
            }
        }
        for (int i = 0; i < length; i++) {
            int a = Math.max(0, i - radius);
            int b = Math.min(i + radius, length - 1);
            float lo;
            float hi;
            if (a / window != b / window) {
                lo = Math.min(loBefore[b], loAfter[a]);
                hi = Math.max(hiBefore[b], hiAfter[a]);
            } else if (a % window == 0) {
                lo = loBefore[b];
                hi = hiBefore[b];
            } else {
                // Within one block a window starts the block or ends it, or ends the profile.
                lo = loAfter[a];
                hi = hiAfter[a];
            }
            out[i] = hi - lo >= 0.25f ? Math.max(0f, Math.min(1f, (p[i] - lo) / (hi - lo))) : p[i];
        }
    }

    /**
     * {@code runs} with each weak run joined to its neighbours: one that stays within a tenth of
     * the level, under half the run width around it, between two runs at least twice as wide that
     * each reach a quarter from their own end of the scale. Null where there is none.
     */
    private static RunWindows.Runs weakExcursions(float[] profile, RunWindows.Runs runs) {
        int n = runs.count;
        int[] start = new int[n];
        int[] end = new int[n];
        for (int i = 0; i < n; i++) {
            start[i] = (int) runs.start[i];
            end[i] = (int) runs.end[i];
        }
        RunWindows.Runs out = null;
        int[] widths = new int[33];
        int i = 0;
        while (i < n) {
            if (i + 2 < n) {
                int a = start[i + 1];
                int b = end[i + 1];
                boolean dark = runs.black[i + 1];
                int width = b - a;
                if (end[i] - start[i] >= 2 * width && end[i + 2] - start[i + 2] >= 2 * width && weak(profile, a, b)) {
                    int scale = lowerQuartile(start, end, Math.max(0, i - 15), Math.min(i + 18, n), widths);
                    if (width * 2 < scale && strong(profile, start[i], end[i], dark) && strong(profile, start[i + 2], end[i + 2], dark)) {
                        if (out == null) {
                            out = new RunWindows.Runs();
                            for (int k = 0; k < i; k++) {
                                out.add(start[k], end[k], runs.black[k]);
                            }
                        }
                        out.add(start[i], end[i + 2], runs.black[i]);
                        i += 3;
                        continue;
                    }
                }
            }
            if (out != null) {
                if (out.count > 0 && out.black[out.count - 1] == runs.black[i]) {
                    out.end[out.count - 1] = end[i];
                } else {
                    out.add(start[i], end[i], runs.black[i]);
                }
            }
            i++;
        }
        return out;
    }

    private static boolean weak(float[] profile, int from, int to) {
        for (int k = from; k < to; k++) {
            if (Math.abs(profile[k] - 0.5f) > 0.10f) {
                return false;
            }
        }
        return true;
    }

    /** Whether a run beside a {@code dark} weak one is clearly light somewhere, or beside a light one clearly dark. */
    private static boolean strong(float[] profile, int from, int to, boolean dark) {
        for (int k = from; k < to; k++) {
            if (dark ? profile[k] <= 0.25f : profile[k] >= 0.75f) {
                return true;
            }
        }
        return false;
    }

    /**
     * The readings of {@code p} cut between its turns: a turn is a peak or a valley the profile
     * leaves by at least {@link #PROMINENCE}, and between each two the edge is where the profile
     * crosses the level halfway between them. Where it crosses more than once, the runs so far
     * are read on their own and only clear readings count. For profiles of 45 to 512 runs.
     */
    static RunWindows.Reads extrema(float[] p, int length, boolean guardBias) {
        int raw = 1;
        for (int i = 1; i < length; i++) {
            raw += p[i - 1] >= 0.5f != p[i] >= 0.5f ? 1 : 0;
        }
        if (raw < 45 || raw > 512) {
            return new RunWindows.Reads();
        }
        int examined = 0;
        int[] at = new int[length + 1];
        boolean[] high = new boolean[length + 1];
        int count = 0;
        int low = 0;
        int top = 0;
        int direction = 0;
        int first = 0;
        int last = 0;
        for (int i = 1; i < length; i++) {
            if (examined++ == MAX_EXAMINED) {
                return new RunWindows.Reads();
            }
            if (direction == 0) {
                if (p[i] < p[low]) {
                    low = i;
                }
                if (p[i] > p[top]) {
                    top = i;
                }
                if (p[i] - p[low] >= PROMINENCE) {
                    at[count] = low;
                    high[count++] = false;
                    direction = 1;
                    first = i;
                    last = i;
                } else if (p[top] - p[i] >= PROMINENCE) {
                    at[count] = top;
                    high[count++] = true;
                    direction = -1;
                    first = i;
                    last = i;
                }
            } else {
                boolean rising = direction == 1;
                if (rising ? p[i] > p[first] : p[i] < p[first]) {
                    first = i;
                    last = i;
                } else if (p[i] == p[first] && i == last + 1) {
                    last = i;
                }
                if ((rising ? p[first] - p[i] : p[i] - p[first]) >= PROMINENCE) {
                    at[count] = (first + last) / 2;
                    high[count++] = rising;
                    direction = -direction;
                    first = i;
                    last = i;
                }
            }
        }
        if (direction != 0) {
            at[count] = (first + last) / 2;
            high[count++] = direction == 1;
        }
        if (count < 2) {
            return new RunWindows.Reads();
        }
        double start = 0;
        boolean pendingStart = false;
        boolean ambiguous = false;
        List<RunWindows.Reads> chunks = new ArrayList<>();
        RunWindows.Runs runs = new RunWindows.Runs();
        for (int k = 1; k < count; k++) {
            int a = at[k - 1];
            int b = at[k];
            double threshold = ((double) p[a] + (double) p[b]) / 2;
            double crossing = Double.NaN;
            for (int j = a + 1; j <= b; j++) {
                if (examined++ == MAX_EXAMINED) {
                    return new RunWindows.Reads();
                }
                double v = p[j - 1];
                double w = p[j];
                if (v >= threshold != w >= threshold) {
                    // More than one crossing: which of them is the edge is not for the digits to say.
                    if (!Double.isNaN(crossing)) {
                        crossing = Double.NaN;
                        break;
                    }
                    crossing = j - 0.5 + (threshold - v) / (w - v);
                }
            }
            if (Double.isNaN(crossing)) {
                ambiguous = true;
                chunks.add(both(runs, guardBias));
                runs = new RunWindows.Runs();
                pendingStart = true;
                continue;
            }
            if (pendingStart) {
                start = crossing;
                pendingStart = false;
                continue;
            }
            if (crossing <= start) {
                return new RunWindows.Reads();
            }
            runs.add(start, crossing, high[k - 1]);
            start = crossing;
        }
        if (!pendingStart) {
            runs.add(start, length, high[count - 1]);
        }
        RunWindows.Reads reads = both(runs, guardBias);
        if (ambiguous) {
            reads.symbols.removeIf(read -> read.cost() > 0.06f || read.gap() < 0.1f);
            for (RunWindows.Reads chunk : chunks) {
                chunk.symbols.removeIf(read -> read.cost() > 0.06f || read.gap() < 0.1f);
                reads = RunWindows.merge(reads, chunk);
            }
        }
        return reads;
    }
}
