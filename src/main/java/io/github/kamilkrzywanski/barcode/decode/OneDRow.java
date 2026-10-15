package io.github.kamilkrzywanski.barcode.decode;

import io.github.kamilkrzywanski.barcode.image.BinaryBitmap;

/**
 * Run-length row for a 1D symbol, ported from zxing-cpp 3.1.1 {@code Pattern.h}
 * and the digit match in {@code ODRowReader.h}.
 *
 * <p>The row always starts with the white run in front of the first bar. The
 * straight scan is the reference loop in {@code GetPatternRow}; the SIMD form
 * there counts the same runs. A view starts on a bar.
 */
final class OneDRow {
    private OneDRow() {
    }

    /** {@code true} is black. A black edge is closed with a zero-width white run. */
    static int[] runs(boolean[] black) {
        return black == null ? new int[]{0} : BinaryBitmap.runLengths(black);
    }

    /**
     * First bar whose {@code len} runs match {@code pattern}. The quiet zone is
     * measured in modules. The window at the first bar is offered an open zone,
     * matching {@code FindLeftGuard}.
     */
    static View findLeftGuard(View view, int minSize, int[] pattern, int sum, double minQuietZone) {
        int len = pattern.length;
        if (view.size() < minSize) {
            return null;
        }
        View window = view.sub(0, len);
        if (window.isAtFirstBar() && isPattern(window, pattern, sum, Integer.MAX_VALUE, minQuietZone) != 0) {
            return window;
        }
        int limit = view.data() + view.size() - minSize;
        while (window.data() < limit) {
            if (isPattern(window, pattern, sum, window.before(), minQuietZone) != 0) {
                return window;
            }
            window.shift(2);
        }
        return null;
    }

    /** Zero means the window is not the pattern. */
    static double isPattern(View view, int[] pattern, int sum, int spaceInPixel, double minQuietZone) {
        int len = pattern.length;
        int width = view.sum(len);
        if (sum > len && width < sum) {
            return 0;
        }
        double moduleSize = (double) width / sum;
        if (minQuietZone != 0 && spaceInPixel < minQuietZone * moduleSize - 1) {
            return 0;
        }
        double threshold = moduleSize * 0.5 + 0.5;
        for (int x = 0; x < len; x++) {
            if (Math.abs(view.at(x) - pattern[x] * moduleSize) > threshold) {
                return 0;
            }
        }
        return moduleSize;
    }

    /**
     * Sparse guard from {@code IsPattern} for a {@code FixedSparsePattern}.
     * {@code narrow} lists the runs that must share one module width, and {@code sum} is how many of them there are.
     * Zero means the window is not the pattern. The dense {@link #isPattern} path is unchanged.
     */
    static double isSparsePattern(View view, int[] narrow, int sum, int spaceInPixel, double minQuietZone) {
        double width = 0;
        for (int x = 0; x < sum; x++) {
            width += view.at(narrow[x]);
        }
        double moduleSize = width / sum;
        if (minQuietZone != 0 && spaceInPixel < minQuietZone * moduleSize - 1) {
            return 0;
        }
        double threshold = moduleSize * 0.5 + 0.5;
        for (int x = 0; x < sum; x++) {
            if (Math.abs(view.at(narrow[x]) - moduleSize) > threshold) {
                return 0;
            }
        }
        return moduleSize;
    }

    /**
     * First bar whose narrow runs match {@code narrow}. {@code window} is the full character length,
     * which is wider than {@code narrow}. The quiet-zone rule matches {@link #findLeftGuard}.
     */
    static View findSparseGuard(View view, int minSize, int window, int[] narrow, int sum, double minQuietZone) {
        if (view.size() < minSize) {
            return null;
        }
        View cursor = view.sub(0, window);
        if (cursor.isAtFirstBar() && isSparsePattern(cursor, narrow, sum, Integer.MAX_VALUE, minQuietZone) != 0) {
            return cursor;
        }
        int limit = view.data() + view.size() - minSize;
        while (cursor.data() < limit) {
            if (isSparsePattern(cursor, narrow, sum, cursor.before(), minQuietZone) != 0) {
                return cursor;
            }
            cursor.shift(2);
        }
        return null;
    }

    /** Edge-to-edge widths. The result length is {@code len - 2}, the default of {@code NormalizedE2EPattern}. */
    static int[] normalizedE2E(View view, int len, int mods) {
        return normalizedE2E(view, len, mods, len - 2, false);
    }

    /**
     * {@code NormalizedE2EPattern}. {@code reverse} reads the pairs from the far end,
     * which DataBar uses for a mirrored finder.
     */
    static int[] normalizedE2E(View view, int len, int mods, int count, boolean reverse) {
        int total = view.sum(len);
        if (total <= 0) {
            return null;
        }
        double moduleSize = (double) total / mods;
        int[] e2e = new int[count];
        for (int i = 0; i < count; i++) {
            int index = reverse ? len - 2 - i : i;
            double value = (view.at(index) + view.at(index + 1)) / moduleSize;
            e2e[i] = (int) (value + 0.5);
        }
        return e2e;
    }

