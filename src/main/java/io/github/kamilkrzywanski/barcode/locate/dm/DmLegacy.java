/*
 * Copyright 2016 Nu-book Inc.
 * Copyright 2016 ZXing authors
 * Copyright 2026 Kamil Krzywański
 */
// SPDX-License-Identifier: Apache-2.0
package io.github.kamilkrzywanski.barcode.locate.dm;

import io.github.kamilkrzywanski.barcode.locate.dm.DmGeometry.Bits;
import io.github.kamilkrzywanski.barcode.locate.dm.DmGeometry.P;
import io.github.kamilkrzywanski.barcode.locate.dm.DmGeometry.Warp;

/**
 * White-rectangle Data Matrix fallback from zxing-cpp 3.1.1 {@code DetectOld}
 * and {@code WhiteRectDetector.cpp}. The edge tracer does not claim this image,
 * so the reference reader uses this path.
 */
final class DmLegacy {
    private static final int INIT_SIZE = 10;
    private static final int CORRECTION = 1;

    private DmLegacy() {
    }

    static DataMatrixDetector.Grid detect(Bits image) {
        P[] corners = whiteRect(image);
        if (corners == null) {
            return null;
        }
        return fromCorners(image, corners[0], corners[1], corners[2], corners[3]);
    }

    private static DataMatrixDetector.Grid fromCorners(Bits image, P pointA, P pointB, P pointC, P pointD) {
        P[] points = {pointA, pointB, pointC, pointD};
        Edge[] edges = {
                edge(image, points, 0, 1),
                edge(image, points, 0, 2),
                edge(image, points, 1, 3),
                edge(image, points, 2, 3)
        };
        java.util.Arrays.sort(edges, (left, right) -> Integer.compare(left.transitions, right.transitions));
        Edge sideOne = edges[0];
        Edge sideTwo = edges[1];
        // At most two corruptions inside the solid L. A noisier pair is not this locator.
        if (sideTwo.transitions > 2) {
            return null;
        }
        int[] seen = new int[4];
        seen[sideOne.from]++;
        seen[sideOne.to]++;
        seen[sideTwo.from]++;
        seen[sideTwo.to]++;
        int bottomLeft = -1;
        int firstOther = -1;
        int secondOther = -1;
        for (int i = 0; i < seen.length; i++) {
            if (seen[i] == 2) {
                bottomLeft = i;
            } else if (seen[i] == 1) {
                if (firstOther < 0) {
                    firstOther = i;
                } else {
                    secondOther = i;
                }
            }
        }
        if (bottomLeft < 0 || firstOther < 0 || secondOther < 0) {
            return null;
        }
        int[] ordered = order(points[firstOther], points[bottomLeft], points[secondOther]);
        // order() returns indices into the triple (other, corner, other): 0 and 2 are the ends, 1 is the corner.
        P bottomRight = ordered[0] == 0 ? points[firstOther] : ordered[0] == 1 ? points[bottomLeft] : points[secondOther];
        P corner = ordered[1] == 0 ? points[firstOther] : ordered[1] == 1 ? points[bottomLeft] : points[secondOther];
        P topLeft = ordered[2] == 0 ? points[firstOther] : ordered[2] == 1 ? points[bottomLeft] : points[secondOther];
        int topRightIndex = 0;
        for (int i = 0; i < seen.length; i++) {
            if (seen[i] == 0) {
                topRightIndex = i;
            }
        }
        P topRight = points[topRightIndex];

        int dimensionTop = transitions(image, topLeft, topRight);
        int dimensionRight = transitions(image, bottomRight, topRight);
        if ((dimensionTop & 1) == 1) {
            dimensionTop++;
        }
        dimensionTop += 2;
        if ((dimensionRight & 1) == 1) {
            dimensionRight++;
        }
        dimensionRight += 2;
        if (dimensionTop < 10 || dimensionTop > 144 || dimensionRight < 8 || dimensionRight > 144) {
            return null;
        }

        P corrected;
        if (4 * dimensionTop >= 7 * dimensionRight || 4 * dimensionRight >= 7 * dimensionTop) {
            corrected = correctRectangular(image, corner, bottomRight, topLeft, topRight, dimensionTop, dimensionRight);
            if (corrected == null) {
                corrected = topRight;
            }
            dimensionTop = transitions(image, topLeft, corrected);
            dimensionRight = transitions(image, bottomRight, corrected);
            if ((dimensionTop & 1) == 1) {
                dimensionTop++;
            }
            if ((dimensionRight & 1) == 1) {
                dimensionRight++;
            }
        } else {
            int dimension = Math.min(dimensionRight, dimensionTop);
            corrected = correctSquare(image, corner, bottomRight, topLeft, topRight, dimension);
            int correctedDimension = Math.max(
                    transitions(image, topLeft, corrected),
                    transitions(image, bottomRight, corrected)
            );
            correctedDimension++;
            if ((correctedDimension & 1) == 1) {
                correctedDimension++;
            }
            dimensionTop = dimensionRight = correctedDimension;
        }
        P[] rectangle = {
                new P(0, 0), new P(dimensionTop, 0), new P(dimensionTop, dimensionRight), new P(0, dimensionRight)
        };
        P[] quad = {topLeft, corrected, bottomRight, corner};
        return DataMatrixDetector.sample(image, dimensionTop, dimensionRight, Warp.between(rectangle, quad));
    }

