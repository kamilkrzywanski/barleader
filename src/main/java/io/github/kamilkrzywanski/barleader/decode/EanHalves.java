/*
 * Copyright 2026 Kamil Krzywański
 */
// SPDX-License-Identifier: Apache-2.0
package io.github.kamilkrzywanski.barleader.decode;

import io.github.kamilkrzywanski.barleader.image.BinaryBitmap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * EAN-13 read in two halves on different rows, as tapirscan assembles partial halves. Glare, a
 * fold or a smudge often spoils every row somewhere, but rarely in the same half: one row still
 * holds the left guard, six digits and the middle guard, another the middle guard, six digits and
 * the right guard. A left and a right half join when their middle guards line up within a module
 * and a half and their modules agree within a fifth, and the 13 digits check.
 *
 * <p>Repeating bars also read as halves: the four-state bars of Royal Mail gave 0212111111111 on
 * 15 rows, a QR code 0000000555555. A joined text therefore needs each half on four rows of the
 * bitmap, no digit five times in either half, and no other joined text on two rows of each.
 */
public final class EanHalves {
    private static final int[] GUARD = {1, 1, 1};
    private static final int[] MIDDLE = {1, 1, 1, 1, 1};
    private static final int[][] L = {
            {3, 2, 1, 1}, {2, 2, 2, 1}, {2, 1, 2, 2}, {1, 4, 1, 1}, {1, 1, 3, 2},
            {1, 2, 3, 1}, {1, 1, 1, 4}, {1, 3, 1, 2}, {1, 2, 1, 3}, {3, 1, 1, 2}};
    private static final int[][] L_AND_G = {
            {3, 2, 1, 1}, {2, 2, 2, 1}, {2, 1, 2, 2}, {1, 4, 1, 1}, {1, 1, 3, 2},
            {1, 2, 3, 1}, {1, 1, 1, 4}, {1, 3, 1, 2}, {1, 2, 1, 3}, {3, 1, 1, 2},
            {1, 1, 2, 3}, {1, 2, 2, 2}, {2, 2, 1, 2}, {1, 1, 4, 1}, {2, 3, 1, 1},
            {1, 3, 2, 1}, {4, 1, 1, 1}, {2, 1, 3, 1}, {3, 1, 2, 1}, {2, 1, 1, 3}};
    private static final int[] FIRST_DIGIT = {0x00, 0x0B, 0x0D, 0x0E, 0x13, 0x19, 0x1C, 0x15, 0x16, 0x1A};
    private static final double QUIET_LEFT = 6;
    private static final double QUIET_RIGHT = 3;
    private static final int MIN_ROWS = 4;
    private static final int RIVAL_ROWS = 2;
    private static final int MAX_SAME_DIGIT = 4;

    /** Seven digits of a left half (the first from its parity) or six of a right one, in row pixels. */
    private record Half(String digits, double mid, double module, double edge, int row) {
    }

    /**
     * A joined EAN-13: its 13 digits, the outer edges of its guards in bitmap pixels on {@code row},
     * and whether the rows were read from right to left.
     */
    public record Found(String text, double left, double right, int row, boolean upsideDown) {
    }

    private EanHalves() {
    }

    /** The joined EAN-13 of the bitmap at edges of {@code 1 / scale} pixel, or null. */
    public static Found read(BinaryBitmap image, int scale) {
        Found best = null;
        int bestRows = 0;
        for (boolean reversed : new boolean[]{false, true}) {
            List<Half> lefts = new ArrayList<>();
            List<Half> rights = new ArrayList<>();
            for (int y = 0; y < image.getHeight(); y++) {
                int[] runs = image.runs(y, reversed, scale);
                if (runs != null) {
                    halves(runs, scale, y, lefts, rights);
                }
            }
            if (lefts.isEmpty() || rights.isEmpty()) {
                continue;
            }
            Map<String, Half[]> joined = new HashMap<>();
            for (Half left : lefts) {
                for (Half right : rights) {
                    if (left.row == right.row) {
                        continue;
                    }
                    double module = (left.module + right.module) / 2;
                    if (Math.abs(left.module - right.module) > 0.2 * module || Math.abs(left.mid - right.mid) > 1.5 * module) {
                        continue;
                    }
                    String text = left.digits + right.digits;
                    if (checks(text)) {
                        joined.putIfAbsent(text, new Half[]{left, right});
                    }
                }
            }
            Map<String, Integer> support = new HashMap<>();
            for (String text : joined.keySet()) {
                support.put(text, Math.min(rows(lefts, text.substring(0, 7)), rows(rights, text.substring(7))));
            }
            for (Map.Entry<String, Integer> entry : support.entrySet()) {
                String text = entry.getKey();
                int rows = entry.getValue();
                if (rows < MIN_ROWS || repetitive(text.substring(1, 7)) || repetitive(text.substring(7))) {
                    continue;
                }
                boolean rival = false;
                for (Map.Entry<String, Integer> other : support.entrySet()) {
                    rival |= !other.getKey().equals(text) && other.getValue() >= RIVAL_ROWS;
                }
                if (rival) {
                    return null;
                }
                if (rows > bestRows) {
                    Half[] pair = joined.get(text);
                    double width = image.getWidth();
                    double left = pair[0].edge;
                    double right = pair[1].edge;
                    if (reversed) {
                        left = width - 1 - pair[1].edge;
                        right = width - 1 - pair[0].edge;
                    }
                    best = new Found(text, left, right, pair[0].row, reversed);
                    bestRows = rows;
                }
            }
        }
        return best;
    }

