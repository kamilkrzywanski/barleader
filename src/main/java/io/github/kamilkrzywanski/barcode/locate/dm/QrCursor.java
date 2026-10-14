package io.github.kamilkrzywanski.barcode.locate.dm;

import io.github.kamilkrzywanski.barcode.locate.dm.DmGeometry.Bits;

/**
 * Run-length view and matrix cursors used by the zxing-cpp 3.1.1 QR finder
 * ({@code Pattern.h}, {@code BitMatrixCursor.h}).
 *
 * <p>Integer division matches C++ toward-zero division. A float sample truncates
 * the same way {@code PointI(PointF)} does. Outside the image is neither black
 * nor white, so it counts as an edge without reading past the buffer.
 */
final class QrCursor {
    static final int LEFT = -1;
    static final int RIGHT = 1;
    private static final int[] FINDER = {1, 1, 3, 1, 1};

    private QrCursor() {
    }

    /** Edge-to-edge module size of a 1:1:3:1:1 view. Zero means "not this pattern". */
    static double moduleSizeE2E(int[] view, int spaceInPixel, double minQuietZone) {
        double bar = 0;
        double space = 0;
        for (int i = 0; i < 5; i++) {
            if ((i & 1) == 0) {
                bar += view[i];
            } else {
                space += view[i];
            }
        }
        double modBar = bar / 5.0;
        double modSpace = space / 2.0;
        if (Math.max(modBar, modSpace) > 4 * Math.min(modBar, modSpace)) {
            return 0;
        }
        if (minQuietZone != 0 && spaceInPixel < minQuietZone * modSpace) {
            return 0;
        }
        double thrBar = modBar * 0.75 + 0.5;
        double thrSpace = modSpace * 0.6 + 0.5;
        for (int x = 0; x < 5; x++) {
            double mod = (x & 1) == 0 ? modBar : modSpace;
            double thr = (x & 1) == 0 ? thrBar : thrSpace;
            if (Math.abs(view[x] - FINDER[x] * mod) > thr) {
                return 0;
            }
        }
        return (modBar + modSpace) / 2.0;
    }

    /**
     * Run lengths of one row. The row starts and ends with a white run,
     * zero when the pixels themselves start or end on black.
     */
    static int[] patternRow(Bits image, int y) {
        int width = image.width;
        if (width <= 0) {
            return new int[0];
        }
        int[] runs = new int[width + 2];
        int count = 0;
        boolean black = image.get(0, y);
        if (black) {
            runs[count++] = 0;
        }
        int length = 1;
        for (int x = 1; x < width; x++) {
            boolean next = image.get(x, y);
            if (next != black) {
                runs[count++] = length;
                length = 1;
                black = next;
            } else {
                length++;
            }
        }
        runs[count++] = length;
        if (black) {
            runs[count++] = 0;
        }
        int[] trimmed = new int[count];
        System.arraycopy(runs, 0, trimmed, 0, count);
        return trimmed;
    }

    static View findPattern(View view) {
        if (view.row == null || view.size < 5) {
            return View.none();
        }
        View window = view.sub(0, 5);
        if (window.isAtFirstBar() && guard(window, Integer.MAX_VALUE) != 0) {
            return window;
        }
        int limit = view.data + view.size - 5;
        while (window.data < limit) {
            if (guard(window, window.at(-1)) != 0) {
                return window;
            }
            window.shift(2);
        }
        return View.none();
    }

    private static double guard(View window, int spaceInPixel) {
        int center = window.at(2);
        if (center < 3
                || center < 2 * Math.max(window.at(0), window.at(4))
                || center < Math.max(window.at(1), window.at(3))) {
            return 0;
        }
        int[] widths = new int[5];
        for (int i = 0; i < 5; i++) {
            widths[i] = window.at(i);
        }
        return moduleSizeE2E(widths, spaceInPixel, 0.1);
    }

    /**
     * Five runs centered on the current pixel. Both directions share one range.
     * The center pixel is counted twice and then corrected.
     */
    static int[] readSymmetric(FloatCursor cursor, int range) {
        if (range <= 0) {
            return null;
        }
        FloatCursor back = cursor.turnedBack();
        int[] runs = new int[5];
        for (int i = 0; i <= 2; i++) {
            int forward = cursor.stepToEdge(1, range, false);
            runs[2 + i] += forward;
            if (range != 0) {
                range -= forward;
            }
            if (forward == 0) {
                return null;
            }
            int backward = back.stepToEdge(1, range, false);
            runs[2 - i] += backward;
            if (range != 0) {
                range -= backward;
            }
            if (backward == 0) {
                return null;
            }
        }
        runs[2]--;
        return runs;
    }

