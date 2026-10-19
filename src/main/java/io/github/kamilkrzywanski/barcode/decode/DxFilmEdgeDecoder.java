/*
 * Copyright 2023 Antoine Mérino
 * Copyright 2023 Axel Waggershauser
 * Copyright 2026 Kamil Krzywański
 */
// SPDX-License-Identifier: Apache-2.0
package io.github.kamilkrzywanski.barcode.decode;

import io.github.kamilkrzywanski.barcode.decode.OneDRow.View;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * DX Film Edge reader ported from zxing-cpp 3.1.1 {@code ODDXFilmEdgeReader.cpp}.
 *
 * <p>{@code BitmapDecoder} calls it on every attempt when {@code Symbology.DX_FILM_EDGE} is asked for. A clock track
 * is remembered and a later data track has to sit beside it. Rows are visited from the middle of
 * the image outward. The walk matches default {@code ReaderOptions}: {@code tryHarder} covers the
 * whole image and {@code tryRotate} keeps rows above the middle. {@code minLineCount} is not applied.
 * The text is the raw DX number, for example {@code 115-10/11A}.
 */
final class DxFilmEdgeDecoder {
    private static final int CLOCK_FN_BITS = 31;
    private static final int CLOCK_NO_BITS = 23;
    private static final int DATA_FN = 23;
    private static final int DATA_NO = 15;
    private static final int[] CLOCK_FN = clock(23);
    private static final int[] CLOCK_NO = clock(15);
    private static final int[] DATA_START = {1, 1, 1, 1, 1};
    private static final int[] DATA_STOP = {1, 1, 1};

    private DxFilmEdgeDecoder() {
    }

    /**
     * {@code true} is black. The first row examined is {@code image.length / 2}, then the rows
     * step outward. Each row is tried upright and reversed.
     * The step is {@code height / 256} and the walk continues until it leaves the image,
     * matching {@code DoDecode} with {@code tryHarder} and one symbol.
     */
    static String decode(boolean[][] image) {
        if (image == null || image.length == 0) {
            return null;
        }
        int height = image.length;
        int middle = height / 2;
        int rowStep = Math.max(1, height / 256);
        State state = new State();
        for (int i = 0; i < height; i++) {
            int steps = (i + 1) / 2;
            int row = middle + rowStep * ((i & 1) == 0 ? steps : -steps);
            if (row < 0 || row >= height) {
                break;
            }
            if (image[row] == null) {
                continue;
            }
            int[] runs = OneDRow.runs(image[row]);
            String text = read(row, runs, state);
            if (text != null) {
                return text;
            }
            text = read(row, reversed(runs), state);
            if (text != null) {
                return text;
            }
        }
        return null;
    }

    /** One visited row. A clock is stored and reported as no text, matching {@code decodePattern}. */
    private static String read(int rowNumber, int[] runs, State state) {
        if (runs == null) {
            return null;
        }
        View guard = findRough(View.of(runs));
        if (guard == null) {
            return null;
        }
        Clock found = clockAt(rowNumber, guard);
        if (found != null) {
            state.add(found);
            return null;
        }
        if (state.clocks.isEmpty()) {
            return null;
        }
        View start = guard.sub(0, DATA_START.length);
        if (!matches(start, DATA_START, DATA_START.length, 0.5)) {
            return null;
        }
        int xStart = start.pixelsInFront();
        Clock clock = state.find(xStart, rowNumber);
        if (clock == null) {
            return null;
        }
        double module = clock.moduleSize();
        if (!(module > 0) || Math.abs(start.sum(0) / module - 5.0) > 1.0) {
            return null;
        }
        // The start pattern is black, white, black, white, black. The next run is a space.
        start.skipSymbol();
        boolean[] bits = new boolean[clock.dataLength()];
        int count = 0;
        while (count < bits.length && start.sub(0, 1).isValid()) {
            long modules = Math.round(start.at(0) / module);
            if (modules < 1 || modules > 20 || count + modules > bits.length) {
                return null;
            }
            // Even run index is a bar. The bits are the run color, repeated for its module count.
            boolean ink = ((start.data() - 1) & 1) == 0;
            for (int i = 0; i < modules; i++) {
                bits[count++] = ink;
            }
            start.shift(1);
        }
        if (count != bits.length) {
            return null;
        }
        View stop = start.sub(0, DATA_STOP.length);
        if (!stop.isValid()) {
            return null;
        }
        int trailing = stop.isAtLastBar() ? Integer.MAX_VALUE : stop.after();
        if (OneDRow.isPattern(stop, DATA_STOP, DATA_STOP.length, trailing, 0.5) == 0) {
            return null;
        }
        if (bits[0] || bits[8] || (clock.frame ? bits[20] || bits[22] : bits[14])) {
            return null;
        }
        int paritySum = 0;
        for (int i = 0; i < bits.length - 2; i++) {
            if (bits[i]) {
                paritySum++;
            }
        }
        if ((paritySum & 1) != (bits[bits.length - 2] ? 1 : 0)) {
            return null;
        }
        int product = value(bits, 1, 7);
        if (product == 0) {
            return null;
        }
        String text = product + "-" + value(bits, 9, 4);
        if (clock.frame) {
            text += "/" + value(bits, 13, 6);
            if (bits[19]) {
                text += "A";
            }
        }
        int xStop = stop.pixelsTillEnd();
        if (!clock.near(xStop, rowNumber, clock.xStop)) {
            return null;
        }
        clock.xStart = xStart;
        clock.xStop = xStop;
        return text;
    }

