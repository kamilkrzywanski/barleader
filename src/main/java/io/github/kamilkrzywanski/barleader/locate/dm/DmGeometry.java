/*
 * Copyright 2016 Nu-book Inc.
 * Copyright 2016 ZXing authors
 * Copyright 2020 Axel Waggershauser
 * Copyright 2026 Kamil Krzywański
 */
// SPDX-License-Identifier: Apache-2.0
package io.github.kamilkrzywanski.barleader.locate.dm;

import io.github.kamilkrzywanski.barleader.image.BlackMatrix;

import java.util.ArrayList;
import java.util.List;

/**
 * Geometry used by the zxing-cpp 3.1.1 Data Matrix detector
 * ({@code core/src/Point.h}, {@code RegressionLine.h}, {@code PerspectiveTransform.cpp},
 * {@code Quadrilateral.h}, {@code DMVersion.cpp}).
 */
final class DmGeometry {
    private DmGeometry() {
    }

    static final class P {
        double x;
        double y;

        P(double x, double y) {
            this.x = x;
            this.y = y;
        }

        P copy() {
            return new P(x, y);
        }

        void set(P other) {
            x = other.x;
            y = other.y;
        }

        static P of(double x, double y) {
            return new P(x, y);
        }

        static P add(P a, P b) {
            return new P(a.x + b.x, a.y + b.y);
        }

        static P sub(P a, P b) {
            return new P(a.x - b.x, a.y - b.y);
        }

        static P mul(P a, double s) {
            return new P(a.x * s, a.y * s);
        }

        static P mul(P a, P b) {
            return new P(a.x * b.x, a.y * b.y);
        }

        static P neg(P a) {
            return new P(-a.x, -a.y);
        }

        static double dot(P a, P b) {
            return a.x * b.x + a.y * b.y;
        }

        static double cross(P a, P b) {
            return a.x * b.y - b.x * a.y;
        }

        static double length(P p) {
            return Math.sqrt(dot(p, p));
        }

        static double distance(P a, P b) {
            return length(sub(a, b));
        }

        static double maxAbs(P p) {
            return Math.max(Math.abs(p.x), Math.abs(p.y));
        }

        static P normalized(P d) {
            double len = length(d);
            if (len == 0.0) {
                return new P(0, 0);
            }
            return new P(d.x / len, d.y / len);
        }

        /** Larger component becomes ±1. Matches {@code bresenhamDirection}. */
        static P bresenham(P d) {
            double m = maxAbs(d);
            if (m == 0.0) {
                return new P(0, 0);
            }
            return new P(d.x / m, d.y / m);
        }

        /** Keeps only the dominant axis. Ties keep the vertical component. */
        static P main(P d) {
            if (Math.abs(d.x) > Math.abs(d.y)) {
                return new P(d.x, 0);
            }
            return new P(0, d.y);
        }

        static P right(P d) {
            return new P(-d.y, d.x);
        }

        static P left(P d) {
            return new P(d.y, -d.x);
        }

        static P centered(P p) {
            return new P(Math.floor(p.x) + 0.5, Math.floor(p.y) + 0.5);
        }

        static P centered(int x, int y) {
            return new P(x + 0.5, y + 0.5);
        }

        static int trunc(double v) {
            return (int) v;
        }
    }

    /** Row-major black/white image. {@code true} is black, matching zxing-cpp {@code BitMatrix}. */
    static final class Bits {
        final int width;
        final int height;
        private final boolean[] data;

        Bits(int width, int height) {
            this.width = width;
            this.height = height;
            this.data = new boolean[width * height];
        }

        /** A copy of {@code matrix}, which other readers of the attempt share; {@link #flipAll} changes this one. */
        static Bits of(BlackMatrix matrix) {
            int width = matrix.width();
            int height = matrix.height();
            Bits bits = new Bits(width, height);
            for (int y = 0; y < height; y++) {
                matrix.copyRow(y, bits.data, y * width);
            }
            return bits;
        }

        boolean isIn(double x, double y) {
            return x >= 0 && x < width && y >= 0 && y < height;
        }

        boolean isIn(P p) {
            return isIn(p.x, p.y);
        }

        boolean get(int x, int y) {
            return data[y * width + x];
        }

        boolean get(P p) {
            return get(P.trunc(p.x), P.trunc(p.y));
        }

        void set(int x, int y) {
            data[y * width + x] = true;
        }

        /** {@code BitMatrix::flipAll}. Inverted reflectance is this matrix, not a new threshold. */
        void flipAll() {
            for (int i = 0; i < data.length; i++) {
                data[i] = !data[i];
            }
        }

        /** Black, white, or outside. Outside is neither. */
        int value(P p) {
            return value(p.x, p.y);
        }

