/*
 * Copyright 2020 Axel Waggershauser
 * Copyright 2016 Nu-book Inc.
 * Copyright 2016 ZXing authors
 * Copyright 2023 gitlost
 * Copyright 2026 Kamil Krzywański
 */
// SPDX-License-Identifier: Apache-2.0
package io.github.kamilkrzywanski.barcode.locate.dm;

import io.github.kamilkrzywanski.barcode.ImagePoint;
import io.github.kamilkrzywanski.barcode.decode.Decoded;
import io.github.kamilkrzywanski.barcode.image.GrayU8;
import io.github.kamilkrzywanski.barcode.locate.dm.DmGeometry.Bits;
import io.github.kamilkrzywanski.barcode.locate.dm.DmGeometry.Line;
import io.github.kamilkrzywanski.barcode.locate.dm.DmGeometry.P;
import io.github.kamilkrzywanski.barcode.locate.dm.DmGeometry.Warp;
import io.github.kamilkrzywanski.barcode.locate.dm.QrConcentric.Ring;
import io.github.kamilkrzywanski.barcode.locate.dm.QrCursor.FloatCursor;
import io.github.kamilkrzywanski.barcode.locate.dm.QrCursor.IntCursor;
import io.github.kamilkrzywanski.barcode.locate.dm.QrCursor.View;
import io.github.kamilkrzywanski.barcode.qr.QrVersion;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;

/**
 * QR finder ported from zxing-cpp 3.1.1 {@code QRDetector.cpp},
 * {@code ConcentricFinder.cpp}, and {@code GridSampler.cpp}.
 *
 * <p>The image is binarized with the zxing-cpp local-average matrix binarizer.
 * Finder patterns are grouped with the spatial binning path, then each triple is
 * sampled and handed to the model-2 bit decoder. Micro QR is a separate attempt.
 * rMQR is not in this slice.
 * {@code DetectPureQR} is not used: the default zxing-cpp read does not call it.
 */
public final class QrDetector {
    private static final int MIN_SKIP = 3;
    private static final int MAX_MODULES_FAST = 20 * 4 + 17;
    private static final int MAX_SETS = 256;
    private static final int MAX_CANDIDATES = 15;
    private static final int[] VERSION_DECODE_INFO = {
            0x07C94, 0x085BC, 0x09A99, 0x0A4D3, 0x0BBF6,
            0x0C762, 0x0D847, 0x0E60D, 0x0F928, 0x10B78,
            0x1145D, 0x12A17, 0x13532, 0x149A6, 0x15683,
            0x168C9, 0x177EC, 0x18EC4, 0x191E1, 0x1AFAB,
            0x1B08E, 0x1CC1A, 0x1D33F, 0x1ED75, 0x1F250,
            0x209D5, 0x216F0, 0x228BA, 0x2379F, 0x24B0B,
            0x2542E, 0x26A64, 0x27541, 0x28C69
    };
    private static final int[][] ALIGN_OFFSETS = {
            {0, 0}, {0, -1}, {0, 1}, {-1, 0}, {1, 0},
            {-1, -1}, {1, -1}, {1, 1}, {-1, 1}
    };

    private QrDetector() {
    }

    /** One decoded symbol. Corners are TL, TR, BR, BL in the gray image. */
    public record Found(String text, byte[] bytes, List<ImagePoint> corners, int orientationDegrees) {
    }

    /**
     * Binarize {@code gray} and run the finder.
     * An empty list means no QR decoded from this image.
     */
    public static List<Found> read(GrayU8 gray) {
        if (gray.width < 3 || gray.height < 3) {
            return List.of();
        }
        return read(DmBinarizer.blackMatrix(gray));
    }

    /** Finder search on an existing matrix. {@code Scan} calls {@link #read(GrayU8)} once. */
    static List<Found> read(Bits bits) {
        if (bits == null || bits.width < 3 || bits.height < 3) {
            return List.of();
        }
        List<Ring> patterns = findFinderPatterns(bits, true);
        List<FinderSet> sets = generateSets(patterns);
        List<Ring> used = new ArrayList<>();
        List<Found> found = new ArrayList<>();
        for (FinderSet set : sets) {
            if (used(used, set.bl) || used(used, set.tl) || used(used, set.tr)) {
                continue;
            }
            boolean decoded = false;
            List<Supplier<Grid>> later = new ArrayList<>(1);
            List<Grid> grids = sample(bits, set, later);
            for (Grid grid : grids) {
                Found hit = decode(grid);
                if (hit != null) {
                    found.add(hit);
                    decoded = true;
                    break;
                }
            }
            for (int i = 0; i < later.size() && !decoded; i++) {
                Grid grid = later.get(i).get();
                Found hit = grid == null ? null : decode(grid);
                if (hit != null) {
                    found.add(hit);
                    decoded = true;
                }
            }
            if (decoded) {
                used.add(set.bl);
                used.add(set.tl);
                used.add(set.tr);
            }
        }
        return found;
    }

    private static boolean used(List<Ring> used, Ring pattern) {
        for (Ring old : used) {
            if (old.x == pattern.x && old.y == pattern.y) {
                return true;
            }
        }
        return false;
    }

