package io.github.kamilkrzywanski.barcode.decode;

import io.github.kamilkrzywanski.barcode.decode.OneDRow.View;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * DataBar Expanded row reader ported from zxing-cpp 3.1.1 {@code ODDataBarExpandedReader.cpp}.
 *
 * <p>The compiled path is the stacked state machine. Pairs accumulate across rows, and each row
 * is also read reversed: a stacked symbol may mirror even rows, and the reference reader does
 * the same before it asks this decoder. The non-stacked shortcut under {@code #if 0} is not
 * ported. The text is the GS1 element string from {@link DataBarExpandedDecoder}. Parentheses,
 * the {@code ]e0} identifier, and symbol position are not added. DataBar Expanded is not a
 * {@code Symbology}, and this reader is not called from the scan ladder.
 */
final class DataBarExpandedReader {
    private static final int FULL = 8 + 5 + 8;
    private static final int HALF = 8 + 5 + 2;
    private static final int FINDER_A = 1;
    private static final int[] SYMBOL_WIDEST = {7, 5, 4, 3, 1};
    private static final int[] EVEN_TOTAL = {4, 20, 52, 104, 204};
    private static final int[] GSUM = {0, 348, 1388, 2948, 3988};
    /** A negative finder is the same pattern read right to left. Each signed finder occurs once. */
    private static final int[][] SEQUENCES = {
            {1, -1},
            {1, -2, 2},
            {1, -3, 2, -4},
            {1, -5, 2, -4, 3},
            {1, -5, 2, -4, 4, -6},
            {1, -5, 2, -4, 5, -6, 6},
            {1, -1, 2, -2, 3, -3, 4, -4},
            {1, -1, 2, -2, 3, -3, 4, -5, 5},
            {1, -1, 2, -2, 3, -3, 4, -5, 6, -6},
            {1, -1, 2, -2, 3, -4, 4, -5, 5, -6, 6}
    };
    /** Half pairs the reference reader accepts. Positive {@code FINDER_A} is not one of them. */
    private static final int[] VALID_HALF = {-1, 2, -4, 3, -6, 6, 5};
    private static final int[][] FINDERS = {
            {9, 12, 5},
            {9, 10, 5},
            {7, 10, 7},
            {5, 10, 9},
            {8, 11, 6},
            {4, 11, 10}
    };
    /** Checksum weights. Row 0 is the check character and stays zero. 24 rows, one per finder/direction/parity. */
    private static final int[][] WEIGHTS = {
            {0, 0, 0, 0, 0, 0, 0, 0},
            {1, 3, 9, 27, 81, 32, 96, 77},
            {20, 60, 180, 118, 143, 7, 21, 63},
            {189, 145, 13, 39, 117, 140, 209, 205},
            {193, 157, 49, 147, 19, 57, 171, 91},
            {62, 186, 136, 197, 169, 85, 44, 132},
            {185, 133, 188, 142, 4, 12, 36, 108},
            {113, 128, 173, 97, 80, 29, 87, 50},
            {150, 28, 84, 41, 123, 158, 52, 156},
            {46, 138, 203, 187, 139, 206, 196, 166},
            {76, 17, 51, 153, 37, 111, 122, 155},
            {43, 129, 176, 106, 107, 110, 119, 146},
            {16, 48, 144, 10, 30, 90, 59, 177},
            {109, 116, 137, 200, 178, 112, 125, 164},
            {70, 210, 208, 202, 184, 130, 179, 115},
            {134, 191, 151, 31, 93, 68, 204, 190},
            {148, 22, 66, 198, 172, 94, 71, 2},
            {6, 18, 54, 162, 64, 192, 154, 40},
            {120, 149, 25, 75, 14, 42, 126, 167},
            {79, 26, 78, 23, 69, 207, 199, 175},
            {103, 98, 83, 38, 114, 131, 182, 124},
            {161, 61, 183, 127, 170, 88, 53, 159},
            {55, 165, 73, 8, 24, 72, 5, 15},
            {45, 135, 194, 160, 58, 174, 100, 89}
    };