    /**
     * {@code FindLeftGuard<4>} with the live rough check. The four compared runs start one past
     * the first bar and include the run just outside the four-run window.
     */
    private static View findRough(View view) {
        if (view.size() < 10) {
            return null;
        }
        View window = view.sub(0, 4);
        if (window.isAtFirstBar() && rough(window, Integer.MAX_VALUE)) {
            return window;
        }
        int limit = view.data() + view.size() - 10;
        while (window.data() < limit) {
            if (rough(window, window.before())) {
                return window;
            }
            window.shift(2);
        }
        return null;
    }

    private static boolean rough(View view, int space) {
        if (!view.sub(0, 5).isValid()) {
            return false;
        }
        int a = view.at(1);
        int b = view.at(2);
        int c = view.at(3);
        int d = view.at(4);
        int spread = Math.abs(a - b) + Math.abs(a - c) + Math.abs(a - d);
        return spread < a && space > a / 2;
    }

    /** Clock with a frame number first. A miss still leaves the following plain clock in reach. */
    private static Clock clockAt(int rowNumber, View guard) {
        View framed = guard.sub(0, CLOCK_FN.length);
        if (matches(framed, CLOCK_FN, CLOCK_FN_BITS, 0.5)) {
            return Clock.of(rowNumber, framed, true);
        }
        View plain = guard.sub(0, CLOCK_NO.length);
        if (matches(plain, CLOCK_NO, CLOCK_NO_BITS, 2.0)) {
            return Clock.of(rowNumber, plain, false);
        }
        return null;
    }

    /** Non-end-to-end {@code IsPattern}. Quiet zone is the space in front of the window. */
    private static boolean matches(View window, int[] pattern, int sum, double quiet) {
        if (!window.isValid()) {
            return false;
        }
        int space = window.isAtFirstBar() ? Integer.MAX_VALUE : window.before();
        return OneDRow.isPattern(window, pattern, sum, space, quiet) != 0;
    }

    /** Head of five modules, {@code ones} single modules, tail of three. Sums are 31 and 23. */
    private static int[] clock(int ones) {
        int[] pattern = new int[ones + 2];
        pattern[0] = 5;
        Arrays.fill(pattern, 1, ones + 1, 1);
        pattern[ones + 1] = 3;
        return pattern;
    }

    /** Most significant bit first, matching {@code ToInt}. */
    private static int value(boolean[] bits, int pos, int count) {
        int value = 0;
        for (int i = 0; i < count; i++) {
            value = (value << 1) | (bits[pos + i] ? 1 : 0);
        }
        return value;
    }

    private static int[] reversed(int[] runs) {
        int[] copy = runs.clone();
        for (int i = 0, j = copy.length - 1; i < j; i++, j--) {
            int swap = copy[i];
            copy[i] = copy[j];
            copy[j] = swap;
        }
        return copy;
    }

    /** A clock track. {@code xStart} and {@code xStop} follow {@code pixelsInFront} and {@code pixelsTillEnd}. */
    private static final class Clock {
        private boolean frame;
        private int row;
        private int xStart;
        private int xStop;

        private static Clock of(int row, View window, boolean frame) {
            Clock clock = new Clock();
            clock.frame = frame;
            clock.row = row;
            clock.xStart = window.pixelsInFront();
            clock.xStop = window.pixelsTillEnd();
            return clock;
        }

        private int dataLength() {
            return frame ? DATA_FN : DATA_NO;
        }

        private double moduleSize() {
            return (double) (xStop + 1 - xStart) / (frame ? CLOCK_FN_BITS : CLOCK_NO_BITS);
        }

        /** Distance limits are truncated, matching {@code PointI(moduleSize * PointF{0.5, 4})}. */
        private boolean near(int x, int y, int origin) {
            double module = moduleSize();
            int limitX = (int) (module * 0.5);
            int limitY = (int) (module * 4.0);
            return Math.abs(x - origin) <= limitX && Math.abs(y - row) <= limitY;
        }
    }

    private static final class State {
        private final List<Clock> clocks = new ArrayList<>();

        private Clock find(int x, int y) {
            for (Clock clock : clocks) {
                if (clock.row != y && clock.near(x, y, clock.xStart)) {
                    return clock;
                }
            }
            return null;
        }

        private void add(Clock clock) {
            Clock previous = find(clock.xStart, clock.row);
            if (previous == null) {
                clocks.add(clock);
                return;
            }
            previous.frame = clock.frame;
            previous.row = clock.row;
            previous.xStart = clock.xStart;
            previous.xStop = clock.xStop;
        }
    }
}
