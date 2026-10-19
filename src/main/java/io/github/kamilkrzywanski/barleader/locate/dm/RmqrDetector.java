/*
 * Copyright 2016 Nu-book Inc.
 * Copyright 2016 ZXing authors
 * Copyright 2020 Axel Waggershauser
 * Copyright 2023 gitlost
 * Copyright 2026 Kamil Krzywański
 */
// SPDX-License-Identifier: Apache-2.0
package io.github.kamilkrzywanski.barleader.locate.dm;

import io.github.kamilkrzywanski.barleader.ImagePoint;
import io.github.kamilkrzywanski.barleader.decode.Decoded;
import io.github.kamilkrzywanski.barleader.locate.dm.DmGeometry.Bits;
import io.github.kamilkrzywanski.barleader.locate.dm.DmGeometry.Line;
import io.github.kamilkrzywanski.barleader.locate.dm.DmGeometry.P;
import io.github.kamilkrzywanski.barleader.locate.dm.DmGeometry.Warp;
import io.github.kamilkrzywanski.barleader.locate.dm.QrConcentric.Ring;

import java.util.ArrayList;
import java.util.List;

/**
 * rMQR sampler ported from zxing-cpp 3.1.1 {@code QRDetector.cpp} {@code SampleRMQR}.
 *
 * <p>Each finder pattern the QR reader finds is tried as the finder of an rMQR symbol: of its
 * four orientations, the one whose top edge shows the timing pattern and whose format
 * information beside the finder decodes best gives the version and the size. When the finder
 * sub-pattern in the opposite corner is found, the two patterns' corners give the perspective;
 * otherwise the finder alone does, as in zxing-cpp. The grid is decoded by {@link RmqrDecoder}.
 * {@code DetectPureRMQR} is not used.
 */
public final class RmqrDetector {
    private static final int[][] FORMAT_EDGE = {{8, 0}, {9, 0}, {10, 0}, {11, 0}};
    private static final int[][] FORMAT = {
            {11, 3}, {11, 2}, {11, 1},
            {10, 5}, {10, 4}, {10, 3}, {10, 2}, {10, 1},
            {9, 5}, {9, 4}, {9, 3}, {9, 2}, {9, 1},
            {8, 5}, {8, 4}, {8, 3}, {8, 2}, {8, 1},
    };

    private RmqrDetector() {
    }

    /** One decoded symbol. Corners are TL, TR, BR, BL in the image. */
    public record Found(String text, byte[] bytes, List<ImagePoint> corners, int orientationDegrees) {
    }

    static List<Found> read(Bits bits) {
        if (bits == null || bits.width < 7 || bits.height < 7) {
            return List.of();
        }
        List<Found> found = new ArrayList<>();
        for (Ring pattern : QrDetector.findFinderPatterns(bits, true)) {
            Found hit = readPattern(bits, pattern);
            if (hit != null) {
                found.add(hit);
            }
        }
        return found;
    }

