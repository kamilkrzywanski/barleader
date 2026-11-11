/*
 * Copyright 2026 Florian Nick
 * Copyright 2026 Kamil Krzywański
 */
// SPDX-License-Identifier: Apache-2.0
package io.github.kamilkrzywanski.barleader;

import io.github.kamilkrzywanski.barleader.image.GrayU8;
import io.github.kamilkrzywanski.barleader.image.PixelMap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Locator for linear symbols, ported from tapirscan 1.5.0
 * ({@code bindings/rust/src/pipeline/segment_voting.rs}). A linear symbol is a family of parallel,
 * similarly long edge segments whose centres lie on one line across the bars. Strong Scharr edges
 * on a raster of at most 768 pixels are labelled into elongated segments by polarity-aware
 * orientation, and segments grown from the longest unused seed form one cluster per symbol. Each
 * cluster becomes a parallelogram, sampled from the original with the bars vertical. Split
 * clusters of one symbol are merged, and collinear fragments also get their union.
 */
final class BarSegments {
    private static final double WORK = 768;
    private static final int BINS = 16;
    private static final float EDGE = 250;
    private static final double MIN_LENGTH = 6;
    private static final double ELONGATION = 3;
    private static final double PARALLEL = Math.toRadians(8);
    private static final double ALONG = 0.35;
    private static final double GAP = 0.6;
    private static final int MIN_SEGMENTS = 6;
    private static final double PAD = 0.08;
    private static final double MERGE_GAP = 0.3;
    private static final int MAX_PROPOSALS = 24;
    private static final double SMALL_BOX = 0.03;
    private static final int SMALL_MEMBERS = 10;
    /** Pixels of quiet zone sampled on each end of a crop. */
    private static final int END = 20;
    private static final int MAX_WIDTH = 2400;
    private static final int MAX_HEIGHT = 1200;
    private static final int MIN_HEIGHT = 12;

    private BarSegments() {
    }

    private record Segment(double x, double y, double angle, double length) {
    }

    /** Corners in source pixels, the first edge across the bars, and the segments that voted for it. */
    private static final class Box {
        double[][] quad;
        int members;

        Box(double[][] quad, int members) {
            this.quad = quad;
            this.members = members;
        }
    }

    /** Up to {@code limit} crops, the clusters with the most segments first. */
    static List<LinearBand.Crop> find(GrayU8 gray, int limit) {
        return find(gray, limit, Fanout.sequential());
    }

    /**
     * {@link #find(GrayU8, int)} with the gradients measured in bands of rows and the first crops
     * sampled side by side on {@code fanout}: the same crops.
     */
    static List<LinearBand.Crop> find(GrayU8 gray, int limit, Fanout fanout) {
        double scale = Math.min(1, WORK / Math.max(gray.width, gray.height));
        int w = Math.max(3, (int) Math.round(gray.width * scale));
        int h = Math.max(3, (int) Math.round(gray.height * scale));
        float[] raster = raster(gray, w, h, scale);
        List<Segment> segments = segments(raster, w, h, fanout);
        if (segments.isEmpty()) {
            return List.of();
        }
        // The first crops side by side, and the next ones after them only while some were empty.
        List<Box> boxes = clusters(segments, w, h, scale);
        LinearBand.Crop[] first = new LinearBand.Crop[Math.min(limit, boxes.size())];
        List<Runnable> sampled = new ArrayList<>(first.length);
        for (int k = 0; k < first.length; k++) {
            int index = k;
            sampled.add(() -> first[index] = crop(gray, boxes.get(index).quad));
        }
        fanout.runAll(sampled);
        List<LinearBand.Crop> crops = new ArrayList<>();
        for (int k = 0; k < boxes.size(); k++) {
            if (crops.size() >= limit) {
                break;
            }
            LinearBand.Crop crop = k < first.length ? first[k] : crop(gray, boxes.get(k).quad);
            if (crop != null) {
                crops.add(crop);
            }
        }
        return crops;
    }

