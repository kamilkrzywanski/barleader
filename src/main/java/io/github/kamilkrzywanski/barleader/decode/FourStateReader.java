/*
 * Copyright 2026 Kamil Krzywański
 */
// SPDX-License-Identifier: Apache-2.0
package io.github.kamilkrzywanski.barleader.decode;

import io.github.kamilkrzywanski.barleader.ImagePoint;
import io.github.kamilkrzywanski.barleader.image.BlackMatrix;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Postal codes of four bar states, the Royal Mail 4-State Customer Code (RM4SCC) and the Japan
 * Post customer barcode, from a binarized image with their bars about vertical.
 *
 * <p>A row through the tracker, the band every bar crosses, meets all bars of a symbol as dark
 * runs of the same width at the same pitch. From such a row each bar is followed up and down, also
 * when the symbol leans a little, to its top and bottom. The tops fall into two lines, one of the
 * bars that ascend above the tracker and one of those that do not, and the bottoms into two lines
 * the same way; together they give each bar's state: full, ascender, descender or tracker. Then
 * the states are decoded both ways round, and only a symbol whose start, stop and check character
 * are right counts.
 */
public final class FourStateReader {
    /** RM4SCC with one character and its check, the fewest bars of any symbol read here. */
    private static final int MIN_BARS = 14;
    private static final int FULL = 3;
    private static final int ASCENDER = 2;
    private static final int DESCENDER = 1;
    private static final int TRACKER = 0;
    /** RM4SCC characters in the order of their value, row of the top half times six plus its bottom half. */
    private static final String ROYAL = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    /** The six 2-of-4 halves of an RM4SCC character, the first bar the highest bit. */
    private static final int[] HALVES = {0b0011, 0b0101, 0b0110, 0b1001, 0b1010, 0b1100};
    /** Japan Post characters in the order of their bar table; a to h are the control codes CC1 to CC8. */
    private static final String JAPAN = "1234567890-abcdefgh";
    /** Japan Post characters in the order of their value for the check character. */
    private static final String JAPAN_CHECK = "0123456789-abcdefgh";
    /** Bars of each {@link #JAPAN} character: 1 full, 2 ascender, 3 descender, 4 tracker. */
    private static final String[] JAPAN_BARS = {
            "114", "132", "312", "123", "141", "321", "213", "231", "411", "144",
            "414", "324", "342", "234", "432", "243", "423", "441", "111"};
    private static final int JAPAN_LENGTH = 67;

    private FourStateReader() {
    }

    public enum Kind { ROYAL_MAIL, JAPAN_POST }

    /** A symbol: its text, the four corners in matrix pixels, and whether it was read from right to left. */
    public record Found(Kind kind, String text, List<ImagePoint> corners, boolean upsideDown) {
    }

    /**
     * One dark run on a row: from {@code start} inclusive to {@code end} exclusive. A clipped run
     * is the end bar of a symbol cut out of the dark background it touches, on the right for
     * {@code background} 1 and on the left for -1.
     */
    private record Run(int start, int end, int background) {
        Run(int start, int end) {
            this(start, end, 0);
        }

        double centre() {
            return (start + end) / 2.0;
        }

        int width() {
            return end - start;
        }
    }

    /**
     * One bar followed up and down from its run on the tracker row. A clipped bar touches the
     * background, which can only make it look longer than it is.
     */
    private record Bar(double x, int top, int bottom, double width, boolean clipped) {
    }

    public static List<Found> read(BlackMatrix matrix, boolean royalMail, boolean japanPost) {
        List<Found> found = new ArrayList<>();
        if (matrix == null || !royalMail && !japanPost) {
            return found;
        }
        boolean[][] rows = matrix.rows();
        int height = matrix.height();
        int step = Math.max(1, height / 512);
        // Rows a symbol already read covers, so its other rows are not followed again.
        List<int[]> covered = new ArrayList<>();
        for (int y = step / 2; y < height; y += step) {
            List<Run> runs = runs(rows[y]);
            for (List<Run> sequence : sequences(runs)) {
                double left = sequence.get(0).start;
                double right = sequence.get(sequence.size() - 1).end;
                if (inside(covered, left, right, y)) {
                    continue;
                }
                List<Bar> bars = follow(rows, sequence, y);
                Found symbol = decode(bars, royalMail, japanPost);
                if (symbol != null) {
                    found.add(symbol);
                    int top = Integer.MAX_VALUE;
                    int bottom = Integer.MIN_VALUE;
                    for (Bar bar : bars) {
                        top = Math.min(top, bar.top);
                        bottom = Math.max(bottom, bar.bottom);
                    }
                    covered.add(new int[]{(int) left, (int) Math.ceil(right), top, bottom});
                }
            }
        }
        return found;
    }