        /** {@link #value(P)} of the point (x, y), without building it. */
        int value(double x, double y) {
            if (!isIn(x, y)) {
                return -1;
            }
            return get(P.trunc(x), P.trunc(y)) ? 1 : 0;
        }
    }

    /**
     * ISO/IEC 16022 and DMRE symbol sizes from zxing-cpp {@code VersionForDimensions}.
     * Pairs are {@code (symbolHeight, symbolWidth)}.
     */
    static final class Versions {
        private static final short[] PAIRS = {
                10, 10, 12, 12, 14, 14, 16, 16, 18, 18, 20, 20, 22, 22, 24, 24, 26, 26,
                32, 32, 36, 36, 40, 40, 44, 44, 48, 48, 52, 52, 64, 64, 72, 72, 80, 80,
                88, 88, 96, 96, 104, 104, 120, 120, 132, 132, 144, 144,
                8, 18, 8, 32, 12, 26, 12, 36, 16, 36, 16, 48,
                8, 48, 8, 64, 8, 80, 8, 96, 8, 120, 8, 144,
                12, 64, 12, 88, 16, 64, 20, 36, 20, 44, 20, 64,
                22, 48, 24, 48, 24, 64, 26, 40, 26, 48, 26, 64
        };

        private Versions() {
        }

        static boolean known(int height, int width) {
            if ((height & 1) != 0 || (width & 1) != 0 || height < 8 || height > 144) {
                return false;
            }
            for (int i = 0; i < PAIRS.length; i += 2) {
                if (PAIRS[i] == height && PAIRS[i + 1] == width) {
                    return true;
                }
            }
            return false;
        }
    }

    /** Regression line from zxing-cpp {@code RegressionLine}. */
    static final class Line {
        final List<P> points = new ArrayList<>();
        private P inward = new P(0, 0);
        private double a = Double.NaN;
        private double b = Double.NaN;
        private double c = Double.NaN;

        void reset() {
            points.clear();
            inward = new P(0, 0);
            a = b = c = Double.NaN;
        }

        void reverse() {
            java.util.Collections.reverse(points);
        }

        void add(P p) {
            points.add(p.copy());
            if (points.size() == 1) {
                c = P.dot(normal(), p);
            }
        }

        void pop() {
            points.remove(points.size() - 1);
        }

        void setInward(P d) {
            inward = P.normalized(d);
        }

        boolean valid() {
            return !Double.isNaN(a);
        }

        /** Two-point fit. The inward normal stays unset, matching the zxing-cpp constructor. */
        static Line through(P a, P b) {
            Line line = new Line();
            line.evaluate(java.util.List.of(a, b));
            return line;
        }

        /** Fit through an existing point list without storing it. */
        static Line fit(List<P> source) {
            Line line = new Line();
            if (source.size() >= 2) {
                line.evaluate(source);
            }
            return line;
        }

        /**
         * False when the segment is short and nearly axis-aligned.
         * Matches {@code RegressionLine::isHighRes}.
         */
        boolean highRes() {
            if (points.isEmpty()) {
                return false;
            }
            double minX = points.get(0).x;
            double maxX = minX;
            double minY = points.get(0).y;
            double maxY = minY;
            for (P point : points) {
                minX = Math.min(minX, point.x);
                maxX = Math.max(maxX, point.x);
                minY = Math.min(minY, point.y);
                maxY = Math.max(maxY, point.y);
            }
            double dx = maxX - minX;
            double dy = maxY - minY;
            double len = Math.max(Math.abs(dx), Math.abs(dy));
            double steps = Math.min(Math.abs(dx), Math.abs(dy));
            return steps > 2 || len > 50;
        }

        int length() {
            if (points.size() < 2) {
                return 0;
            }
            return (int) P.distance(points.get(0), points.get(points.size() - 1));
        }

        P normal() {
            return valid() ? new P(a, b) : inward.copy();
        }

        double signedDistance(P p) {
            return P.dot(normal(), p) - c;
        }

        double distance(P p) {
            return Math.abs(signedDistance(p));
        }

        P project(P p) {
            return P.sub(p, P.mul(normal(), signedDistance(p)));
        }

        P centroid() {
            double x = 0;
            double y = 0;
            for (P p : points) {
                x += p.x;
                y += p.y;
            }
            int n = points.size();
            return new P(x / n, y / n);
        }

        boolean evaluate() {
            return evaluate(points);
        }

        boolean evaluate(double maxSignedDist, boolean updatePoints) {
            boolean ok = evaluate(points);
            if (maxSignedDist > 0) {
                List<P> kept = new ArrayList<>(points);
                while (true) {
                    int before = kept.size();
                    kept.removeIf(p -> {
                        double sd = signedDistance(p);
                        return sd > maxSignedDist || sd < -2 * maxSignedDist;
                    });
                    if (kept.size() < before / 2 || kept.size() < 2) {
                        return false;
                    }
                    if (before == kept.size()) {
                        break;
                    }
                    ok = evaluate(kept);
                }
                if (updatePoints) {
                    points.clear();
                    points.addAll(kept);
                }
            }
            return ok;
        }