    private static Found readPattern(Bits image, Ring pattern) {
        P[] finder = QrConcentric.corners(image, pattern.x, pattern.y, (int) pattern.size, 2);
        if (finder == null) {
            return null;
        }
        P[] source = {new P(0.5, 0.5), new P(6.5, 0.5), new P(6.5, 6.5), new P(0.5, 6.5)};
        RmqrDecoder.Format best = new RmqrDecoder.Format();
        Warp bestWarp = null;
        for (int rotation = 0; rotation < 4; rotation++) {
            Warp warp = Warp.between(source, rotated(finder, rotation));
            if (!warp.valid()) {
                continue;
            }
            // The top edge timing pattern beside the format information.
            if (!colourAt(image, warp, FORMAT_EDGE[0], true) || !colourAt(image, warp, FORMAT_EDGE[1], false)
                    || !colourAt(image, warp, FORMAT_EDGE[2], true) || !colourAt(image, warp, FORMAT_EDGE[3], false)) {
                continue;
            }
            int raw = 0;
            for (int[] at : FORMAT) {
                raw = (raw << 1) | (blackAt(image, warp.apply(P.centered(at[0], at[1]))) ? 1 : 0);
            }
            RmqrDecoder.Format candidate = RmqrDecoder.decodeFormat(raw, 0, false);
            if (candidate.hamming < best.hamming) {
                best = candidate;
                bestWarp = warp;
            }
        }
        if (!best.valid() || bestWarp == null || best.version > RmqrDecoder.SIZES.length) {
            return null;
        }
        int width = RmqrDecoder.SIZES[best.version - 1][0];
        int height = RmqrDecoder.SIZES[best.version - 1][1];
        // The sub-pattern where the finder alone puts it, as zxing-cpp looks for it, then, as that
        // reaches across up to 139 modules from a finder of 7, further along the symbol both ways.
        int moduleSize = (int) pattern.size / 7;
        List<P> tried = new ArrayList<>();
        for (int k = 0; k <= 16; k += 2) {
            for (int sign : k == 0 ? new int[]{1} : new int[]{-1, 1}) {
                P sub = QrDetector.locateAlignment(image, moduleSize, bestWarp.apply(width - 3 + sign * k, height - 3));
                if (sub == null || near(tried, sub, moduleSize)) {
                    continue;
                }
                tried.add(sub);
                Warp warp = subWarp(image, finder, pattern, sub, width, height);
                Found found = warp == null ? null : decode(image, width, height, warp);
                if (found == null) {
                    warp = axisWarp(finder, pattern, sub, width, height);
                    found = warp == null ? null : decode(image, width, height, warp);
                }
                if (found != null) {
                    return found;
                }
            }
        }
        return decode(image, width, height, bestWarp);
    }

    private static boolean near(List<P> points, P point, int moduleSize) {
        for (P other : points) {
            if (distance(other, point) < moduleSize) {
                return true;
            }
        }
        return false;
    }

    /** The perspective from the corners of the finder and of the sub-pattern at {@code sub}, as {@code SampleRMQR} has it. */
    private static Warp subWarp(Bits image, P[] finder, Ring pattern, P sub, int width, int height) {
        P[] subQuad = QrConcentric.corners(image, sub.x, sub.y, (int) pattern.size / 2, 1);
        if (subQuad == null) {
            return null;
        }
        P[][] quads = {finder.clone(), subQuad};
        P[] dest = intersect(quads);
        if (height <= 9) {
            return Warp.between(
                    new P[]{new P(6.5, 0.5), new P(width - 1.5, height - 3.5), new P(width - 1.5, height - 1.5), new P(6.5, 6.5)},
                    new P[]{quads[0][1], quads[1][1], quads[1][2], quads[0][2]});
        }
        dest[0] = new P(pattern.x, pattern.y);
        dest[2] = sub;
        return Warp.between(
                new P[]{new P(3.5, 3.5), new P(width - 2.5, 3.5), new P(width - 2.5, height - 2.5), new P(3.5, height - 2.5)},
                dest);
    }

    /**
     * An affine grid from the centres of the finder and of the sub-pattern, which lie at opposite
     * ends of the symbol, and the module column of the finder: the edges of a finder of seven
     * modules, extended across up to 139, give the far corners several modules off when the
     * symbol is turned, but the line between the two centres does not.
     */
    private static Warp axisWarp(P[] finder, Ring pattern, P sub, int width, int height) {
        P centre = new P(pattern.x, pattern.y);
        P[] a = finder.clone();
        int farthest = 0;
        for (int i = 1; i < 4; i++) {
            if (distance(a[farthest], sub) < distance(a[i], sub)) {
                farthest = i;
            }
        }
        a = rotated(a, farthest);
        // Corners are the centres of the finder's outer modules, six modules apart. Of the two
        // edges from the outer corner, the module column is the one more across the line to the sub-pattern.
        P along = P.sub(sub, centre);
        P first = P.mul(P.sub(a[1], a[0]), 1 / 6.0);
        P second = P.mul(P.sub(a[3], a[0]), 1 / 6.0);
        P column = Math.abs(cosine(first, along)) < Math.abs(cosine(second, along)) ? first : second;
        double du = width - 6;
        double dv = height - 6;
        P row = P.mul(P.sub(along, P.mul(column, dv)), 1 / du);
        P[] source = {new P(0, 0), new P(width, 0), new P(width, height), new P(0, height)};
        P[] dest = new P[4];
        for (int i = 0; i < 4; i++) {
            double u = source[i].x - 3.5;
            double v = source[i].y - 3.5;
            dest[i] = P.add(centre, P.add(P.mul(row, u), P.mul(column, v)));
        }
        Warp warp = Warp.between(source, dest);
        return warp.valid() ? warp : null;
    }