    private static int rows(List<Half> halves, String digits) {
        Set<Integer> rows = new HashSet<>();
        for (Half half : halves) {
            if (half.digits.equals(digits)) {
                rows.add(half.row);
            }
        }
        return rows.size();
    }

    private static boolean repetitive(String digits) {
        int[] count = new int[10];
        for (int i = 0; i < digits.length(); i++) {
            if (++count[digits.charAt(i) - '0'] > MAX_SAME_DIGIT) {
                return true;
            }
        }
        return false;
    }

    private static boolean checks(String text) {
        int sum = 0;
        for (int i = 0; i < 12; i++) {
            sum += (text.charAt(i) - '0') * (i % 2 == 0 ? 1 : 3);
        }
        return (10 - sum % 10) % 10 == text.charAt(12) - '0';
    }

    /** The halves of one row; runs start white, so bars sit at odd indices. */
    private static void halves(int[] runs, int scale, int row, List<Half> lefts, List<Half> rights) {
        for (int i = 1; i + 3 + 24 + 5 <= runs.length; i += 2) {
            if (!pattern(runs, i, GUARD, QUIET_LEFT, 0)) {
                continue;
            }
            int[] parity = {0};
            String digits = digits(runs, i + 3, L_AND_G, parity);
            int middle = i + 27;
            if (digits == null || !pattern(runs, middle, MIDDLE, 0, 0)) {
                continue;
            }
            int first = -1;
            for (int d = 0; d < 10; d++) {
                if (FIRST_DIGIT[d] == parity[0]) {
                    first = d;
                }
            }
            if (first < 0) {
                continue;
            }
            int start = before(runs, i);
            int mid = before(runs, middle);
            int midWidth = before(runs, middle + 5) - mid;
            lefts.add(new Half(first + digits, (mid + midWidth / 2.0) / scale, (mid + midWidth - start) / 50.0 / scale,
                    (double) start / scale, row));
        }
        for (int j = 2; j + 5 + 24 + 3 <= runs.length; j += 2) {
            if (!pattern(runs, j, MIDDLE, 0, 0)) {
                continue;
            }
            String digits = digits(runs, j + 5, L, null);
            int end = j + 29;
            if (digits == null || !pattern(runs, end, GUARD, 0, QUIET_RIGHT)) {
                continue;
            }
            int mid = before(runs, j);
            int midWidth = before(runs, j + 5) - mid;
            int stop = before(runs, end + 3);
            rights.add(new Half(digits, (mid + midWidth / 2.0) / scale, (stop - mid) / 50.0 / scale,
                    (stop - 1.0) / scale, row));
        }
    }

    private static int before(int[] runs, int index) {
        int total = 0;
        for (int i = 0; i < index; i++) {
            total += runs[i];
        }
        return total;
    }

    private static String digits(int[] runs, int start, int[][] patterns, int[] parity) {
        StringBuilder out = new StringBuilder(6);
        OneDRow.View view = OneDRow.View.of(runs).sub(start - 1, 4);
        for (int i = 0; i < 6; i++) {
            if (!view.isValid()) {
                return null;
            }
            int match = OneDRow.decodeDigit(view, patterns, 0.48f, 0.7f, false);
            if (match < 0) {
                return null;
            }
            out.append((char) ('0' + match % 10));
            if (parity != null) {
                parity[0] = (parity[0] << 1) | (match >= 10 ? 1 : 0);
            }
            view.skipSymbol();
        }
        return out.toString();
    }

    private static boolean pattern(int[] runs, int start, int[] pattern, double quietBefore, double quietAfter) {
        if (start < 1 || start + pattern.length > runs.length) {
            return false;
        }
        OneDRow.View view = OneDRow.View.of(runs).sub(start - 1, pattern.length);
        if (!view.isValid()) {
            return false;
        }
        int space = quietBefore > 0 ? runs[start - 1] : 0;
        if (OneDRow.isPattern(view, pattern, pattern.length, space, quietBefore) == 0) {
            return false;
        }
        if (quietAfter > 0) {
            int after = start + pattern.length < runs.length ? runs[start + pattern.length] : Integer.MAX_VALUE;
            double module = view.sum(pattern.length) / (double) pattern.length;
            return after >= quietAfter * module - 1;
        }
        return true;
    }
}