        private boolean evaluate(List<P> source) {
            if (source.size() < 2) {
                return false;
            }
            double mx = 0;
            double my = 0;
            for (P p : source) {
                mx += p.x;
                my += p.y;
            }
            mx /= source.size();
            my /= source.size();
            double sumXX = 0;
            double sumYY = 0;
            double sumXY = 0;
            for (P p : source) {
                double dx = p.x - mx;
                double dy = p.y - my;
                sumXX += dx * dx;
                sumYY += dy * dy;
                sumXY += dx * dy;
            }
            double na;
            double nb;
            if (sumYY >= sumXX) {
                double l = Math.sqrt(sumYY * sumYY + sumXY * sumXY);
                if (l == 0.0) {
                    a = Double.NaN;
                    return false;
                }
                na = sumYY / l;
                nb = -sumXY / l;
            } else {
                double l = Math.sqrt(sumXX * sumXX + sumXY * sumXY);
                if (l == 0.0) {
                    a = Double.NaN;
                    return false;
                }
                na = sumXY / l;
                nb = -sumXX / l;
            }
            if (P.dot(inward, new P(na, nb)) < 0) {
                na = -na;
                nb = -nb;
            }
            a = na;
            b = nb;
            c = P.dot(normal(), new P(mx, my));
            return P.dot(inward, normal()) > 0.5;
        }

        /**
         * Module count along the timing edge. {@code modules()} in {@code DMRegressionLine}.
         * Returns the number of two-module steps; the caller doubles it.
         */
        double modules(P beg, P end) {
            if (points.size() <= 3) {
                return 0;
            }
            evaluate(1.2, true);
            if (points.size() < 2) {
                return 0;
            }
            List<Double> gaps = new ArrayList<>();
            for (int i = 1; i < points.size(); i++) {
                gaps.add(P.distance(project(points.get(i)), project(points.get(i - 1))));
            }
            double unit = P.length(P.bresenham(P.sub(points.get(points.size() - 1), points.get(0))));
            double sumFront = P.distance(beg, project(points.get(0))) - unit;
            double sumBack = 0;
            List<Double> mods = new ArrayList<>();
            for (double dist : gaps) {
                if (dist > 1.9 * unit) {
                    mods.add(sumBack);
                    sumBack = 0;
                }
                sumFront += dist;
                sumBack += dist;
                if (dist > 1.9 * unit) {
                    mods.add(sumFront);
                    sumFront = 0;
                }
            }
            if (mods.isEmpty()) {
                return 0;
            }
            mods.add(sumFront + P.distance(end, project(points.get(points.size() - 1))));
            mods.set(0, 0.0);
            double lineLength = P.distance(beg, end) - unit;
            double min = Double.POSITIVE_INFINITY;
            double max = Double.NEGATIVE_INFINITY;
            for (int i = 1; i < mods.size(); i++) {
                min = Math.min(min, mods.get(i));
                max = Math.max(max, mods.get(i));
            }
            double mean = averagePositive(mods);
            if (max > 2 * min) {
                for (int i = 1; i < mods.size() - 2; i++) {
                    double left = mods.get(i);
                    double skip = mods.get(i + 2);
                    if (left > 0 && left + skip < mean * 1.4) {
                        mods.set(i, left + skip);
                        mods.set(i + 2, 0.0);
                    } else if (left > mean * 1.6) {
                        mods.set(i, 0.0);
                    }
                }
                mean = averagePositive(mods);
            }
            if (!(mean > 0)) {
                return 0;
            }
            return lineLength / mean;
        }

        boolean truncateIfL() {
            int n = points.size();
            if (n < 16) {
                return false;
            }
            Line chord = new Line();
            chord.points.add(points.get(0));
            chord.points.add(points.get(n - 1));
            chord.evaluate();
            if (chord.distance(points.get(n / 2)) < 5) {
                return false;
            }
            int maxIndex = 0;
            double maxD = 0;
            for (int i = 0; i < n; i++) {
                double d = chord.distance(points.get(i));
                if (d > maxD) {
                    maxD = d;
                    maxIndex = i;
                }
            }
            P corner = points.get(maxIndex);
            double lenL = P.distance(points.get(0), corner) - 1;
            double lenB = P.distance(corner, points.get(n - 1)) - 1;
            if (maxD < Math.min(lenL, lenB) / 2) {
                return false;
            }
            setInward(P.sub(points.get(n - 1), corner));
            if (maxIndex <= 1) {
                return false;
            }
            points.subList(maxIndex - 1, points.size()).clear();
            return points.size() >= 2;
        }