    /**
     * Packs alternating ones and zeros. Even indexes are runs of ones.
     * A negative width or a pattern wider than 32 bits returns -1.
     */
    static int pack(int[] widths) {
        int used = 0;
        long pattern = 0;
        for (int i = 0; i < widths.length; i++) {
            int width = widths[i];
            if (width < 0) {
                return -1;
            }
            used += width;
            if (used > 32) {
                return -1;
            }
            long mask = (i & 1) == 0 && width > 0 ? (1L << width) - 1 : 0;
            pattern = (pattern << width) | mask;
        }
        return (int) pattern;
    }

    /**
     * Closest pattern under the average variance. A tie is not a match.
     * Returns -1 when nothing is close enough.
     */
    static int decodeDigit(View view, int[][] patterns, float maxAverage, float maxIndividual) {
        return decodeDigit(view, patterns, maxAverage, maxIndividual, true);
    }

    /**
     * Same match as {@code DecodeDigit}. When {@code unambiguous} is false, the first
     * pattern that reaches the best variance is kept, which is what UPC/EAN asks for.
     */
    static int decodeDigit(View view, int[][] patterns, float maxAverage, float maxIndividual, boolean unambiguous) {
        float best = maxAverage;
        int bestMatch = -1;
        for (int i = 0; i < patterns.length; i++) {
            float variance = variance(view, patterns[i], maxIndividual);
            if (variance < best) {
                best = variance;
                bestMatch = i;
            } else if (unambiguous && variance == best) {
                bestMatch = -1;
            }
        }
        return bestMatch;
    }

    private static float variance(View view, int[] pattern, float maxIndividual) {
        int total = 0;
        int patternLength = 0;
        for (int i = 0; i < pattern.length; i++) {
            total += view.at(i);
            patternLength += pattern[i];
        }
        if (total < patternLength) {
            return Float.MAX_VALUE;
        }
        float unit = (float) total / patternLength;
        float limit = maxIndividual * unit;
        float totalVariance = 0;
        for (int i = 0; i < pattern.length; i++) {
            float delta = Math.abs(view.at(i) - pattern[i] * unit);
            if (delta > limit) {
                return Float.MAX_VALUE;
            }
            totalVariance += delta;
        }
        return totalVariance / total;
    }

    /** A window into a run-length row. {@code data} is the first bar of the window. */
    static final class View {
        private final int[] runs;
        private final int end;
        private int data;
        private int size;

        private View(int[] runs, int data, int size, int end) {
            this.runs = runs;
            this.data = data;
            this.size = size;
            this.end = end;
        }

        static View of(int[] runs) {
            if (runs.length < 2) {
                return new View(runs, 0, 0, runs.length);
            }
            return new View(runs, 1, runs.length - 1, runs.length);
        }

        int data() {
            return data;
        }

        int size() {
            return size;
        }

        int at(int index) {
            return runs[data + index];
        }

        int before() {
            return runs[data - 1];
        }

        /** Pixels from the start of the row up to this bar. {@code PatternView::pixelsInFront}. */
        int pixelsInFront() {
            int total = 0;
            for (int i = 0; i < data; i++) {
                total += runs[i];
            }
            return total;
        }

        /** Sum of runs through the end of this window, minus one. {@code PatternView::pixelsTillEnd}. */
        int pixelsTillEnd() {
            int total = 0;
            int stop = data + size;
            for (int i = 0; i < stop; i++) {
                total += runs[i];
            }
            return total - 1;
        }

        /** The run immediately after this window, or 0 when the window sits on the edge. */
        int after() {
            int index = data + size;
            if (index < 0 || index >= end) {
                return 0;
            }
            return runs[index];
        }

        int sum(int n) {
            int count = n == 0 ? size : n;
            int total = 0;
            for (int i = 0; i < count; i++) {
                total += at(i);
            }
            return total;
        }

        boolean isAtFirstBar() {
            return data == 1;
        }

        boolean isAtLastBar() {
            return data + size == end - 1;
        }

        boolean isValid() {
            return data >= 0 && data + size <= end;
        }

        boolean hasQuietZoneAfter(float scale) {
            if (isAtLastBar()) {
                return true;
            }
            if (data + size >= end) {
                return false;
            }
            return runs[data + size] >= sum(0) * scale;
        }

        View sub(int offset, int newSize) {
            int next = newSize == 0 ? size - offset : newSize;
            return new View(runs, data + offset, Math.max(next, 0), end);
        }

        boolean shift(int n) {
            data += n;
            return data + size <= end;
        }

        /** Grow this window to the end of the row. {@code PatternView::extend}. */
        void extend() {
            size = Math.max(0, end - data);
        }

        boolean skipSymbol() {
            return shift(size);
        }

        /** Step over one run and accept it only when its width is at most {@code maxWidth}. */
        boolean skipSingle(int maxWidth) {
            return shift(1) && before() <= maxWidth;
        }
    }
}