    static {
        if (WEIGHTS.length != 24 || SEQUENCES.length != 10 || SYMBOL_WIDEST.length != GSUM.length) {
            throw new IllegalStateException("table size mismatch");
        }
        for (int[] row : WEIGHTS) {
            if (row.length != 8) {
                throw new IllegalStateException("weight row");
            }
        }
    }

    private DataBarExpandedReader() {
    }

    /**
     * Element string of one expanded symbol, linear or stacked.
     * Null when the rows do not hold a checksum-valid symbol.
     */
    static String decode(boolean[][] rows) {
        if (rows == null) {
            return null;
        }
        Map<Integer, List<Pair>> all = new HashMap<>();
        for (boolean[] row : rows) {
            if (row == null) {
                continue;
            }
            String text = scan(OneDRow.runs(row), all);
            if (text != null) {
                return text;
            }
            String reversed = scan(OneDRow.runs(reverse(row)), all);
            if (reversed != null) {
                return reversed;
            }
        }
        return null;
    }

    /** One direction of one row, including the {@code tryHarder} rescan that follows a pair group. */
    private static String scan(int[] runs, Map<Integer, List<Pair>> all) {
        View cursor = View.of(runs);
        int seen = Integer.MIN_VALUE;
        while (cursor.size() > 0) {
            if (cursor.data() <= seen) {
                return null;
            }
            seen = cursor.data();
            Found found = readPairs(cursor);
            cursor = found.view;
            if (found.pairs == null) {
                return null;
            }
            insert(all, found.pairs);
            List<Pair> sequence = findSequence(all);
            if (!sequence.isEmpty()) {
                boolean[] bits = buildBits(sequence);
                if (bits != null) {
                    String text = DataBarExpandedDecoder.decode(bits);
                    if (text != null) {
                        return text;
                    }
                }
            }
            int index = cursor.data() - 1;
            if (index < 0 || !cursor.shift(2 - (index % 2))) {
                return null;
            }
            cursor.extend();
        }
        return null;
    }

    /**
     * First pair on this row, then alternating pairs while they last.
     * A null pair list means the row had none; the view is then unused.
     */
    private static Found readPairs(View row) {
        View next = row.sub(0, HALF);
        Pair pair = null;
        while (next.shift(1)) {
            if (isLeftToRight(next)) {
                Pair candidate = readPair(next, false);
                if (candidate != null && (candidate.finder != FINDER_A || DataBarDecoder.isGuard(next.before(), next.at(11)))) {
                    pair = candidate;
                    break;
                }
            }
            if (next.shift(1) && isRightToLeft(next)) {
                Pair candidate = readPair(next, true);
                if (candidate != null) {
                    pair = candidate;
                    break;
                }
            }
        }
        if (pair == null) {
            return new Found(next, null);
        }
        List<Pair> pairs = new ArrayList<>();
        while (true) {
            pairs.add(pair);
            if (pair.right == null || !next.shift(FULL)) {
                break;
            }
            // A left-to-right pair (positive finder) is followed by a right-to-left one.
            Pair candidate = readPair(next, pair.finder > 0);
            if (candidate == null || !validTail(candidate, next)) {
                break;
            }
            pair = candidate;
        }
        return new Found(next, pairs);
    }

    private static boolean isLeftToRight(View view) {
        return finderShape(view.at(8), view.at(9), view.at(10), view.at(11), view.at(12)) && characterPair(view);
    }

    private static boolean isRightToLeft(View view) {
        return finderShape(view.at(12), view.at(11), view.at(10), view.at(9), view.at(8)) && characterPair(view);
    }

    /** {@code IsFinderPattern}. The center element has to be wider than three times the last one. */
    private static boolean finderShape(int a, int b, int c, int d, int e) {
        return DataBarDecoder.isFinder(a, b, c, d, e) && c > 3 * e;
    }

    private static boolean characterPair(View view) {
        float module = view.sub(8, 5).sum(0) / 15f;
        return DataBarDecoder.isCharacter(view.sub(0, 8), 17, module)
                && (view.size() == HALF || DataBarDecoder.isCharacter(view.sub(13, 8), 17, module));
    }

