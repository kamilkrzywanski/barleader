package io.github.kamilkrzywanski.barcode.locate.dm;

import io.github.kamilkrzywanski.barcode.locate.dm.DmGeometry.Bits;
import io.github.kamilkrzywanski.barcode.locate.dm.DmGeometry.Line;
import io.github.kamilkrzywanski.barcode.locate.dm.DmGeometry.P;
import io.github.kamilkrzywanski.barcode.locate.dm.QrCursor.IntCursor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Concentric-square finder from zxing-cpp 3.1.1 {@code ConcentricFinder.cpp}.
 * Rings are walked clockwise with the edge kept on the requested side.
 */
final class QrConcentric {
    /** Full 8-neighborhood. Bit 4 is the unused center of this encoding. */
    private static final int FULL_CIRCLE = 0b111101111;

    private QrConcentric() {
    }

    static final class Ring {
        double x;
        double y;
        double size;

        Ring(double x, double y, double size) {
            this.x = x;
            this.y = y;
            this.size = size;
        }

        P point() {
            return new P(x, y);
        }
    }

    static Ring locate(Bits image, double cx, double cy, int width) {
        int x = (int) cx;
        int y = (int) cy;
        if (!image.isIn(x, y)) {
            return null;
        }
        IntCursor cursor = new IntCursor(image, x, y, 0, 0);
        int range = width * 2;
        int minSpread = image.width;
        int maxSpread = 0;
        int maxError = 0;
        for (int[] direction : new int[][]{{0, 1}, {1, 0}}) {
            cursor.dx = direction[0];
            cursor.dy = direction[1];
            int spread = QrCursor.checkSymmetric(cursor, range, true);
            if (spread != 0) {
                minSpread = Math.min(minSpread, spread);
                maxSpread = Math.max(maxSpread, spread);
            } else if (--maxError < 0) {
                return null;
            }
        }
        for (int[] direction : new int[][]{{1, 1}, {1, -1}}) {
            cursor.dx = direction[0];
            cursor.dy = direction[1];
            int spread = QrCursor.checkSymmetric(cursor, range, false);
            if (spread != 0) {
                minSpread = Math.min(minSpread, spread);
                maxSpread = Math.max(maxSpread, spread);
            } else if (--maxError < 0) {
                return null;
            }
        }
        if (maxSpread > 5 * minSpread) {
            return null;
        }
        Ring tuned = finetune(image, cursor.x, cursor.y, width, 5);
        if (tuned != null && tuned.size == 0) {
            tuned.size = (maxSpread + minSpread) / 2.0;
        }
        return tuned;
    }

    static Ring finetune(Bits image, double cx, double cy, int width, int finderPatternSize) {
        Ring first = centerOfRing(image, (int) cx, (int) cy, width * 2 / 3, 1, true);
        if (first == null || !black(image, first.x, first.y)) {
            return null;
        }
        Ring rings = centerOfRings(image, first.x, first.y, width, finderPatternSize / 2);
        if (rings != null && black(image, rings.x, rings.y)) {
            rings.size = rings.size * 7 / 5;
            return rings;
        }
        Ring cross = centerOfDoubleCross(
                image,
                (int) first.x,
                (int) first.y,
                width,
                finderPatternSize / 2 + 1
        );
        if (cross != null && black(image, cross.x, cross.y)) {
            return cross;
        }
        return null;
    }

    static P[] fitSquare(Bits image, double cx, double cy, int width, int lineIndex, boolean backup) {
        List<P> points = collectRing(image, cx, cy, width, lineIndex, backup);
        if (points.isEmpty()) {
            return null;
        }
        P[] quad = fitQuadrilateral(new P(cx, cy), points);
        if (quad == null || !plausibleSquare(quad, lineIndex - (backup ? 1 : 0))) {
            return null;
        }
        return quad;
    }

    static P[] corners(Bits image, double cx, double cy, int width, int ringIndex) {
        P[] inner = fitSquare(image, cx, cy, width, ringIndex, false);
        if (inner == null) {
            return null;
        }
        P[] outer = fitSquare(image, cx, cy, width, ringIndex + 1, true);
        if (outer == null) {
            return null;
        }
        return blend(inner, outer);
    }