    /** Finder centers in scan order. Micro QR runs the same pass on its own list. */
    static List<Ring> findFinderPatterns(Bits image, boolean tryHarder) {
        int skip = (3 * image.height) / (4 * MAX_MODULES_FAST);
        if (skip < MIN_SKIP || tryHarder) {
            skip = MIN_SKIP;
        }
        List<Ring> found = new ArrayList<>();
        for (int y = skip - 1; y < image.height; y += skip) {
            int[] row = QrCursor.patternRow(image, y);
            View next = View.of(row);
            for (int guard = 0; guard < row.length + 2; guard++) {
                View hit = QrCursor.findPattern(next);
                if (!hit.isValid()) {
                    break;
                }
                double px = hit.pixelsInFront() + hit.at(0) + hit.at(1) + hit.at(2) / 2.0;
                double py = y + 0.5;
                if (!insideKnown(found, px, py)) {
                    Ring pattern = QrConcentric.locate(image, px, py, 2 * hit.sum());
                    if (pattern != null && pattern.size > 0) {
                        found.add(pattern);
                    }
                }
                hit.shift(2);
                hit.shift(2);
                hit.extend();
                next = hit;
            }
        }
        return found;
    }

    private static boolean insideKnown(List<Ring> found, double x, double y) {
        for (Ring old : found) {
            if (distance(x, y, old.x, old.y) < old.size / 2.0) {
                return true;
            }
        }
        return false;
    }

    private static List<FinderSet> generateSets(List<Ring> patterns) {
        if (patterns.size() < 3) {
            return List.of();
        }
        patterns.sort((left, right) -> Double.compare(right.size, left.size));
        int count = patterns.size();
        double minX = patterns.get(0).x;
        double maxX = minX;
        double minY = patterns.get(0).y;
        double maxY = minY;
        for (Ring pattern : patterns) {
            minX = Math.min(minX, pattern.x);
            maxX = Math.max(maxX, pattern.x);
            minY = Math.min(minY, pattern.y);
            maxY = Math.max(maxY, pattern.y);
        }
        int medianSize = (int) patterns.get(count / 2).size;
        int binSize = Math.max(32, medianSize * 3);
        int binsX = (int) Math.ceil((maxX - minX + 1.0) / binSize);
        int binsY = (int) Math.ceil((maxY - minY + 1.0) / binSize);
        binsX = Math.max(1, binsX);
        binsY = Math.max(1, binsY);
        List<List<Integer>> bins = new ArrayList<>(binsX * binsY);
        for (int i = 0; i < binsX * binsY; i++) {
            bins.add(new ArrayList<>());
        }
        for (int index = 0; index < count; index++) {
            Ring pattern = patterns.get(index);
            int bx = clamp((int) ((pattern.x - minX) / binSize), binsX);
            int by = clamp((int) ((pattern.y - minY) / binSize), binsY);
            bins.get(by * binsX + bx).add(index);
        }
        boolean useFilters = count > 5;
        double cosUpper = Math.cos(60.0 / 180.0 * Math.PI);
        double cosLower = Math.cos(120.0 / 180.0 * Math.PI);
        List<ScoredSet> sets = new ArrayList<>();
        List<Integer> candidates = new ArrayList<>();
        for (int i = 0; i < count - 2; i++) {
            Ring origin = patterns.get(i);
            double maxDist = origin.size / 7.0 * 177 * 1.5;
            int originX = clamp((int) ((origin.x - minX) / binSize), binsX);
            int originY = clamp((int) ((origin.y - minY) / binSize), binsY);
            int radius = (int) Math.ceil(maxDist / binSize);
            candidates.clear();
            for (int[] delta : spiral(radius)) {
                int bx = originX + delta[0];
                int by = originY + delta[1];
                if (bx < 0 || by < 0 || bx >= binsX || by >= binsY) {
                    continue;
                }
                for (int index : bins.get(by * binsX + bx)) {
                    if (index <= i) {
                        continue;
                    }
                    Ring other = patterns.get(index);
                    if (useFilters && origin.size > other.size * 2 + 2) {
                        continue;
                    }
                    candidates.add(index);
                }
                if (candidates.size() >= MAX_CANDIDATES) {
                    break;
                }
            }
            for (int u = 0; u < candidates.size() - 1; u++) {
                for (int v = u + 1; v < candidates.size(); v++) {
                    int j = candidates.get(u);
                    int k = candidates.get(v);
                    Ring a = patterns.get(Math.max(j, k));
                    Ring b = patterns.get(Math.min(j, k));
                    Ring c = origin;
                    double ab2 = scaledDistance(a, b);
                    double bc2 = scaledDistance(b, c);
                    double ac2 = scaledDistance(a, c);
                    if (bc2 >= ab2 && bc2 >= ac2) {
                        Ring swap = a;
                        a = b;
                        b = swap;
                        double swapped = bc2;
                        bc2 = ac2;
                        ac2 = swapped;
                    } else if (ab2 >= ac2 && ab2 >= bc2) {
                        Ring swap = b;
                        b = c;
                        c = swap;
                        double swapped = ab2;
                        ab2 = ac2;
                        ac2 = swapped;
                    }
                    if (useFilters && (ab2 > 4 * bc2 || bc2 > 4 * ab2)) {
                        continue;
                    }
                    double ab = Math.sqrt(ab2);
                    double bc = Math.sqrt(bc2);
                    double moduleCount = (ab + bc) / (2 * (a.size + b.size + c.size) / (3 * 7.0)) + 7;
                    if (moduleCount < 21 * 0.9 || moduleCount > 177 * 1.5) {
                        continue;
                    }
                    double cosine = (ab2 + bc2 - ac2) / (2 * ab * bc);
                    if (useFilters && (Double.isNaN(cosine) || cosine > cosUpper || cosine < cosLower)) {
                        continue;
                    }
                    double score = ab + bc + Math.abs(ab - bc);
                    if (P.cross(P.sub(c.point(), b.point()), P.sub(a.point(), b.point())) < 0) {
                        Ring swap = a;
                        a = c;
                        c = swap;
                    }
                    if (sets.size() < MAX_SETS || worstScore(sets) > score) {
                        sets.add(new ScoredSet(score, new FinderSet(a, b, c)));
                        if (sets.size() > MAX_SETS) {
                            removeWorst(sets);
                        }
                    }
                }
            }
        }
        sets.sort(Comparator.comparingDouble(set -> set.score));
        List<FinderSet> ordered = new ArrayList<>(sets.size());
        for (ScoredSet set : sets) {
            ordered.add(set.set);
        }
        return ordered;
    }

