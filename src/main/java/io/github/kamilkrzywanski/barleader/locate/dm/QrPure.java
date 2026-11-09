/*
 * Copyright 2026 Kamil Krzywański
 */
// SPDX-License-Identifier: Apache-2.0
package io.github.kamilkrzywanski.barleader.locate.dm;

import io.github.kamilkrzywanski.barleader.ImagePoint;
import io.github.kamilkrzywanski.barleader.decode.Decoded;
import io.github.kamilkrzywanski.barleader.image.GrayU8;
import io.github.kamilkrzywanski.barleader.locate.dm.DmGeometry.Bits;

import java.util.List;

/**
 * A QR Code that fills its image upright, read without its finder patterns. The finder search
 * wants the 1:1:3:1:1 runs through every finder, and BoofCV's pathological samples break exactly
 * those: corners cut off the finders, a dot in a finder's light ring, a hole in its centre. A
 * symbol drawn upright on a plain background needs none of that: the box around its dark pixels,
 * leaving out rows and columns that are dark from edge to edge, is its outline, and a grid of
 * 21, 25, ... modules across it is sampled for each size and each quarter turn until one decodes.
 * The decoder's format and error correction reject every wrong size.
 */
public final class QrPure {
    /** Rows or columns at least this dark are a band across the image, not the symbol. */
    private static final double BAND = 0.9;
    private static final double MIN_MODULE = 2;
    /** The outline may be this many modules longer one way than the other. */
    private static final double SQUARE = 1.5;

    private QrPure() {
    }

    /** The symbol, or null. Corners are TL, TR, BR, BL of the outline in {@code gray}. */
    public static QrDetector.Found read(GrayU8 gray) {
        if (gray.width < 21 * MIN_MODULE || gray.height < 21 * MIN_MODULE) {
            return null;
        }
        Bits bits = DmBinarizer.blackMatrix(gray);
        int w = bits.width;
        int h = bits.height;
        int[] rowDark = new int[h];
        int[] colDark = new int[w];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (bits.get(x, y)) {
                    rowDark[y]++;
                    colDark[x]++;
                }
            }
        }
        boolean[] bandRow = new boolean[h];
        boolean[] bandCol = new boolean[w];
        for (int y = 0; y < h; y++) {
            bandRow[y] = rowDark[y] >= BAND * w;
        }
        for (int x = 0; x < w; x++) {
            bandCol[x] = colDark[x] >= BAND * h;
        }
        int left = w;
        int right = -1;
        int top = h;
        int bottom = -1;
        for (int y = 0; y < h; y++) {
            if (bandRow[y]) {
                continue;
            }
            for (int x = 0; x < w; x++) {
                if (!bandCol[x] && bits.get(x, y)) {
                    left = Math.min(left, x);
                    right = Math.max(right, x);
                    top = Math.min(top, y);
                    bottom = Math.max(bottom, y);
                }
            }
        }
        if (right < left || bottom < top) {
            return null;
        }
        double width = right - left + 1;
        double height = bottom - top + 1;
        for (int dimension = 21; dimension <= 177; dimension += 4) {
            double module = width / dimension;
            if (module < MIN_MODULE) {
                break;
            }
            if (Math.abs(height - width) > SQUARE * module) {
                continue;
            }
            double moduleY = height / dimension;
            Bits grid = new Bits(dimension, dimension);
            for (int r = 0; r < dimension; r++) {
                for (int c = 0; c < dimension; c++) {
                    if (dark(bits, left + (c + 0.5) * module, top + (r + 0.5) * moduleY, module, moduleY, bandRow, bandCol)) {
                        grid.set(c, r);
                    }
                }
            }
            for (int turn = 0; turn < 4; turn++) {
                Decoded decoded = QrDecoder.decodeContent(grid);
                if (decoded != null && !decoded.text().isBlank()) {
                    List<ImagePoint> corners = List.of(
                            new ImagePoint(left, top), new ImagePoint(right + 1, top),
                            new ImagePoint(right + 1, bottom + 1), new ImagePoint(left, bottom + 1));
                    // The decoded grid is the outline turned by {@code turn} quarters clockwise.
                    return new QrDetector.Found(decoded.text(), decoded.bytes(), rotate(corners, turn), (360 - 90 * turn) % 360);
                }
                grid = turned(grid);
            }
        }
        return null;
    }

    /** The majority of the middle third of a module, so a dot or a nick at its centre does not decide it. */
    private static boolean dark(Bits bits, double cx, double cy, double mx, double my, boolean[] bandRow, boolean[] bandCol) {
        int dark = 0;
        int all = 0;
        for (double fy = -1 / 3.0; fy <= 1 / 3.0 + 1e-9; fy += 1 / 3.0) {
            for (double fx = -1 / 3.0; fx <= 1 / 3.0 + 1e-9; fx += 1 / 3.0) {
                int x = (int) (cx + fx * mx * 0.5);
                int y = (int) (cy + fy * my * 0.5);
                if (x < 0 || y < 0 || x >= bits.width || y >= bits.height) {
                    continue;
                }
                all++;
                if (bits.get(x, y) && !bandRow[y] && !bandCol[x]) {
                    dark++;
                }
            }
        }
        return all > 0 && 2 * dark > all;
    }

    /** {@code grid} turned a quarter clockwise. */
    private static Bits turned(Bits grid) {
        int n = grid.width;
        Bits out = new Bits(n, n);
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                if (grid.get(x, y)) {
                    out.set(n - 1 - y, x);
                }
            }
        }
        return out;
    }

    /** TL, TR, BR, BL of the symbol when the image outline was turned {@code turn} quarters to read it. */
    private static List<ImagePoint> rotate(List<ImagePoint> corners, int turn) {
        int shift = (4 - turn) % 4;
        return List.of(corners.get(shift % 4), corners.get((shift + 1) % 4), corners.get((shift + 2) % 4), corners.get((shift + 3) % 4));
    }
}