    /** Finder number 1..6, negated when {@code rightToLeft}. Zero is not a finder. */
    private static int parseFinder(View view, boolean rightToLeft) {
        int[] edges = OneDRow.normalizedE2E(view, 5, 15, 3, rightToLeft);
        if (edges == null) {
            return 0;
        }
        int best = -1;
        int bestError = 3;
        for (int i = 0; i < FINDERS.length; i++) {
            int error = 0;
            for (int j = 0; j < 3; j++) {
                error += Math.abs(FINDERS[i][j] - edges[j]);
            }
            if (error < bestError) {
                bestError = error;
                best = i;
            }
        }
        int index = bestError <= 1 ? 1 + best : 0;
        return rightToLeft ? -index : index;
    }

    private static Pair readPair(View view, boolean rightToLeft) {
        int finder = parseFinder(view.sub(8, 5), rightToLeft);
        if (finder == 0) {
            return null;
        }
        Character left = readCharacter(view.sub(0, 8), finder, false);
        if (left == null || (finder == FINDER_A && !knownSequence(left.value))) {
            return null;
        }
        View rightView = view.sub(13, 8);
        Character right = null;
        float module = view.sub(8, 5).sum(0) / 15f;
        if (rightView.isValid() && DataBarDecoder.isCharacter(rightView, 17, module)) {
            right = readCharacter(rightView, finder, true);
        }
        if (right == null && !halfPair(finder)) {
            return null;
        }
        return new Pair(left, right, finder);
    }

    private static boolean validTail(Pair pair, View view) {
        if (pair.right != null) {
            return true;
        }
        return DataBarDecoder.isGuard(view.at(pair.finder < 0 ? 9 : 11), view.at(13));
    }

    private static Character readCharacter(View view, int finder, boolean reversed) {
        int[] odd = new int[4];
        int[] even = new int[4];
        if (!view.isValid() || !DataBarDecoder.readRaw(view, 17, reversed, odd, even)) {
            return null;
        }
        int weightRow = 4 * (Math.abs(finder) - 1) + (finder < 0 ? 2 : 0) + (reversed ? 1 : 0);
        if (weightRow < 0 || weightRow >= WEIGHTS.length) {
            return null;
        }
        int checksum = weighted(WEIGHTS[weightRow], odd, false) + weighted(WEIGHTS[weightRow], even, true);
        int oddSum = odd[0] + odd[1] + odd[2] + odd[3];
        int group = (13 - oddSum) / 2;
        if (group < 0 || group >= SYMBOL_WIDEST.length) {
            return null;
        }
        int oddWidest = SYMBOL_WIDEST[group];
        int value = DataBarDecoder.getValue(odd, oddWidest, true) * EVEN_TOTAL[group]
                + DataBarDecoder.getValue(even, 9 - oddWidest, false)
                + GSUM[group];
        return new Character(value, checksum);
    }

    /** Every other weight, starting at the odd or even column. */
    private static int weighted(int[] weights, int[] counts, boolean even) {
        int offset = even ? 1 : 0;
        int sum = 0;
        for (int i = 0; i < counts.length; i++) {
            sum += counts[i] * weights[offset + 2 * i];
        }
        return sum;
    }

    private static boolean knownSequence(int value) {
        int index = sequenceIndex(value);
        return index >= 0 && index < SEQUENCES.length;
    }

    /** Length of the finder sequence, minus 2. Derived from the check character. */
    private static int sequenceIndex(int value) {
        return (value / 211 + 4 + 1) / 2 - 2;
    }

    private static boolean insert(Map<Integer, List<Pair>> all, List<Pair> row) {
        for (Pair pair : row) {
            List<Pair> pairs = all.computeIfAbsent(pair.finder, key -> new ArrayList<>());
            int found = indexOf(pairs, pair);
            if (found < 0) {
                pairs.add(pair);
                continue;
            }
            Pair existing = pairs.get(found);
            existing.count++;
            while (found > 0 && pairs.get(found).count > pairs.get(found - 1).count) {
                pairs.set(found, pairs.set(found - 1, existing));
                found--;
            }
        }
        return true;
    }