    private static int clamp(int index, int limit) {
        if (index < 0) {
            return 0;
        }
        if (index >= limit) {
            return limit - 1;
        }
        return index;
    }

    private static double scaledDistance(Ring a, Ring b) {
        double dx = a.x - b.x;
        double dy = a.y - b.y;
        return (dx * dx + dy * dy) * b.size / a.size;
    }

    private static double worstScore(List<ScoredSet> sets) {
        double worst = Double.NEGATIVE_INFINITY;
        for (ScoredSet set : sets) {
            worst = Math.max(worst, set.score);
        }
        return worst;
    }

    private static void removeWorst(List<ScoredSet> sets) {
        int index = 0;
        for (int i = 1; i < sets.size(); i++) {
            if (sets.get(i).score >= sets.get(index).score) {
                index = i;
            }
        }
        sets.remove(index);
    }

    private static List<int[]> spiral(int radius) {
        List<int[]> points = new ArrayList<>();
        points.add(new int[]{0, 0});
        for (int r = 1; r <= radius; r++) {
            for (int k = 0; k < 2 * r; k++) {
                points.add(new int[]{r, -(r - 1) + k});
            }
            for (int k = 0; k < 2 * r; k++) {
                points.add(new int[]{(r - 1) - k, r});
            }
            for (int k = 0; k < 2 * r; k++) {
                points.add(new int[]{-r, (r - 1) - k});
            }
            for (int k = 0; k < 2 * r; k++) {
                points.add(new int[]{-(r - 1) + k, -r});
            }
        }
        return points;
    }

    /** The grids to decode in turn; {@code later} gets the bent grid, which is worth sampling only when they all fail. */
    private static List<Grid> sample(Bits image, FinderSet fp, List<Supplier<Grid>> later) {
        Estimate top = estimateDimension(image, fp.tl, fp.tr);
        Estimate left = estimateDimension(image, fp.tl, fp.bl);
        if (top.dim == 0 && left.dim == 0) {
            return List.of();
        }
        Estimate best = top.err == left.err
                ? (top.dim > left.dim ? top : left)
                : (top.err < left.err ? top : left);
        int dimension = best.dim;
        if (dimension <= 0) {
            return List.of();
        }
        int moduleSize = (int) (top.dim == left.dim ? (top.ms + left.ms) / 2.0 : best.ms) + 1;
        P bottomRight = new P(-1, -1);
        double offsetX = 3;
        double offsetY = 3;
        boolean bottomRightFound = false;
        Line blOuter = traceLine(image, fp.bl, fp.tl, 2);
        Line blInner = traceLine(image, fp.bl, fp.tl, 3);
        Line trOuter = traceLine(image, fp.tr, fp.tl, 2);
        Line trInner = traceLine(image, fp.tr, fp.tl, 3);
        if (blOuter.valid() && trOuter.valid() && blInner.valid() && trInner.valid()) {
            P intersection = P.mul(
                    P.add(DmGeometry.intersect(blOuter, trOuter), DmGeometry.intersect(blInner, trInner)),
                    0.5
            );
            if (dimension > 21) {
                P alignment = locateAlignment(image, moduleSize, intersection);
                if (alignment != null) {
                    bottomRight = alignment;
                }
            }
            bottomRightFound = image.isIn(bottomRight);
            if (!bottomRightFound) {
                bottomRight = intersection;
            }
        }
        if (!image.isIn(bottomRight)
                || QrConcentric.fitSquare(image, fp.bl.x, fp.bl.y, (int) fp.bl.size, 2, false) == null) {
            bottomRight = new P(fp.tr.x - fp.tl.x + fp.bl.x, fp.tr.y - fp.tl.y + fp.bl.y);
            offsetX = 0;
            offsetY = 0;
        }
        Warp warp = mod2Pix(dimension, offsetX, offsetY, fp.tl, fp.tr, bottomRight, fp.bl);
        List<Grid> grids = new ArrayList<>(2);
        if (dimension >= 45) {
            QrVersion version = readVersion(image, dimension, warp);
            if (version == null
                    || Math.min(
                            Math.abs(version.dimension() - top.dim),
                            Math.abs(version.dimension() - left.dim)
                    ) > 8) {
                return grids;
            }
            if (version.dimension() != dimension) {
                dimension = version.dimension();
                warp = mod2Pix(dimension, offsetX, offsetY, fp.tl, fp.tr, bottomRight, fp.bl);
            }
            Grid aligned = sampleAligned(image, dimension, moduleSize, warp, version, fp);
            if (aligned != null) {
                grids.add(aligned);
            }
        } else {
            Grid simple = sampleGrid(image, dimension, dimension, List.of(new Roi(0, dimension, 0, dimension, warp)));
            if (simple != null) {
                grids.add(simple);
            }
        }
        int finalDimension = dimension;
        Warp finalWarp = warp;
        later.add(() -> sampleRefined(image, finalDimension, finalWarp));
        boolean offset = offsetX != 0 || offsetY != 0;
        boolean lowResolution = !(blOuter.highRes() && blInner.highRes() && trOuter.highRes() && trInner.highRes());
        if (!bottomRightFound && ((dimension == 21 && offset) || (tilt(fp) < 1.1 && lowResolution))) {
            P extrapolated = new P(fp.tr.x - fp.tl.x + fp.bl.x, fp.tr.y - fp.tl.y + fp.bl.y);
            Warp fallback = mod2Pix(dimension, 0, 0, fp.tl, fp.tr, extrapolated, fp.bl);
            Grid grid = sampleGrid(image, dimension, dimension, List.of(new Roi(0, dimension, 0, dimension, fallback)));
            if (grid != null) {
                grids.add(grid);
            }
        }
        return grids;
    }