    /** {@code OrderByBestPatterns}. Returns indices 0, 1, 2 of the input triple as A, B, C. */
    private static int[] order(P p0, P p1, P p2) {
        P[] points = {p0, p1, p2};
        double zeroOne = P.distance(p0, p1);
        double oneTwo = P.distance(p1, p2);
        double zeroTwo = P.distance(p0, p2);
        int a;
        int b;
        int c;
        if (oneTwo >= zeroOne && oneTwo >= zeroTwo) {
            b = 0;
            a = 1;
            c = 2;
        } else if (zeroTwo >= oneTwo && zeroTwo >= zeroOne) {
            b = 1;
            a = 0;
            c = 2;
        } else {
            b = 2;
            a = 0;
            c = 1;
        }
        if (cross(points[a], points[b], points[c]) < 0) {
            int swap = a;
            a = c;
            c = swap;
        }
        return new int[]{a, b, c};
    }

    private static double cross(P a, P b, P c) {
        return (c.x - b.x) * (a.y - b.y) - (c.y - b.y) * (a.x - b.x);
    }

    private static P correctSquare(Bits image, P bottomLeft, P bottomRight, P topLeft, P topRight, int dimension) {
        float corr = Math.round(P.distance(bottomLeft, bottomRight)) / (float) dimension;
        float norm = Math.round(P.distance(topLeft, topRight));
        float cos = (float) ((topRight.x - topLeft.x) / norm);
        float sin = (float) ((topRight.y - topLeft.y) / norm);
        P c1 = new P(topRight.x + corr * cos, topRight.y + corr * sin);

        corr = Math.round(P.distance(bottomLeft, topLeft)) / (float) dimension;
        norm = Math.round(P.distance(bottomRight, topRight));
        cos = (float) ((topRight.x - bottomRight.x) / norm);
        sin = (float) ((topRight.y - bottomRight.y) / norm);
        P c2 = new P(topRight.x + corr * cos, topRight.y + corr * sin);

        if (!validPoint(c1, image)) {
            if (!validPoint(c2, image)) {
                return topRight;
            }
            return c2;
        }
        if (!validPoint(c2, image)) {
            return c1;
        }
        int left = Math.abs(transitions(image, topLeft, c1) - transitions(image, bottomRight, c1));
        int right = Math.abs(transitions(image, topLeft, c2) - transitions(image, bottomRight, c2));
        return left <= right ? c1 : c2;
    }