    private static boolean inside(List<int[]> covered, double left, double right, int y) {
        for (int[] box : covered) {
            if (y >= box[2] && y <= box[3] && right > box[0] && left < box[1]) {
                return true;
            }
        }
        return false;
    }

    private static List<Run> runs(boolean[] row) {
        List<Run> runs = new ArrayList<>();
        int x = 0;
        int width = row.length;
        while (x < width) {
            while (x < width && !row[x]) {
                x++;
            }
            int start = x;
            while (x < width && row[x]) {
                x++;
            }
            if (x > start) {
                runs.add(new Run(start, x));
            }
        }
        return runs;
    }

    /**
     * The longest stretches of runs of about the same width at about the same pitch, each with at
     * least {@link #MIN_BARS} runs: what a row through a tracker crosses.
     */
    private static List<List<Run>> sequences(List<Run> runs) {
        List<List<Run>> sequences = new ArrayList<>();
        int i = 0;
        while (i + MIN_BARS <= runs.size()) {
            List<Run> sequence = new ArrayList<>();
            sequence.add(runs.get(i));
            double pitch = 0;
            double width = runs.get(i).width();
            int j = i + 1;
            for (; j < runs.size(); j++) {
                Run last = sequence.get(sequence.size() - 1);
                Run next = runs.get(j);
                double gap = next.centre() - last.centre();
                if (sequence.size() == 1) {
                    if (gap > 4 * width || next.width() > 2.5 * width || 2.5 * next.width() < width || next.width() >= gap) {
                        break;
                    }
                } else if (gap < 0.75 * pitch || gap > 1.33 * pitch || next.width() > 2.5 * width || 2.5 * next.width() < width
                        || next.width() >= 0.85 * pitch) {
                    break;
                }
                sequence.add(next);
                int n = sequence.size() - 1;
                pitch = sequence.size() == 2 ? gap : (pitch * (n - 1) + gap) / n;
                width = (width * n + next.width()) / (n + 1);
            }
            if (sequence.size() >= 2 && j < runs.size()) {
                Run tail = clip(sequence.get(sequence.size() - 1), runs.get(j), pitch, width, true);
                if (tail != null) {
                    sequence.add(tail);
                }
            }
            if (sequence.size() >= 2 && i > 0) {
                Run head = clip(sequence.get(0), runs.get(i - 1), pitch, width, false);
                if (head != null) {
                    sequence.add(0, head);
                }
            }
            if (sequence.size() >= MIN_BARS) {
                sequences.add(sequence);
                i = j;
            } else {
                i++;
            }
        }
        return sequences;
    }

    /**
     * The end bar of a symbol that touches a dark background, cut out of the wide run it merged
     * into: the part of {@code run} a pitch beyond {@code end}, one bar wide. Null when the run is
     * not clearly wider than a bar or not where the next bar would be.
     */
    private static Run clip(Run end, Run run, double pitch, double width, boolean after) {
        if (run.width() < 1.5 * width) {
            return null;
        }
        int bar = (int) Math.round(width);
        if (after) {
            double expected = end.centre() + pitch - width / 2;
            return Math.abs(run.start - expected) <= 0.3 * pitch ? new Run(run.start, run.start + bar, 1) : null;
        }
        double expected = end.centre() - pitch + width / 2;
        return Math.abs(run.end - expected) <= 0.3 * pitch ? new Run(run.end - bar, run.end, -1) : null;
    }

    /** Every run followed up and down, row by row, as long as a run of about its width overlaps it. */
    private static List<Bar> follow(boolean[][] rows, List<Run> sequence, int y) {
        List<Bar> bars = new ArrayList<>(sequence.size());
        for (Run run : sequence) {
            int top = walk(rows, run, y, -1);
            int bottom = walk(rows, run, y, 1);
            bars.add(new Bar(run.centre(), top, bottom, run.width(), run.background != 0));
        }
        return bars;
    }

