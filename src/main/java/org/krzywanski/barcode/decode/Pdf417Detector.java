package org.krzywanski.barcode.decode;

import org.krzywanski.barcode.decode.OneDRow.View;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * PDF417 detector ported from zxing-cpp 3.1.1 {@code PDFDetector.cpp}.
 *
 * <p>The search is the compiled path: a stop pattern is looked for only after a start pattern.
 * Rotation follows {@code Detector::Detect}: upright, then 180 degrees, and 90 degrees when
 * {@code tryRotate} is set. The points are in the returned bitmap, which may be that rotation
 * of the input. The scan ladder reaches this detector through {@link Pdf417ScanningDecoder}
 * and leaves the 90 degree turn on the ladder.
 */
final class Pdf417Detector {
    private static final int[] START_INDEXES = {0, 4, 1, 5};
    private static final int[] STOP_INDEXES = {6, 2, 7, 3};
    private static final int[] START_PATTERN = {8, 1, 1, 1, 1, 1, 1, 3};
    private static final int[] STOP_PATTERN = {7, 1, 1, 3, 1, 1, 1, 2, 1};
    private static final float MAX_AVG_VARIANCE = 0.42f;
    private static final float MAX_INDIVIDUAL_VARIANCE = 0.8f;
    private static final int MAX_PIXEL_DRIFT = 3;
    private static final int MAX_PATTERN_DRIFT = 5;
    private static final int SKIPPED_ROW_COUNT_MAX = 25;
    private static final int ROW_STEP = 8;
    private static final int BARCODE_MIN_HEIGHT = 10;
    private static final int START_SUM = 17;
    private static final int MIN_SYMBOL_WIDTH = 3 * 8 + 1;

    private Pdf417Detector() {
    }

    /**
     * One detected symbol. Missing corners are null. Indexes match {@code FindVertices}:
     * 0 and 1 are the start-pattern corners, 2 and 3 the stop-pattern corners, and 4..7
     * the codeword area inside those guards.
     */
    static final class Symbol {
        final Point[] points;

        Symbol(Point[] points) {
            this.points = points;
        }
    }

    static final class Point {
        final int x;
        final int y;

        Point(int x, int y) {
            this.x = x;
            this.y = y;
        }
    }

    /** Located symbols, or null when the image has none. {@code rotation} is 0, 90, 180, or 270. */
    static final class Result {
        final boolean[][] bits;
        final int rotation;
        final List<Symbol> symbols;

        Result(boolean[][] bits, int rotation, List<Symbol> symbols) {
            this.bits = bits;
            this.rotation = rotation;
            this.symbols = symbols;
        }
    }

    static Result detect(boolean[][] image, boolean multiple, boolean tryRotate) {
        if (!rectangular(image)) {
            return null;
        }
        boolean[][] bits = image;
        for (int rotate90 = 0; rotate90 <= (tryRotate ? 1 : 0); rotate90++) {
            if (!hasStartPattern(bits, rotate90 == 1)) {
                continue;
            }
            boolean[][] oriented = rotate90 == 0 ? bits : rotate90(bits);
            int rotation = 90 * rotate90;
            List<Symbol> symbols = detectBarcode(oriented, multiple);
            if (symbols.isEmpty()) {
                oriented = rotate180(oriented);
                symbols = detectBarcode(oriented, multiple);
                rotation += 180;
            }
            if (!symbols.isEmpty()) {
                return new Result(oriented, rotation, symbols);
            }
        }
        return null;
    }

    private static boolean rectangular(boolean[][] image) {
        if (image == null || image.length == 0 || image[0] == null || image[0].length == 0) {
            return false;
        }
        int width = image[0].length;
        for (boolean[] row : image) {
            if (row == null || row.length != width) {
                return false;
            }
        }
        return true;
    }

    /** {@code HasStartPattern}. Both directions of the row are tried, so column order does not matter. */
    private static boolean hasStartPattern(boolean[][] image, boolean rotate90) {
        int end = rotate90 ? image[0].length : image.length;
        for (int row = ROW_STEP; row < end; row += ROW_STEP) {
            boolean[] line = rotate90 ? column(image, row) : image[row];
            if (starts(line) || starts(reverse(line))) {
                return true;
            }
        }
        return false;
    }

    private static boolean starts(boolean[] line) {
        return OneDRow.findLeftGuard(View.of(OneDRow.runs(line)), MIN_SYMBOL_WIDTH, START_PATTERN, START_SUM, 2) != null;
    }

    private static boolean[] column(boolean[][] image, int x) {
        boolean[] line = new boolean[image.length];
        for (int y = 0; y < image.length; y++) {
            line[y] = image[y][x];
        }
        return line;
    }

    private static List<Symbol> detectBarcode(boolean[][] image, boolean multiple) {
        int row = 0;
        int column = 0;
        boolean foundInRow = false;
        List<Symbol> symbols = new ArrayList<>();
        while (row < image.length) {
            Point[] vertices = findVertices(image, row, column);
            if (vertices[0] == null && vertices[3] == null) {
                if (!foundInRow) {
                    break;
                }
                foundInRow = false;
                column = 0;
                for (Symbol symbol : symbols) {
                    if (symbol.points[1] != null) {
                        row = Math.max(row, symbol.points[1].y);
                    }
                    if (symbol.points[3] != null) {
                        row = Math.max(row, symbol.points[3].y);
                    }
                }
                row += ROW_STEP;
                continue;
            }
            foundInRow = true;
            symbols.add(new Symbol(vertices));
            if (!multiple) {
                break;
            }
            if (vertices[2] != null) {
                column = vertices[2].x;
                row = vertices[2].y;
            } else {
                column = vertices[4].x;
                row = vertices[4].y;
            }
        }
        return symbols;
    }