    /**
     * A crop of {@link #find} sampled again from its box made {@code times} as long, about its
     * centre. The bars an edge cluster takes in can stop short of a symbol's ends: of 60 turned
     * photos of the public tapirscan benchmark that fast mode missed, mostly EAN-8, 7 read on the
     * crops and 22 on the crops 1.8 times as long; 1.3 and 2 times read 13 and 20. Fitting the box
     * to the bars first, after tapirscan's {@code shear::refine}, read fewer and none of the others.
     */
    static LinearBand.Crop lengthened(GrayU8 gray, LinearBand.Crop crop, double times) {
        GrayU8 image = crop.image();
        PixelMap map = crop.toSource();
        ImagePoint a = map.toSource(END, 0);
        ImagePoint b = map.toSource(image.width - END, 0);
        ImagePoint d = map.toSource(END, image.height);
        double ex = (b.x() - a.x()) * (times - 1) / 2;
        double ey = (b.y() - a.y()) * (times - 1) / 2;
        double vx = d.x() - a.x();
        double vy = d.y() - a.y();
        return crop(gray, new double[][]{
                {a.x() - ex, a.y() - ey},
                {b.x() + ex, b.y() + ey},
                {b.x() + ex + vx, b.y() + ey + vy},
                {a.x() - ex + vx, a.y() - ey + vy},
        });
    }

    private static float[] raster(GrayU8 gray, int w, int h, double scale) {
        float[] out = new float[w * h];
        if (scale >= 1) {
            // The raster is at least 3×3, so an image one or two pixels thin repeats its last row or column.
            for (int y = 0; y < h; y++) {
                int row = gray.startIndex + Math.min(y, gray.height - 1) * gray.stride;
                for (int x = 0; x < w; x++) {
                    out[y * w + x] = gray.data[row + Math.min(x, gray.width - 1)] & 255;
                }
            }
            return out;
        }
        // Two by two samples per cell: single samples alias bars under a working pixel.
        int[] x0 = new int[w];
        int[] x1 = new int[w];
        for (int x = 0; x < w; x++) {
            x0[x] = Math.min((int) ((x + 0.25) / scale), gray.width - 1);
            x1[x] = Math.min((int) ((x + 0.75) / scale), gray.width - 1);
        }
        for (int y = 0; y < h; y++) {
            int row0 = gray.startIndex + Math.min((int) ((y + 0.25) / scale), gray.height - 1) * gray.stride;
            int row1 = gray.startIndex + Math.min((int) ((y + 0.75) / scale), gray.height - 1) * gray.stride;
            for (int x = 0; x < w; x++) {
                int sum = (gray.data[row0 + x0[x]] & 255) + (gray.data[row0 + x1[x]] & 255)
                        + (gray.data[row1 + x0[x]] & 255) + (gray.data[row1 + x1[x]] & 255);
                out[y * w + x] = sum / 4f;
            }
        }
        return out;
    }

    /** Polynomial arctangent, ample for bins of 22.5 degrees. */
    private static float fastAngle(float gx, float gy) {
        float ax = Math.abs(gx);
        float ay = Math.abs(gy);
        float least = Math.min(ax, ay);
        float most = Math.max(Math.max(ax, ay), 1e-9f);
        float t = least / most;
        float r = t * (0.9724f - 0.1919f * t * t);
        if (ay > ax) {
            r = (float) (Math.PI / 2) - r;
        }
        if (gx < 0) {
            r = (float) Math.PI - r;
        }
        if (gy < 0) {
            r = -r;
        }
        return r;
    }