    private static double cosine(P a, P b) {
        return (a.x * b.x + a.y * b.y) / (Math.hypot(a.x, a.y) * Math.hypot(b.x, b.y));
    }

    private static Found decode(Bits image, int width, int height, Warp warp) {
        Grid grid = sample(image, width, height, warp);
        if (grid == null) {
            return null;
        }
        Decoded decoded = RmqrDecoder.decodeContent(grid.bits);
        if (decoded == null) {
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

    /**
     * {@code intersectQuads}: turns the finder's corners so the first is the farthest from the
     * sub-pattern, and the sub-pattern's so the first is the nearest to the finder, in place, and
     * returns the finder's centre, the two other corners where their edges meet, and the
     * sub-pattern's centre.
     */
    private static P[] intersect(P[][] quads) {
        P[] a = quads[0];
        P[] b = quads[1];
        P tl = centre(a);
        P br = centre(b);
        int offsetA = 0;
        for (int i = 1; i < 4; i++) {
            if (distance(a[offsetA], br) < distance(a[i], br)) {
                offsetA = i;
            }
        }
        int offsetB = 0;
        for (int i = 1; i < 4; i++) {
            if (distance(b[i], tl) < distance(b[offsetB], tl)) {
                offsetB = i;
            }
        }
        a = rotated(a, offsetA);
        b = rotated(b, offsetB);
        quads[0] = a;
        quads[1] = b;
        P tr = P.mul(P.add(
                DmGeometry.intersect(Line.through(a[0], a[1]), Line.through(b[1], b[2])),
                DmGeometry.intersect(Line.through(a[3], a[2]), Line.through(b[0], b[3]))), 0.5);
        P bl = P.mul(P.add(
                DmGeometry.intersect(Line.through(a[0], a[3]), Line.through(b[2], b[3])),
                DmGeometry.intersect(Line.through(a[1], a[2]), Line.through(b[0], b[1]))), 0.5);
        return new P[]{tl, tr, br, bl};
    }

    private static P centre(P[] quad) {
        return new P((quad[0].x + quad[1].x + quad[2].x + quad[3].x) / 4, (quad[0].y + quad[1].y + quad[2].y + quad[3].y) / 4);
    }

    private static double distance(P a, P b) {
        return Math.hypot(a.x - b.x, a.y - b.y);
    }

    private static boolean colourAt(Bits image, Warp warp, int[] at, boolean black) {
        P point = warp.apply(P.centered(at[0], at[1]));
        return image.isIn(point) && image.get(point) == black;
    }

    private static boolean blackAt(Bits image, P point) {
        return image.isIn(point) && image.get(point);
    }

    private static P[] rotated(P[] quad, int turns) {
        int shift = Math.floorMod(turns, 4);
        P[] turned = new P[4];
        for (int i = 0; i < 4; i++) {
            turned[i] = quad[(i + shift) % 4];
        }
        return turned;
    }

    private static Grid sample(Bits image, int width, int height, Warp warp) {
        if (warp == null || !warp.valid()) {
            return null;
        }
        Bits bits = new Bits(width, height);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                P pixel = warp.apply(x + 0.5, y + 0.5);
                if (!image.isIn(pixel)) {
                    return null;
                }
                if (image.get(pixel)) {
                    bits.set(x, y);
                }
            }
        }
        return new Grid(bits, new P[]{project(warp, 0, 0), project(warp, width, 0), project(warp, width, height), project(warp, 0, height)});
    }

    private static P project(Warp warp, int x, int y) {
        P at = warp.apply(x, y);
        return new P((int) (at.x + 0.5), (int) (at.y + 0.5));
    }

    private static int orientation(P a, P b) {
        int degrees = (int) Math.round(Math.toDegrees(Math.atan2(b.y - a.y, b.x - a.x)));
        return Math.floorMod(degrees, 360);
    }

    private record Grid(Bits bits, P[] corners) {
    }
}
