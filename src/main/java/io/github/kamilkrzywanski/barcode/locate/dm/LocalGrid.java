/*
 * Copyright 2026 Axel Waggershauser
 * Copyright 2026 Kamil Krzywański
 */
// SPDX-License-Identifier: Apache-2.0
package io.github.kamilkrzywanski.barcode.locate.dm;

import io.github.kamilkrzywanski.barcode.locate.dm.DmGeometry.Bits;
import io.github.kamilkrzywanski.barcode.locate.dm.DmGeometry.P;
import io.github.kamilkrzywanski.barcode.locate.dm.DmGeometry.Warp;
import io.github.kamilkrzywanski.barcode.locate.dm.QrCursor.FloatCursor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Module-center search from zxing-cpp 3.1.1 {@code LocalGrid.cpp}.
 *
 * <p>The grid starts from a global module-to-pixel warp, then walks module edges
 * to pull the origin onto a module center and, when every sample agrees, to
 * update the step. {@link #findTimingPatternCross} is the Aztec reference-grid
 * search, {@link #findPattern} the Data Matrix alignment-pattern search. The C++
 * debug prints are omitted.
 */
final class LocalGrid {
    private final Bits image;
    private final int dimX;
    private final int dimY;
    private final int centerX;
    private final int centerY;
    private P origin;
    private P stepX;
    private P stepY;

    /**
     * @param moduleX module column of the grid origin
     * @param moduleY module row of the grid origin
     * @param offsetX module offset applied while measuring the center, then removed
     * @param offsetY module offset applied while measuring the center, then removed
     */
    LocalGrid(Bits image, Warp mod2Pix, int moduleX, int moduleY, int dimX, int dimY, int offsetX, int offsetY) {
        this.image = image;
        this.dimX = dimX;
        this.dimY = dimY;
        this.centerX = moduleX;
        this.centerY = moduleY;
        if (mod2Pix == null || !mod2Pix.valid()) {
            this.origin = new P(Double.NaN, Double.NaN);
            this.stepX = new P(0, 0);
            this.stepY = new P(0, 0);
            return;
        }
        P centered = P.centered(moduleX, moduleY);
        origin = mod2Pix.apply(centered);
        stepX = P.sub(mod2Pix.apply(centered.x + 1, centered.y), origin);
        stepY = P.sub(mod2Pix.apply(centered.x, centered.y + 1), origin);
        // Copied once. Later step updates must not rewrite these offsets.
        P[] offsets = {
                new P(-stepX.x, -stepX.y),
                new P(-stepY.x, -stepY.y),
                new P(stepX.x, stepX.y),
                new P(stepY.x, stepY.y)
        };
        origin = getPos(offsetX, offsetY);
        for (int pass = 0; pass < 2; pass++) {
            adjustOriginAndStep(stepX, 2, offsets);
            adjustOriginAndStep(stepY, 2, offsets);
        }
        origin = getPos(-offsetX, -offsetY);
    }

    /**
     * Center of a timing-pattern cross, in pixels.
     * {@code null} when no spiral candidate survives the full arm length.
     * A failed candidate still keeps the step update from that attempt.
     */
    P findTimingPatternCross(boolean blackCenter, int radius) {
        for (int[] point : spiral(3)) {
            if (!isTimingPatternCross(point[0], point[1], blackCenter, radius / 2, 0)) {
                continue;
            }
            double savedX = origin.x;
            double savedY = origin.y;
            origin = getPos(point[0], point[1]);
            adjustOriginAndStep(stepX, radius, new P[]{new P(-stepX.x, -stepX.y), new P(stepX.x, stepX.y)});
            adjustOriginAndStep(stepY, radius, new P[]{new P(-stepY.x, -stepY.y), new P(stepY.x, stepY.y)});
            if (isTimingPatternCross(0, 0, blackCenter, radius, 0)
                    && Double.isFinite(origin.x) && Double.isFinite(origin.y)) {
                return new P(origin.x, origin.y);
            }
            origin = new P(savedX, savedY);
        }
        return null;
    }

    /** Current grid origin in pixels, {@code LocalGrid::getPos()}. */
    P origin() {
        return new P(origin.x, origin.y);
    }

    /**
     * Data Matrix alignment-pattern search, {@code LocalGrid::findPattern}. Around each spiral
     * candidate, the modules from {@code timingStart} along {@code timingDirs} must alternate
     * (white first), those from {@code blackStart} along {@code blackDirs} be black and those from
     * {@code whiteStart} along {@code whiteDirs} be white, for {@code radius} modules. On a match the
     * origin moves there and is refined along the timing directions; when the pattern no longer
     * matches after that refinement, the origin goes back to the match.
     */
    boolean findPattern(int radius, int[] timingStart, int[][] timingDirs, int[] blackStart, int[][] blackDirs,
                        int[] whiteStart, int[][] whiteDirs) {
        for (int[] point : spiral(3)) {
            if (!isPatternAt(point[0], point[1], radius, timingStart, timingDirs, blackStart, blackDirs, whiteStart, whiteDirs)) {
                continue;
            }
            origin = getPos(point[0], point[1]);
            P match = new P(origin.x, origin.y);
            List<P> stepsX = new ArrayList<>();
            List<P> stepsY = new ArrayList<>();
            for (int[] d : timingDirs) {
                if (d[1] == 0) {
                    stepsX.add(new P(d[0] * stepX.x, d[0] * stepX.y));
                } else {
                    stepsY.add(new P(d[1] * stepY.x, d[1] * stepY.y));
                }
            }
            if (!stepsX.isEmpty()) {
                adjustOriginAndStep(stepX, radius, stepsX.toArray(new P[0]));
            }
            if (!stepsY.isEmpty()) {
                adjustOriginAndStep(stepY, radius, stepsY.toArray(new P[0]));
            }
            if ((!stepsX.isEmpty() || !stepsY.isEmpty())
                    && !isPatternAt(0, 0, radius, timingStart, timingDirs, blackStart, blackDirs, whiteStart, whiteDirs)) {
                origin = match;
            }
            return true;
        }
        return false;
    }

    private boolean isPatternAt(int px, int py, int radius, int[] timingStart, int[][] timingDirs, int[] blackStart,
                                int[][] blackDirs, int[] whiteStart, int[][] whiteDirs) {
        for (int r = 0; r <= radius; r++) {
            for (int[] d : timingDirs) {
                if (!findValue(px + timingStart[0] + r * d[0], py + timingStart[1] + r * d[1], d[0], d[1], r % 2 == 1)) {
                    return false;
                }
            }
            for (int[] d : blackDirs) {
                if (!findValue(px + blackStart[0] + r * d[0], py + blackStart[1] + r * d[1], d[0], d[1], true)) {
                    return false;
                }
            }
            for (int[] d : whiteDirs) {
                if (!findValue(px + whiteStart[0] + r * d[0], py + whiteStart[1] + r * d[1], d[0], d[1], false)) {
                    return false;
                }
            }
        }
        return true;
    }

    private void adjustOriginAndStep(P step, int radius, P[] offsets) {
        if (step == null || offsets == null || offsets.length == 0) {
            return;
        }
        if (!Double.isFinite(step.x) || !Double.isFinite(step.y) || !Double.isFinite(origin.x) || !Double.isFinite(origin.y)) {
            return;
        }
        double modSize = P.length(step);
        int limit = (int) (6 * modSize);
        P dir = P.bresenham(step);
        double dirLength = P.length(dir);
        if (!(dirLength > 0) || !Double.isFinite(modSize)) {
            return;
        }
        modSize /= dirLength;
        List<Dist> samples = new ArrayList<>();
        for (int arm = 0; arm <= radius; arm++) {
            for (P offset : offsets) {
                P start = new P(origin.x + arm * offset.x, origin.y + arm * offset.y);
                P startCenter = P.centered(start);
                int stepsPos = new FloatCursor(image, startCenter.x, startCenter.y, dir.x, dir.y).stepToEdge(1, limit, false);
                int stepsNeg = new FloatCursor(image, startCenter.x, startCenter.y, -dir.x, -dir.y).stepToEdge(1, limit, false);
                P fromCenter = P.sub(start, startCenter);
                double distPos = stepsPos - P.dot(fromCenter, dir) - 0.5;
                double distNeg = stepsNeg - P.dot(fromCenter, new P(-dir.x, -dir.y)) - 0.5;
                if (stepsPos != 0 && stepsNeg != 0) {
                    int blockSize = stepsPos + stepsNeg - 1;
                    double rounded = Math.max(1.0, roundHalfAway(blockSize / modSize));
                    double localModSize = blockSize / rounded;
                    samples.add(new Dist(distPos, blockSize / modSize < 5 ? localModSize : 0.0));
                } else if (stepsPos != 0) {
                    samples.add(new Dist(distPos, 0.0));
                } else if (stepsNeg != 0) {
                    samples.add(new Dist(-distNeg, 0.0));
                }
                if (arm == 0) {
                    break;
                }
            }
        }
        double localModSize = 0;
        int counted = 0;
        for (Dist sample : samples) {
            if (sample.modSize > 0) {
                localModSize += sample.modSize;
                counted++;
            }
        }
        if (counted == 0) {
            return;
        }
        localModSize /= counted;
        double[] residuals = new double[samples.size()];
        for (int i = 0; i < samples.size(); i++) {
            Dist sample = samples.get(i);
            if (sample.modSize == 0.0) {
                sample.modSize = localModSize;
            }
            double distance = sample.dist < 0 ? -sample.dist : sample.dist;
            double residual = nearestHalfResidual(distance, sample.modSize);
            residuals[i] = sample.dist < 0 ? -residual : residual;
        }
        double shift = clusterAvg(residuals, modSize / 2);
        origin = new P(origin.x + shift * dir.x, origin.y + shift * dir.y);
        if (counted == samples.size() && Math.abs(localModSize - modSize) > modSize * 0.1) {
            double scale = localModSize / modSize;
            step.x *= scale;
            step.y *= scale;
        }
    }

    private boolean isTimingPatternCross(int px, int py, boolean blackCenter, int radius, int errorThreshold) {
        int errors = 0;
        for (int arm = 0; arm <= radius; arm++) {
            errors += checkArm(px, py, -arm, 0, blackCenter, radius);
            errors += checkArm(px, py, arm, 0, blackCenter, radius);
            errors += checkArm(px, py, 0, -arm, blackCenter, radius);
            errors += checkArm(px, py, 0, arm, blackCenter, radius);
            if (errors > errorThreshold) {
                return false;
            }
        }
        return errors <= errorThreshold;
    }

    private int checkArm(int px, int py, int ox, int oy, boolean blackCenter, int radius) {
        int x = wrapOffset(centerX, ox, dimX, radius);
        int y = wrapOffset(centerY, oy, dimY, radius);
        // C++ `%` truncates toward zero. A negative wrapped coordinate must not use floorMod.
        boolean black = (x + y) % 2 == (blackCenter ? 0 : 1);
        return findValue(px + x, py + y, x, y, black) ? 0 : 1;
    }

    /** Reflect an arm that leaves the symbol, matching {@code LocalGrid::isTimingPatternCross}. */
    private static int wrapOffset(int center, int offset, int dimension, int radius) {
        int position = center + offset;
        if (position < 0) {
            return radius - position;
        }
        if (position >= dimension) {
            return -(radius + (position - dimension) + 1);
        }
        return offset;
    }

    /**
     * {@code true} when the module is outside the image or the expected color is
     * visible at the module or a quarter module either way along {@code dir}.
     */
    private boolean findValue(int px, int py, int dirX, int dirY, boolean black) {
        if (!image.isIn(getPos(px, py))) {
            return true;
        }
        int expected = black ? 1 : 0;
        double qx = dirX != 0 ? 0.25 : 0;
        double qy = dirY != 0 ? 0.25 : 0;
        return color(px, py) == expected
                || color(px + qx, py + qy) == expected
                || color(px - qx, py - qy) == expected;
    }

    private int color(double x, double y) {
        P pixel = getPos(x, y);
        if (!image.isIn(pixel)) {
            return -1;
        }
        return image.get(pixel) ? 1 : 0;
    }

    private P getPos(double x, double y) {
        return new P(
                origin.x + x * stepX.x + y * stepY.x,
                origin.y + x * stepX.y + y * stepY.y
        );
    }

    /** Largest run of values that lie within {@code threshold} of each other. */
    private static double clusterAvg(double[] values, double threshold) {
        double[] sorted = Arrays.copyOf(values, values.length);
        Arrays.sort(sorted);
        int bestStart = 0;
        int bestLength = 0;
        int start = 0;
        for (int end = 0; end < sorted.length; end++) {
            while (sorted[end] - sorted[start] >= threshold) {
                start++;
            }
            int length = end - start + 1;
            if (length > bestLength) {
                bestLength = length;
                bestStart = start;
            }
        }
        double sum = 0;
        for (int i = bestStart; i < bestStart + bestLength; i++) {
            sum += sorted[i];
        }
        return sum / bestLength;
    }

    /** {@code n - (round((n - s / 2) / s) * s + s / 2)}, with halfway cases away from zero. */
    private static double nearestHalfResidual(double n, double s) {
        double rounded = roundHalfAway((n - s / 2) / s);
        return n - (rounded * s + s / 2);
    }

    /** {@code std::round}: halfway cases go away from zero, not toward {@code +Inf}. */
    private static double roundHalfAway(double value) {
        if (value >= 0) {
            return Math.floor(value + 0.5);
        }
        return Math.ceil(value - 0.5);
    }

    /** One edge distance and the module size measured across that edge. Zero size means unknown. */
    private static final class Dist {
        private final double dist;
        private double modSize;

        private Dist(double dist, double modSize) {
            this.dist = dist;
            this.modSize = modSize;
        }
    }

    /** Spiral around the origin, radius {@code radius}, in the zxing-cpp order. */
    private static int[][] spiral(int radius) {
        int count = 1;
        for (int arm = 1; arm <= radius; arm++) {
            count += 8 * arm;
        }
        int[][] points = new int[count][2];
        int n = 0;
        points[n++][0] = 0;
        for (int arm = 1; arm <= radius; arm++) {
            for (int k = 0; k < 2 * arm; k++) {
                points[n][0] = arm;
                points[n][1] = -(arm - 1) + k;
                n++;
            }
            for (int k = 0; k < 2 * arm; k++) {
                points[n][0] = (arm - 1) - k;
                points[n][1] = arm;
                n++;
            }
            for (int k = 0; k < 2 * arm; k++) {
                points[n][0] = -arm;
                points[n][1] = (arm - 1) - k;
                n++;
            }
            for (int k = 0; k < 2 * arm; k++) {
                points[n][0] = -(arm - 1) + k;
                points[n][1] = -arm;
                n++;
            }
        }
        return points;
    }
}