    /**
     * The grid again after bending it onto the module edges of the whole symbol. The displacement
     * from the homography starts from the timing patterns of row and column 6, which cross the
     * symbol; then, walking every module row and column of the current grid, each edge between
     * two modules is where the colour changes, its offset from the nearest module boundary samples
     * the displacement, and a cubic in both module coordinates fitted to all of them, twice without
     * the points a third of a module off, moves the sample points. Three rounds. A symbol on a
     * bottle or a can, or under a wide lens, bends smoothly, and the field follows it where one
     * homography cannot. Null when a walk leaves the image or too few edges fit.
     */
    private static Grid sampleRefined(Bits image, int dimension, Warp warp) {
        if (warp == null || !warp.valid()) {
            return null;
        }
        // Start from the timing patterns, measured where the homography is still close.
        double[] fieldU = timingField(timing(image, dimension, warp, true), dimension, true);
        double[] fieldV = timingField(timing(image, dimension, warp, false), dimension, false);
        for (int iteration = 0; iteration < 3; iteration++) {
            List<double[]> alongRows = new ArrayList<>();
            List<double[]> alongColumns = new ArrayList<>();
            for (int line = 0; line < dimension; line++) {
                if (!edges(image, dimension, warp, fieldU, fieldV, line + 0.5, true, alongRows)
                        || !edges(image, dimension, warp, fieldU, fieldV, line + 0.5, false, alongColumns)) {
                    return null;
                }
            }
            double[] nextU = fit(alongRows, dimension);
            double[] nextV = fit(alongColumns, dimension);
            if (nextU == null || nextV == null) {
                return null;
            }
            fieldU = nextU;
            fieldV = nextV;
        }
        Bits bits = new Bits(dimension, dimension);
        for (int y = 0; y < dimension; y++) {
            for (int x = 0; x < dimension; x++) {
                double u = x + 0.5;
                double v = y + 0.5;
                P pixel = warp.apply(u + field(fieldU, u, v, dimension), v + field(fieldV, u, v, dimension));
                if (!image.isIn(pixel)) {
                    return null;
                }
                if (image.get(pixel)) {
                    bits.set(x, y);
                }
            }
        }
        P[] corners = {warp.apply(0, 0), warp.apply(dimension, 0), warp.apply(dimension, dimension), warp.apply(0, dimension)};
        for (P corner : corners) {
            corner.x = (int) (corner.x + 0.5);
            corner.y = (int) (corner.y + 0.5);
        }
        return new Grid(bits, corners);
    }

    private static final int TERMS = 10;

    /**
     * The edges along module row ({@code rows}) or column {@code at}, walked through the current
     * field: each adds the displacement that puts the nearest module boundary on it.
     */
    private static boolean edges(Bits image, int dimension, Warp warp, double[] fieldU, double[] fieldV,
                                 double at, boolean rows, List<double[]> out) {
        double step = 1 / 16.0;
        int hold = 3;
        Boolean colour = null;
        int run = 0;
        double candidate = Double.NaN;
        for (double t = 0.25; t <= dimension - 0.25; t += step) {
            double u = rows ? t : at;
            double v = rows ? at : t;
            P pixel = warp.apply(u + field(fieldU, u, v, dimension), v + field(fieldV, u, v, dimension));
            if (!image.isIn(pixel)) {
                return false;
            }
            boolean here = image.get(pixel);
            if (colour == null) {
                colour = here;
                continue;
            }
            if (here == colour) {
                run = 0;
                continue;
            }
            if (run == 0) {
                candidate = t - step / 2;
            }
            run++;
            if (run >= hold) {
                double boundary = Math.rint(candidate);
                double offset = candidate - boundary;
                if (Math.abs(offset) < 0.4 && boundary > 0 && boundary < dimension) {
                    // The edge seen at walk position candidate is where boundary really is.
                    double fu = rows ? candidate : at;
                    double fv = rows ? at : candidate;
                    double shift = rows ? field(fieldU, fu, fv, dimension) : field(fieldV, fu, fv, dimension);
                    out.add(rows ? new double[]{boundary, at, offset + shift} : new double[]{at, boundary, offset + shift});
                }
                colour = here;
                run = 0;
            }
        }
        return true;
    }

