/*
 * Copyright 2026 Kamil Krzywański
 */
// SPDX-License-Identifier: Apache-2.0
package io.github.kamilkrzywanski.barleader.decode;

import io.github.kamilkrzywanski.barleader.image.GrayU8;

import java.util.Arrays;

/**
 * Code 128 with modules of about one pixel, read by fitting the gray profile of a strip whose
 * bars are about vertical. A one-module bar under a pixel and a half never crosses a threshold
 * cleanly, but the profile it leaves still matches its pattern blurred. Every symbol position
 * gets the least-squares residual of every pattern under a Gaussian blur, and a search over the
 * weighted sum modulo 103 finds the cheapest sequence whose check symbol is right.
 *
 * <p>The outer edges of the symbol come from the profile, the number of symbols, the blur and a
 * quarter pixel at each end from how well the start and stop patterns fit; only the best of
 * those get the full fit; when none counts, the best few again with each symbol shifted to where
 * it fits best. A read is kept when the check did not have to change any symbol, the
 * mean residual is small, and every symbol fits clearly better than its runner-up. On BarBeR's
 * ParcelBar part that reads 50 parcel codes in fast mode that no threshold reads, mostly with
 * modules of 0.7 to 1.3 pixels, and one wrong text; on KAIST's photos without a barcode it
 * reads nothing.
 */
public final class Code128Fit {
    private static final double STEP = GrayProfile.STEP;
    private static final double MIN_SPAN = GrayProfile.MIN_SPAN;
    private static final double MIN_MODULE = 0.6;
    private static final double MAX_MODULE = 3.5;
    private static final double[] SIGMAS = {0.7, 0.9, 1.1};
    private static final double[] SHIFTS = {-0.25, 0, 0.25};
    /** Screened geometries that get the full fit. */
    private static final int KEEP = 16;
    /** The best screened geometries that are aligned symbol by symbol when no fit counts. */
    private static final int ALIGN = 4;
    /** Shifts of one symbol from where a constant module puts it, in modules. */
    private static final double[] OFFSETS = {-0.75, -0.5, -0.25, 0, 0.25, 0.5, 0.75};
    private static final double MAX_COST = 0.2;
    private static final double MIN_MARGIN = 0.05;
    private static final int STOP = 106;
    private static final int[] STOP_PATTERN = {2, 3, 3, 1, 1, 1, 2};
    /** Dark modules of each pattern, then the first bar of the next symbol. */
    private static final boolean[][] DARK = darkModules();

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
            double slope = GrayProfile.shear(strip, y, Math.max(3, strip.height / 5), half);
            double[] profile = GrayProfile.profile(strip, y, half, slope);
            for (int[] span : GrayProfile.spans(profile)) {
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
        boolean upsideDown = behind != null && (ahead == null || better(behind, ahead));
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

    /** An accepted fit beats one that is not; then the lower cost wins. */
    private static boolean better(Fit fit, Fit than) {
        if (fit.accepted() != than.accepted()) {
            return fit.accepted();
        }
        return fit.cost < than.cost;
    }

    /**
     * The cheapest full fit of the screened geometries, reading left to right. When none counts,
     * the best few are fitted again with each symbol shifted to where it fits best, as a module
     * a hundredth off or a slight perspective moves the last symbols of a long code by a module.
     */
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
            Fit fit = fit(g, geometry[2], geometry[3], (int) geometry[0], geometry[1], null);
            if (fit != null && (best == null || fit.cost < best.cost)) {
                best = fit;
            }
        }
        if (best != null && best.accepted()) {
            return best;
        }
        for (int i = 0; i < ALIGN && kept[i] != null; i++) {
            double[] geometry = kept[i];
            double[] shifts = align(g, geometry[2], geometry[3], (int) geometry[0], geometry[1]);
            Fit fit = shifts == null ? null : fit(g, geometry[2], geometry[3], (int) geometry[0], geometry[1], shifts);
            if (fit != null && (best == null || better(fit, best))) {
                best = fit;
            }
        }
        return best;
    }

    /**
     * Each symbol's shift from {@link #OFFSETS}, neighbours at most one step apart, that minimises
     * the sum of every symbol's cheapest pattern at its shift. Null when a window leaves the profile.
     */
    private static double[] align(double[] g, double left, double right, int symbols, double sigma) {
        double module = (right - left) / (11 * symbols + 2);
        int n = OFFSETS.length;
        double[][] total = new double[symbols][n];
        int[][] from = new int[symbols][n];
        for (int k = 0; k < symbols; k++) {
            boolean stop = k == symbols - 1;
            int low = stop ? STOP : k == 0 ? 103 : 0;
            int high = stop ? STOP : k == 0 ? 105 : 102;
            for (int o = 0; o < n; o++) {
                Window window = Window.of(g, left + (11 * k + OFFSETS[o]) * module, module, sigma, stop ? 13 : 11);
                if (window == null) {
                    return null;
                }
                double cheapest = Double.POSITIVE_INFINITY;
                for (int value = low; value <= high; value++) {
                    cheapest = Math.min(cheapest, window.cost(DARK[value]));
                }
                if (k == 0) {
                    total[k][o] = cheapest;
                    continue;
                }
                total[k][o] = Double.POSITIVE_INFINITY;
                for (int p = Math.max(0, o - 1); p <= Math.min(n - 1, o + 1); p++) {
                    if (total[k - 1][p] + cheapest < total[k][o]) {
                        total[k][o] = total[k - 1][p] + cheapest;
                        from[k][o] = p;
                    }
                }
            }
        }
        int o = 0;
        for (int i = 1; i < n; i++) {
            if (total[symbols - 1][i] < total[symbols - 1][o]) {
                o = i;
            }
        }
        double[] shifts = new double[symbols];
        for (int k = symbols - 1; k >= 0; k--) {
            shifts[k] = OFFSETS[o];
            o = from[k][o];
        }
        return shifts;
    }

    /**
     * {@code symbols} counts the start, the data, the check and the stop pattern, which spans
     * 13 modules where every other symbol spans 11. {@code shifts}, when given, moves each symbol
     * by that many modules.
     */
    private static Fit fit(double[] g, double left, double right, int symbols, double sigma, double[] shifts) {
        double module = (right - left) / (11 * symbols + 2);
        double[][] costs = new double[symbols][];
        for (int k = 0; k < symbols; k++) {
            double x = left + (11 * k + (shifts == null ? 0 : shifts[k])) * module;
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
                    edge[j][i] = GrayProfile.phi((at - (first + i) * STEP) / sigma);
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

}
