/*
 * Copyright 2026 Kamil Krzywański
 */
// SPDX-License-Identifier: Apache-2.0
package io.github.kamilkrzywanski.barcode.decode;

import io.github.kamilkrzywanski.barcode.image.GrayU8;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Code 128 with modules of about one pixel, read by fitting the gray profile of a strip whose
 * bars are about vertical. A one-module bar under a pixel and a half never crosses a threshold
 * cleanly, but the profile it leaves still matches its pattern blurred. Every symbol position
 * gets the least-squares residual of every pattern under a Gaussian blur, and a search over the
 * weighted sum modulo 103 finds the cheapest sequence whose check symbol is right.
 *
 * <p>The outer edges of the symbol come from the profile, the number of symbols, the blur and a
 * quarter pixel at each end from how well the start and stop patterns fit; only the best of
 * those get the full fit. A read is kept when the check did not have to change any symbol, the
 * mean residual is small, and every symbol fits clearly better than its runner-up. On BarBeR's
 * ParcelBar part that reads 50 parcel codes in fast mode that no threshold reads, mostly with
 * modules of 0.7 to 1.3 pixels, and one wrong text; on KAIST's photos without a barcode it
 * reads nothing.
 */
public final class Code128Fit {
    /** Profile samples per pixel. */
    private static final int FINE = 4;
    private static final double STEP = 1.0 / FINE;
    /** A quiet zone of a span is at least this many pixels of flat, light profile. */
    private static final double QUIET = 8;
    private static final double MARGIN = 6;
    private static final double MIN_SPAN = 40;
    private static final double MAX_SPAN = 1200;
    private static final double MIN_MODULE = 0.6;
    private static final double MAX_MODULE = 3.5;
    private static final double[] SIGMAS = {0.7, 0.9, 1.1};
    private static final double[] SHIFTS = {-0.25, 0, 0.25};
    /** Screened geometries that get the full fit. */
    private static final int KEEP = 16;
    private static final double MAX_COST = 0.2;
    private static final double MIN_MARGIN = 0.05;
    private static final int STOP = 106;
    private static final int[] STOP_PATTERN = {2, 3, 3, 1, 1, 1, 2};
    /** Dark modules of each pattern, then the first bar of the next symbol. */
    private static final boolean[][] DARK = darkModules();
    private static final double[] PHI = phiTable();

    private Code128Fit() {
    }

    /** {@code left} and {@code right} are the outer edges in strip pixels on row {@code y}. */
    public record Found(String text, double left, double right, double y, boolean upsideDown) {
    }