    /** The last row, going in {@code direction} from {@code y}, where the bar is still dark; one white row is bridged. */
    private static int walk(boolean[][] rows, Run run, int y, int direction) {
        int start = run.start;
        int end = run.end;
        int limit = 2 * run.width() + 2;
        int last = y;
        int misses = 0;
        for (int at = y + direction; at >= 0 && at < rows.length; at += direction) {
            boolean[] row = rows[at];
            int from = Math.max(0, start - 1);
            int to = Math.min(row.length, end + 1);
            int x = from;
            while (x < to && !row[x]) {
                x++;
            }
            if (x == to) {
                if (++misses > 1) {
                    break;
                }
                continue;
            }
            int left = x;
            while (left > 0 && row[left - 1]) {
                left--;
            }
            int right = x;
            while (right < row.length && row[right]) {
                right++;
            }
            if (run.background != 0) {
                // Merged into the background: the bar keeps its columns, and ends where the dark
                // reaches the gap on its other side.
                if (run.background > 0 ? left < start - 1 : right > end + 1) {
                    break;
                }
                last = at;
                misses = 0;
                continue;
            }
            if (right - left > limit) {
                break;
            }
            start = left;
            end = right;
            last = at;
            misses = 0;
        }
        return last;
    }

    /**
     * The bars' states from their tops and bottoms, then a symbol both ways round, or null. The
     * state of a clipped bar is the most it can be: it is read as any state within it.
     */
    private static Found decode(List<Bar> bars, boolean royalMail, boolean japanPost) {
        int n = bars.size();
        double[] xs = new double[n];
        double[] tops = new double[n];
        double[] bottoms = new double[n];
        boolean[] loose = new boolean[n];
        for (int i = 0; i < n; i++) {
            Bar bar = bars.get(i);
            xs[i] = bar.x;
            tops[i] = -bar.top;
            bottoms[i] = bar.bottom;
            loose[i] = bar.clipped;
        }
        boolean[] up = extended(xs, tops, loose);
        boolean[] down = extended(xs, bottoms, loose);
        if (up == null || down == null) {
            return null;
        }
        int[] states = new int[n];
        for (int i = 0; i < n; i++) {
            states[i] = (up[i] ? ASCENDER : 0) | (down[i] ? DESCENDER : 0);
        }
        for (boolean upsideDown : new boolean[]{false, true}) {
            int[] read = upsideDown ? turned(states) : states;
            boolean[] within = upsideDown ? reversed(loose) : loose;
            String text = null;
            Kind kind = null;
            if (royalMail) {
                text = royalMail(read, within);
                kind = Kind.ROYAL_MAIL;
            }
            if (text == null && japanPost) {
                text = japanPost(read, within);
                kind = Kind.JAPAN_POST;
            }
            if (text != null) {
                return new Found(kind, text, corners(bars), upsideDown);
            }
        }
        return null;
    }

    /** The states read from the other end: the bars in reverse order, ascenders and descenders swapped. */
    private static int[] turned(int[] states) {
        int[] turned = new int[states.length];
        for (int i = 0; i < states.length; i++) {
            int state = states[states.length - 1 - i];
            turned[i] = ((state & ASCENDER) != 0 ? DESCENDER : 0) | ((state & DESCENDER) != 0 ? ASCENDER : 0);
        }
        return turned;
    }

    private static boolean[] reversed(boolean[] values) {
        boolean[] reversed = new boolean[values.length];
        for (int i = 0; i < values.length; i++) {
            reversed[i] = values[values.length - 1 - i];
        }
        return reversed;
    }

    /** Whether bar {@code i} reads as {@code state}: exactly, or for a loose bar as any state within what was measured. */
    private static boolean is(int[] states, boolean[] loose, int i, int state) {
        return loose[i] ? (state & ~states[i]) == 0 : states[i] == state;
    }

