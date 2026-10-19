/*
 * Copyright 2026 Axel Waggershauser
 * Copyright 2026 Kamil Krzywański
 */
// SPDX-License-Identifier: Apache-2.0
package io.github.kamilkrzywanski.barleader.decode;

import io.github.kamilkrzywanski.barleader.ImagePoint;
import io.github.kamilkrzywanski.barleader.ecc.ReedSolomonException;
import io.github.kamilkrzywanski.barleader.image.BlackMatrix;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

/**
 * MicroPDF417 reader ported from zxing-cpp 3.1.1 {@code MicroPDFReader.cpp} and {@code PDF417.cpp}
 * ({@code ReadCodeword}, {@code SkipCodeword}), with the edge-to-edge codeword lookup of
 * {@code PDFCodewordDecoder.cpp}.
 *
 * <p>Rows of the matrix are searched for left row address patterns (RAP) followed by a codeword,
 * both ways round; RAPs one above another form a candidate. From its left edge a cursor walks
 * down the symbol row by row, reading each row's RAPs and codewords; the row number comes from
 * the RAP and each codeword's cluster. The table of ISO/IEC 24728 symbol sizes that best explains
 * where codewords were seen gives the size and the error correction, and the PDF417 decoder does
 * the rest. Rotation by 90° is left to the ladder, which turns the image.
 */
final class MicroPdf417Reader {
    private static final int MS_THR = 2;
    private static final int[][] LRRAPS = {
            {2, 2, 1, 3, 1, 1}, {3, 1, 1, 3, 1, 1}, {3, 1, 2, 2, 1, 1}, {2, 2, 2, 2, 1, 1}, {2, 1, 3, 2, 1, 1},
            {2, 1, 4, 1, 1, 1}, {2, 2, 3, 1, 1, 1}, {3, 1, 3, 1, 1, 1}, {3, 2, 2, 1, 1, 1}, {4, 1, 2, 1, 1, 1},
            {4, 2, 1, 1, 1, 1}, {3, 3, 1, 1, 1, 1}, {2, 4, 1, 1, 1, 1}, {2, 3, 2, 1, 1, 1}, {2, 3, 1, 2, 1, 1},
            {3, 2, 1, 2, 1, 1}, {4, 1, 1, 2, 1, 1}, {4, 1, 1, 1, 2, 1}, {4, 1, 1, 1, 1, 2}, {3, 2, 1, 1, 1, 2},
            {3, 1, 2, 1, 1, 2}, {3, 1, 1, 2, 1, 2}, {3, 1, 1, 2, 2, 1}, {3, 1, 1, 1, 3, 1}, {3, 1, 1, 1, 2, 2},
            {3, 1, 1, 1, 1, 3}, {2, 2, 1, 1, 1, 3}, {2, 2, 1, 1, 2, 2}, {2, 2, 1, 1, 3, 1}, {2, 2, 1, 2, 2, 1},
            {2, 2, 2, 1, 2, 1}, {3, 1, 2, 1, 2, 1}, {3, 2, 1, 1, 2, 1}, {2, 3, 1, 1, 2, 1}, {2, 3, 1, 1, 1, 2},
            {2, 2, 2, 1, 1, 2}, {2, 1, 3, 1, 1, 2}, {2, 1, 2, 2, 1, 2}, {2, 1, 2, 2, 2, 1}, {2, 1, 2, 1, 3, 1},
            {2, 1, 2, 1, 2, 2}, {2, 1, 2, 1, 1, 3}, {2, 1, 1, 2, 1, 3}, {2, 1, 1, 1, 2, 3}, {2, 1, 1, 1, 3, 2},
            {2, 1, 1, 1, 4, 1}, {2, 1, 1, 2, 3, 1}, {2, 1, 1, 2, 2, 2}, {2, 1, 1, 3, 1, 2}, {2, 1, 1, 3, 2, 1},
            {2, 1, 1, 4, 1, 1}, {2, 1, 2, 3, 1, 1},
    };
    private static final int[][] CRAPS = {
            {1, 1, 2, 2, 3, 1}, {1, 2, 1, 2, 3, 1}, {1, 2, 2, 1, 3, 1}, {1, 3, 1, 1, 3, 1}, {1, 3, 1, 2, 2, 1},
            {1, 3, 2, 1, 2, 1}, {1, 4, 1, 1, 2, 1}, {1, 4, 1, 2, 1, 1}, {1, 4, 2, 1, 1, 1}, {1, 3, 3, 1, 1, 1},
            {1, 3, 2, 2, 1, 1}, {1, 3, 1, 3, 1, 1}, {1, 2, 2, 3, 1, 1}, {1, 2, 3, 2, 1, 1}, {1, 2, 4, 1, 1, 1},
            {1, 1, 5, 1, 1, 1}, {1, 1, 4, 2, 1, 1}, {1, 1, 4, 1, 2, 1}, {1, 2, 3, 1, 2, 1}, {1, 2, 3, 1, 1, 2},
            {1, 2, 2, 2, 1, 2}, {1, 2, 2, 2, 2, 1}, {1, 2, 1, 3, 2, 1}, {1, 2, 1, 4, 1, 1}, {1, 1, 2, 4, 1, 1},
            {1, 1, 3, 3, 1, 1}, {1, 1, 3, 2, 2, 1}, {1, 1, 3, 2, 1, 2}, {1, 1, 3, 1, 2, 2}, {1, 2, 2, 1, 2, 2},
            {1, 3, 1, 1, 2, 2}, {1, 3, 1, 1, 1, 3}, {1, 2, 2, 1, 1, 3}, {1, 1, 3, 1, 1, 3}, {1, 1, 2, 2, 1, 3},
            {1, 1, 2, 2, 2, 2}, {1, 1, 2, 3, 1, 2}, {1, 1, 2, 3, 2, 1}, {1, 1, 1, 4, 2, 1}, {1, 1, 1, 3, 3, 1},
            {1, 1, 1, 3, 2, 2}, {1, 1, 1, 2, 3, 2}, {1, 1, 1, 2, 2, 3}, {1, 1, 1, 1, 3, 3}, {1, 1, 1, 1, 2, 4},
            {1, 1, 1, 2, 1, 4}, {1, 1, 2, 1, 1, 4}, {1, 2, 1, 1, 1, 4}, {1, 2, 1, 1, 2, 3}, {1, 2, 1, 1, 3, 2},
            {1, 1, 2, 1, 3, 2}, {1, 1, 2, 1, 4, 1},
    };
    private static final int[] LRRAP_INTS = toInts(LRRAPS);
    private static final int[] CRAP_INTS = toInts(CRAPS);
    private static final int L = 0;
    private static final int C = 1;
    private static final int R = 2;
    /** {nCols, nRows, nECCs, rotFam, startRow, rowB, rowE} of ISO/IEC 24728 tables 1, 10, 11 and 12; the first is a dummy. */
    private static final int[][] SYMBOLS = {
            {0, 0, 0, 0, 0, 0, 0},
            {1, 11, 7, 8, 1, 1, 8}, {1, 14, 7, 0, 8, 8, 18}, {1, 17, 7, 0, 36, 39, 52}, {1, 20, 8, 0, 19, 22, 35},
            {1, 24, 8, 8, 9, 12, 24}, {1, 28, 8, 8, 25, 33, 52},
            {2, 8, 8, 0, 1, 1, 7}, {2, 11, 9, 8, 1, 1, 8}, {2, 14, 9, 0, 8, 9, 18}, {2, 17, 10, 0, 36, 39, 52},
            {2, 20, 11, 0, 19, 22, 35}, {2, 23, 13, 8, 9, 12, 26}, {2, 26, 15, 8, 27, 32, 52},
            {3, 6, 12, 0, 1, 1, 6}, {3, 8, 14, 0, 7, 7, 14}, {3, 10, 16, 0, 15, 15, 24}, {3, 12, 18, 0, 25, 25, 36},
            {3, 15, 21, 0, 37, 37, 51}, {3, 20, 26, 16, 1, 1, 14}, {3, 26, 32, 8, 1, 1, 20}, {3, 32, 38, 8, 21, 27, 52},
            {3, 38, 44, 16, 15, 21, 52}, {3, 44, 50, 24, 1, 1, 44},
            {4, 4, 8, 24, 47, 47, 50}, {4, 6, 12, 0, 1, 1, 6}, {4, 8, 14, 0, 7, 7, 14}, {4, 10, 16, 0, 15, 15, 24},
            {4, 12, 18, 0, 25, 25, 36}, {4, 15, 21, 0, 37, 37, 51}, {4, 20, 26, 16, 1, 1, 14}, {4, 26, 32, 8, 1, 1, 20},
            {4, 32, 38, 8, 21, 27, 52}, {4, 38, 44, 16, 15, 21, 52}, {4, 44, 50, 24, 1, 1, 44},
    };
    /** Packed edge-to-edge patterns of all codewords, sorted, and the codeword of each. */
    private static final long[] PATTERNS = patternTable();