    private static double field(double[] c, double u, double v, int dimension) {
        double[] b = basis(u / dimension - 0.5, v / dimension - 0.5);
        double sum = 0;
        for (int i = 0; i < TERMS; i++) {
            sum += c[i] * b[i];
        }
        return sum;
    }

    private static double[] basis(double u, double v) {
        return new double[]{1, u, v, u * u, u * v, v * v, u * u * u, u * u * v, u * v * v, v * v * v};
    }

    /** Least squares of the cubic, then again without the points more than a third of a module off. */
    private static double[] fit(List<double[]> points, int dimension) {
        if (points.size() < 4 * TERMS) {
            return null;
        }
        double[] c = solve(points, dimension, null, 0);
        for (int pass = 0; pass < 2 && c != null; pass++) {
            c = solve(points, dimension, c, 0.33);
        }
        return c;
    }

    private static double[] solve(List<double[]> points, int dimension, double[] previous, double limit) {
        double[][] a = new double[TERMS][TERMS + 1];
        int used = 0;
        for (double[] p : points) {
            if (previous != null && Math.abs(field(previous, p[0], p[1], dimension) - p[2]) > limit) {
                continue;
            }
            double[] b = basis(p[0] / dimension - 0.5, p[1] / dimension - 0.5);
            for (int i = 0; i < TERMS; i++) {
                for (int j = 0; j < TERMS; j++) {
                    a[i][j] += b[i] * b[j];
                }
                a[i][TERMS] += b[i] * p[2];
            }
            used++;
        }
        if (used < 4 * TERMS) {
            return null;
        }
        for (int i = 0; i < TERMS; i++) {
            a[i][i] += 1e-6;
        }
        for (int col = 0; col < TERMS; col++) {
            int pivot = col;
            for (int row = col + 1; row < TERMS; row++) {
                if (Math.abs(a[row][col]) > Math.abs(a[pivot][col])) {
                    pivot = row;
                }
            }
            double[] swap = a[col];
            a[col] = a[pivot];
            a[pivot] = swap;
            if (Math.abs(a[col][col]) < 1e-12) {
                return null;
            }
            for (int row = 0; row < TERMS; row++) {
                if (row == col) {
                    continue;
                }
                double factor = a[row][col] / a[col][col];
                for (int k = col; k <= TERMS; k++) {
                    a[row][k] -= factor * a[col][k];
                }
            }
        }
        double[] c = new double[TERMS];
        for (int i = 0; i < TERMS; i++) {
            c[i] = a[i][TERMS] / a[i][i];
        }
        return c;
    }

    /**
     * A displacement field that moves each module column ({@code columns}) or row to the centre
     * the timing pattern measured for it, the same across the symbol; zero without a measurement.
     */
    private static double[] timingField(double[] centres, int dimension, boolean columns) {
        if (centres == null) {
            return new double[TERMS];
        }
        List<double[]> points = new ArrayList<>();
        for (int i = 0; i < dimension; i++) {
            for (int k = 0; k < 5; k++) {
                double across = (k + 0.5) * dimension / 5;
                double shift = centres[i] - (i + 0.5);
                points.add(columns ? new double[]{i + 0.5, across, shift} : new double[]{across, i + 0.5, shift});
            }
        }
        double[] field = fit(points, dimension);
        return field == null ? new double[TERMS] : field;
    }

    /**
     * Module centres in module coordinates along the timing pattern of row 6 ({@code horizontal})
     * or column 6, from its edges in the image: modules 6 to {@code dimension - 7} alternate,
     * dark first, so a walk from the centre of the first to the centre of the last crosses
     * {@code dimension - 13} edges. A colour has to hold for a sixth of a module to count.
     */
    private static double[] timing(Bits image, int dimension, Warp warp, boolean horizontal) {
        double step = 1 / 24.0;
        int hold = 4;
        List<Double> edges = new ArrayList<>();
        boolean colour = true;
        int run = 0;
        double candidate = Double.NaN;
        for (double t = 6.5; t <= dimension - 6.5; t += step) {
            int dark = 0;
            for (double across = -0.2; across <= 0.21; across += 0.2) {
                P pixel = horizontal ? warp.apply(t, 6.5 + across) : warp.apply(6.5 + across, t);
                if (!image.isIn(pixel)) {
                    return null;
                }
                if (image.get(pixel)) {
                    dark++;
                }
            }
            boolean here = dark >= 2;
            if (here == colour) {
                run = 0;
                continue;
            }
            if (run == 0) {
                candidate = t;
            }
            run++;
            if (run >= hold) {
                edges.add(candidate - step / 2);
                colour = here;
                run = 0;
            }
        }
        if (edges.size() != dimension - 13) {
            return null;
        }
        double[] centres = new double[dimension];
        for (int i = 0; i + 1 < edges.size(); i++) {
            centres[7 + i] = (edges.get(i) + edges.get(i + 1)) / 2;
        }
        double head = centres[7] - 7.5;
        double tail = centres[dimension - 8] - (dimension - 7.5);
        for (int x = 0; x < 7; x++) {
            centres[x] = x + 0.5 + head;
            centres[dimension - 1 - x] = dimension - 0.5 - x + tail;
        }
        return centres;
    }

