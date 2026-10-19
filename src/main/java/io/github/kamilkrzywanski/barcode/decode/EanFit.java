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
 * EAN-13 and UPC-A with modules of about one pixel, read by fitting the gray profile of a strip
 * whose bars are about vertical, as {@link Code128Fit} reads Code 128. The symbol always spans 95
 * modules, so its outer edges give the module. Each end is screened on its guard and the cheapest
 * digit beside it, for the blur and a quarter pixel at a time; every digit position of the best
 * geometries gets the least-squares residual of each of its 20 or 10 patterns, and a search over
 * the parity of the left half and the weighted sum modulo 10 finds the cheapest digits whose
 * check digit is right.
 *
 * <p>A read counts when its mean residual is small, every other set of digits with a right check
 * costs clearly more, and a second band of rows of the strip fits the same digits: a texture or a
 * line of text can fit 13 digits on one band, but not on two. EAN-8 is not fitted: its 67 modules
 * matched textures as often as symbols. On KAIST's EAN-13 photos this reads 26 codes in fast mode
 * that no threshold reads, and on its photos without a barcode nothing.
 */
public final class EanFit {
    private static final double STEP = GrayProfile.STEP;
    private static final int MODULES = 95;
    private static final int MIDDLE = 45;
    private static final double MIN_MODULE = 0.6;
    private static final double MAX_MODULE = 4.0;
    private static final double[] SIGMAS = {0.6, 0.8, 1.0, 1.2};
    /** Screened geometries that get the full fit. */
    private static final int KEEP = 12;
    /** The outer edges are where the profile drops this far toward its darkest. */
    private static final double DEPTH = 0.3;
    private static final double MAX_COST = 0.28;
    private static final double MIN_MARGIN = 0.18;
    /** L-code modules of each digit, dark true; R is the complement, G the reversed R. */
    private static final boolean[][] L = new boolean[10][];
    private static final boolean[][] G = new boolean[10][];
    private static final boolean[][] R = new boolean[10][];
    /** Parity of the six left digits for each first digit, G as a set bit, the first left digit the lowest. */
    private static final int[] PARITY = {
            0b000000, 0b110100, 0b101100, 0b011100, 0b110010, 0b100110, 0b001110, 0b101010, 0b011010, 0b010110};
    private static final boolean[] START_GUARD = {false, true, false, true, false};
    private static final boolean[] MIDDLE_GUARD = {true, false, true, false, true, false, true};
    private static final boolean[] END_GUARD = {false, true, false, true, false};

    static {
        int[][] widths = {{3, 2, 1, 1}, {2, 2, 2, 1}, {2, 1, 2, 2}, {1, 4, 1, 1}, {1, 1, 3, 2},
                {1, 2, 3, 1}, {1, 1, 1, 4}, {1, 3, 1, 2}, {1, 2, 1, 3}, {3, 1, 1, 2}};
        for (int d = 0; d < 10; d++) {
            boolean[] l = new boolean[7];
            int at = 0;
            boolean dark = false;
            for (int w : widths[d]) {
                for (int i = 0; i < w; i++) {
                    l[at++] = dark;
                }
                dark = !dark;
            }
            boolean[] r = new boolean[7];
            boolean[] g = new boolean[7];
            for (int i = 0; i < 7; i++) {
                r[i] = !l[i];
            }
            for (int i = 0; i < 7; i++) {
                g[i] = r[6 - i];
            }
            L[d] = l;
            R[d] = r;
            G[d] = g;
        }
    }

    private EanFit() {
    }

    /** The 13 digits; {@code left} and {@code right} are the outer edges in strip pixels on row {@code y}. */
    public record Found(String text, double left, double right, double y, boolean upsideDown) {
    }

    public static Found read(GrayU8 strip) {
        if (strip == null || strip.width < GrayProfile.MIN_SPAN || strip.height < 6) {
            return null;
        }
        List<String> seen = new ArrayList<>();
        double[] bands = {0.5, 0.3, 0.7};
        for (int band = 0; band < bands.length; band++) {
            // With nothing fitted on the first two bands the last has nothing to confirm.
            if (band == bands.length - 1 && seen.isEmpty()) {
                return null;
            }
            int y = (int) (strip.height * bands[band]);
            int half = Math.max(2, strip.height / 10);
            double slope = GrayProfile.shear(strip, y, Math.max(3, strip.height / 5), half);
            double[] profile = GrayProfile.profile(strip, y, half, slope);
            for (int[] span : GrayProfile.spans(profile)) {
                Found found = readSpan(profile, span[0], span[1], y);
                if (found == null) {
                    continue;
                }
                // The bars of a symbol run across the strip: the same digits have to fit on
                // another band of rows before they count.
                if (seen.contains(found.text())) {
                    return found;
                }
                seen.add(found.text());
            }
        }
        return null;
    }