    private MicroPdf417Reader() {
    }

    /** One decoded symbol. Corners are TL, TR, BR, BL in the matrix. */
    record Found(String text, byte[] bytes, List<ImagePoint> corners) {
    }

    static List<Found> read(BlackMatrix image) {
        List<Found> found = new ArrayList<>();
        if (image == null) {
            return found;
        }
        for (boolean reversed : new boolean[]{false, true}) {
            for (List<Lrap> cluster : findCandidates(image, true, reversed)) {
                Found hit = scanCandidate(image, cluster);
                if (hit != null && found.stream().noneMatch(f -> f.text().equals(hit.text()))) {
                    found.add(hit);
                }
            }
        }
        return found;
    }

    // ---- patterns -------------------------------------------------------------------------

    /** {@code ToInt}: bars of ones and spaces of zeros, the first entry a bar. */
    private static int toInt(int[] a) {
        int total = 0;
        for (int v : a) {
            if (v < 0) {
                return -1;
            }
            total += v;
        }
        if (total > 31) {
            return -1;
        }
        int pattern = 0;
        for (int i = 0; i < a.length; i++) {
            pattern = (pattern << a[i]) | ((i & 1) == 0 ? (1 << a[i]) - 1 : 0);
        }
        return pattern;
    }

    /**
     * {@code NormalizedE2EPattern}: sums of neighbouring elements in modules, five of the six
     * elements of a RAP ({@code <6, 10, 5>}) and six of the eight of a codeword ({@code <8, 17>}).
     */
    private static int[] e2e(int[] view, int from, int len, int sum) {
        double total = 0;
        for (int i = 0; i < len; i++) {
            total += view[from + i];
        }
        double module = total / sum;
        int[] out = new int[len == 6 ? 5 : len - 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (int) ((view[from + i] + view[from + i + 1]) / module + 0.5);
        }
        return out;
    }

    private static int[] toInts(int[][] patterns) {
        int[] out = new int[patterns.length];
        for (int i = 0; i < patterns.length; i++) {
            out[i] = toInt(e2e(patterns[i], 0, 6, 10));
        }
        return out;
    }

    /** {@code RAPIndex}: 1 to 52, or 0. */
    private static int rapIndex(int value, int type) {
        if (value == 0 || value == -1) {
            return 0;
        }
        int[] table = type == C ? CRAP_INTS : LRRAP_INTS;
        for (int i = 0; i < table.length; i++) {
            if (table[i] == value) {
                return i + 1;
            }
        }
        return 0;
    }

    private static int rapCluster(int index) {
        return ((index - 1) % 3) * 3;
    }