    private static double tilt(FinderSet fp) {
        int min = (int) Math.min(fp.bl.size, Math.min(fp.tl.size, fp.tr.size));
        int max = (int) Math.max(fp.bl.size, Math.max(fp.tl.size, fp.tr.size));
        if (min <= 0) {
            return Double.POSITIVE_INFINITY;
        }
        return (double) max / min;
    }

    private static Estimate estimateDimension(Bits image, Ring a, Ring b) {
        double fromA = estimateModuleSize(image, a, b);
        double fromB = estimateModuleSize(image, b, a);
        if (fromA < 0 || fromB < 0) {
            return new Estimate(0, 0, 4);
        }
        double moduleSize = (fromA + fromB) / 2.0;
        if (!(moduleSize > 0)) {
            return new Estimate(0, 0, 4);
        }
        int dimension = (int) Math.round(distance(a.x, a.y, b.x, b.y) / moduleSize) + 7;
        int error = 1 - Math.floorMod(dimension, 4);
        return new Estimate(dimension + error, moduleSize, Math.abs(error));
    }

    private static double estimateModuleSize(Bits image, Ring a, Ring b) {
        FloatCursor cursor = new FloatCursor(image, a.x, a.y, b.x - a.x, b.y - a.y);
        int[] pattern = QrCursor.readSymmetric(cursor, (int) (a.size * 2));
        if (pattern == null || QrCursor.moduleSizeE2E(pattern, 0, 0) == 0) {
            return -1;
        }
        int sum = 0;
        for (int run : pattern) {
            sum += run;
        }
        return (2.0 * sum - pattern[0] - pattern[4]) / 12.0 * cursor.directionLength();
    }

    private static Line traceLine(Bits image, Ring from, Ring toward, int edge) {
        FloatCursor cursor = new FloatCursor(image, from.x, from.y, toward.x - from.x, toward.y - from.y);
        Line line = new Line();
        line.setInward(new P(-cursor.dx, -cursor.dy));
        cursor.stepToEdge(edge, 0, edge == 3);
        if (edge == 3) {
            cursor.turnBack();
        }
        int mainX;
        int mainY;
        if (Math.abs(cursor.dx) > Math.abs(cursor.dy)) {
            mainX = (int) cursor.dx;
            mainY = 0;
        } else {
            mainX = 0;
            mainY = (int) cursor.dy;
        }
        IntCursor seated = new IntCursor(image, (int) cursor.x, (int) cursor.y, mainX, mainY);
        int guard = image.width + image.height;
        while (seated.isIn() && !seated.edgeAtBack() && guard-- > 0) {
            if (seated.edgeAtLeft()) {
                seated.turn(QrCursor.RIGHT);
            } else if (seated.edgeAtRight()) {
                seated.turn(QrCursor.LEFT);
            } else {
                seated.step(-1);
            }
        }
        for (int direction : new int[]{QrCursor.LEFT, QrCursor.RIGHT}) {
            IntCursor edgeCursor = new IntCursor(
                    image,
                    seated.x,
                    seated.y,
                    seated.sideX(direction),
                    seated.sideY(direction)
            );
            int steps = (int) Math.max(Math.abs(cursor.x - from.x), Math.abs(cursor.y - from.y));
            do {
                line.add(P.centered(edgeCursor.x, edgeCursor.y));
            } while (--steps > 0 && edgeCursor.stepAlongEdge(direction, true));
        }
        line.evaluate(1.0, true);
        return line;
    }

    static P locateAlignment(Bits image, int moduleSize, P estimate) {
        if (moduleSize <= 0) {
            return null;
        }
        double scale = moduleSize * 2.25;
        for (int[] delta : ALIGN_OFFSETS) {
            P point = new P(estimate.x + scale * delta[0], estimate.y + scale * delta[1]);
            if (!image.isIn(point)) {
                continue;
            }
            Ring rough = QrConcentric.centerOfRing(image, (int) point.x, (int) point.y, moduleSize * 3, 1, false);
            if (rough == null || !image.isIn(rough.x, rough.y) || !image.get(rough.point())) {
                continue;
            }
            int ox = (int) rough.x;
            int oy = (int) rough.y;
            Ring inner = QrConcentric.centerOfRing(image, ox, oy, moduleSize * 2, 1, true);
            Ring outer = inner == null
                    ? null
                    : QrConcentric.centerOfRing(image, ox, oy, moduleSize * 3, 2, true);
            if (inner != null
                    && outer != null
                    && distance(inner.x, inner.y, outer.x, outer.y) < moduleSize / 2
                    && outer.size > inner.size) {
                return new P((inner.x + outer.x) / 2.0, (inner.y + outer.y) / 2.0);
            }
        }
        return null;
    }