    public static Found read(GrayU8 strip) {
        if (strip == null || strip.width < MIN_SPAN || strip.height < 6) {
            return null;
        }
        for (double at : new double[]{0.5, 0.3, 0.7}) {
            int y = (int) (strip.height * at);
            int half = Math.max(2, strip.height / 10);
            double slope = shear(strip, y, Math.max(3, strip.height / 5), half);
            double[] profile = profile(strip, y, half, slope);
            for (int[] span : spans(profile)) {
                Found found = readSpan(profile, span[0], span[1], y);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static Found readSpan(double[] profile, int from, int to, int y) {
        double[] forward = Arrays.copyOfRange(profile, from, to);
        double[] backward = new double[forward.length];
        for (int i = 0; i < forward.length; i++) {
            backward[i] = forward[forward.length - 1 - i];
        }
        Fit ahead = search(forward);
        Fit behind = search(backward);
        boolean upsideDown = ahead == null || (behind != null && behind.cost < ahead.cost);
        Fit best = upsideDown ? behind : ahead;
        if (best == null || !best.accepted()) {
            return null;
        }
        double base = from * STEP;
        double length = (forward.length - 1) * STEP;
        double left = upsideDown ? base + length - best.right : base + best.left;
        double right = upsideDown ? base + length - best.left : base + best.right;
        return new Found(best.text, left, right, y, upsideDown);
    }

    /** One full fit: the symbol values, the mean residual, and how clear the worst symbol was. */
    private record Fit(String text, double cost, double margin, boolean checkChanged, double left, double right) {
        boolean accepted() {
            return !checkChanged && cost < MAX_COST && margin >= MIN_MARGIN;
        }
    }

    /** The cheapest full fit of the screened geometries, reading left to right. */
    private static Fit search(double[] g) {
        int n = g.length;
        double dark = Double.MAX_VALUE;
        for (double v : g) {
            dark = Math.min(dark, v);
        }
        int quiet = Math.min(16, Math.max(4, n / 40));
        double whiteLeft = 0;
        double whiteRight = 0;
        for (int i = 0; i < quiet; i++) {
            whiteLeft += g[i];
            whiteRight += g[n - 1 - i];
        }
        double thresholdLeft = (whiteLeft / quiet + dark) / 2;
        double thresholdRight = (whiteRight / quiet + dark) / 2;
        double left = -1;
        for (int i = 1; i < n; i++) {
            if (g[i] < thresholdLeft) {
                left = (i - 1 + (g[i - 1] - thresholdLeft) / (g[i - 1] - g[i])) * STEP;
                break;
            }
        }
        double right = -1;
        for (int i = n - 2; i >= 0; i--) {
            if (g[i] < thresholdRight) {
                right = (i + 1 - (g[i + 1] - thresholdRight) / (g[i + 1] - g[i])) * STEP;
                break;
            }
        }
        if (left < 0 || right <= left) {
            return null;
        }
        double[] scores = new double[KEEP];
        double[][] kept = new double[KEEP][];
        Arrays.fill(scores, Double.MAX_VALUE);
        for (int symbols = 4; ; symbols++) {
            double module = (right - left) / (11 * symbols + 2);
            if (module < MIN_MODULE) {
                break;
            }
            if (module > MAX_MODULE) {
                continue;
            }
            for (double sigma : SIGMAS) {
                for (double shiftLeft : SHIFTS) {
                    Window first = Window.of(g, left + shiftLeft, module, sigma, 11);
                    if (first == null) {
                        continue;
                    }
                    double start = Math.min(first.cost(DARK[103]), Math.min(first.cost(DARK[104]), first.cost(DARK[105])));
                    for (double shiftRight : SHIFTS) {
                        double width = (right + shiftRight - left - shiftLeft) / (11 * symbols + 2);
                        Window last = Window.of(g, left + shiftLeft + 11 * (symbols - 1) * width, width, sigma, 13);
                        if (last == null) {
                            continue;
                        }
                        double score = start + last.cost(DARK[STOP]);
                        if (!(score < scores[KEEP - 1])) {
                            continue;
                        }
                        int at = KEEP - 1;
                        while (at > 0 && scores[at - 1] > score) {
                            scores[at] = scores[at - 1];
                            kept[at] = kept[at - 1];
                            at--;
                        }
                        scores[at] = score;
                        kept[at] = new double[]{symbols, sigma, left + shiftLeft, right + shiftRight};
                    }
                }
            }
        }
        Fit best = null;
        for (double[] geometry : kept) {
            if (geometry == null) {
                continue;
            }
            Fit fit = fit(g, geometry[2], geometry[3], (int) geometry[0], geometry[1]);
            if (fit != null && (best == null || fit.cost < best.cost)) {
                best = fit;
            }
        }
        return best;
    }

    /**
     * {@code symbols} counts the start, the data, the check and the stop pattern, which spans
     * 13 modules where every other symbol spans 11.
     */
    private static Fit fit(double[] g, double left, double right, int symbols, double sigma) {
        double module = (right - left) / (11 * symbols + 2);
        double[][] costs = new double[symbols][];
        for (int k = 0; k < symbols; k++) {
            double x = left + 11 * k * module;
            costs[k] = new double[STOP + 1];
            Arrays.fill(costs[k], Double.POSITIVE_INFINITY);
            boolean stop = k == symbols - 1;
            Window window = Window.of(g, x, module, sigma, stop ? 13 : 11);
            if (window == null) {
                return null;
            }
            if (stop) {
                costs[k][STOP] = window.cost(DARK[STOP]);
                continue;
            }
            int low = k == 0 ? 103 : 0;
            int high = k == 0 ? 105 : 102;
            for (int value = low; value <= high; value++) {
                costs[k][value] = window.cost(DARK[value]);
            }
        }
        // Cheapest prefix for each weighted sum modulo 103; the check symbol closes it.
        double[] best = new double[103];
        int[][] previous = new int[symbols][103];
        int[][] chosen = new int[symbols][103];
        Arrays.fill(best, Double.POSITIVE_INFINITY);
        for (int value = 103; value <= 105; value++) {
            if (costs[0][value] < best[value % 103]) {
                best[value % 103] = costs[0][value];
                chosen[0][value % 103] = value;
            }
        }
        for (int k = 1; k <= symbols - 3; k++) {
            double[] next = new double[103];
            Arrays.fill(next, Double.POSITIVE_INFINITY);
            for (int sum = 0; sum < 103; sum++) {
                if (best[sum] == Double.POSITIVE_INFINITY) {
                    continue;
                }
                for (int value = 0; value <= 102; value++) {
                    double total = best[sum] + costs[k][value];
                    int to = (sum + k * value) % 103;
                    if (total < next[to]) {
                        next[to] = total;
                        previous[k][to] = sum;
                        chosen[k][to] = value;
                    }
                }
            }
            best = next;
        }
        int check = -1;
        double total = Double.POSITIVE_INFINITY;
        for (int sum = 0; sum < 103; sum++) {
            double withCheck = best[sum] + costs[symbols - 2][sum];
            if (withCheck < total) {
                total = withCheck;
                check = sum;
            }
        }
        if (check < 0) {
            return null;
        }
        int[] values = new int[symbols];
        values[symbols - 1] = STOP;
        values[symbols - 2] = check;
        for (int k = symbols - 3, sum = check; k >= 0; k--) {
            values[k] = chosen[k][sum];
            if (k > 0) {
                sum = previous[k][sum];
            }
        }
        boolean changed = false;
        double margin = Double.POSITIVE_INFINITY;
        for (int k = 0; k < symbols - 1; k++) {
            double own = costs[k][values[k]];
            double runnerUp = Double.POSITIVE_INFINITY;
            for (int value = 0; value < STOP; value++) {
                if (value == values[k]) {
                    continue;
                }
                if (costs[k][value] < own) {
                    changed = true;
                }
                runnerUp = Math.min(runnerUp, costs[k][value]);
            }
            if (k > 0) {
                margin = Math.min(margin, runnerUp - own);
            }
        }
        String text = Code128Decoder.decode(modules(values));
        if (text == null) {
            return null;
        }
        return new Fit(text, (total + costs[symbols - 1][STOP]) / symbols, margin, changed, left, right);
    }

    /** The symbol values as modules between quiet zones, for {@link Code128Decoder#decode(boolean[])}. */
    private static boolean[] modules(int[] values) {
        boolean[] out = new boolean[24 + 11 * values.length + 2];
        int at = 12;
        for (int value : values) {
            int[] widths = value == STOP ? STOP_PATTERN : Code128Decoder.PATTERNS[value];
            boolean bar = true;
            for (int width : widths) {
                for (int i = 0; i < width; i++) {
                    out[at++] = bar;
                }
                bar = !bar;
            }
        }
        return out;
    }

    /**
     * The sums one symbol window needs. A pattern is a set of dark modules, so its least-squares
     * fit {@code g = white + slope * blurred} comes from sums over pairs of modules.
     */
    private static final class Window {
        private final int count;
        private final int modules;
        private final double sum;
        private final double squares;
        /** Per module: the sum of its blurred response, and of that times the profile. */
        private final double[] response;
        private final double[] product;
        private final double[][] overlap;

        private Window(int count, int modules, double sum, double squares) {
            this.count = count;
            this.modules = modules;
            this.sum = sum;
            this.squares = squares;
            this.response = new double[modules + 1];
            this.product = new double[modules + 1];
            this.overlap = new double[modules + 1][modules + 1];
        }

        /**
         * The symbol at {@code x} with {@code modules} modules of {@code width}, from half a
         * module before it, where a space ends the symbol in front, to its end, where the next
         * symbol starts with a bar. Null when the window leaves the profile.
         */
        static Window of(double[] g, double x, double width, double sigma, int modules) {
            int first = (int) Math.ceil((x - 0.5 * width) / STEP);
            int last = (int) Math.floor((x + modules * width) / STEP);
            if (first < 0 || last >= g.length || last <= first) {
                return null;
            }
            int count = last - first + 1;
            double sum = 0;
            double squares = 0;
            for (int i = first; i <= last; i++) {
                sum += g[i];
                squares += g[i] * g[i];
            }
            Window window = new Window(count, modules, sum, squares);
            double[][] edge = new double[modules + 2][count];
            for (int j = 0; j <= modules + 1; j++) {
                double at = x + j * width;
                for (int i = 0; i < count; i++) {
                    edge[j][i] = phi((at - (first + i) * STEP) / sigma);
                }
            }
            double[][] blurred = new double[modules + 1][count];
            for (int j = 0; j <= modules; j++) {
                for (int i = 0; i < count; i++) {
                    blurred[j][i] = edge[j + 1][i] - edge[j][i];
                    window.response[j] += blurred[j][i];
                    window.product[j] += blurred[j][i] * g[first + i];
                }
            }
            for (int a = 0; a <= modules; a++) {
                for (int b = a; b <= modules; b++) {
                    double dot = 0;
                    for (int i = 0; i < count; i++) {
                        dot += blurred[a][i] * blurred[b][i];
                    }
                    window.overlap[a][b] = dot;
                    window.overlap[b][a] = dot;
                }
            }
            return window;
        }

        /** Residual of the best fit with dark bars, over the variance of the window. */
        double cost(boolean[] dark) {
            double model = 0;
            double modelTimesProfile = 0;
            double modelSquares = 0;
            for (int a = 0; a <= modules; a++) {
                if (!dark[a]) {
                    continue;
                }
                model += response[a];
                modelTimesProfile += product[a];
                for (int b = 0; b <= modules; b++) {
                    if (dark[b]) {
                        modelSquares += overlap[a][b];
                    }
                }
            }
            double determinant = count * modelSquares - model * model;
            double variance = squares - sum * sum / count;
            if (Math.abs(determinant) < 1e-9 || variance < 1e-6) {
                return Double.POSITIVE_INFINITY;
            }
            // Bars are darker than spaces by at least one gray level.
            double slope = Math.min(-1, (count * modelTimesProfile - model * sum) / determinant);
            double white = (sum - slope * model) / count;
            double residual = squares + count * white * white + slope * slope * modelSquares
                    - 2 * white * sum - 2 * slope * modelTimesProfile + 2 * white * slope * model;
            return Math.max(residual, 0) / variance;
        }
    }

    /** Horizontal shift of the bars per row, from the profiles {@code distance} rows above and below. */
    private static double shear(GrayU8 strip, int y, int distance, int half) {
        int above = y - distance;
        int below = y + distance;
        if (above - half < 0 || below + half >= strip.height) {
            return 0;
        }
        double[] a = profile(strip, above, half, 0);
        double[] b = profile(strip, below, half, 0);
        int reach = (int) Math.ceil(Math.min(2 * distance * 0.07, 12) * FINE);
        double[] score = new double[2 * reach + 1];
        int bestShift = 0;
        for (int shift = -reach; shift <= reach; shift++) {
            int from = Math.max(0, -shift);
            int to = Math.min(a.length, b.length - shift);
            if (to - from < 2) {
                return 0;
            }
            double meanA = 0;
            double meanB = 0;
            for (int i = from; i < to; i++) {
                meanA += a[i];
                meanB += b[i + shift];
            }
            meanA /= to - from;
            meanB /= to - from;
            double covariance = 0;
            for (int i = from; i < to; i++) {
                covariance += (a[i] - meanA) * (b[i + shift] - meanB);
            }
            score[shift + reach] = covariance / (to - from);
            if (score[shift + reach] > score[bestShift + reach]) {
                bestShift = shift;
            }
        }
        double fraction = 0;
        int at = bestShift + reach;
        if (at > 0 && at < score.length - 1) {
            double curvature = score[at - 1] - 2 * score[at] + score[at + 1];
            if (curvature < 0) {
                fraction = 0.5 * (score[at - 1] - score[at + 1]) / curvature;
            }
        }
        return (bestShift + fraction) * STEP / (below - above);
    }

    /** The mean of rows {@code y - half} to {@code y + half}, each shifted by its distance times {@code slope}. */
    private static double[] profile(GrayU8 strip, int y, int half, double slope) {
        int count = (strip.width - 1) * FINE;
        double[] out = new double[count];
        int top = Math.max(0, y - half);
        int bottom = Math.min(strip.height - 1, y + half);
        for (int i = 0; i < count; i++) {
            double x = i * STEP;
            double sum = 0;
            int rows = 0;
            for (int row = top; row <= bottom; row++) {
                double shifted = x + (row - y) * slope;
                int x0 = (int) Math.floor(shifted);
                if (x0 < 0 || x0 + 1 >= strip.width) {
                    continue;
                }
                double fraction = shifted - x0;
                sum += strip.get(x0, row) * (1 - fraction) + strip.get(x0 + 1, row) * fraction;
                rows++;
            }
            out[i] = rows > 0 ? sum / rows : i > 0 ? out[i - 1] : 255;
        }
        return out;
    }

    /** Stretches between quiet zones, as sample ranges with some quiet zone on each side. */
    private static List<int[]> spans(double[] g) {
        int n = g.length;
        double low = Double.MAX_VALUE;
        double high = -1;
        for (double v : g) {
            low = Math.min(low, v);
            high = Math.max(high, v);
        }
        double contrast = high - low;
        List<int[]> spans = new ArrayList<>();
        if (contrast < 20) {
            return spans;
        }
        int window = (int) (QUIET * FINE);
        boolean[] quiet = new boolean[n];
        for (int i = 0; i + window <= n; i++) {
            double min = Double.MAX_VALUE;
            double max = -1;
            for (int j = i; j < i + window; j++) {
                min = Math.min(min, g[j]);
                max = Math.max(max, g[j]);
            }
            if (max - min < 0.25 * contrast && min > low + 0.5 * contrast) {
                Arrays.fill(quiet, i, i + window, true);
            }
        }
        int margin = (int) (MARGIN * FINE);
        int i = 0;
        while (i < n && !quiet[i]) {
            i++;
        }
        while (i < n) {
            while (i < n && quiet[i]) {
                i++;
            }
            int start = i;
            while (i < n && !quiet[i]) {
                i++;
            }
            if (i >= n) {
                break;
            }
            double width = (i - start) * STEP;
            if (width >= MIN_SPAN && width <= MAX_SPAN) {
                spans.add(new int[]{Math.max(0, start - margin), Math.min(n, i + margin)});
            }
        }
        return spans;
    }

    private static boolean[][] darkModules() {
        boolean[][] out = new boolean[STOP + 1][];
        for (int value = 0; value <= STOP; value++) {
            int[] widths = value == STOP ? STOP_PATTERN : Code128Decoder.PATTERNS[value];
            int modules = value == STOP ? 13 : 11;
            boolean[] dark = new boolean[modules + 1];
            int at = 0;
            boolean bar = true;
            for (int width : widths) {
                for (int i = 0; i < width; i++) {
                    dark[at++] = bar;
                }
                bar = !bar;
            }
            dark[modules] = true;
            out[value] = dark;
        }
        return out;
    }

    /** The normal distribution function from -6 to 6 in steps of 0.01. */
    private static double[] phiTable() {
        double[] table = new double[1201];
        for (int i = 0; i < table.length; i++) {
            double z = (i - 600) / 100.0;
            double t = 1 / (1 + 0.2316419 * Math.abs(z));
            double density = 0.3989423 * Math.exp(-z * z / 2);
            double tail = density * t * (0.3193815 + t * (-0.3565638 + t * (1.781478 + t * (-1.821256 + t * 1.330274))));
            table[i] = z > 0 ? 1 - tail : tail;
        }
        return table;
    }

    private static double phi(double z) {
        double at = z * 100 + 600;
        if (at <= 0) {
            return 0;
        }
        if (at >= 1200) {
            return 1;
        }
        int i = (int) at;
        return PHI[i] + (PHI[i + 1] - PHI[i]) * (at - i);
    }
}