    /** {@code NormalizedPattern<8, 17>}: module widths that sum to 17, or all zero when they cannot. */
    private static int[] normalized(int[] view) {
        double total = 0;
        for (int v : view) {
            total += v;
        }
        double module = total / 17;
        int err = 17;
        int[] is = new int[8];
        double[] rs = new double[8];
        for (int i = 0; i < 8; i++) {
            double v = view[i] / module;
            is[i] = (int) (v + 0.5);
            rs[i] = v - is[i];
            err -= is[i];
        }
        if (Math.abs(err) > 1) {
            return new int[8];
        }
        if (err != 0) {
            int at = 0;
            for (int i = 1; i < 8; i++) {
                if (err > 0 ? rs[i] > rs[at] : rs[i] < rs[at]) {
                    at = i;
                }
            }
            is[at] += err;
        }
        return is;
    }

    /** {@code GenerateCodewordPatternTable}, sorted: each entry the packed pattern above the codeword. */
    private static long[] patternTable() {
        long[] table = new long[Pdf417Symbols.COUNT];
        for (int i = 0; i < table.length; i++) {
            int symbol = Pdf417Symbols.symbolAt(i) | 0x10000;
            int[] bars = new int[8];
            int value = symbol;
            for (int k = 7; k >= 0; k--) {
                int run = (value & 1) == 1 ? Integer.numberOfTrailingZeros(~value) : Integer.numberOfTrailingZeros(value);
                bars[k] = run;
                value >>>= run;
            }
            long packed = packed(e2e(bars, 0, 8, 17));
            table[i] = (packed << 16) | Pdf417Symbols.codewordAt(i);
        }
        Arrays.sort(table);
        return table;
    }

    /** {@code PackedPattern<3>(np, 2)}, or -1. */
    private static long packed(int[] np) {
        long out = 0;
        for (int v : np) {
            if (v < 2 || v - 2 >= 8) {
                return -1;
            }
            out = (out << 3) | (v - 2);
        }
        return out;
    }

    /** {@code CodewordDecoder::GetCodeword(ne2ep)}: the codeword whose pattern and cluster both match, or -1. */
    private static int codeword(int[] e2e) {
        long bits = packed(e2e);
        if (bits < 0) {
            return -1;
        }
        int at = Arrays.binarySearch(PATTERNS, bits << 16);
        if (at < 0) {
            at = -at - 1;
        }
        if (at < PATTERNS.length && PATTERNS[at] >>> 16 == bits) {
            // The packed pattern determines the cluster, so a match of the bits is one of the cluster too.
            return (int) (PATTERNS[at] & 0xFFFF);
        }
        return -1;
    }

    // ---- the cursor -----------------------------------------------------------------------

    /** {@code BitMatrixModuleCursorF}: a position, a Bresenham direction and a module size. */
    private static final class Cursor {
        private final BlackMatrix image;
        double x;
        double y;
        double dx;
        double dy;
        double ms;

        Cursor(BlackMatrix image, double x, double y, double dx, double dy, double ms) {
            this.image = image;
            this.x = x;
            this.y = y;
            double scale = Math.max(Math.abs(dx), Math.abs(dy));
            this.dx = scale == 0 ? 0 : dx / scale;
            this.dy = scale == 0 ? 0 : dy / scale;
            this.ms = ms;
        }

        Cursor copy() {
            Cursor copy = new Cursor(image, x, y, 0, 0, ms);
            copy.dx = dx;
            copy.dy = dy;
            return copy;
        }

        Cursor movedBy(double ox, double oy) {
            Cursor moved = copy();
            moved.x += ox;
            moved.y += oy;
            return moved;
        }

        /** -1 outside, else 1 for black. */
        int at(double px, double py) {
            if (!(px >= 0 && py >= 0 && px < image.width() && py < image.height())) {
                return -1;
            }
            return image.get((int) px, (int) py) ? 1 : 0;
        }

        boolean isIn() {
            return at(x, y) >= 0;
        }

        boolean isWhite() {
            return at(x, y) == 0;
        }

        /** {@code stepToEdge}: steps taken to land just past the {@code nth} edge, or 0 when out of range or of the image. */
        int stepToEdge(int nth, int range) {
            int steps = 0;
            int last = at(x, y);
            while (nth != 0 && (range == 0 || steps < range) && last >= 0) {
                steps++;
                int v = at(x + steps * dx, y + steps * dy);
                if (last != v) {
                    last = v;
                    nth--;
                }
            }
            x += steps * dx;
            y += steps * dy;
            return nth == 0 ? steps : 0;
        }

        int[] readPattern(int n, int max, int min) {
            int[] res = new int[n];
            for (int i = 0; i < n; i++) {
                res[i] = stepToEdge(1, max);
                if (res[i] == 0) {
                    return res;
                }
                if (max != 0) {
                    max -= res[i];
                }
            }
            if (min != 0 && max != 0) {
                for (int v : res) {
                    min -= v;
                }
                int steps;
                while (min > 0 && max != 0 && (steps = stepToEdge(2, max)) != 0) {
                    max -= steps;
                    min -= steps;
                }
            }
            return res;
        }

        int[] readPatternFromBlack(int n, int maxWhitePrefix, int max, int min) {
            if (maxWhitePrefix != 0 && isWhite() && stepToEdge(1, maxWhitePrefix) == 0) {
                return new int[n];
            }
            return readPattern(n, max, min);
        }
    }

    private static int sum(int[] values) {
        int total = 0;
        for (int v : values) {
            total += v;
        }
        return total;
    }

    /** {@code ReadRAP}: the RAP index 1 to 52 under the cursor, or 0; a right RAP has to end the symbol. */
    private static int readRap(Cursor cur, int type) {
        int[] pattern = cur.readPatternFromBlack(6, (int) (cur.ms * 1), (int) (cur.ms * (10 + MS_THR)), (int) (cur.ms * (10 - MS_THR)));
        if (sum(pattern) == 0) {
            return 0;
        }
        int res = rapIndex(toInt(e2e(pattern, 0, 6, 10)), type);
        if (res != 0) {
            cur.ms = sum(pattern) / 10.0;
            if (type == R) {
                int threshold = (int) (cur.ms * 1.5 + 1);
                if (cur.stepToEdge(1, threshold) == 0) {
                    res = 0;
                } else {
                    Cursor c = cur.copy();
                    if (c.stepToEdge(1, threshold) != 0 && c.isIn()) {
                        res = 0;
                    }
                }
            }
        }
        return res;
    }