    private static QrVersion readVersion(Bits image, int dimension, Warp warp) {
        int[] bits = new int[2];
        for (int mirror = 0; mirror < 2; mirror++) {
            int versionBits = 0;
            for (int y = 5; y >= 0; y--) {
                for (int x = dimension - 9; x >= dimension - 11; x--) {
                    int mx = mirror == 1 ? y : x;
                    int my = mirror == 1 ? x : y;
                    P pixel = warp.apply(mx + 0.5, my + 0.5);
                    if (!image.isIn(pixel)) {
                        versionBits = -1;
                    } else {
                        versionBits = (versionBits << 1) | (image.get(pixel) ? 1 : 0);
                    }
                }
            }
            bits[mirror] = versionBits;
        }
        int version = versionNumber(bits[0], bits[1]);
        return version == 0 ? null : QrVersion.of(version);
    }

    /** {@code Version::DecodeVersionInformation}. Zero when neither sample is within 3 bits. */
    static int versionNumber(int bitsA, int bitsB) {
        int bestDifference = Integer.MAX_VALUE;
        int bestVersion = 0;
        for (int i = 0; i < VERSION_DECODE_INFO.length; i++) {
            int target = VERSION_DECODE_INFO[i];
            for (int value : new int[]{bitsA, bitsB}) {
                int difference = Integer.bitCount(value ^ target);
                if (difference < bestDifference) {
                    bestVersion = i + 7;
                    bestDifference = difference;
                }
            }
            if (bestDifference == 0) {
                break;
            }
        }
        if (bestDifference > 3 || bestVersion < 7 || bestVersion > 40) {
            return 0;
        }
        return bestVersion;
    }

    private static Grid sampleAligned(
            Bits image,
            int dimension,
            int moduleSize,
            Warp warp,
            QrVersion version,
            FinderSet fp
    ) {
        int[] centers = version.alignmentPatternCenters();
        int n = centers.length;
        if (n < 2) {
            return sampleGrid(image, dimension, dimension, List.of(new Roi(0, dimension, 0, dimension, warp)));
        }
        int last = n - 1;
        P[][] at = new P[n][n];
        findInnerCorner(image, at, centers, warp, 0, 0, fp.tl);
        findInnerCorner(image, at, centers, warp, 0, last, fp.bl);
        findInnerCorner(image, at, centers, warp, last, 0, fp.tr);
        for (int y = 0; y <= last; y++) {
            for (int x = 0; x <= last; x++) {
                if (at[y][x] != null) {
                    continue;
                }
                P guessed = x * y == 0
                        ? knownOrProjected(at, centers, warp, x, y)
                        : P.sub(
                                P.add(knownOrProjected(at, centers, warp, x - 1, y), knownOrProjected(at, centers, warp, x, y - 1)),
                                knownOrProjected(at, centers, warp, x - 1, y - 1)
                        );
                P found = locateAlignment(image, moduleSize, guessed);
                if (found != null) {
                    at[y][x] = found;
                }
            }
        }
        for (int y = 0; y <= last; y++) {
            for (int x = 0; x <= last; x++) {
                if (at[y][x] != null) {
                    continue;
                }
                List<P> horizontal = new ArrayList<>(2);
                List<P> vertical = new ArrayList<>(2);
                for (int i = 2; i < 2 * last + 2 && horizontal.size() < 2; i++) {
                    int xi = x + (i / 2) * ((i % 2) != 0 ? 1 : -1);
                    if (xi >= 0 && xi <= last && at[y][xi] != null) {
                        horizontal.add(at[y][xi]);
                    }
                }
                for (int i = 2; i < 2 * last + 2 && vertical.size() < 2; i++) {
                    int yi = y + (i / 2) * ((i % 2) != 0 ? 1 : -1);
                    if (yi >= 0 && yi <= last && at[yi][x] != null) {
                        vertical.add(at[yi][x]);
                    }
                }
                if (horizontal.size() == 2 && vertical.size() == 2) {
                    P guessed = DmGeometry.intersect(
                            Line.through(horizontal.get(0), horizontal.get(1)),
                            Line.through(vertical.get(0), vertical.get(1))
                    );
                    P found = locateAlignment(image, moduleSize, guessed);
                    at[y][x] = found != null ? found : guessed;
                }
            }
        }
        if (at[last][last] != null) {
            warp = mod2Pix(dimension, 3, 3, fp.tl, fp.tr, at[last][last], fp.bl);
        }
        for (int y = 0; y <= last; y++) {
            for (int x = 0; x <= last; x++) {
                if (at[y][x] == null) {
                    at[y][x] = warp.apply(centers[x] + 0.5, centers[y] + 0.5);
                }
            }
        }
        List<Roi> rois = new ArrayList<>();
        for (int y = 0; y < last; y++) {
            for (int x = 0; x < last; x++) {
                int x0 = centers[x];
                int x1 = centers[x + 1];
                int y0 = centers[y];
                int y1 = centers[y + 1];
                P[] source = {
                        new P(x0 + 0.5, y0 + 0.5),
                        new P(x1 + 0.5, y0 + 0.5),
                        new P(x1 + 0.5, y1 + 0.5),
                        new P(x0 + 0.5, y1 + 0.5)
                };
                P[] destination = {at[y][x], at[y][x + 1], at[y + 1][x + 1], at[y + 1][x]};
                rois.add(new Roi(
                        x == 0 ? 0 : x0,
                        x == last - 1 ? dimension : x1,
                        y == 0 ? 0 : y0,
                        y == last - 1 ? dimension : y1,
                        Warp.between(source, destination)
                ));
            }
        }
        return sampleGrid(image, dimension, dimension, rois);
    }