    private static Point[] findVertices(boolean[][] image, int startRow, int startColumn) {
        Point[] result = new Point[8];
        Point[] rows = findRows(image, image.length, image[0].length, startRow, startColumn, START_PATTERN);
        copy(result, rows, START_INDEXES);
        if (result[4] != null) {
            copy(result, findRows(image, image.length, image[0].length, result[4].y, result[4].x, STOP_PATTERN),
                    STOP_INDEXES);
        }
        return result;
    }

    private static void copy(Point[] result, Point[] found, int[] indexes) {
        for (int i = 0; i < 4; i++) {
            result[indexes[i]] = found[i];
        }
    }

    private static Point[] findRows(boolean[][] image, int height, int width, int startRow, int startColumn, int[] pattern) {
        Point[] result = new Point[4];
        boolean found = false;
        int minStartRow = startRow;
        int[] counters = new int[pattern.length];
        int[] span = new int[2];
        for (; startRow < height; startRow += ROW_STEP) {
            if (findGuard(image, startColumn, startRow, width, pattern, counters, span)) {
                while (startRow > minStartRow + 1) {
                    if (!findGuard(image, startColumn, --startRow, width, pattern, counters, span)) {
                        startRow++;
                        break;
                    }
                }
                result[0] = new Point(span[0], startRow);
                result[1] = new Point(span[1], startRow);
                found = true;
                break;
            }
        }
        int stopRow = startRow + 1;
        if (found) {
            int skipped = 0;
            int previousStart = result[0].x;
            int previousEnd = result[1].x;
            for (; stopRow < height; stopRow++) {
                boolean rowFound = findGuard(image, previousStart, stopRow, width, pattern, counters, span);
                if (rowFound && Math.abs(previousStart - span[0]) < MAX_PATTERN_DRIFT
                        && Math.abs(previousEnd - span[1]) < MAX_PATTERN_DRIFT) {
                    previousStart = span[0];
                    previousEnd = span[1];
                    skipped = 0;
                } else if (skipped > SKIPPED_ROW_COUNT_MAX) {
                    break;
                } else {
                    skipped++;
                }
            }
            stopRow -= skipped + 1;
            result[2] = new Point(previousStart, stopRow);
            result[3] = new Point(previousEnd, stopRow);
        }
        if (stopRow - startRow < BARCODE_MIN_HEIGHT) {
            Arrays.fill(result, null);
        }
        return result;
    }

    /**
     * {@code FindGuardPattern}. {@code span} receives the start and end columns.
     * The pattern is matched from a black run, which is what every caller asks for.
     */
    private static boolean findGuard(boolean[][] image, int column, int row, int width, int[] pattern, int[] counters,
            int[] span) {
        Arrays.fill(counters, 0);
        int patternLength = pattern.length;
        boolean white = false;
        int patternStart = column;
        int drift = 0;
        while (patternStart > 0 && image[row][patternStart] && drift++ < MAX_PIXEL_DRIFT) {
            patternStart--;
        }
        int counterPosition = 0;
        for (int x = patternStart; x < width; x++) {
            boolean pixel = image[row][x];
            if (pixel != white) {
                counters[counterPosition]++;
            } else {
                if (counterPosition == patternLength - 1) {
                    if (variance(counters, pattern) < MAX_AVG_VARIANCE) {
                        span[0] = patternStart;
                        span[1] = x;
                        return true;
                    }
                    patternStart += counters[0] + counters[1];
                    System.arraycopy(counters, 2, counters, 0, patternLength - 2);
                    counters[patternLength - 2] = 0;
                    counters[patternLength - 1] = 0;
                    counterPosition--;
                } else {
                    counterPosition++;
                }
                counters[counterPosition] = 1;
                white = !white;
            }
        }
        if (counterPosition == patternLength - 1 && variance(counters, pattern) < MAX_AVG_VARIANCE) {
            span[0] = patternStart;
            span[1] = width - 1;
            return true;
        }
        return false;
    }

    /** {@code PatternMatchVariance}. A ratio above the caller's limit is a miss, reported as infinity. */
    private static float variance(int[] counters, int[] pattern) {
        int total = 0;
        int patternLength = 0;
        for (int i = 0; i < counters.length; i++) {
            total += counters[i];
            patternLength += pattern[i];
        }
        if (total < patternLength) {
            return Float.MAX_VALUE;
        }
        float unit = (float) total / patternLength;
        float limit = MAX_INDIVIDUAL_VARIANCE * unit;
        float totalVariance = 0f;
        for (int i = 0; i < counters.length; i++) {
            float scaled = pattern[i] * unit;
            float delta = Math.abs(counters[i] - scaled);
            if (delta > limit) {
                return Float.MAX_VALUE;
            }
            totalVariance += delta;
        }
        return totalVariance / total;
    }

    /** {@code BitMatrix::rotate90}: {@code (x, y)} moves to {@code (y, width - x - 1)}. */
    private static boolean[][] rotate90(boolean[][] image) {
        int height = image.length;
        int width = image[0].length;
        boolean[][] turned = new boolean[width][height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                turned[width - 1 - x][y] = image[y][x];
            }
        }
        return turned;
    }

    /** {@code BitMatrix::rotate180}. */
    private static boolean[][] rotate180(boolean[][] image) {
        int height = image.length;
        int width = image[0].length;
        boolean[][] turned = new boolean[height][width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                turned[height - 1 - y][width - 1 - x] = image[y][x];
            }
        }
        return turned;
    }

    private static boolean[] reverse(boolean[] row) {
        boolean[] out = new boolean[row.length];
        for (int i = 0; i < row.length; i++) {
            out[i] = row[row.length - 1 - i];
        }
        return out;
    }
}
