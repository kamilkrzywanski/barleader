package io.github.kamilkrzywanski.barcode.image;

import io.github.kamilkrzywanski.barcode.ImagePoint;

/**
 * Plane homography {@code x = H u}. {@code h22} is kept explicit so normalized
 * solves can be rescaled. Used to deskew a quadrilateral barcode into a square.
 */
final class Homography {
    private final double h00;
    private final double h01;
    private final double h02;
    private final double h10;
    private final double h11;
    private final double h12;
    private final double h20;
    private final double h21;
    private final double h22;

    private Homography(
            double h00, double h01, double h02,
            double h10, double h11, double h12,
            double h20, double h21, double h22
    ) {
        this.h00 = h00;
        this.h01 = h01;
        this.h02 = h02;
        this.h10 = h10;
        this.h11 = h11;
        this.h12 = h12;
        this.h20 = h20;
        this.h21 = h21;
        this.h22 = h22;
    }

    /**
     * Homography that maps {@code from} points onto {@code to} points.
     * Both arrays must contain exactly four points in matching order.
     * Returns null when the points are degenerate.
     */
    static Homography mapping(ImagePoint[] from, ImagePoint[] to) {
        if (from == null || to == null || from.length != 4 || to.length != 4) {
            return null;
        }
        double su = 1.0;
        double sv = 1.0;
        double sx = 1.0;
        double sy = 1.0;
        for (int i = 0; i < 4; i++) {
            su = Math.max(su, Math.abs(from[i].x()));
            sv = Math.max(sv, Math.abs(from[i].y()));
            sx = Math.max(sx, Math.abs(to[i].x()));
            sy = Math.max(sy, Math.abs(to[i].y()));
        }
        double[][] rows = new double[8][9];
        for (int i = 0; i < 4; i++) {
            double u = from[i].x() / su;
            double v = from[i].y() / sv;
            double x = to[i].x() / sx;
            double y = to[i].y() / sy;
            int r = i * 2;
            rows[r][0] = u;
            rows[r][1] = v;
            rows[r][2] = 1.0;
            rows[r][6] = -x * u;
            rows[r][7] = -x * v;
            rows[r][8] = x;
            rows[r + 1][3] = u;
            rows[r + 1][4] = v;
            rows[r + 1][5] = 1.0;
            rows[r + 1][6] = -y * u;
            rows[r + 1][7] = -y * v;
            rows[r + 1][8] = y;
        }
        double[] h = solve(rows);
        if (h == null) {
            return null;
        }
        return new Homography(
                sx * h[0] / su,
                sx * h[1] / sv,
                sx * h[2],
                sy * h[3] / su,
                sy * h[4] / sv,
                sy * h[5],
                h[6] / su,
                h[7] / sv,
                1.0
        );
    }

    ImagePoint apply(double u, double v) {
        double den = h20 * u + h21 * v + h22;
        if (Math.abs(den) < 1e-12) {
            return new ImagePoint(Double.NaN, Double.NaN);
        }
        return new ImagePoint(
                (h00 * u + h01 * v + h02) / den,
                (h10 * u + h11 * v + h12) / den
        );
    }

    private static double[] solve(double[][] a) {
        int n = 8;
        for (int col = 0; col < n; col++) {
            int pivot = col;
            for (int row = col + 1; row < n; row++) {
                if (Math.abs(a[row][col]) > Math.abs(a[pivot][col])) {
                    pivot = row;
                }
            }
            double[] swap = a[col];
            a[col] = a[pivot];
            a[pivot] = swap;
            double div = a[col][col];
            if (Math.abs(div) < 1e-12) {
                return null;
            }
            for (int c = col; c <= n; c++) {
                a[col][c] /= div;
            }
            for (int row = 0; row < n; row++) {
                if (row == col) {
                    continue;
                }
                double factor = a[row][col];
                if (factor == 0.0) {
                    continue;
                }
                for (int c = col; c <= n; c++) {
                    a[row][c] -= factor * a[col][c];
                }
            }
        }
        double[] h = new double[n];
        for (int i = 0; i < n; i++) {
            h[i] = a[i][n];
            if (!Double.isFinite(h[i])) {
                return null;
            }
        }
        return h;
    }
}