    private static void findInnerCorner(
            Bits image,
            P[][] at,
            int[] centers,
            Warp warp,
            int x,
            int y,
            Ring pattern
    ) {
        P projected = warp.apply(centers[x] + 0.5, centers[y] + 0.5);
        at[y][x] = projected;
        P[] quad = QrConcentric.corners(image, pattern.x, pattern.y, (int) pattern.size, 2);
        if (quad == null) {
            return;
        }
        for (P corner : quad) {
            if (P.distance(corner, projected) < pattern.size / 2.0) {
                at[y][x] = corner;
            }
        }
    }

    private static P knownOrProjected(P[][] at, int[] centers, Warp warp, int x, int y) {
        if (at[y][x] != null) {
            return at[y][x];
        }
        return warp.apply(centers[x] + 0.5, centers[y] + 0.5);
    }

    private static Warp mod2Pix(int dimension, double offsetX, double offsetY, Ring tl, Ring tr, P br, Ring bl) {
        double margin = 3.5;
        P[] source = {
                new P(margin, margin),
                new P(dimension - margin, margin),
                new P(dimension - margin - offsetX, dimension - margin - offsetY),
                new P(margin, dimension - margin)
        };
        P[] destination = {tl.point(), tr.point(), br, bl.point()};
        return Warp.between(source, destination);
    }

    private static Grid sampleGrid(Bits image, int width, int height, List<Roi> rois) {
        if (width <= 0 || height <= 0) {
            return null;
        }
        for (Roi roi : rois) {
            if (roi.warp == null || !roi.warp.valid()) {
                return null;
            }
            if (!inside(image, roi, roi.x0, roi.y0)
                    || !inside(image, roi, roi.x1 - 1, roi.y0)
                    || !inside(image, roi, roi.x1 - 1, roi.y1 - 1)
                    || !inside(image, roi, roi.x0, roi.y1 - 1)) {
                return null;
            }
        }
        Bits bits = new Bits(width, height);
        for (Roi roi : rois) {
            for (int y = roi.y0; y < roi.y1; y++) {
                for (int x = roi.x0; x < roi.x1; x++) {
                    P pixel = roi.warp.apply(x + 0.5, y + 0.5);
                    if (!image.isIn(pixel)) {
                        return null;
                    }
                    if (image.get(pixel)) {
                        bits.set(x, y);
                    }
                }
            }
        }
        return new Grid(bits, new P[]{
                projectCorner(rois, 0, 0),
                projectCorner(rois, width, 0),
                projectCorner(rois, width, height),
                projectCorner(rois, 0, height)
        });
    }

    private static boolean inside(Bits image, Roi roi, int x, int y) {
        return image.isIn(roi.warp.apply(x + 0.5, y + 0.5));
    }

    private static P projectCorner(List<Roi> rois, int x, int y) {
        for (Roi roi : rois) {
            if (roi.x0 <= x && x <= roi.x1 && roi.y0 <= y && y <= roi.y1) {
                P at = roi.warp.apply(x, y);
                return new P((int) (at.x + 0.5), (int) (at.y + 0.5));
            }
        }
        return new P(0, 0);
    }

    private static Found decode(Grid grid) {
        Decoded decoded = QrDecoder.decodeContent(grid.bits);
        if (decoded == null || decoded.text().isBlank()) {
            return null;
        }
        List<ImagePoint> corners = new ArrayList<>(4);
        for (P corner : grid.corners) {
            if (!Double.isFinite(corner.x) || !Double.isFinite(corner.y)) {
                return null;
            }
            corners.add(new ImagePoint(corner.x, corner.y));
        }
        return new Found(decoded.text(), decoded.bytes(), List.copyOf(corners), orientation(grid.corners[0], grid.corners[1]));
    }

    private static int orientation(P a, P b) {
        int degrees = (int) Math.round(Math.toDegrees(Math.atan2(b.y - a.y, b.x - a.x)));
        return Math.floorMod(degrees, 360);
    }

    private static double distance(double ax, double ay, double bx, double by) {
        double dx = ax - bx;
        double dy = ay - by;
        return Math.sqrt(dx * dx + dy * dy);
    }

    private record Estimate(int dim, double ms, int err) {
    }

    private record FinderSet(Ring bl, Ring tl, Ring tr) {
    }

    private record ScoredSet(double score, FinderSet set) {
    }

    private record Grid(Bits bits, P[] corners) {
    }

    private record Roi(int x0, int x1, int y0, int y1, Warp warp) {
    }
}