    static Ring centerOfRing(Bits image, int cx, int cy, int width, int nth, boolean requireCircle) {
        if (width == 0 || !image.isIn(cx, cy)) {
            return null;
        }
        int maxN = 4 * (width + 1) * 3 / 2;
        int maxR = requireCircle ? width + 1 : 2 * width;
        boolean inner = nth < 0;
        nth = Math.abs(nth);
        IntCursor cursor = new IntCursor(image, cx, cy, 1, 0);
        if (cursor.stepToEdge(nth, maxR, inner) == 0) {
            return null;
        }
        cursor.turnRight();
        int edgeDir = inner ? QrCursor.LEFT : QrCursor.RIGHT;
        int mask = 0;
        int startX = cursor.x;
        int startY = cursor.y;
        double sumX = 0;
        double sumY = 0;
        double sumR = 0;
        int count = 0;
        while (true) {
            sumX += cursor.x + 0.5;
            sumY += cursor.y + 0.5;
            count++;
            mask |= neighbourBit(cursor.x - cx, cursor.y - cy);
            if (!cursor.stepAlongEdge(edgeDir, false)) {
                return null;
            }
            double radius = distance(cursor.x, cursor.y, cx, cy) + (inner ? 0.5 : -0.5);
            sumR += radius;
            if (radius > maxR || (cursor.x == cx && cursor.y == cy) || count > maxN) {
                return null;
            }
            if (cursor.x == startX && cursor.y == startY) {
                break;
            }
        }
        double meanX = sumX / count;
        double meanY = sumY / count;
        double meanR = sumR / count;
        if (requireCircle) {
            if (mask != FULL_CIRCLE) {
                return null;
            }
            double circumference = meanR == 0 ? Double.POSITIVE_INFINITY : count / meanR;
            double centerMove = distance(meanX, meanY, cx, cy) / width;
            if (circumference > 12 || centerMove > 0.5) {
                return null;
            }
        }
        return new Ring(meanX, meanY, 1.74 * meanR);
    }

    private static Ring centerOfRings(Bits image, double cx, double cy, int range, int numOfRings) {
        int count = 1;
        double size = 0;
        double sumX = cx;
        double sumY = cy;
        for (int ring = 2; ring < numOfRings + 1; ring++) {
            Ring next = centerOfRing(image, (int) cx, (int) cy, range, ring, true);
            if (next == null) {
                if (count == 1) {
                    return null;
                }
                break;
            }
            if (P.distance(next.point(), new P(cx, cy)) > range / numOfRings / 2) {
                return null;
            }
            sumX += next.x;
            sumY += next.y;
            size = next.size;
            count++;
        }
        return new Ring(sumX / count, sumY / count, count == numOfRings ? size : 0);
    }

    private static Ring centerOfDoubleCross(Bits image, int cx, int cy, int width, int numOfEdges) {
        double sumX = 0;
        double sumY = 0;
        double sumSize = 0;
        int reach = width * 3 / 5;
        for (int[] direction : new int[][]{{0, 1}, {1, 0}, {1, 1}, {1, -1}}) {
            Ring one = averageEdges(image, cx, cy, direction[0], direction[1], reach, numOfEdges);
            Ring two = averageEdges(image, cx, cy, -direction[0], -direction[1], reach, numOfEdges);
            if (one == null || two == null) {
                return null;
            }
            double low = Math.min(one.size, two.size);
            double high = Math.max(one.size, two.size);
            if (high > 1.2 * low + 1) {
                return null;
            }
            sumX += one.x + two.x;
            sumY += one.y + two.y;
            sumSize += one.size + two.size;
        }
        return new Ring(sumX / 8, sumY / 8, 2 * sumSize / 8);
    }

    private static Ring averageEdges(Bits image, int cx, int cy, int dx, int dy, int range, int numOfEdges) {
        IntCursor cursor = new IntCursor(image, cx, cy, dx, dy);
        double sumX = 0;
        double sumY = 0;
        double totalSteps = 0;
        for (int i = 0; i < numOfEdges; i++) {
            int steps = cursor.stepToEdge(1, (int) (range - totalSteps), false);
            if (steps == 0) {
                return null;
            }
            totalSteps += steps;
            sumX += (cursor.x + 0.5) + (cursor.x - cursor.dx + 0.5);
            sumY += (cursor.y + 0.5) + (cursor.y - cursor.dy + 0.5);
        }
        return new Ring(sumX / (2.0 * numOfEdges), sumY / (2.0 * numOfEdges), totalSteps);
    }

    private static List<P> collectRing(Bits image, double cx, double cy, int width, int edgeIndex, boolean backup) {
        int icx = (int) cx;
        int icy = (int) cy;
        if (width == 0 || !image.isIn(icx, icy)) {
            return List.of();
        }
        int maxN = 4 * (width + 1) * 3 / 2;
        int maxR = width * 2;
        IntCursor cursor = new IntCursor(image, icx, icy, 1, 0);
        if (cursor.stepToEdge(edgeIndex, maxR, backup) == 0) {
            return List.of();
        }
        cursor.turnRight();
        int edgeDir = backup ? QrCursor.LEFT : QrCursor.RIGHT;
        int mask = 0;
        int startX = cursor.x;
        int startY = cursor.y;
        List<P> points = new ArrayList<>();
        while (true) {
            points.add(P.centered(cursor.x, cursor.y));
            mask |= neighbourBit(cursor.x - icx, cursor.y - icy);
            if (!cursor.stepAlongEdge(edgeDir, false)) {
                return List.of();
            }
            if (distance(cursor.x, cursor.y, icx, icy) > maxR
                    || (cursor.x == icx && cursor.y == icy)
                    || points.size() > maxN) {
                return List.of();
            }
            if (cursor.x == startX && cursor.y == startY) {
                break;
            }
        }
        if (mask != FULL_CIRCLE) {
            return List.of();
        }
        return points;
    }