    private static List<Pair> findSequence(Map<Integer, List<Pair>> all) {
        List<Pair> firsts = all.get(FINDER_A);
        if (firsts == null) {
            return List.of();
        }
        for (Pair first : firsts) {
            int index = sequenceIndex(first.left.value);
            if (index < 0 || index >= SEQUENCES.length || all.size() < index + 2) {
                continue;
            }
            List<Pair> stack = new ArrayList<>();
            stack.add(first);
            if (fill(all, SEQUENCES[index], 1, stack)) {
                return stack;
            }
        }
        return List.of();
    }

    /** Tries the two most common pairs at this finder. A skipped half pair still uses one of the two slots. */
    private static boolean fill(Map<Integer, List<Pair>> all, int[] sequence, int at, List<Pair> stack) {
        if (at == sequence.length) {
            return checksumValid(stack);
        }
        List<Pair> options = all.get(sequence[at]);
        if (options == null) {
            return false;
        }
        int tried = 0;
        for (Pair candidate : options) {
            if (tried == 2) {
                break;
            }
            tried++;
            if (candidate.right == null && at + 1 != sequence.length) {
                continue;
            }
            stack.add(candidate);
            if (fill(all, sequence, at + 1, stack)) {
                return true;
            }
            stack.remove(stack.size() - 1);
        }
        return false;
    }

    private static boolean checksumValid(List<Pair> pairs) {
        int sum = 0;
        for (Pair pair : pairs) {
            sum += pair.left.checksum + (pair.right == null ? 0 : pair.right.checksum);
        }
        Pair last = pairs.get(pairs.size() - 1);
        int checksum = sum % 211 + 211 * (2 * pairs.size() - 4 - (last.right == null ? 1 : 0));
        return pairs.get(0).left.value == checksum;
    }

    /** Twelve-bit characters. The first pair contributes only its right character; that left one is the check. */
    private static boolean[] buildBits(List<Pair> pairs) {
        if (pairs.isEmpty() || pairs.get(0).right == null) {
            return null;
        }
        int characters = 1;
        for (int i = 1; i < pairs.size(); i++) {
            characters++;
            if (pairs.get(i).right != null) {
                characters++;
            }
        }
        boolean[] bits = new boolean[characters * 12];
        int at = append(bits, 0, pairs.get(0).right.value);
        for (int i = 1; i < pairs.size(); i++) {
            at = append(bits, at, pairs.get(i).left.value);
            if (pairs.get(i).right != null) {
                at = append(bits, at, pairs.get(i).right.value);
            }
        }
        return bits;
    }

    private static int append(boolean[] bits, int at, int value) {
        for (int bit = 11; bit >= 0; bit--) {
            bits[at++] = ((value >> bit) & 1) != 0;
        }
        return at;
    }

    private static boolean halfPair(int finder) {
        for (int valid : VALID_HALF) {
            if (valid == finder) {
                return true;
            }
        }
        return false;
    }

    private static int indexOf(List<Pair> pairs, Pair pair) {
        for (int i = 0; i < pairs.size(); i++) {
            if (pairs.get(i).equals(pair)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean[] reverse(boolean[] row) {
        boolean[] out = new boolean[row.length];
        for (int i = 0; i < row.length; i++) {
            out[i] = row[row.length - 1 - i];
        }
        return out;
    }

    private static final class Found {
        final View view;
        final List<Pair> pairs;

        Found(View view, List<Pair> pairs) {
            this.view = view;
            this.pairs = pairs;
        }
    }

    private static final class Character {
        final int value;
        final int checksum;

        Character(int value, int checksum) {
            this.value = value;
            this.checksum = checksum;
        }

        boolean same(Character other) {
            return other != null && value == other.value && checksum == other.checksum;
        }
    }

    /** Equality ignores the sighting count. A missing right character stays null. */
    private static final class Pair {
        final Character left;
        final Character right;
        final int finder;
        int count = 1;

        Pair(Character left, Character right, int finder) {
            this.left = left;
            this.right = right;
            this.finder = finder;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Pair)) {
                return false;
            }
            Pair that = (Pair) other;
            return finder == that.finder && left.same(that.left) && (right == null ? that.right == null : right.same(that.right));
        }

        @Override
        public int hashCode() {
            int rightValue = right == null ? 0 : right.value ^ right.checksum;
            return left.value ^ left.checksum ^ rightValue ^ finder;
        }
    }
}