    // ---- codewords ------------------------------------------------------------------------

    /** {@code PDF417::Codeword}: a value, its cluster, and the sum of where each sighting began and ended. */
    private static final class Codeword {
        int codeword = -1;
        int cluster = -1;
        int count;
        double leftX;
        double leftY;
        double rightX;
        double rightY;

        boolean valid() {
            return codeword != -1 && cluster % 3 == 0;
        }

        Codeword copy() {
            Codeword c = new Codeword();
            c.codeword = codeword;
            c.cluster = cluster;
            c.count = count;
            c.leftX = leftX;
            c.leftY = leftY;
            c.rightX = rightX;
            c.rightY = rightY;
            return c;
        }
    }

    private static Codeword readCodeword(Cursor cur) {
        double startX = cur.x;
        double startY = cur.y;
        int[] pattern = cur.readPatternFromBlack(8, (int) (cur.ms / 2), (int) (cur.ms * (17 + MS_THR)), (int) (cur.ms * (17 - MS_THR)));
        Codeword cw = new Codeword();
        if (sum(pattern) == 0) {
            return cw;
        }
        int[] np = normalized(pattern);
        cw.cluster = Math.floorMod(np[0] - np[2] + np[4] - np[6] + 9, 9);
        cw.codeword = codeword(e2e(pattern, 0, 8, 17));
        cw.count = 1;
        cw.leftX = startX;
        cw.leftY = startY;
        cw.rightX = cur.x;
        cw.rightY = cur.y;
        return cw;
    }

    /** {@code ReadCodeword(cur, expectedCluster)}: tried half a module to either side when the cluster is wrong. */
    private static Codeword readCodeword(Cursor cur, int expectedCluster) {
        Cursor start = cur.copy();
        Codeword cw = readCodeword(cur);
        if (!cw.valid() || cw.cluster != expectedCluster) {
            double[][] offsets = {{start.dy, -start.dx}, {-start.dy, start.dx}};
            for (double[] offset : offsets) {
                Cursor alt = start.movedBy(cur.ms / 2 * offset[0], cur.ms / 2 * offset[1]);
                Codeword other = readCodeword(alt);
                if (other.valid()) {
                    if (!cw.valid() || other.cluster == expectedCluster) {
                        cw = other;
                        if (other.cluster == expectedCluster) {
                            break;
                        }
                    }
                }
            }
        }
        if (cw.valid()) {
            double mx = Math.abs(cur.dx) > Math.abs(cur.dy) ? cur.dx : 0;
            double my = Math.abs(cur.dx) > Math.abs(cur.dy) ? 0 : cur.dy;
            cur.ms = ((cur.x - start.x) * mx + (cur.y - start.y) * my) / 17.0;
        }
        return cw;
    }

    private static boolean skipCodeword(Cursor cur) {
        int min = (int) (cur.ms * (17 - MS_THR));
        int max = (int) (cur.ms * (17 + MS_THR));
        int steps = cur.stepToEdge(8, max);
        int total = steps;
        while (total < min && steps != 0) {
            steps = cur.stepToEdge(2, max - total);
            total += steps;
        }
        cur.ms = total / 17.0;
        return total >= min && max > 0;
    }

    // ---- candidates -----------------------------------------------------------------------

    /** A left RAP with the codeword after it, seen on one row. */
    private static final class Lrap {
        int x;
        int y;
        int index;
        int width;

        Lrap(int x, int y, int index, int width) {
            this.x = x;
            this.y = y;
            this.index = index;
            this.width = width;
        }
    }

    /** {@code GetPatternRow}: the white in front, then bars and spaces, then the white behind, which may be 0. */
    private static int[] patternRow(BlackMatrix image, int y, boolean reversed) {
        int width = image.width();
        int[] runs = new int[width + 2];
        int count = 0;
        boolean first = image.get(reversed ? width - 1 : 0, y);
        if (first) {
            runs[count++] = 0;
        }
        int run = 1;
        boolean previous = first;
        for (int i = 1; i < width; i++) {
            boolean v = image.get(reversed ? width - 1 - i : i, y);
            if (v == previous) {
                run++;
            } else {
                runs[count++] = run;
                run = 1;
                previous = v;
            }
        }
        runs[count++] = run;
        if (previous) {
            runs[count++] = 0;
        }
        return Arrays.copyOf(runs, count);
    }

    /** {@code IsLRAP} on the 14 elements from {@code at}: the RAP and the codeword after it. */
    private static int isLrap(int[] row, int at) {
        int l = 0;
        for (int i = 0; i < 6; i++) {
            l += row[at + i];
        }
        int r = 0;
        for (int i = 6; i < 14; i++) {
            r += row[at + i];
        }
        boolean firstBar = at == 1;
        // Nominal l:r is 10:17; ratios between 10:13 and 10:22 pass, about 45° of tilt.
        if (l < 10 || r < 17 || l * 20 < r * 10 || l * 14 > r * 10 || (!firstBar && row[at - 1] < l / 10)) {
            return 0;
        }
        int m = row[at];
        int big = m;
        for (int i = 1; i < 6; i++) {
            m = Math.min(m, row[at + i]);
            big = Math.max(big, row[at + i]);
        }
        // The first bar is nominally at least twice the narrowest, the widest at most four times.
        if (row[at] < m * 3 / 2 || big > m * 6 || row[at + 5] > 5 * m) {
            return 0;
        }
        return rapIndex(toInt(e2e(row, at, 6, 10)), L);
    }