    private static List<Segment> segments(float[] g, int w, int h, Fanout fanout) {
        byte[] bin = new byte[w * h];
        Arrays.fill(bin, (byte) -1);
        // The strong pixels of each band of rows, joined in the order of the rows.
        int bands = fanout.parallel() ? Math.max(1, Math.min(BANDS, (h - 2) / BAND_ROWS)) : 1;
        int[][] strongOf = new int[bands][];
        int[] countOf = new int[bands];
        List<Runnable> measured = new ArrayList<>(bands);
        for (int band = 0; band < bands; band++) {
            int index = band;
            int from = 1 + (int) ((long) (h - 2) * band / bands);
            int to = 1 + (int) ((long) (h - 2) * (band + 1) / bands);
            measured.add(() -> {
                int[] strong = new int[Math.max(0, to - from) * w];
                countOf[index] = gradients(g, w, from, to, bin, strong);
                strongOf[index] = strong;
            });
        }
        fanout.runAll(measured);
        int strongCount = 0;
        for (int count : countOf) {
            strongCount += count;
        }
        int[] strong = new int[strongCount];
        for (int band = 0, at = 0; band < bands; band++) {
            System.arraycopy(strongOf[band], 0, strong, at, countOf[band]);
            at += countOf[band];
        }
        return segments(bin, w, strong, strongCount);
    }

    /** Bands {@link #segments} measures its gradients in at most, and the rows a band holds at least. */
    private static final int BANDS = 8;
    private static final int BAND_ROWS = 32;

    /** The gradient bins of rows {@code from} to {@code to} into {@code bin}, and their strong pixels, in order, into {@code strong}; returns how many. */
    private static int gradients(float[] g, int w, int from, int to, byte[] bin, int[] strong) {
        int strongCount = 0;
        float limit = EDGE * EDGE;
        for (int y = from; y < to; y++) {
            int r0 = (y - 1) * w;
            int r1 = y * w;
            int r2 = (y + 1) * w;
            for (int x = 1; x < w - 1; x++) {
                float gx = 3 * (g[r0 + x + 1] - g[r0 + x - 1])
                        + 10 * (g[r1 + x + 1] - g[r1 + x - 1])
                        + 3 * (g[r2 + x + 1] - g[r2 + x - 1]);
                float gy = 3 * (g[r2 + x - 1] - g[r0 + x - 1])
                        + 10 * (g[r2 + x] - g[r0 + x])
                        + 3 * (g[r2 + x + 1] - g[r0 + x + 1]);
                if (gx * gx + gy * gy > limit) {
                    float a = fastAngle(gx, gy);
                    int i = r1 + x;
                    bin[i] = (byte) ((int) (((a + (float) Math.PI) / (float) (2 * Math.PI)) * BINS) % BINS);
                    strong[strongCount++] = i;
                }
            }
        }
        return strongCount;
    }

    /** The segments grown from the strong pixels, in their order. */
    private static List<Segment> segments(byte[] bin, int w, int[] strong, int strongCount) {
        int[] stack = new int[strongCount + 8];
        long[] sums = new long[6];
        List<Segment> out = new ArrayList<>();
        for (int s = 0; s < strongCount; s++) {
            int seed = strong[s];
            if (bin[seed] < 0) {
                continue;
            }
            grow(bin, w, seed, stack, sums);
            double c = sums[0];
            if (c < MIN_LENGTH) {
                continue;
            }
            double mx = sums[1] / c;
            double my = sums[2] / c;
            double vxx = sums[3] / c - mx * mx;
            double vyy = sums[4] / c - my * my;
            double vxy = sums[5] / c - mx * my;
            double trace = vxx + vyy;
            double det = vxx * vyy - vxy * vxy;
            double disc = Math.sqrt(Math.max(0, trace * trace / 4 - det));
            double l1 = trace / 2 + disc;
            double l2 = Math.max(trace / 2 - disc, 1e-6);
            double length = 4 * Math.sqrt(l1);
            double width = 4 * Math.sqrt(l2);
            if (length < MIN_LENGTH || length < ELONGATION * width) {
                continue;
            }
            double angle = 0.5 * Math.atan2(2 * vxy, vxx - vyy);
            angle = ((angle % Math.PI) + Math.PI) % Math.PI;
            out.add(new Segment(mx, my, angle, length));
        }
        return out;
    }