    /**
     * Which bars reach out on this side: the ends of the bars, higher values further out, fall on
     * two lines across the symbol, and a bar reaches out when its end is on the outer line. The
     * loose bars take no part in the lines, and reach out when their end is past the middle of
     * them. Null when the ends do not split clearly into two lines.
     */
    static boolean[] extended(double[] xs, double[] ends, boolean[] loose) {
        int n = xs.length;
        boolean[] firm = new boolean[n];
        for (int i = 0; i < n; i++) {
            firm[i] = !loose[i];
        }
        double[] line = fit(xs, ends, firm, true);
        if (line == null) {
            return null;
        }
        double[] residuals = new double[n];
        for (int i = 0; i < n; i++) {
            residuals[i] = ends[i] - (line[0] + line[1] * xs[i]);
        }
        boolean[] outer = split(residuals, firm);
        if (outer == null) {
            return null;
        }
        double[] outerLine = null;
        double[] innerLine = null;
        for (int round = 0; round < 3; round++) {
            boolean[] outerFirm = new boolean[n];
            boolean[] innerFirm = new boolean[n];
            for (int i = 0; i < n; i++) {
                outerFirm[i] = firm[i] && outer[i];
                innerFirm[i] = firm[i] && !outer[i];
            }
            outerLine = fit(xs, ends, outerFirm, true);
            innerLine = fit(xs, ends, innerFirm, true);
            if (outerLine == null || innerLine == null) {
                return null;
            }
            boolean changed = false;
            for (int i = 0; i < n; i++) {
                double toOuter = Math.abs(ends[i] - (outerLine[0] + outerLine[1] * xs[i]));
                double toInner = Math.abs(ends[i] - (innerLine[0] + innerLine[1] * xs[i]));
                boolean isOuter = toOuter < toInner;
                if (isOuter != outer[i]) {
                    outer[i] = isOuter;
                    changed = true;
                }
            }
            if (!changed) {
                break;
            }
        }
        double middle = (xs[0] + xs[n - 1]) / 2;
        double separation = (outerLine[0] + outerLine[1] * middle) - (innerLine[0] + innerLine[1] * middle);
        if (separation < 2) {
            return null;
        }
        for (int i = 0; i < n; i++) {
            double[] own = outer[i] ? outerLine : innerLine;
            if (firm[i] && Math.abs(ends[i] - (own[0] + own[1] * xs[i])) > 0.35 * separation) {
                return null;
            }
        }
        return outer;
    }

    /** Least squares line {@code a + b x} through the points of one group, or all when {@code group} is null. */
    private static double[] fit(double[] xs, double[] ys, boolean[] group, boolean member) {
        double n = 0;
        double sx = 0;
        double sy = 0;
        double sxx = 0;
        double sxy = 0;
        for (int i = 0; i < xs.length; i++) {
            if (group != null && group[i] != member) {
                continue;
            }
            n++;
            sx += xs[i];
            sy += ys[i];
            sxx += xs[i] * xs[i];
            sxy += xs[i] * ys[i];
        }
        if (n < 2) {
            return null;
        }
        double d = n * sxx - sx * sx;
        if (d <= 0) {
            return new double[]{sy / n, 0};
        }
        double b = (n * sxy - sx * sy) / d;
        return new double[]{(sy - b * sx) / n, b};
    }

    /** Otsu's split of the values into a high and a low group, from the values of {@code firm}; null when one group is empty. */
    private static boolean[] split(double[] values, boolean[] firm) {
        int count = 0;
        for (boolean f : firm) {
            count += f ? 1 : 0;
        }
        double[] sorted = new double[count];
        for (int i = 0, k = 0; i < values.length; i++) {
            if (firm[i]) {
                sorted[k++] = values[i];
            }
        }
        Arrays.sort(sorted);
        int n = sorted.length;
        double total = 0;
        for (double v : sorted) {
            total += v;
        }
        double best = -1;
        double threshold = Double.NaN;
        double low = 0;
        for (int k = 1; k < n; k++) {
            low += sorted[k - 1];
            double meanLow = low / k;
            double meanHigh = (total - low) / (n - k);
            double between = (double) k * (n - k) * (meanHigh - meanLow) * (meanHigh - meanLow);
            if (between > best) {
                best = between;
                threshold = (sorted[k - 1] + sorted[k]) / 2;
            }
        }
        if (Double.isNaN(threshold)) {
            return null;
        }
        boolean[] high = new boolean[values.length];
        for (int i = 0; i < values.length; i++) {
            high[i] = values[i] > threshold;
        }
        return high;
    }