    private static List<List<Lrap>> findCandidates(BlackMatrix image, boolean tryHarder, boolean reversed) {
        int height = image.height();
        int width = image.width();
        List<List<Lrap>> res = new LinkedList<>();
        if (height < 4 || width < 27) {
            return res;
        }
        int minClusterSize = 4;
        int margin = tryHarder ? Math.min(8, height / 44) : height / 4;
        int skip = tryHarder ? 8 : Math.max((height - 2 * margin) / 32, 8);
        for (int y = margin; y < height - margin; y += skip) {
            int[] row = patternRow(image, reversed ? height - 1 - y : y, reversed);
            int at = 1;
            while (true) {
                int found = -1;
                int index = 0;
                for (int s = at; s < row.length - 14; s += 2) {
                    int i = isLrap(row, s);
                    if (i != 0) {
                        found = s;
                        index = i;
                        break;
                    }
                }
                if (found < 0) {
                    break;
                }
                int front = 0;
                for (int i = 0; i < found; i++) {
                    front += row[i];
                }
                int span = 0;
                for (int i = found; i < found + 14; i++) {
                    span += row[i];
                }
                Lrap p = new Lrap(front, y, index, span);
                boolean attached = false;
                for (Iterator<List<Lrap>> it = res.iterator(); it.hasNext(); ) {
                    List<Lrap> cluster = it.next();
                    Lrap last = cluster.get(cluster.size() - 1);
                    int dx = p.x - last.x;
                    int dy = p.y - last.y;
                    if (dy <= 3 * skip && Math.abs(dx) <= Math.max(dy, 2)) {
                        cluster.add(p);
                        attached = true;
                        break;
                    } else if (dy > 2 * skip && cluster.size() < minClusterSize) {
                        it.remove();
                    }
                }
                if (!attached && !(p.x == 0 && p.y == 0)) {
                    List<Lrap> cluster = new ArrayList<>();
                    cluster.add(p);
                    res.add(cluster);
                }
                at = found + 2;
            }
        }
        res.removeIf(cluster -> cluster.size() < minClusterSize);
        res.removeIf(MicroPdf417Reader::pruned);
        if (reversed) {
            for (List<Lrap> cluster : res) {
                for (Lrap lrap : cluster) {
                    lrap.x = width - 1 - lrap.x;
                    lrap.y = height - 1 - lrap.y;
                }
            }
        }
        return res;
    }

    /**
     * Groups the RAPs of a cluster into streaks of one index, drops short streaks and ones out of
     * order at the ends, and keeps the middle RAP of each streak; true when too little is left.
     */
    private static boolean pruned(List<Lrap> lraps) {
        List<List<Lrap>> segments = new ArrayList<>();
        List<Integer> indexes = new ArrayList<>();
        for (Lrap p : lraps) {
            if (indexes.isEmpty() || p.index != indexes.get(indexes.size() - 1)) {
                indexes.add(p.index);
                segments.add(new ArrayList<>());
            }
            segments.get(segments.size() - 1).add(p);
        }
        int longest = 0;
        for (List<Lrap> segment : segments) {
            longest = Math.max(longest, segment.size());
        }
        for (int i = segments.size() - 1; i >= 0; i--) {
            if (segments.get(i).size() < longest / 2) {
                segments.remove(i);
                indexes.remove(i);
            }
        }
        for (int i = 1; i < segments.size(); ) {
            if (indexes.get(i).equals(indexes.get(i - 1))) {
                segments.remove(i);
                indexes.remove(i);
            } else {
                i++;
            }
        }
        int diff;
        while (segments.size() > 1 && ((diff = indexes.get(1) - indexes.get(0)) < 0 || diff > 3)) {
            segments.remove(0);
            indexes.remove(0);
        }
        while (segments.size() > 1
                && ((diff = indexes.get(indexes.size() - 2) - indexes.get(indexes.size() - 1)) > 0 || diff < -3)) {
            segments.remove(segments.size() - 1);
            indexes.remove(indexes.size() - 1);
        }
        if (segments.size() < 3 || Math.abs(indexes.get(indexes.size() - 1) - indexes.get(0)) > 2 * segments.size()) {
            return true;
        }
        for (int i = 1; i < indexes.size(); i++) {
            if (indexes.get(i) < indexes.get(i - 1)) {
                return true;
            }
        }
        List<Lrap> centres = new ArrayList<>();
        for (List<Lrap> segment : segments) {
            centres.add(segment.get(segment.size() / 2));
        }
        lraps.clear();
        lraps.addAll(centres);
        return false;
    }

    /** {@code RAPPair}: the row-address step between two RAPs of a row, which tells the rotation family. */
    private static final class RapPair {
        final int first;
        final int second;
        int offset;
        int family;

        RapPair(int first, int second) {
            this.first = first;
            this.second = second;
            int diff = second - first;
            if (diff < -4) {
                diff += 52;
            }
            family = (diff + 4) / 8 * 8;
            offset = diff - family;
        }

        boolean valid() {
            return first != 0 && second != 0 && family <= 24 && Math.abs(offset) <= 3;
        }
    }