        private static double averagePositive(List<Double> values) {
            double sum = 0;
            int num = 0;
            for (double v : values) {
                if (v > 0) {
                    sum += v;
                    num++;
                }
            }
            return num == 0 ? 0 : sum / num;
        }
    }

    static P intersect(Line l1, Line l2) {
        double d = l1.a * l2.b - l1.b * l2.a;
        double x = (l1.c * l2.b - l1.b * l2.c) / d;
        double y = (l1.a * l2.c - l1.c * l2.a) / d;
        return new P(x, y);
    }

    /** Perspective map from zxing-cpp {@code PerspectiveTransform}. Points are TL, TR, BR, BL. */
    static final class Warp {
        private double a11, a12, a13, a21, a22, a23, a31, a32, a33 = Double.NaN;

        private Warp() {
        }

        private Warp(double a11, double a21, double a31, double a12, double a22, double a32, double a13, double a23, double a33) {
            this.a11 = a11;
            this.a12 = a12;
            this.a13 = a13;
            this.a21 = a21;
            this.a22 = a22;
            this.a23 = a23;
            this.a31 = a31;
            this.a32 = a32;
            this.a33 = a33;
        }

        boolean valid() {
            return !Double.isNaN(a33);
        }

        static Warp between(P[] src, P[] dst) {
            Warp warp = new Warp();
            if (!convex(src) || !convex(dst)) {
                return warp;
            }
            Warp toDst = unitTo(dst);
            Warp toSrc = unitTo(src);
            return toDst.times(toSrc.inverse());
        }

        P apply(P p) {
            double denom = a13 * p.x + a23 * p.y + a33;
            return new P((a11 * p.x + a21 * p.y + a31) / denom, (a12 * p.x + a22 * p.y + a32) / denom);
        }

        P apply(double x, double y) {
            return apply(new P(x, y));
        }

        private Warp inverse() {
            return new Warp(
                    a22 * a33 - a23 * a32,
                    a23 * a31 - a21 * a33,
                    a21 * a32 - a22 * a31,
                    a13 * a32 - a12 * a33,
                    a11 * a33 - a13 * a31,
                    a12 * a31 - a11 * a32,
                    a12 * a23 - a13 * a22,
                    a13 * a21 - a11 * a23,
                    a11 * a22 - a12 * a21
            );
        }

        private Warp times(Warp o) {
            return new Warp(
                    a11 * o.a11 + a21 * o.a12 + a31 * o.a13,
                    a11 * o.a21 + a21 * o.a22 + a31 * o.a23,
                    a11 * o.a31 + a21 * o.a32 + a31 * o.a33,
                    a12 * o.a11 + a22 * o.a12 + a32 * o.a13,
                    a12 * o.a21 + a22 * o.a22 + a32 * o.a23,
                    a12 * o.a31 + a22 * o.a32 + a32 * o.a33,
                    a13 * o.a11 + a23 * o.a12 + a33 * o.a13,
                    a13 * o.a21 + a23 * o.a22 + a33 * o.a23,
                    a13 * o.a31 + a23 * o.a32 + a33 * o.a33
            );
        }

        private static Warp unitTo(P[] q) {
            P q0 = q[0];
            P q1 = q[1];
            P q2 = q[2];
            P q3 = q[3];
            P d3 = P.sub(P.add(q0, q2), P.add(q1, q3));
            if (d3.x == 0 && d3.y == 0) {
                return new Warp(
                        q1.x - q0.x, q2.x - q1.x, q0.x,
                        q1.y - q0.y, q2.y - q1.y, q0.y,
                        0, 0, 1
                );
            }
            P d1 = P.sub(q1, q2);
            P d2 = P.sub(q3, q2);
            double denom = P.cross(d1, d2);
            double pa13 = P.cross(d3, d2) / denom;
            double pa23 = P.cross(d1, d3) / denom;
            return new Warp(
                    q1.x - q0.x + pa13 * q1.x, q3.x - q0.x + pa23 * q3.x, q0.x,
                    q1.y - q0.y + pa13 * q1.y, q3.y - q0.y + pa23 * q3.y, q0.y,
                    pa13, pa23, 1
            );
        }

        private static boolean convex(P[] poly) {
            boolean sign = false;
            double low = Double.POSITIVE_INFINITY;
            double high = 0;
            int n = poly.length;
            for (int i = 0; i < n; i++) {
                P d1 = P.sub(poly[(i + 2) % n], poly[(i + 1) % n]);
                P d2 = P.sub(poly[i], poly[(i + 1) % n]);
                double cp = P.cross(d1, d2);
                double abs = Math.abs(cp);
                low = Math.min(low, abs);
                high = Math.max(high, abs);
                if (i == 0) {
                    sign = cp > 0;
                } else if (sign != (cp > 0)) {
                    return false;
                }
            }
            return high / low < 4.0;
        }
    }
}