    private static P[] fitQuadrilateral(P center, List<P> source) {
        int count = source.size();
        if (count < 4) {
            return null;
        }
        List<P> points = new ArrayList<>(source);
        int minIndex = 0;
        int maxIndex = 0;
        for (int i = 1; i < count; i++) {
            double distance = P.distance(points.get(i), center);
            if (distance < P.distance(points.get(minIndex), center)) {
                minIndex = i;
            }
            if (distance >= P.distance(points.get(maxIndex), center)) {
                maxIndex = i;
            }
        }
        double minDistance = P.distance(points.get(minIndex), center);
        double maxDistance = P.distance(points.get(maxIndex), center);
        if (!(maxDistance > 0) || minDistance / maxDistance > 0.85) {
            return null;
        }
        Collections.rotate(points, -maxIndex);
        int opposite = farthestFromCenter(points, count * 3 / 8, count * 5 / 8, center);
        if (opposite <= 0) {
            return null;
        }
        Line diagonal = Line.through(points.get(0), points.get(opposite));
        if (!diagonal.valid()) {
            return null;
        }
        int left = farthestFromLine(points, count / 8, count * 3 / 8, diagonal);
        int right = farthestFromLine(points, count * 5 / 8, count * 7 / 8, diagonal);
        if (left <= 0 || right <= opposite || opposite <= left || right >= count) {
            return null;
        }
        int[] corner = {0, left, opposite, right};
        int[] end = {left, opposite, right, count};
        Line[] sides = new Line[4];
        for (int i = 0; i < 4; i++) {
            int from = corner[i] + 1;
            int to = end[i];
            if (to - from < 2) {
                return null;
            }
            sides[i] = Line.fit(points.subList(from, to));
            if (!sides[i].valid()) {
                return null;
            }
            int length = to - from;
            if (length > 3) {
                double limit = Math.max(1.0, Math.min(8.0, length / 8.0));
                for (int p = from; p < to; p++) {
                    if (sides[i].distance(points.get(p)) > limit) {
                        return null;
                    }
                }
            }
        }
        P[] quad = new P[4];
        for (int i = 0; i < 4; i++) {
            quad[i] = DmGeometry.intersect(sides[i], sides[(i + 1) % 4]);
            if (!Double.isFinite(quad[i].x) || !Double.isFinite(quad[i].y)) {
                return null;
            }
        }
        return quad;
    }

    private static boolean plausibleSquare(P[] quad, int lineIndex) {
        double low = P.distance(quad[0], quad[3]);
        double high = low;
        for (int i = 1; i < 4; i++) {
            double side = P.distance(quad[i - 1], quad[i]);
            low = Math.min(low, side);
            high = Math.max(high, side);
        }
        return low >= lineIndex * 2.0 && low > high / 3.0;
    }

    private static P[] blend(P[] a, P[] b) {
        int offset = 0;
        double best = P.distance(b[0], a[0]);
        for (int i = 1; i < 4; i++) {
            double distance = P.distance(b[i], a[0]);
            if (distance < best) {
                best = distance;
                offset = i;
            }
        }
        P[] blended = new P[4];
        for (int i = 0; i < 4; i++) {
            P other = b[(i + offset) % 4];
            blended[i] = new P((a[i].x + other.x) / 2.0, (a[i].y + other.y) / 2.0);
        }
        return blended;
    }

    private static int farthestFromCenter(List<P> points, int from, int to, P center) {
        if (from >= to) {
            return -1;
        }
        int best = from;
        double bestDistance = P.distance(points.get(from), center);
        for (int i = from + 1; i < to; i++) {
            double distance = P.distance(points.get(i), center);
            if (distance > bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return best;
    }

    private static int farthestFromLine(List<P> points, int from, int to, Line line) {
        if (from >= to) {
            return -1;
        }
        int best = from;
        double bestDistance = line.distance(points.get(from));
        for (int i = from + 1; i < to; i++) {
            double distance = line.distance(points.get(i));
            if (distance > bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return best;
    }

    private static int neighbourBit(int dx, int dy) {
        int scale = Math.max(Math.abs(dx), Math.abs(dy));
        if (scale == 0) {
            return 0;
        }
        int bx = dx / scale;
        int by = dy / scale;
        int shift = 4 + bx + 3 * by;
        if (shift < 0 || shift >= 31) {
            return 0;
        }
        return 1 << shift;
    }

    private static boolean black(Bits image, double x, double y) {
        return image.isIn(x, y) && image.get(new P(x, y));
    }

    private static double distance(double ax, double ay, double bx, double by) {
        double dx = ax - bx;
        double dy = ay - by;
        return Math.sqrt(dx * dx + dy * dy);
    }
}