    /** The cheaper direction's fit when it is accepted. */
    private static Found readSpan(double[] profile, int from, int to, int y) {
        double[] forward = Arrays.copyOfRange(profile, from, to);
        Fit ahead = search(forward);
        Fit behind = search(GrayProfile.reversed(forward));
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

    /** One full fit: the digits, the mean residual, and how much more the cheapest other digits cost. */
    private record Fit(String text, double cost, double margin, double left, double right) {
        boolean accepted() {
            return cost < MAX_COST && margin >= MIN_MARGIN;
        }
    }

    /** An accepted fit beats one that is not; then the lower cost wins. */
    private static boolean better(Fit fit, Fit than) {
        if (than == null) {
            return true;
        }
        if (fit.accepted() != than.accepted()) {
            return fit.accepted();
        }
        return fit.cost < than.cost;
    }

    /** The best full fit of the screened geometries, reading left to right. */
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
        // Not halfway down: a guard bar of one module may never reach half the depth.
        double thresholdLeft = whiteLeft / quiet - DEPTH * (whiteLeft / quiet - dark);
        double thresholdRight = whiteRight / quiet - DEPTH * (whiteRight / quiet - dark);
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
        double module = (right - left) / MODULES;
        if (module < MIN_MODULE || module > MAX_MODULE) {
            return null;
        }
        double[] scores = new double[KEEP];
        double[][] kept = new double[KEEP][];
        Arrays.fill(scores, Double.MAX_VALUE);
        // The true edge can lie a module and a half either side of the crossing.
        int steps = (int) Math.ceil(1.5 * module / 0.25);
        double[] shifts = new double[2 * steps + 1];
        for (int k = 0; k < shifts.length; k++) {
            shifts[k] = (k - steps) * 0.25;
        }
        for (double sigma : SIGMAS) {
            // Each end on its own guard and digit first, then the best few of each together with the middle guard.
            double[] leftScore = new double[shifts.length];
            double[] rightScore = new double[shifts.length];
            for (int k = 0; k < shifts.length; k++) {
                leftScore[k] = startCost(Window.of(g, left + shifts[k], module, sigma, 10));
                rightScore[k] = endCost(Window.of(g, right - shifts[k] - 10 * module, module, sigma, 10));
            }
            for (int a : lowest(leftScore, 4)) {
                for (int b : lowest(rightScore, 4)) {
                    double x0 = left + shifts[a];
                    double x1 = right - shifts[b];
                    double w = (x1 - x0) / MODULES;
                    Window first = Window.of(g, x0, w, sigma, 3);
                    Window centre = Window.of(g, x0 + MIDDLE * w, w, sigma, 5);
                    Window last = Window.of(g, x0 + (MODULES - 3) * w, w, sigma, 3);
                    if (first == null || centre == null || last == null) {
                        continue;
                    }
                    double score = startCost(Window.of(g, x0, w, sigma, 10)) + centre.cost(MIDDLE_GUARD)
                            + endCost(Window.of(g, x0 + (MODULES - 10) * w, w, sigma, 10));
                    if (!(score < scores[KEEP - 1])) {
                        continue;
                    }
                    int i = KEEP - 1;
                    while (i > 0 && scores[i - 1] > score) {
                        scores[i] = scores[i - 1];
                        kept[i] = kept[i - 1];
                        i--;
                    }
                    scores[i] = score;
                    kept[i] = new double[]{sigma, x0, x1};
                }
            }
        }
        Fit best = null;
        for (double[] geometry : kept) {
            if (geometry == null) {
                continue;
            }
            Fit fit = fit(g, geometry[1], geometry[2], geometry[0]);
            if (fit != null && better(fit, best)) {
                best = fit;
            }
        }
        return best;
    }