    /** Claims the pixels connected to {@code seed} through bins within one circular step of its own. */
    private static void grow(byte[] bin, int w, int seed, int[] stack, long[] sums) {
        int k = bin[seed];
        int near = (7 << k | 7 << (k + BINS)) >>> 1;
        near = (near | near >>> BINS) & 0xFFFF;
        bin[seed] = -1;
        stack[0] = seed;
        int len = 1;
        Arrays.fill(sums, 0);
        while (len > 0) {
            int i = stack[--len];
            long x = i % w;
            long y = i / w;
            sums[0]++;
            sums[1] += x;
            sums[2] += y;
            sums[3] += x * x;
            sums[4] += y * y;
            sums[5] += x * y;
            for (int dy = -w; dy <= w; dy += w) {
                for (int dx = -1; dx <= 1; dx++) {
                    int j = i + dy + dx;
                    int b = bin[j];
                    if (b >= 0 && (near >> b & 1) != 0) {
                        bin[j] = -1;
                        stack[len++] = j;
                    }
                }
            }
        }
    }

    private static final class Grid {
        final int[] starts;
        final int[] items;
        final int width;
        final int height;
        final double cell;

        Grid(List<Segment> segs, int w, int h, double cell) {
            this.cell = cell;
            width = (int) (w / cell) + 1;
            height = (int) (h / cell) + 1;
            starts = new int[width * height + 1];
            for (Segment s : segs) {
                starts[index(s) + 1]++;
            }
            for (int c = 0; c < width * height; c++) {
                starts[c + 1] += starts[c];
            }
            int[] fill = starts.clone();
            items = new int[segs.size()];
            for (int i = 0; i < segs.size(); i++) {
                int c = index(segs.get(i));
                items[fill[c]++] = i;
            }
        }

        private int index(Segment s) {
            return (int) (s.y / cell) * width + (int) (s.x / cell);
        }

        /** Unused segments parallel to seed {@code i} and aligned along its bars: offset across them, index. */
        void candidates(List<Segment> segs, boolean[] used, int i, List<double[]> out) {
            Segment a = segs.get(i);
            double va0 = Math.cos(a.angle);
            double va1 = Math.sin(a.angle);
            double ua0 = -va1;
            double ua1 = va0;
            int r = (int) Math.ceil(4 * a.length / cell);
            int cx = (int) (a.x / cell);
            int cy = (int) (a.y / cell);
            double reach = ALONG * a.length;
            out.clear();
            for (int gy = Math.max(cy - r, 0); gy <= Math.min(cy + r, height - 1); gy++) {
                int x0 = cx - r;
                int x1 = cx + r;
                if (Math.abs(va0) > 1e-3) {
                    double y0 = gy * cell - a.y;
                    double y1 = (gy + 1) * cell - a.y;
                    double e0 = (-reach - y0 * va1) / va0;
                    double e1 = (reach - y0 * va1) / va0;
                    double e2 = (-reach - y1 * va1) / va0;
                    double e3 = (reach - y1 * va1) / va0;
                    double lo = Math.min(Math.min(e0, e1), Math.min(e2, e3)) + a.x;
                    double hi = Math.max(Math.max(e0, e1), Math.max(e2, e3)) + a.x;
                    x0 = Math.max(x0, (int) Math.floor(lo / cell) - 1);
                    x1 = Math.min(x1, (int) Math.floor(hi / cell) + 1);
                }
                x0 = Math.max(x0, 0);
                x1 = Math.min(x1, width - 1);
                if (x0 > x1) {
                    continue;
                }
                int row = gy * width;
                for (int k = starts[row + x0]; k < starts[row + x1 + 1]; k++) {
                    int j = items[k];
                    if (used[j]) {
                        continue;
                    }
                    Segment b = segs.get(j);
                    double da = Math.abs(a.angle - b.angle);
                    da = Math.min(da, Math.PI - da);
                    if (da > PARALLEL || b.length < 0.5 * a.length || b.length > 2 * a.length) {
                        continue;
                    }
                    double dx = b.x - a.x;
                    double dy = b.y - a.y;
                    if (Math.abs(dx * va0 + dy * va1) < reach) {
                        out.add(new double[]{dx * ua0 + dy * ua1, j});
                    }
                }
            }
        }
    }