    /**
     * {@code DetermineNumCols}: data columns from the RAPs that follow the first codeword, one,
     * two or four codewords on; turns the cursor to the average row slope those RAPs show.
     */
    private static int determineColumns(Cursor start, List<Lrap> lraps) {
        int[] histogram = new int[16];
        int[] offsets = new int[16];
        for (int s = 0; s < 2 && max(histogram) < 3; s++) {
            for (Lrap p : lraps) {
                // A second pass one module to the right when too few RAPs were read.
                Cursor cur = start.copy();
                cur.x = p.x + 0.5 + s * cur.ms * -cur.dy;
                cur.y = p.y + 0.5 + s * cur.ms * cur.dx;
                Columns columns = new Columns(histogram, offsets, readRap(cur, L));
                if (columns.first == 0 || !skipCodeword(cur)) {
                    continue;
                }
                columns.check(cur, R, 1);
                if (columns.check(cur, C, 3)) {
                    columns.first = columns.second;
                    cur = columns.tmp;
                    if (skipCodeword(cur) && skipCodeword(cur) && columns.check(cur, R, 3)) {
                        continue;
                    }
                }
                if (skipCodeword(cur)) {
                    columns.check(cur, R, 2);
                    if (columns.check(cur, C, 4)) {
                        columns.first = columns.second;
                        cur = columns.tmp;
                        if (skipCodeword(cur) && skipCodeword(cur) && columns.check(cur, R, 4)) {
                            continue;
                        }
                    }
                }
            }
        }
        int column = 0;
        for (int i = 1; i < 16; i++) {
            if (histogram[i] > histogram[column]) {
                column = i;
            }
        }
        if (histogram[column] != 0) {
            double offset = (double) offsets[column] / histogram[column];
            // (10 + 17 * 2) * d - 2 * offset * right(d)
            double nx = (10 + 17 * 2) * start.dx - 2 * offset * -start.dy;
            double ny = (10 + 17 * 2) * start.dy - 2 * offset * start.dx;
            double scale = Math.max(Math.abs(nx), Math.abs(ny));
            start.dx = nx / scale;
            start.dy = ny / scale;
        }
        return column / 4 + 1;
    }

    /** The {@code pair} and {@code checkRAP} of {@code DetermineNumCols}: the last RAP pair read, counted per column and family. */
    private static final class Columns {
        final int[] histogram;
        final int[] offsets;
        int first;
        int second;
        Cursor tmp;

        Columns(int[] histogram, int[] offsets, int first) {
            this.histogram = histogram;
            this.offsets = offsets;
            this.first = first;
        }

        /** Reads a RAP of {@code type} on a copy of {@code cur}, kept as {@link #tmp}, and counts the pair for {@code column}. */
        boolean check(Cursor cur, int type, int column) {
            tmp = cur.copy();
            RapPair pair = new RapPair(first, readRap(tmp, type));
            second = pair.second;
            if (pair.valid()) {
                histogram[(column - 1) * 4 + pair.family / 8]++;
                offsets[(column - 1) * 4 + pair.family / 8] += pair.offset;
            }
            return pair.valid();
        }
    }

    private static int max(int[] values) {
        int m = Integer.MIN_VALUE;
        for (int v : values) {
            m = Math.max(m, v);
        }
        return m;
    }

    // ---- regression line --------------------------------------------------------------------

    /** {@code RegressionLine}: a least-squares line whose normal points inward. */
    private static final class Line {
        final List<double[]> points = new ArrayList<>();
        double inX;
        double inY;
        double a = Double.NaN;
        double b = Double.NaN;
        double c = Double.NaN;

        void setDirectionInward(double x, double y) {
            double length = Math.hypot(x, y);
            inX = x / length;
            inY = y / length;
        }

        void add(double x, double y) {
            points.add(new double[]{x, y});
            if (points.size() == 1) {
                double[] n = normal();
                c = n[0] * x + n[1] * y;
            }
        }

        double[] normal() {
            return Double.isNaN(a) ? new double[]{inX, inY} : new double[]{a, b};
        }

        double signedDistance(double[] p) {
            double[] n = normal();
            return n[0] * p[0] + n[1] * p[1] - c;
        }

        private boolean evaluate(List<double[]> pts) {
            double mx = 0;
            double my = 0;
            for (double[] p : pts) {
                mx += p[0];
                my += p[1];
            }
            mx /= pts.size();
            my /= pts.size();
            double xx = 0;
            double yy = 0;
            double xy = 0;
            for (double[] p : pts) {
                double dx = p[0] - mx;
                double dy = p[1] - my;
                xx += dx * dx;
                yy += dy * dy;
                xy += dx * dy;
            }
            if (yy >= xx) {
                double l = Math.sqrt(yy * yy + xy * xy);
                a = yy / l;
                b = -xy / l;
            } else {
                double l = Math.sqrt(xx * xx + xy * xy);
                a = xy / l;
                b = -xx / l;
            }
            if (inX * a + inY * b < 0) {
                a = -a;
                b = -b;
            }
            c = a * mx + b * my;
            return inX * a + inY * b > 0.5;
        }

        boolean evaluate(double maxSignedDist, boolean updatePoints) {
            boolean ret = evaluate(points);
            if (maxSignedDist > 0) {
                List<double[]> kept = new ArrayList<>(points);
                while (true) {
                    int old = kept.size();
                    kept.removeIf(p -> {
                        double sd = signedDistance(p);
                        return sd > maxSignedDist || sd < -2 * maxSignedDist;
                    });
                    if (kept.size() < old / 2 || kept.size() < 2) {
                        return false;
                    }
                    if (old == kept.size()) {
                        break;
                    }
                    ret = evaluate(kept);
                }
                if (updatePoints) {
                    points.clear();
                    points.addAll(kept);
                }
            }
            return ret;
        }
    }

    // ---- the scan ---------------------------------------------------------------------------