    /**
     * Axis or diagonal symmetry check. The template flag in zxing-cpp is passed
     * through to {@code IsPattern} as the edge-to-edge switch, which is what QR uses.
     */
    static int checkSymmetric(IntCursor cursor, int range, boolean updatePosition) {
        if (!cursor.image.isIn(cursor.x, cursor.y)) {
            return 0;
        }
        FastEdge forward = new FastEdge(cursor);
        FastEdge backward = new FastEdge(cursor.image, cursor.x, cursor.y, -cursor.dx, -cursor.dy);
        int centerForward = forward.stepToNextEdge(range);
        if (centerForward == 0) {
            return 0;
        }
        int centerBackward = backward.stepToNextEdge(range);
        if (centerBackward == 0) {
            return 0;
        }
        int[] runs = new int[5];
        runs[2] = centerForward + centerBackward - 1;
        range -= runs[2];
        for (int i = 1; i <= 2; i++) {
            int next = forward.stepToNextEdge(range);
            if (next == 0) {
                return 0;
            }
            runs[2 + i] = next;
            range -= next;
            next = backward.stepToNextEdge(range);
            if (next == 0) {
                return 0;
            }
            runs[2 - i] = next;
            range -= next;
        }
        if (moduleSizeE2E(runs, 0, 0) == 0) {
            return 0;
        }
        if (updatePosition) {
            cursor.step(runs[2] / 2 - (centerBackward - 1));
        }
        int sum = 0;
        for (int run : runs) {
            sum += run;
        }
        return sum;
    }

    /** A window into a pattern row. Index 0 of the row is the leading white run. */
    static final class View {
        int[] row;
        int data;
        int size;
        int end;

        static View of(int[] row) {
            View view = new View();
            view.row = row;
            view.end = row.length;
            view.data = 1;
            view.size = Math.max(0, row.length - 1);
            return view;
        }

        static View none() {
            return new View();
        }

        boolean isValid() {
            return row != null && data >= 0 && data + size <= end;
        }

        boolean isAtFirstBar() {
            return data == 1;
        }

        int at(int index) {
            return row[data + index];
        }

        int sum() {
            int total = 0;
            for (int i = 0; i < size; i++) {
                total += row[data + i];
            }
            return total;
        }

        int pixelsInFront() {
            int total = 0;
            for (int i = 0; i < data; i++) {
                total += row[i];
            }
            return total;
        }

        View sub(int offset, int length) {
            View view = new View();
            view.row = row;
            view.end = end;
            view.data = data + offset;
            view.size = length;
            return view;
        }

        void shift(int count) {
            if (row != null) {
                data += count;
            }
        }

        void extend() {
            size = Math.max(0, end - data);
        }
    }

    static final class IntCursor {
        final Bits image;
        int x;
        int y;
        int dx;
        int dy;

        IntCursor(Bits image, int x, int y, int dx, int dy) {
            this.image = image;
            this.x = x;
            this.y = y;
            this.dx = dx;
            this.dy = dy;
        }

        boolean isIn() {
            return image.isIn(x, y);
        }

        void step(int count) {
            x += count * dx;
            y += count * dy;
        }

        boolean step() {
            step(1);
            return isIn();
        }

        void turn(int dir) {
            int rx = -dy;
            int ry = dx;
            dx = dir * rx;
            dy = dir * ry;
        }

        void turnRight() {
            turn(RIGHT);
        }

        int sideX(int dir) {
            return dir * (-dy);
        }

        int sideY(int dir) {
            return dir * dx;
        }

        /**
         * Land one step beyond the n-th color change.
         * {@code range == 0} means no limit. Backup steps back onto the old color
         * and the returned length includes that step. Position moves even when the
         * search fails.
         */
        int stepToEdge(int nth, int range, boolean backup) {
            int steps = 0;
            int current = value(x, y);
            while (nth != 0 && (range == 0 || steps < range) && current >= 0) {
                steps++;
                int next = value(x + steps * dx, y + steps * dy);
                if (current != next) {
                    current = next;
                    nth--;
                }
            }
            if (backup) {
                steps--;
            }
            x += steps * dx;
            y += steps * dy;
            return nth == 0 ? steps : 0;
        }

        boolean stepAlongEdge(int dir, boolean skipCorner) {
            if (!edgeAt(sideX(dir), sideY(dir))) {
                turn(dir);
            } else if (edgeAt(dx, dy)) {
                turn(-dir);
                if (edgeAt(dx, dy)) {
                    turn(-dir);
                    if (edgeAt(dx, dy)) {
                        return false;
                    }
                }
            }
            boolean inside = step();
            if (inside && skipCorner && !edgeAt(sideX(dir), sideY(dir))) {
                turn(dir);
                inside = step();
            }
            return inside;
        }