    private static List<Box> clusters(List<Segment> segs, int w, int h, double scale) {
        int n = segs.size();
        double[] lengths = new double[n];
        for (int i = 0; i < n; i++) {
            lengths[i] = segs.get(i).length;
        }
        double[] sorted = lengths.clone();
        Arrays.sort(sorted);
        Grid grid = new Grid(segs, w, h, Math.max(sorted[n / 2], 4));
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (a, b) -> Double.compare(lengths[b], lengths[a]));
        boolean[] used = new boolean[n];
        List<Box> out = new ArrayList<>();
        List<double[]> cand = new ArrayList<>();
        Comparator<double[]> byOffset = Comparator.comparingDouble(c -> c[0]);
        for (int i : order) {
            if (used[i]) {
                continue;
            }
            Segment a = segs.get(i);
            double va0 = Math.cos(a.angle);
            double va1 = Math.sin(a.angle);
            double ua0 = -va1;
            double ua1 = va0;
            grid.candidates(segs, used, i, cand);
            if (cand.size() < MIN_SEGMENTS) {
                continue;
            }
            cand.sort(byOffset);
            int seed = -1;
            for (int k = 0; k < cand.size(); k++) {
                if ((int) cand.get(k)[1] == i) {
                    seed = k;
                    break;
                }
            }
            if (seed < 0) {
                continue;
            }
            double limit = GAP * a.length;
            int lo = seed;
            while (lo > 0 && cand.get(lo)[0] - cand.get(lo - 1)[0] < limit) {
                lo--;
            }
            int hi = seed;
            while (hi + 1 < cand.size() && cand.get(hi + 1)[0] - cand.get(hi)[0] < limit) {
                hi++;
            }
            int count = hi - lo + 1;
            if (count < MIN_SEGMENTS) {
                continue;
            }
            int[] members = new int[count];
            double[] pv = new double[count];
            double[] ls = new double[count];
            for (int k = 0; k < count; k++) {
                int m = (int) cand.get(lo + k)[1];
                members[k] = m;
                used[m] = true;
                Segment s = segs.get(m);
                pv[k] = (s.x - a.x) * va0 + (s.y - a.y) * va1;
                ls[k] = s.length;
            }
            double pad = PAD * (cand.get(hi)[0] - cand.get(lo)[0]);
            double u0 = cand.get(lo)[0] - pad;
            double u1 = cand.get(hi)[0] + pad;
            Arrays.sort(pv);
            Arrays.sort(ls);
            double vc = pv[count / 2];
            double l = ls[count / 2];
            double[][] polygon = parallelogram(segs, members, new double[]{ua0, ua1}, new double[]{va0, va1}, l, scale);
            if (polygon == null) {
                polygon = new double[][]{
                        corner(a, u0, vc - l / 2, ua0, ua1, va0, va1, scale),
                        corner(a, u1, vc - l / 2, ua0, ua1, va0, va1, scale),
                        corner(a, u1, vc + l / 2, ua0, ua1, va0, va1, scale),
                        corner(a, u0, vc + l / 2, ua0, ua1, va0, va1, scale)};
            }
            out.add(new Box(polygon, count));
        }
        out.sort((x, y) -> y.members - x.members);
        List<Box> merged = mergeSplit(out);
        return limitBoxes(merged, w * (double) h / (scale * scale));
    }

    private static double[] corner0(double x, double y) {
        return new double[]{x, y};
    }

    private static double[] corner(Segment a, double uu, double vv, double ua0, double ua1, double va0, double va1, double scale) {
        return corner0((a.x + uu * ua0 + vv * va0) / scale, (a.y + uu * ua1 + vv * va1) / scale);
    }

    /** Scan axis from the member centres, unless a narrow cluster spreads along its bars; bar direction from their angles. */
    private static double[][] parallelogram(List<Segment> segs, int[] members, double[] across, double[] along, double barLength, double scale) {
        double k = members.length;
        double mx = 0;
        double my = 0;
        for (int m : members) {
            mx += segs.get(m).x / k;
            my += segs.get(m).y / k;
        }
        double cxx = 0;
        double cyy = 0;
        double cxy = 0;
        for (int m : members) {
            double dx = segs.get(m).x - mx;
            double dy = segs.get(m).y - my;
            cxx += dx * dx;
            cyy += dy * dy;
            cxy += dx * dy;
        }
        double theta = 0.5 * Math.atan2(2 * cxy, cxx - cyy);
        double d0 = Math.cos(theta);
        double d1 = Math.sin(theta);
        if (d0 * across[0] + d1 * across[1] < 0) {
            d0 = -d0;
            d1 = -d1;
        }
        if (d0 * across[0] + d1 * across[1] < Math.cos(Math.toRadians(30))) {
            d0 = across[0];
            d1 = across[1];
        }
        double c2 = 0;
        double s2 = 0;
        for (int m : members) {
            Segment s = segs.get(m);
            c2 += s.length * Math.cos(2 * s.angle);
            s2 += s.length * Math.sin(2 * s.angle);
        }
        double bar = 0.5 * Math.atan2(s2, c2);
        double vb0 = Math.cos(bar);
        double vb1 = Math.sin(bar);
        if (vb0 * along[0] + vb1 * along[1] < 0) {
            vb0 = -vb0;
            vb1 = -vb1;
        }
        double cross = d0 * vb1 - d1 * vb0;
        if (Math.abs(cross) <= 0.3) {
            return null;
        }
        double[] ts = new double[members.length];
        double[] ws = new double[members.length];
        for (int i = 0; i < members.length; i++) {
            double ex = segs.get(members[i]).x - mx;
            double ey = segs.get(members[i]).y - my;
            ts[i] = (ex * vb1 - ey * vb0) / cross;
            ws[i] = (d0 * ey - d1 * ex) / cross;
        }
        Arrays.sort(ts);
        Arrays.sort(ws);
        double pad = PAD * (ts[ts.length - 1] - ts[0]);
        double t0 = ts[0] - pad;
        double t1 = ts[ts.length - 1] + pad;
        double wc = ws[ws.length / 2];
        double l = barLength;
        double[][] q = new double[4][];
        double[][] tw = {{t0, wc - l / 2}, {t1, wc - l / 2}, {t1, wc + l / 2}, {t0, wc + l / 2}};
        for (int i = 0; i < 4; i++) {
            q[i] = corner0((mx + tw[i][0] * d0 + tw[i][1] * vb0) / scale, (my + tw[i][0] * d1 + tw[i][1] * vb1) / scale);
        }
        return q;
    }

    /** Joins split clusters of one symbol: parallel, close across the scan axis and adjacent along it. */
    private static List<Box> mergeSplit(List<Box> boxes) {
        List<Box> merged = new ArrayList<>();
        for (Box box : boxes) {
            double[][] q = box.quad;
            boolean absorbed = false;
            for (Box kept : merged) {
                double[][] a = kept.quad;
                double ux = a[1][0] - a[0][0];
                double uy = a[1][1] - a[0][1];
                double vx = a[3][0] - a[0][0];
                double vy = a[3][1] - a[0][1];
                double wa = Math.hypot(ux, uy);
                double ha = Math.hypot(vx, vy);
                if (wa < 1e-6 || ha < 1e-6) {
                    continue;
                }
                double u0 = ux / wa;
                double u1 = uy / wa;
                double v0 = vx / ha;
                double v1 = vy / ha;
                double bx = q[1][0] - q[0][0];
                double by = q[1][1] - q[0][1];
                double bw = Math.max(Math.hypot(bx, by), 1e-6);
                if (Math.abs((u0 * by - u1 * bx) / bw) > Math.sin(Math.toRadians(5))) {
                    continue;
                }
                double[] ca = centre(a);
                double[] cb = centre(q);
                double dv = u0 * (cb[1] - ca[1]) - u1 * (cb[0] - ca[0]);
                double hp = Math.abs(u0 * vy - u1 * vx);
                double b0 = Double.POSITIVE_INFINITY;
                double b1 = Double.NEGATIVE_INFINITY;
                for (double[] c : q) {
                    double p = (c[0] - a[0][0]) * u0 + (c[1] - a[0][1]) * u1;
                    b0 = Math.min(b0, p);
                    b1 = Math.max(b1, p);
                }
                if (Math.abs(dv) > 0.3 * hp || Math.max(b0 - wa, -b1) > MERGE_GAP * ha) {
                    continue;
                }
                double lo = Math.min(b0, 0);
                double hi = Math.max(b1, wa);
                double ax = a[0][0];
                double ay = a[0][1];
                kept.quad = new double[][]{
                        corner0(ax + lo * u0, ay + lo * u1),
                        corner0(ax + hi * u0, ay + hi * u1),
                        corner0(ax + hi * u0 + ha * v0, ay + hi * u1 + ha * v1),
                        corner0(ax + lo * u0 + ha * v0, ay + lo * u1 + ha * v1)};
                kept.members += box.members;
                absorbed = true;
                break;
            }
            if (!absorbed) {
                merged.add(box);
            }
        }
        merged.sort((x, y) -> y.members - x.members);
        return merged;
    }

    private static double[] centre(double[][] q) {
        return new double[]{(q[0][0] + q[1][0] + q[2][0] + q[3][0]) / 4, (q[0][1] + q[1][1] + q[2][1] + q[3][1]) / 4};
    }

    private static double area(double[][] q) {
        double sum = 0;
        for (int k = 0; k < 4; k++) {
            sum += q[k][0] * q[(k + 1) % 4][1] - q[(k + 1) % 4][0] * q[k][1];
        }
        return Math.abs(sum) / 2;
    }

    /** The first boxes, plus the unions of their collinear fragments; a small box needs enough segments. */
    private static List<Box> limitBoxes(List<Box> boxes, double frame) {
        List<Box> out = new ArrayList<>(boxes.subList(0, Math.min(MAX_PROPOSALS, boxes.size())));
        out.addAll(fragmentUnions(out));
        out.removeIf(b -> b.members < SMALL_MEMBERS && area(b.quad) < SMALL_BOX * frame);
        return out;
    }

    /** Collinear fragments of one symbol, split by blur, glare or a border, also get their union box. */
    private static List<Box> fragmentUnions(List<Box> boxes) {
        int considered = Math.min(12, boxes.size());
        List<Box> unions = new ArrayList<>();
        for (int i = 0; i < considered; i++) {
            for (int j = i + 1; j < considered; j++) {
                if (unions.size() == 4) {
                    return unions;
                }
                double[][] a = boxes.get(i).quad;
                double[][] b = boxes.get(j).quad;
                double[] fa = frame(a);
                double[] fb = frame(b);
                double ua0 = fa[0];
                double ua1 = fa[1];
                double wa = fa[2];
                double ha = fa[3];
                double wb = fb[2];
                double hb = fb[3];
                if (Math.abs(ua0 * fb[1] - ua1 * fb[0]) > Math.sin(Math.toRadians(10))) {
                    continue;
                }
                double n0 = -ua1;
                double n1 = ua0;
                double[] ca = centre(a);
                double[] cb = centre(b);
                double acrossA = (ca[0] - a[0][0]) * n0 + (ca[1] - a[0][1]) * n1;
                double acrossB = (cb[0] - a[0][0]) * n0 + (cb[1] - a[0][1]) * n1;
                if (Math.abs(acrossB - acrossA) > 0.5 * Math.max(ha, hb)) {
                    continue;
                }
                double b0 = Double.POSITIVE_INFINITY;
                double b1 = Double.NEGATIVE_INFINITY;
                for (double[] p : b) {
                    double t = (p[0] - a[0][0]) * ua0 + (p[1] - a[0][1]) * ua1;
                    b0 = Math.min(b0, t);
                    b1 = Math.max(b1, t);
                }
                double lo = Math.min(b0, 0);
                double hi = Math.max(b1, wa);
                if (Math.max(b0 - wa, -b1) > 0.3 * Math.max(wa, wb) || hi - lo < 1.1 * Math.max(wa, b1 - b0)) {
                    continue;
                }
                double c0 = Double.POSITIVE_INFINITY;
                double c1 = Double.NEGATIVE_INFINITY;
                for (double[][] q : new double[][][]{a, b}) {
                    for (double[] p : q) {
                        double d = (p[0] - a[0][0]) * n0 + (p[1] - a[0][1]) * n1;
                        c0 = Math.min(c0, d);
                        c1 = Math.max(c1, d);
                    }
                }
                double ax = a[0][0];
                double ay = a[0][1];
                unions.add(new Box(new double[][]{
                        corner0(ax + lo * ua0 + c0 * n0, ay + lo * ua1 + c0 * n1),
                        corner0(ax + hi * ua0 + c0 * n0, ay + hi * ua1 + c0 * n1),
                        corner0(ax + hi * ua0 + c1 * n0, ay + hi * ua1 + c1 * n1),
                        corner0(ax + lo * ua0 + c1 * n0, ay + lo * ua1 + c1 * n1)},
                        boxes.get(i).members + boxes.get(j).members));
            }
        }
        return unions;
    }

    /** Unit scan axis, width, and extent across it. */
    private static double[] frame(double[][] q) {
        double ux = q[1][0] - q[0][0];
        double uy = q[1][1] - q[0][1];
        double width = Math.max(Math.hypot(ux, uy), 1e-9);
        double u0 = ux / width;
        double u1 = uy / width;
        double lo = Double.POSITIVE_INFINITY;
        double hi = Double.NEGATIVE_INFINITY;
        for (double[] p : q) {
            double across = -(p[0] - q[0][0]) * u1 + (p[1] - q[0][1]) * u0;
            lo = Math.min(lo, across);
            hi = Math.max(hi, across);
        }
        return new double[]{u0, u1, width, hi - lo};
    }

    /**
     * The box from the original, bars vertical, with {@link #END} crop pixels past each end. A box
     * longer than {@link #MAX_WIDTH} or taller than {@link #MAX_HEIGHT} is sampled more coarsely,
     * each crop pixel the mean of four samples, rather than dropped: a large symbol close to the
     * lens fills such a box.
     */
    private static LinearBand.Crop crop(GrayU8 gray, double[][] q) {
        double ux = q[1][0] - q[0][0];
        double uy = q[1][1] - q[0][1];
        double vx = q[3][0] - q[0][0];
        double vy = q[3][1] - q[0][1];
        double along = Math.hypot(ux, uy);
        double tall = Math.hypot(vx, vy);
        if (along < 16 || tall < 1e-6) {
            return null;
        }
        double step = Math.max(1, Math.max((along + 2 * END) / MAX_WIDTH, tall / MAX_HEIGHT));
        double u0 = ux / along * step;
        double u1 = uy / along * step;
        double v0 = vx / tall * step;
        double v1 = vy / tall * step;
        int width = (int) Math.round(along / step + 2 * END);
        int height = Math.max(MIN_HEIGHT, (int) Math.round(tall / step));
        double originX = q[0][0] - END * u0 - (height - tall / step) / 2 * v0;
        double originY = q[0][1] - END * u1 - (height - tall / step) / 2 * v1;
        PixelMap toSource = (x, y) -> new ImagePoint(originX + u0 * x + v0 * y, originY + u1 * x + v1 * y);
        GrayU8 image = new GrayU8(width, height);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double sx = originX + u0 * x + v0 * y;
                double sy = originY + u1 * x + v1 * y;
                if (step == 1) {
                    image.set(x, y, LinearBand.bilinear(gray, sx, sy));
                } else {
                    int sum = 0;
                    for (double dy = -0.25; dy <= 0.25; dy += 0.5) {
                        for (double dx = -0.25; dx <= 0.25; dx += 0.5) {
                            sum += LinearBand.bilinear(gray, sx + u0 * dx + v0 * dy, sy + u1 * dx + v1 * dy);
                        }
                    }
                    image.set(x, y, (sum + 2) / 4);
                }
            }
        }
        int degrees = (int) Math.round(Math.toDegrees(Math.atan2(uy, ux)));
        return new LinearBand.Crop(image, toSource, Math.floorMod(degrees, 360));
    }
}