    private static Found scanCandidate(BlackMatrix image, List<Lrap> lraps) {
        Lrap first = lraps.get(0);
        Lrap last = lraps.get(lraps.size() - 1);
        int inward = last.y > first.y ? 1 : -1;
        Line lineL = new Line();
        Line lineR = new Line();
        lineL.setDirectionInward(inward, 0);
        lineR.setDirectionInward(inward, 0);
        for (Lrap p : lraps) {
            lineL.add(p.x, p.y);
            lineR.add(p.x + p.width * inward, p.y);
        }
        lineL.evaluate(2, true);
        lineR.evaluate(2, true);
        double[] nl = lineL.normal();
        // right(normal) = (-n.y, n.x), then Bresenham.
        double downX = -nl[1];
        double downY = nl[0];
        double scale = Math.max(Math.abs(downX), Math.abs(downY));
        downX /= scale;
        downY /= scale;
        double[] nr = lineR.normal();
        Cursor start = new Cursor(image, first.x + 0.5, first.y + 0.5, nr[0], nr[1], first.width / (10.0 + 17.0));
        start.x -= start.dx;
        start.y -= start.dy;
        int columns = determineColumns(start, lraps);
        if (columns == 0) {
            return null;
        }
        int failed = 0;
        while (failed < 10 && inside(image, start.x - downX, start.y - downY)) {
            start.x -= downX;
            start.y -= downY;
            Cursor cur = start.copy();
            if (readRap(cur, L) == 0) {
                failed++;
            }
        }
        start.x += failed * downX;
        start.y += failed * downY;

        List<List<Codeword>> cells = new ArrayList<>(columns * 53);
        for (int i = 0; i < columns * 53; i++) {
            cells.add(new ArrayList<>(1));
        }
        int[] families = new int[4];
        double[][] position = new double[4][];
        failed = 1;
        for (; inside(image, start.x + downX, start.y + downY)
                && ((last.x - start.x) * downX + (last.y - start.y) * downY >= 0 || failed < 10 * start.ms);
             start.x += downX, start.y += downY, failed++) {
            Cursor cur = start.copy();
            int li = readRap(cur, L);
            if (li == 0) {
                continue;
            }
            start.ms = cur.ms;
            Codeword[] cw = {new Codeword(), new Codeword(), new Codeword(), new Codeword()};
            int ci = 0;
            int cluster = rapCluster(li);
            cw[0] = readCodeword(cur, cluster);
            if (columns == 2) {
                cw[1] = readCodeword(cur, cluster);
            } else if (columns == 3) {
                ci = readRap(cur, C);
                if (pair(families, li, ci)) {
                    failed = 0;
                    cluster = rapCluster(ci);
                }
                cw[1] = readCodeword(cur, cluster);
                cw[2] = readCodeword(cur, cluster);
            } else if (columns == 4) {
                cw[1] = readCodeword(cur, cluster);
                ci = readRap(cur, C);
                if (pair(families, li, ci)) {
                    failed = 0;
                    cluster = rapCluster(ci);
                }
                cw[2] = readCodeword(cur, cluster);
                cw[3] = readCodeword(cur, cluster);
            }
            int ri = readRap(cur, R);
            if (columns <= 2) {
                if (pair(families, li, ri)) {
                    failed = 0;
                }
            } else {
                if (pair(families, li, ci)) {
                    failed = 0;
                }
                if (pair(families, ci, ri)) {
                    failed = 0;
                }
            }
            if (ci != 0 || ri != 0 || cw[0].valid() || cw[1].valid() || cw[2].valid() || cw[3].valid()) {
                if (position[0] == null) {
                    position[0] = new double[]{(int) start.x, (int) start.y};
                    position[1] = new double[]{(int) cur.x, (int) cur.y};
                } else {
                    position[2] = new double[]{(int) cur.x, (int) cur.y};
                    position[3] = new double[]{(int) start.x, (int) start.y};
                }
            }
            int previous = rapCluster(li);
            for (int x = 0; x < columns; x++) {
                if (!cw[x].valid()) {
                    continue;
                }
                int o = ((cw[x].cluster - previous + 9) / 3) % 3;
                if (o == 2) {
                    o = -1;
                }
                previous = cw[x].cluster;
                li += o;
                if (li < 1 || li > 52) {
                    continue;
                }
                List<Codeword> cell = cells.get(li * columns + x);
                Codeword same = null;
                for (Codeword seen : cell) {
                    if (seen.codeword == cw[x].codeword) {
                        same = seen;
                        break;
                    }
                }
                if (same != null) {
                    same.count++;
                    same.leftX += cw[x].leftX;
                    same.leftY += cw[x].leftY;
                    same.rightX += cw[x].rightX;
                    same.rightY += cw[x].rightY;
                } else {
                    cell.add(cw[x].copy());
                }
            }
        }

        Codeword[] matrix = new Codeword[columns * 53];
        for (int i = 0; i < matrix.length; i++) {
            List<Codeword> cell = cells.get(i);
            if (cell.isEmpty()) {
                matrix[i] = new Codeword();
            } else if (cell.size() == 1) {
                matrix[i] = cell.get(0);
            } else {
                Codeword best = cell.get(0);
                for (Codeword c : cell) {
                    if (best.count < c.count) {
                        best = c;
                    }
                }
                boolean unique = true;
                for (Codeword c : cell) {
                    if (c != best && c.count >= best.count) {
                        unique = false;
                    }
                }
                matrix[i] = unique ? best : new Codeword();
            }
        }
        int[] si = symbolInfo(matrix, columns, families);
        if (si[0] <= 0 || si[1] <= 0) {
            return null;
        }
        int nCols = si[0];
        int nRows = si[1];
        int nEccs = si[2];
        int startRow = si[4];
        int count = nCols * nRows;
        int[] codewords = new int[count + 1];
        List<Integer> erasures = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            codewords[i + 1] = matrix[startRow * columns + i].codeword;
        }
        for (int i = 0; i < codewords.length; i++) {
            if (codewords[i] == -1) {
                erasures.add(i);
                codewords[i] = 0;
            }
        }
        if (erasures.size() > nEccs) {
            return null;
        }
        Decoded decoded;
        try {
            decoded = Pdf417Decoder.decodeCodewordsContent(codewords, nEccs, erasures.stream().mapToInt(Integer::intValue).toArray());
        } catch (ReedSolomonException failedChecksum) {
            return null;
        }
        if (decoded == null || decoded.text().isEmpty()) {
            return null;
        }
        return new Found(decoded.text(), decoded.bytes(), corners(matrix, columns, si, position));
    }

    private static boolean pair(int[] families, int li, int ri) {
        RapPair pair = new RapPair(li, ri);
        if (!pair.valid()) {
            return false;
        }
        families[pair.family / 8]++;
        return true;
    }

    private static boolean inside(BlackMatrix image, double x, double y) {
        return x >= 0 && y >= 0 && x < image.width() && y < image.height();
    }

    /** {@code DetermineSymbolInfo}: the symbol size of the right width that best explains where codewords were seen. */
    private static int[] symbolInfo(Codeword[] matrix, int columns, int[] families) {
        int maxAt = 0;
        int total = 0;
        for (int i = 0; i < 4; i++) {
            total += families[i];
            if (families[i] > families[maxAt]) {
                maxAt = i;
            }
        }
        int family = families[maxAt] != 0 && families[maxAt] > total / 2 ? maxAt * 8 : -1;
        int[] sights = new int[53];
        float sum = 0;
        int filled = 0;
        for (int y = 0; y < 53; y++) {
            for (int x = 0; x < columns; x++) {
                Codeword c = matrix[y * columns + x];
                if (y >= 1) {
                    sights[y] += c.count;
                }
                sum += c.count;
                if (c.count > 0) {
                    filled++;
                }
            }
        }
        float mean = sum / filled;
        int[] best = SYMBOLS[0];
        int minError = Integer.MAX_VALUE;
        for (int[] s : SYMBOLS) {
            if (s[0] != columns || (family != -1 && s[3] != family)) {
                continue;
            }
            int error = (int) (s[0] * s[1] * mean);
            int lastRow = s[4] + s[1] - 1;
            for (int y = 1; y < 53; y++) {
                boolean outside = y < s[4] || y > lastRow;
                error += (outside ? 1 : -1) * sights[y];
            }
            if (error < minError) {
                minError = error;
                best = s;
            }
        }
        return best;
    }

    /** The symbol's corners from a perspective through codeword ends seen near its corners, as {@code ScanCandidate} extrapolates them. */
    private static List<ImagePoint> corners(Codeword[] matrix, int columns, int[] si, double[][] fallback) {
        int nCols = si[0];
        int nRows = si[1];
        int startRow = si[4];
        int lastRow = startRow + nRows - 1;
        int width = 21 + nCols * 17 + (nCols > 2 ? 10 : 0);
        int height = nRows * 2;
        int[] tl = closest(matrix, columns, nCols, nRows, 0, startRow, 1, 1);
        int[] tr = closest(matrix, columns, nCols, nRows, nCols - 1, startRow, -1, 1);
        int[] br = closest(matrix, columns, nCols, nRows, nCols - 1, lastRow, -1, -1);
        int[] bl = closest(matrix, columns, nCols, nRows, 0, lastRow, 1, -1);
        double[][] src = {
                {10 + tl[0] * 17, 1 + tl[1] * 2},
                {width - 11 + tr[0] * 17, 1 + tr[1] * 2},
                {width - 11 + br[0] * 17, height - 1 + br[1] * 2},
                {10 + bl[0] * 17, height - 1 + bl[1] * 2},
        };
        Codeword ctl = matrix[(startRow + tl[1]) * columns + tl[0]];
        Codeword ctr = matrix[(startRow + tr[1]) * columns + nCols - 1 + tr[0]];
        Codeword cbr = matrix[(lastRow + br[1]) * columns + nCols - 1 + br[0]];
        Codeword cbl = matrix[(lastRow + bl[1]) * columns + bl[0]];
        double[][] dst = {
                {ctl.leftX / ctl.count, ctl.leftY / ctl.count},
                {ctr.rightX / ctr.count, ctr.rightY / ctr.count},
                {cbr.rightX / cbr.count, cbr.rightY / cbr.count},
                {cbl.leftX / cbl.count, cbl.leftY / cbl.count},
        };
        double[] h = homography(src, dst);
        List<ImagePoint> out = new ArrayList<>(4);
        if (h != null) {
            for (double[] p : new double[][]{{0, 0}, {width, 0}, {width, height}, {0, height}}) {
                double w = h[6] * p[0] + h[7] * p[1] + 1;
                out.add(new ImagePoint((h[0] * p[0] + h[1] * p[1] + h[2]) / w, (h[3] * p[0] + h[4] * p[1] + h[5]) / w));
            }
            boolean finite = out.stream().allMatch(p -> Double.isFinite(p.x()) && Double.isFinite(p.y()));
            if (finite) {
                return out;
            }
            out.clear();
        }
        for (double[] p : fallback) {
            if (p != null) {
                out.add(new ImagePoint(p[0], p[1]));
            }
        }
        return out;
    }

    private static int[] closest(Codeword[] matrix, int columns, int nCols, int nRows, int cx, int cy, int dx, int dy) {
        for (int x = 0; x <= nCols / 2; x++) {
            for (int y = 0; y < nRows / 2; y++) {
                int px = cx + x * dx;
                int py = cy + y * dy;
                if (px >= 0 && px < columns && py >= 0 && py < 53 && matrix[py * columns + px].count > 1) {
                    return new int[]{x * dx, y * dy};
                }
            }
        }
        return new int[]{0, 0};
    }

    /** The perspective taking {@code src} to {@code dst}, as {h11, h12, h13, h21, h22, h23, h31, h32}, or null. */
    private static double[] homography(double[][] src, double[][] dst) {
        double[][] a = new double[8][9];
        for (int i = 0; i < 4; i++) {
            double x = src[i][0];
            double y = src[i][1];
            double u = dst[i][0];
            double v = dst[i][1];
            if (!Double.isFinite(u) || !Double.isFinite(v)) {
                return null;
            }
            a[2 * i] = new double[]{x, y, 1, 0, 0, 0, -u * x, -u * y, u};
            a[2 * i + 1] = new double[]{0, 0, 0, x, y, 1, -v * x, -v * y, v};
        }
        for (int col = 0; col < 8; col++) {
            int pivot = col;
            for (int row = col + 1; row < 8; row++) {
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
            for (int row = 0; row < 8; row++) {
                if (row != col) {
                    double f = a[row][col] / a[col][col];
                    for (int k = col; k < 9; k++) {
                        a[row][k] -= f * a[col][k];
                    }
                }
            }
        }
        double[] h = new double[8];
        for (int i = 0; i < 8; i++) {
            h[i] = a[i][8] / a[i][i];
        }
        return h;
    }
}