    private static P correctRectangular(
            Bits image, P bottomLeft, P bottomRight, P topLeft, P topRight, int dimensionTop, int dimensionRight
    ) {
        float corr = Math.round(P.distance(bottomLeft, bottomRight)) / (float) dimensionTop;
        float norm = Math.round(P.distance(topLeft, topRight));
        float cos = (float) ((topRight.x - topLeft.x) / norm);
        float sin = (float) ((topRight.y - topLeft.y) / norm);
        P c1 = new P(topRight.x + corr * cos, topRight.y + corr * sin);

        corr = Math.round(P.distance(bottomLeft, topLeft)) / (float) dimensionRight;
        norm = Math.round(P.distance(bottomRight, topRight));
        cos = (float) ((topRight.x - bottomRight.x) / norm);
        sin = (float) ((topRight.y - bottomRight.y) / norm);
        P c2 = new P(topRight.x + corr * cos, topRight.y + corr * sin);

        if (!validPoint(c1, image)) {
            return validPoint(c2, image) ? c2 : null;
        }
        if (!validPoint(c2, image)) {
            return c1;
        }
        int left = Math.abs(dimensionTop - transitions(image, topLeft, c1))
                + Math.abs(dimensionRight - transitions(image, bottomRight, c1));
        int right = Math.abs(dimensionTop - transitions(image, topLeft, c2))
                + Math.abs(dimensionRight - transitions(image, bottomRight, c2));
        return left <= right ? c1 : c2;
    }

    /** {@code y > 0}, matching {@code IsValidPoint}. */
    private static boolean validPoint(P point, Bits image) {
        return point.x >= 0 && point.x < image.width && point.y > 0 && point.y < image.height;
    }

    private static Edge edge(Bits image, P[] points, int from, int to) {
        return new Edge(from, to, transitions(image, points[from], points[to]));
    }

    private static int transitions(Bits image, P from, P to) {
        int fromX = (int) from.x;
        int fromY = (int) from.y;
        int toX = (int) to.x;
        int toY = (int) to.y;
        boolean steep = Math.abs(toY - fromY) > Math.abs(toX - fromX);
        if (steep) {
            int swap = fromX;
            fromX = fromY;
            fromY = swap;
            swap = toX;
            toX = toY;
            toY = swap;
        }
        int dx = Math.abs(toX - fromX);
        int dy = Math.abs(toY - fromY);
        int error = -dx / 2;
        int yStep = fromY < toY ? 1 : -1;
        int xStep = fromX < toX ? 1 : -1;
        int count = 0;
        boolean inBlack = black(image, steep ? fromY : fromX, steep ? fromX : fromY);
        for (int x = fromX, y = fromY; x != toX; x += xStep) {
            boolean isBlack = black(image, steep ? y : x, steep ? x : y);
            if (isBlack != inBlack) {
                count++;
                inBlack = isBlack;
            }
            error += dy;
            if (error > 0) {
                if (y == toY) {
                    break;
                }
                y += yStep;
                error -= dx;
            }
        }
        return count;
    }

    private static boolean black(Bits image, int x, int y) {
        return x >= 0 && y >= 0 && x < image.width && y < image.height && image.get(x, y);
    }

    private static P[] whiteRect(Bits image) {
        return whiteRect(image, INIT_SIZE, image.width / 2, image.height / 2);
    }