    /** The start guard and the cheapest first digit, from the quiet zone to the next digit. */
    private static double startCost(Window window) {
        if (window == null) {
            return Double.POSITIVE_INFINITY;
        }
        double best = Double.POSITIVE_INFINITY;
        // Quiet module, guard 101, the digit, then the next digit's first module, a space.
        boolean[] dark = new boolean[12];
        dark[1] = true;
        dark[3] = true;
        for (int o = 0; o < 20; o++) {
            System.arraycopy(o < 10 ? L[o] : G[o - 10], 0, dark, 4, 7);
            best = Math.min(best, window.cost(dark));
        }
        return best;
    }

    /** The cheapest last digit and the end guard, from the digit before to the quiet zone. */
    private static double endCost(Window window) {
        if (window == null) {
            return Double.POSITIVE_INFINITY;
        }
        double best = Double.POSITIVE_INFINITY;
        boolean[] dark = new boolean[12];
        for (int d = 0; d < 10; d++) {
            System.arraycopy(R[d], 0, dark, 1, 7);
            dark[8] = true;
            dark[9] = false;
            dark[10] = true;
            best = Math.min(best, window.cost(dark));
        }
        return best;
    }

    /** Indexes of the {@code count} lowest values, lowest first. */
    private static int[] lowest(double[] values, int count) {
        Integer[] order = new Integer[values.length];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (i, j) -> Double.compare(values[i], values[j]));
        int[] out = new int[Math.min(count, order.length)];
        for (int i = 0; i < out.length; i++) {
            out[i] = order[i];
        }
        return out;
    }

    /**
     * The 12 encoded digits between the guards: options 0 to 9 are L codes and 10 to 19 G codes
     * on the left half, 0 to 9 R codes on the right.
     */
    private static Fit fit(double[] g, double left, double right, double sigma) {
        double w = (right - left) / MODULES;
        double[][] costs = new double[12][];
        for (int p = 0; p < 12; p++) {
            boolean leftHalf = p < 6;
            double x = left + (leftHalf ? 3 + 7 * p : 3 + 42 + 5 + 7 * (p - 6)) * w;
            Window window = Window.of(g, x, w, sigma, 7);
            if (window == null) {
                return null;
            }
            costs[p] = new double[leftHalf ? 20 : 10];
            for (int o = 0; o < costs[p].length; o++) {
                boolean[] code = leftHalf ? (o < 10 ? L[o] : G[o - 10]) : R[o];
                // A left digit follows a bar and precedes a space, a right digit the other way round.
                boolean[] dark = new boolean[9];
                dark[0] = leftHalf;
                System.arraycopy(code, 0, dark, 1, 7);
                dark[8] = !leftHalf;
                costs[p][o] = window.cost(dark);
            }
        }
        double guardCost = 0;
        boolean[][] guards = {START_GUARD, MIDDLE_GUARD, END_GUARD};
        double[] at = {left, left + MIDDLE * w, left + (MODULES - 3) * w};
        int[] modules = {3, 5, 3};
        for (int k = 0; k < 3; k++) {
            Window window = Window.of(g, at[k], w, sigma, modules[k]);
            if (window == null) {
                return null;
            }
            guardCost += window.cost(guards[k]);
        }
        // States: the parity mask of the left half times the weighted sum modulo 10.
        // forward[p][s]: cheapest digits before position p ending in s; backward[p][s]: cheapest
        // digits from p on that make s a symbol with a right check digit.
        int states = 64 * 10;
        double[][] forward = new double[13][states];
        double[][] backward = new double[13][states];
        for (double[] row : forward) {
            Arrays.fill(row, Double.POSITIVE_INFINITY);
        }
        for (double[] row : backward) {
            Arrays.fill(row, Double.POSITIVE_INFINITY);
        }
        forward[0][0] = 0;
        for (int p = 0; p < 12; p++) {
            for (int s = 0; s < states; s++) {
                if (forward[p][s] == Double.POSITIVE_INFINITY) {
                    continue;
                }
                for (int o = 0; o < costs[p].length; o++) {
                    int to = next(s, p, o);
                    forward[p + 1][to] = Math.min(forward[p + 1][to], forward[p][s] + costs[p][o]);
                }
            }
        }
        int[] lead = new int[states];
        for (int s = 0; s < states; s++) {
            lead[s] = -1;
            for (int d = 0; d < 10; d++) {
                if (PARITY[d] == s / 10 && (s % 10 + d) % 10 == 0) {
                    lead[s] = d;
                }
            }
            if (lead[s] >= 0) {
                backward[12][s] = 0;
            }
        }
        for (int p = 11; p >= 0; p--) {
            for (int s = 0; s < states; s++) {
                double cheapest = Double.POSITIVE_INFINITY;
                for (int o = 0; o < costs[p].length; o++) {
                    cheapest = Math.min(cheapest, costs[p][o] + backward[p + 1][next(s, p, o)]);
                }
                backward[p][s] = cheapest;
            }
        }
        double cost = backward[0][0];
        if (cost == Double.POSITIVE_INFINITY) {
            return null;
        }
        // The cheapest digits, and the cheapest other digits: the same up to some position, another option there.
        int[] chosen = new int[12];
        double runnerUp = Double.POSITIVE_INFINITY;
        int state = 0;
        for (int p = 0; p < 12; p++) {
            int pick = -1;
            double own = Double.POSITIVE_INFINITY;
            double prefix = forward[p][state];
            for (int o = 0; o < costs[p].length; o++) {
                double through = prefix + costs[p][o] + backward[p + 1][next(state, p, o)];
                if (through < own) {
                    if (pick >= 0) {
                        runnerUp = Math.min(runnerUp, own);
                    }
                    own = through;
                    pick = o;
                } else {
                    runnerUp = Math.min(runnerUp, through);
                }
            }
            chosen[p] = pick;
            state = next(state, p, pick);
        }
        StringBuilder text = new StringBuilder().append(lead[state]);
        for (int p = 0; p < 12; p++) {
            text.append(chosen[p] % 10);
        }
        return new Fit(text.toString(), (cost + guardCost) / 15, runnerUp - cost, left, right);
    }

    /** The state after option {@code o} at position {@code p}: G sets the parity bit, the digit adds to the sum. */
    private static int next(int state, int p, int o) {
        int mask = state / 10;
        int sum = state % 10;
        int weight = p % 2 == 0 ? 3 : 1;
        if (p < 6 && o >= 10) {
            mask |= 1 << p;
        }
        return mask * 10 + (sum + weight * (o % 10)) % 10;
    }

    /**
     * Sums of one window: {@code modules} modules of {@code width} from {@code x}, with the module
     * before and the module after it, sampled from half a module before to half a module after.
     * A pattern is a set of dark modules, so its least-squares fit
     * {@code g = white + slope * blurred} comes from sums over pairs of modules.
     */
    private static final class Window {
        private final int count;
        private final int modules;
        private final double sum;
        private final double squares;
        private final double[] response;
        private final double[] product;
        private final double[][] overlap;

        private Window(int count, int modules, double sum, double squares) {
            this.count = count;
            this.modules = modules;
            this.sum = sum;
            this.squares = squares;
            this.response = new double[modules + 2];
            this.product = new double[modules + 2];
            this.overlap = new double[modules + 2][modules + 2];
        }

        /** Null when the window leaves the profile. */
        static Window of(double[] g, double x, double width, double sigma, int modules) {
            int first = (int) Math.ceil((x - 0.5 * width) / STEP);
            int last = (int) Math.floor((x + (modules + 0.5) * width) / STEP);
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
            double[][] edge = new double[modules + 3][count];
            for (int j = 0; j <= modules + 2; j++) {
                double at = x + (j - 1) * width;
                for (int i = 0; i < count; i++) {
                    edge[j][i] = GrayProfile.phi((at - (first + i) * STEP) / sigma);
                }
            }
            double[][] blurred = new double[modules + 2][count];
            for (int j = 0; j <= modules + 1; j++) {
                for (int i = 0; i < count; i++) {
                    blurred[j][i] = edge[j + 1][i] - edge[j][i];
                    window.response[j] += blurred[j][i];
                    window.product[j] += blurred[j][i] * g[first + i];
                }
            }
            for (int a = 0; a <= modules + 1; a++) {
                for (int b = a; b <= modules + 1; b++) {
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

        /** Residual of the best fit with dark bars, over the variance of the window. {@code dark} includes the modules before and after. */
        double cost(boolean[] dark) {
            double model = 0;
            double modelTimesProfile = 0;
            double modelSquares = 0;
            for (int a = 0; a <= modules + 1; a++) {
                if (!dark[a]) {
                    continue;
                }
                model += response[a];
                modelTimesProfile += product[a];
                for (int b = 0; b <= modules + 1; b++) {
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
}