    /**
     * RM4SCC: an ascender to start, a full bar to stop, and four bars a character between them,
     * two of which ascend and two descend. The last character is the check of the others' top and
     * bottom halves summed modulo 6; the text keeps it, as it is printed.
     */
    static String royalMail(int[] states, boolean[] loose) {
        int n = states.length;
        if (n < 10 || (n - 2) % 4 != 0 || !is(states, loose, 0, ASCENDER) || !is(states, loose, n - 1, FULL)) {
            return null;
        }
        int count = (n - 2) / 4;
        StringBuilder text = new StringBuilder(count);
        int sumTop = 0;
        int sumBottom = 0;
        for (int c = 0; c < count; c++) {
            int top = 0;
            int bottom = 0;
            for (int k = 0; k < 4; k++) {
                int state = states[1 + 4 * c + k];
                top = top << 1 | ((state & ASCENDER) != 0 ? 1 : 0);
                bottom = bottom << 1 | ((state & DESCENDER) != 0 ? 1 : 0);
            }
            int row = indexOf(HALVES, top);
            int column = indexOf(HALVES, bottom);
            if (row < 0 || column < 0) {
                return null;
            }
            if (c < count - 1) {
                sumTop += row + 1;
                sumBottom += column + 1;
            } else {
                int checkRow = (sumTop - 1) % 6;
                int checkColumn = (sumBottom - 1) % 6;
                if (row != checkRow || column != checkColumn) {
                    return null;
                }
            }
            text.append(ROYAL.charAt(6 * row + column));
        }
        return text.toString();
    }

    /**
     * Japan Post: a full bar and a descender to start, a descender and a full bar to stop, and
     * between them 20 characters and a check, three bars each. A control code and a digit stand for
     * a letter, and CC4 pads the text to 20 characters.
     */
    static String japanPost(int[] states, boolean[] loose) {
        if (states.length != JAPAN_LENGTH || !is(states, loose, 0, FULL) || !is(states, loose, 1, DESCENDER)
                || !is(states, loose, JAPAN_LENGTH - 2, DESCENDER) || !is(states, loose, JAPAN_LENGTH - 1, FULL)) {
            return null;
        }
        char[] chars = new char[21];
        for (int c = 0; c < 21; c++) {
            StringBuilder bars = new StringBuilder(3);
            for (int k = 0; k < 3; k++) {
                bars.append(switch (states[2 + 3 * c + k]) {
                    case FULL -> '1';
                    case ASCENDER -> '2';
                    case DESCENDER -> '3';
                    default -> '4';
                });
            }
            int index = Arrays.asList(JAPAN_BARS).indexOf(bars.toString());
            if (index < 0) {
                return null;
            }
            chars[c] = JAPAN.charAt(index);
        }
        int sum = 0;
        for (int c = 0; c < 20; c++) {
            sum += JAPAN_CHECK.indexOf(chars[c]);
        }
        int check = 19 - sum % 19;
        if (check == 19) {
            check = 0;
        }
        if (JAPAN_CHECK.charAt(check) != chars[20]) {
            return null;
        }
        StringBuilder text = new StringBuilder(20);
        for (int c = 0; c < 20; c++) {
            char ch = chars[c];
            if (ch >= '0' && ch <= '9' || ch == '-') {
                text.append(ch);
            } else if (ch == 'd') {
                // CC4 pads the rest; nothing but padding may follow.
                for (int rest = c; rest < 20; rest++) {
                    if (chars[rest] != 'd') {
                        return null;
                    }
                }
                break;
            } else if ((ch == 'a' || ch == 'b' || ch == 'c') && c + 1 < 20 && chars[c + 1] >= '0' && chars[c + 1] <= '9') {
                int letter = (ch - 'a') * 10 + (chars[c + 1] - '0');
                if (letter >= 26) {
                    return null;
                }
                text.append((char) ('A' + letter));
                c++;
            } else {
                return null;
            }
        }
        return text.isEmpty() ? null : text.toString();
    }

    private static int indexOf(int[] values, int value) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == value) {
                return i;
            }
        }
        return -1;
    }

    private static List<ImagePoint> corners(List<Bar> bars) {
        Bar first = bars.get(0);
        Bar last = bars.get(bars.size() - 1);
        int top = Integer.MAX_VALUE;
        int bottom = Integer.MIN_VALUE;
        for (Bar bar : bars) {
            top = Math.min(top, bar.top);
            bottom = Math.max(bottom, bar.bottom);
        }
        double left = first.x - first.width / 2;
        double right = last.x + last.width / 2;
        return List.of(new ImagePoint(left, top), new ImagePoint(right, top),
                new ImagePoint(right, bottom + 1), new ImagePoint(left, bottom + 1));
    }
}