    private static P[] whiteRect(Bits image, int initSize, int x, int y) {
        int half = initSize / 2;
        int left = x - half;
        int right = x + half;
        int up = y - half;
        int down = y + half;
        if (up < 0 || left < 0 || down >= image.height || right >= image.width) {
            return null;
        }
        boolean grew = true;
        boolean any = false;
        boolean hitRight = false;
        boolean hitBottom = false;
        boolean hitLeft = false;
        boolean hitTop = false;
        while (grew) {
            grew = false;
            boolean rightDark = true;
            while ((rightDark || !hitRight) && right < image.width) {
                rightDark = contains(image, up, down, right, false);
                if (rightDark) {
                    right++;
                    grew = true;
                    hitRight = true;
                } else if (!hitRight) {
                    right++;
                }
            }
            boolean bottomDark = true;
            while ((bottomDark || !hitBottom) && down < image.height) {
                bottomDark = contains(image, left, right, down, true);
                if (bottomDark) {
                    down++;
                    grew = true;
                    hitBottom = true;
                } else if (!hitBottom) {
                    down++;
                }
            }
            boolean leftDark = true;
            while ((leftDark || !hitLeft) && left >= 0) {
                leftDark = contains(image, up, down, left, false);
                if (leftDark) {
                    left--;
                    grew = true;
                    hitLeft = true;
                } else if (!hitLeft) {
                    left--;
                }
            }
            boolean topDark = true;
            while ((topDark || !hitTop) && up >= 0) {
                topDark = contains(image, left, right, up, true);
                if (topDark) {
                    up--;
                    grew = true;
                    hitTop = true;
                } else if (!hitTop) {
                    up--;
                }
            }
            if (grew) {
                any = true;
            }
        }
        if (up < 0 || left < 0 || down >= image.height || right >= image.width || !any) {
            return null;
        }
        int maxSize = right - left;
        P bottomLeft = null;
        for (int i = 1; bottomLeft == null && i < maxSize; i++) {
            bottomLeft = firstBlack(image, left, down - i, left + i, down);
        }
        if (bottomLeft == null) {
            return null;
        }
        P topLeft = null;
        for (int i = 1; topLeft == null && i < maxSize; i++) {
            topLeft = firstBlack(image, left, up + i, left + i, up);
        }
        if (topLeft == null) {
            return null;
        }
        P topRight = null;
        for (int i = 1; topRight == null && i < maxSize; i++) {
            topRight = firstBlack(image, right, up + i, right - i, up);
        }
        if (topRight == null) {
            return null;
        }
        P bottomRight = null;
        for (int i = 1; bottomRight == null && i < maxSize; i++) {
            bottomRight = firstBlack(image, right, down - i, right - i, down);
        }
        if (bottomRight == null) {
            return null;
        }
        return center(bottomRight, bottomLeft, topRight, topLeft, image.width);
    }

    /** {@code CenterEdges}. Returned order is the detector's point A, B, C, D. */
    private static P[] center(P y, P z, P x, P t, int width) {
        if (y.x < width / 2.0) {
            return new P[]{
                    new P(t.x - CORRECTION, t.y + CORRECTION),
                    new P(z.x + CORRECTION, z.y + CORRECTION),
                    new P(x.x - CORRECTION, x.y - CORRECTION),
                    new P(y.x + CORRECTION, y.y - CORRECTION)
            };
        }
        return new P[]{
                new P(t.x + CORRECTION, t.y + CORRECTION),
                new P(z.x + CORRECTION, z.y - CORRECTION),
                new P(x.x - CORRECTION, x.y + CORRECTION),
                new P(y.x - CORRECTION, y.y - CORRECTION)
        };
    }

    private static P firstBlack(Bits image, int ax, int ay, int bx, int by) {
        P start = new P(ax, ay);
        P end = new P(bx, by);
        P direction = P.bresenham(P.sub(end, start));
        double length = P.length(direction);
        if (length == 0.0) {
            return null;
        }
        int steps = (int) Math.round(P.distance(start, end) / length);
        P cursor = start;
        for (int i = 0; i < steps; i++) {
            if (black(image, P.trunc(cursor.x), P.trunc(cursor.y))) {
                return cursor;
            }
            cursor = P.add(cursor, direction);
        }
        return null;
    }

    private static boolean contains(Bits image, int from, int to, int fixed, boolean horizontal) {
        from = Math.max(from, 0);
        if (horizontal) {
            if (fixed < 0 || fixed >= image.height) {
                return false;
            }
            to = Math.min(to, image.width - 1);
            for (int x = from; x <= to; x++) {
                if (image.get(x, fixed)) {
                    return true;
                }
            }
        } else {
            if (fixed < 0 || fixed >= image.width) {
                return false;
            }
            to = Math.min(to, image.height - 1);
            for (int y = from; y <= to; y++) {
                if (image.get(fixed, y)) {
                    return true;
                }
            }
        }
        return false;
    }

    private record Edge(int from, int to, int transitions) {
    }
}