        boolean edgeAtBack() {
            return edgeAt(-dx, -dy);
        }

        boolean edgeAtLeft() {
            return edgeAt(dy, -dx);
        }

        boolean edgeAtRight() {
            return edgeAt(-dy, dx);
        }

        private boolean edgeAt(int ox, int oy) {
            int current = value(x, y);
            if (current < 0) {
                return false;
            }
            return value(x + ox, y + oy) != current;
        }

        private int value(int px, int py) {
            if (px < 0 || py < 0 || px >= image.width || py >= image.height) {
                return -1;
            }
            return image.get(px, py) ? 1 : 0;
        }
    }

    static final class FloatCursor {
        final Bits image;
        double x;
        double y;
        double dx;
        double dy;

        FloatCursor(Bits image, double x, double y, double dirX, double dirY) {
            this.image = image;
            this.x = x;
            this.y = y;
            setDirection(dirX, dirY);
        }

        void setDirection(double dirX, double dirY) {
            double scale = Math.max(Math.abs(dirX), Math.abs(dirY));
            if (scale == 0) {
                dx = 0;
                dy = 0;
            } else {
                dx = dirX / scale;
                dy = dirY / scale;
            }
        }

        FloatCursor turnedBack() {
            FloatCursor copy = new FloatCursor(image, x, y, 0, 0);
            copy.dx = -dx;
            copy.dy = -dy;
            return copy;
        }

        void turnBack() {
            dx = -dx;
            dy = -dy;
        }

        double directionLength() {
            return Math.sqrt(dx * dx + dy * dy);
        }

        /**
         * Land one step beyond the n-th color change.
         * {@code range == 0} walks until the image border. A bresenham step has unit
         * max-norm, so that border is at most {@code width + height} steps away.
         * A zero direction never moves (C++ {@code 0/0} is NaN and {@code isIn}
         * rejects it); the same bound stops that walk. Outside and non-finite
         * samples count as an edge. Backup steps back onto the old color and the
         * returned length includes that step. Position moves even when the search fails.
         */
        int stepToEdge(int nth, int range, boolean backup) {
            int steps = 0;
            int current = value(x, y);
            int limit = range > 0 ? range : image.width + image.height + 1;
            while (nth != 0 && steps < limit && current >= 0) {
                steps++;
                int next = value(x + steps * dx, y + steps * dy);
                if (current != next) {
                    current = next;
                    nth--;
                }
            }
            if (backup) {
                steps--;
            }
            x += steps * dx;
            y += steps * dy;
            return nth == 0 ? steps : 0;
        }

        private int value(double px, double py) {
            if (!Double.isFinite(px) || !Double.isFinite(py)
                    || px < 0 || py < 0 || px >= image.width || py >= image.height) {
                return -1;
            }
            return image.get((int) px, (int) py) ? 1 : 0;
        }
    }

    /**
     * Integer edge walk that stops on the last in-image pixel.
     * Walking off the border returns {@code stepsToBorder + 1} and does not read outside.
     * Running out of {@code range} first returns 0 and leaves the position alone.
     * Aztec's bullseye check walks the same counter.
     */
    static final class FastEdge {
        final Bits image;
        int x;
        int y;
        final int dx;
        final int dy;
        int stepsToBorder;

        FastEdge(IntCursor cursor) {
            this(cursor.image, cursor.x, cursor.y, cursor.dx, cursor.dy);
        }

        FastEdge(Bits image, int x, int y, int dx, int dy) {
            this.image = image;
            this.x = x;
            this.y = y;
            this.dx = dx;
            this.dy = dy;
            int maxX = dx == 0 ? Integer.MAX_VALUE : (dx > 0 ? image.width - 1 - x : x);
            int maxY = dy == 0 ? Integer.MAX_VALUE : (dy > 0 ? image.height - 1 - y : y);
            stepsToBorder = Math.min(maxX, maxY);
        }

        int stepToNextEdge(int range) {
            if (!image.isIn(x, y)) {
                return 0;
            }
            int maxSteps = Math.min(stepsToBorder, range);
            int steps = 0;
            boolean origin = image.get(x, y);
            while (true) {
                if (++steps > maxSteps) {
                    if (maxSteps == stepsToBorder) {
                        break;
                    }
                    return 0;
                }
                if (image.get(x + steps * dx, y + steps * dy) != origin) {
                    break;
                }
            }
            x += steps * dx;
            y += steps * dy;
            stepsToBorder -= steps;
            return steps;
        }
    }
}
