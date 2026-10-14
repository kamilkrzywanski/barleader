package io.github.kamilkrzywanski.barcode.locate.dm;

import io.github.kamilkrzywanski.barcode.image.GrayU8;
import com.google.zxing.common.reedsolomon.GenericGF;
import com.google.zxing.common.reedsolomon.ReedSolomonDecoder;
import com.google.zxing.common.reedsolomon.ReedSolomonException;
import io.github.kamilkrzywanski.barcode.ImagePoint;
import io.github.kamilkrzywanski.barcode.decode.Decoded;
import io.github.kamilkrzywanski.barcode.locate.dm.DmGeometry.Bits;
import io.github.kamilkrzywanski.barcode.locate.dm.DmGeometry.P;
import io.github.kamilkrzywanski.barcode.locate.dm.DmGeometry.Warp;
import io.github.kamilkrzywanski.barcode.locate.dm.QrConcentric.Ring;
import io.github.kamilkrzywanski.barcode.locate.dm.QrCursor.FastEdge;
import io.github.kamilkrzywanski.barcode.locate.dm.QrCursor.IntCursor;
import io.github.kamilkrzywanski.barcode.locate.dm.QrCursor.View;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Aztec bullseye detector ported from zxing-cpp 3.1.1 {@code AZDetector.cpp}.
 *
 * <p>The image is binarized with the zxing-cpp local-average matrix binarizer.
 * The center is the compact 7-run bullseye. Orientation and the mode message
 * come from the core, then the symbol is sampled and decoded by {@link AztecDecoder}.
 * {@code DetectPure} is not used. Symbols of dimension 35 or larger are sampled
 * from the reference-grid crosses in {@link LocalGrid}, not from one flat warp.
 * The scan ladder reads this detector from the attempt's own black matrix, so a
 * clean symbol keeps an {@code original-*} attempt. The trace rung binarizes
 * with local average.
 */
public final class AztecDetector {
    /** First dimension that zxing-cpp refines with {@code LocalGrid}. */
    private static final int LOCAL_GRID_DIMENSION = 35;
    private static final int[][] QUAD_DIRS = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};

    private AztecDetector() {
    }

    /** One decoded symbol. Corners are TL, TR, BR, BL in the gray image. */
    public record Found(String text, byte[] bytes, List<ImagePoint> corners, int orientationDegrees) {
    }

    /**
     * Binarize {@code gray} and run the detector.
     * An empty list means no Aztec decoded from this image.
     */
    public static List<Found> read(GrayU8 gray) {
        if (gray.width < 11 || gray.height < 11) {
            return List.of();
        }
        return read(DmBinarizer.blackMatrix(gray));
    }

    /** Bull's-eye search on an existing matrix. {@code Scan} calls {@link #read(GrayU8)} once. */
    static List<Found> read(Bits bits) {
        if (bits == null || bits.width < 11 || bits.height < 11) {
            return List.of();
        }
        List<Found> found = new ArrayList<>();
        for (Ring center : findCenters(bits)) {
            Found hit = readCenter(bits, center);
            if (hit != null) {
                found.add(hit);
            }
        }
        return found;
    }

    private static List<Ring> findCenters(Bits image) {
        int margin = 5;
        List<Ring> found = new ArrayList<>();
        for (int y = margin; y < image.height - margin; y++) {
            int[] row = QrCursor.patternRow(image, y);
            View next = View.of(row);
            next.shift(1);
            for (int guard = 0; guard < row.length + 2; guard++) {
                View hit = findCenterPattern(next);
                if (!hit.isValid()) {
                    break;
                }
                double px = hit.pixelsInFront() + hit.at(0) + hit.at(1) + hit.at(2) + hit.at(3) / 2.0;
                double py = y + 0.5;
                if (!insideKnown(found, px, py)) {
                    Ring pattern = locate(image, px, py, hit.sum());
                    if (pattern != null) {
                        found.add(pattern);
                    }
                }
                hit.shift(2);
                hit.extend();
                next = hit;
            }
        }
        return found;
    }

    /**
     * Seven-run bullseye. The window starts on white. The runs just outside it
     * have to be about as wide as the center run.
     */
    private static View findCenterPattern(View view) {
        // PatternView::end() is data + size. shift() does not shrink size, so the
        // first scan of a row (after the opening shift) reaches one run further
        // than the fixed row length. minSize is 8 because the check reads one run
        // past the 7-wide window.
        if (view.row == null || view.size <= 8) {
            return View.none();
        }
        View window = view.sub(0, 7);
        int limit = view.data + view.size - 8;
        while (window.data < limit) {
            if (isCenterPattern(window)) {
                return window;
            }
            window.shift(2);
        }
        return View.none();
    }

    private static boolean isCenterPattern(View view) {
        int low = view.at(0) + view.at(1);
        int high = low;
        for (int i = 1; i < view.size - 1; i++) {
            int pair = view.at(i) + view.at(i + 1);
            low = Math.min(low, pair);
            high = Math.max(high, pair);
        }
        int middle = view.at(view.size / 2);
        return high <= low * 4 / 3 + 1
                && view.at(-1) >= middle - 2
                && view.at(view.size) >= middle - 2;
    }

    private static boolean insideKnown(List<Ring> found, double x, double y) {
        for (int i = found.size() - 1; i >= 0; i--) {
            Ring old = found.get(i);
            if (y - old.y > old.size / 2.0) {
                break;
            }
            if (distance(x, y, old.x, old.y) < old.size / 2.0) {
                return true;
            }
        }
        return false;
    }

    private static Ring locate(Bits image, double cx, double cy, int spreadH) {
        int x = (int) cx;
        int y = (int) cy;
        if (!image.isIn(x, y)) {
            return null;
        }
        IntCursor cursor = new IntCursor(image, x, y, 0, 0);
        int minSpread = spreadH;
        int maxSpread = 0;
        int[][] directions = {{0, 1}, {1, 0}, {1, 1}, {1, -1}};
        for (int i = 0; i < directions.length; i++) {
            cursor.dx = directions[i][0];
            cursor.dy = directions[i][1];
            int spread = symmetric(cursor, spreadH, i == 0);
            if (spread == 0) {
                return null;
            }
            minSpread = Math.min(minSpread, spread);
            maxSpread = Math.max(maxSpread, spread);
        }
        return new Ring(cursor.x + 0.5, cursor.y + 0.5, (maxSpread + minSpread) / 2.0);
    }

    private static int symmetric(IntCursor cursor, int range, boolean updatePosition) {
        range *= 2;
        FastEdge forward = new FastEdge(cursor);
        FastEdge backward = new FastEdge(cursor.image, cursor.x, cursor.y, -cursor.dx, -cursor.dy);
        int centerForward = forward.stepToNextEdge(range / 7);
        if (centerForward == 0) {
            return 0;
        }
        int centerBackward = backward.stepToNextEdge(range / 7);
        if (centerBackward == 0) {
            return 0;
        }
        int center = centerForward + centerBackward - 1;
        if (center > range / 7 || center < range / (4 * 7)) {
            return 0;
        }
        int spread = center;
        int low = 0;
        int high = 0;
        FastEdge[] sides = {forward, backward};
        for (FastEdge side : sides) {
            int previous = center;
            for (int i = 0; i < 3; i++) {
                int length = side.stepToNextEdge(range - spread);
                if (length == 0) {
                    return 0;
                }
                int pair = length + previous;
                if (low == 0) {
                    low = pair;
                    high = pair;
                } else {
                    low = Math.min(low, pair);
                    high = Math.max(high, pair);
                }
                if (high > low * 4 / 3 + 1) {
                    return 0;
                }
                spread += length;
                previous = length;
            }
        }
        if (updatePosition) {
            cursor.step(centerForward - centerBackward);
        }
        return spread;
    }

    private static Found readCenter(Bits image, Ring center) {
        int width = (int) center.size;
        P[] source = centeredSquare(7);
        P[] destination = QrConcentric.corners(image, center.x, center.y, width, 3);
        if (destination == null) {
            return null;
        }
        Mode mode = new Mode();
        boolean parsed = mode.parse(image, source, destination);
        if (!parsed || mode.radius == 7) {
            P[] outer = QrConcentric.corners(image, center.x, center.y, (int) (center.size * 5 / 3.0), 5);
            if (outer != null) {
                boolean again = mode.parse(image, centeredSquare(11), outer);
                if (again && mode.radius == 7) {
                    source = centeredSquare(11);
                    destination = outer;
                }
            }
            if (mode.message < 0) {
                return null;
            }
        }
        destination = rotatedCorners(destination, mode.rotate, mode.mirror);
        int layers = 0;
        int dataBlocks = 0;
        if (!mode.rune) {
            int[] parameters = parameters(mode.message, mode.radius == 5);
            layers = parameters[0];
            dataBlocks = parameters[1];
        }
        int dimension = mode.radius == 5
                ? 4 * layers + 11
                : 4 * layers + 2 * ((2 * layers + 6) / 15) + 15;
        if (dimension <= 0) {
            return null;
        }
        double middle = dimension / 2.0;
        Warp warp = Warp.between(move(source, middle, middle), destination);
        Grid grid = dimension >= LOCAL_GRID_DIMENSION
                ? sampleWithTiming(image, dimension, warp, middle)
                : sample(image, dimension, warp);
        if (grid == null) {
            return null;
        }
        Decoded decoded = mode.rune
                ? rune(mode.message)
                : AztecDecoder.decodeContent(grid.bits, mode.radius == 5, layers, dataBlocks);
        if (decoded == null) {
            return null;
        }
        List<ImagePoint> corners = new ArrayList<>(4);
        for (P corner : grid.corners) {
            if (!Double.isFinite(corner.x) || !Double.isFinite(corner.y)) {
                return null;
            }
            corners.add(new ImagePoint(corner.x, corner.y));
        }
        return new Found(decoded.text(), decoded.bytes(), List.copyOf(corners), orientation(grid.corners[0], grid.corners[1]));
    }

    private static Decoded rune(int value) {
        String text = runeText(value);
        return text == null ? null : new Decoded(text, text.getBytes(StandardCharsets.US_ASCII));
    }

    /** Rune value, three digits, matching zxing-cpp {@code ToString(value, 3)}. */
    private static String runeText(int value) {
        if (value < 0 || value > 999) {
            return null;
        }
        return String.format("%03d", value);
    }

    /**
     * Layers and data-block count from the corrected mode message.
     * The artificial reader-init bit is cleared before the block count is read.
     */
    private static int[] parameters(int modeMessage, boolean compact) {
        int layers;
        int blocks;
        if (compact) {
            layers = (modeMessage >> 6) + 1;
            if (layers == 1 && (modeMessage & 0x20) != 0) {
                modeMessage &= ~0x20;
            }
            blocks = (modeMessage & 0x3F) + 1;
        } else {
            layers = (modeMessage >> 11) + 1;
            if (layers <= 22 && (modeMessage & 0x400) != 0) {
                modeMessage &= ~0x400;
            }
            blocks = (modeMessage & 0x7FF) + 1;
        }
        return new int[]{layers, blocks};
    }

    /**
     * Reference-grid sample for dimension 35 and up.
     * A missing cross is filled from the current warp. The single-warp sampler is not used here.
     */
    private static Grid sampleWithTiming(Bits image, int dimension, Warp mod2Pix, double middle) {
        if (mod2Pix == null || !mod2Pix.valid()) {
            return null;
        }
        int rings = (dimension / 2) / 16;
        int first = dimension / 2 - rings * 16;
        int count = 0;
        for (int module = first; module < dimension; module += 16) {
            count++;
        }
        if (count < 2) {
            return null;
        }
        int[] modules = new int[count];
        int index = 0;
        for (int module = first; module < dimension; module += 16) {
            modules[index++] = module;
        }
        P[][] at = new P[count][count];
        int mid = count / 2;
        at[mid][mid] = finite(mod2Pix.apply(middle, middle));
        for (int ring = rings - 1; ring >= 0; ring--) {
            int reach = rings - ring;
            P[] src = move(centeredSquare(32 * reach), middle, middle);
            P[] dst = new P[4];
            for (int i = 0; i < 4; i++) {
                int px = reach * QUAD_DIRS[i][0] + mid;
                int py = reach * QUAD_DIRS[i][1] + mid;
                if (px < 0 || py < 0 || px >= count || py >= count) {
                    return null;
                }
                P found = finite(new LocalGrid(
                        image, mod2Pix, (int) src[i].x, (int) src[i].y, dimension, dimension, 0, 0
                ).findTimingPatternCross(true, 4));
                at[py][px] = found;
                dst[i] = found != null ? found : mod2Pix.apply(src[i]);
            }
            mod2Pix = Warp.between(src, dst);
            if (mod2Pix == null || !mod2Pix.valid()) {
                return null;
            }
        }
        for (int y = 0; y < count; y++) {
            for (int x = 0; x < count; x++) {
                if (at[y][x] != null) {
                    continue;
                }
                at[y][x] = finite(new LocalGrid(
                        image, mod2Pix, modules[x], modules[y], dimension, dimension, 0, 0
                ).findTimingPatternCross(true, 4));
            }
        }
        return sampleAligned(image, dimension, mod2Pix, at, modules);
    }

    /** {@code SampleGrid} with one warp per reference-grid cell. Missing crosses use {@code mod2Pix}. */
    private static Grid sampleAligned(Bits image, int dimension, Warp mod2Pix, P[][] at, int[] modules) {
        int cells = modules.length - 1;
        for (int y = 0; y <= cells; y++) {
            for (int x = 0; x <= cells; x++) {
                if (at[y][x] == null) {
                    at[y][x] = finite(mod2Pix.apply(modules[x] + 0.5, modules[y] + 0.5));
                    if (at[y][x] == null) {
                        return null;
                    }
                }
            }
        }
        List<Roi> rois = new ArrayList<>();
        for (int y = 0; y < cells; y++) {
            for (int x = 0; x < cells; x++) {
                int x0 = modules[x];
                int x1 = modules[x + 1];
                int y0 = modules[y];
                int y1 = modules[y + 1];
                P[] source = {
                        new P(x0 + 0.5, y0 + 0.5),
                        new P(x1 + 0.5, y0 + 0.5),
                        new P(x1 + 0.5, y1 + 0.5),
                        new P(x0 + 0.5, y1 + 0.5)
                };
                P[] destination = {at[y][x], at[y][x + 1], at[y + 1][x + 1], at[y + 1][x]};
                rois.add(new Roi(
                        x == 0 ? 0 : x0,
                        x == cells - 1 ? dimension : x1,
                        y == 0 ? 0 : y0,
                        y == cells - 1 ? dimension : y1,
                        Warp.between(source, destination)
                ));
            }
        }
        if (dimension <= 0) {
            return null;
        }
        for (Roi roi : rois) {
            if (roi.warp == null || !roi.warp.valid()
                    || !inside(image, roi.warp, roi.x0, roi.y0)
                    || !inside(image, roi.warp, roi.x1 - 1, roi.y0)
                    || !inside(image, roi.warp, roi.x1 - 1, roi.y1 - 1)
                    || !inside(image, roi.warp, roi.x0, roi.y1 - 1)) {
                return null;
            }
        }
        Bits bits = new Bits(dimension, dimension);
        for (Roi roi : rois) {
            for (int y = roi.y0; y < roi.y1; y++) {
                for (int x = roi.x0; x < roi.x1; x++) {
                    P pixel = roi.warp.apply(x + 0.5, y + 0.5);
                    if (!image.isIn(pixel)) {
                        return null;
                    }
                    if (image.get(pixel)) {
                        bits.set(x, y);
                    }
                }
            }
        }
        return new Grid(bits, new P[]{
                projectCorner(rois, 0, 0),
                projectCorner(rois, dimension, 0),
                projectCorner(rois, dimension, dimension),
                projectCorner(rois, 0, dimension)
        });
    }

    private static P finite(P point) {
        if (point == null || !Double.isFinite(point.x) || !Double.isFinite(point.y)) {
            return null;
        }
        return point;
    }

    private static P projectCorner(List<Roi> rois, int x, int y) {
        for (Roi roi : rois) {
            if (roi.x0 <= x && x <= roi.x1 && roi.y0 <= y && y <= roi.y1) {
                P at = roi.warp.apply(x, y);
                return new P((int) (at.x + 0.5), (int) (at.y + 0.5));
            }
        }
        return new P(0, 0);
    }

    private record Roi(int x0, int x1, int y0, int y1, Warp warp) {
    }

    private static Grid sample(Bits image, int dimension, Warp warp) {
        if (warp == null || !warp.valid()) {
            return null;
        }
        if (!inside(image, warp, 0, 0)
                || !inside(image, warp, dimension - 1, 0)
                || !inside(image, warp, dimension - 1, dimension - 1)
                || !inside(image, warp, 0, dimension - 1)) {
            return null;
        }
        Bits bits = new Bits(dimension, dimension);
        for (int y = 0; y < dimension; y++) {
            for (int x = 0; x < dimension; x++) {
                P pixel = warp.apply(x + 0.5, y + 0.5);
                if (!image.isIn(pixel)) {
                    return null;
                }
                if (image.get(pixel)) {
                    bits.set(x, y);
                }
            }
        }
        return new Grid(bits, new P[]{
                project(warp, 0, 0),
                project(warp, dimension, 0),
                project(warp, dimension, dimension),
                project(warp, 0, dimension)
        });
    }

    private static boolean inside(Bits image, Warp warp, int x, int y) {
        return image.isIn(warp.apply(x + 0.5, y + 0.5));
    }

    private static P project(Warp warp, int x, int y) {
        P at = warp.apply(x, y);
        return new P((int) (at.x + 0.5), (int) (at.y + 0.5));
    }

    /** Square of half-extent {@code size / 2}, order TL, TR, BR, BL with Y down. */
    private static P[] centeredSquare(int size) {
        int half = size / 2;
        return new P[]{
                new P(-half, -half),
                new P(half, -half),
                new P(half, half),
                new P(-half, half)
        };
    }

    private static P[] move(P[] quad, double dx, double dy) {
        P[] moved = new P[4];
        for (int i = 0; i < 4; i++) {
            moved[i] = new P(quad[i].x + dx, quad[i].y + dy);
        }
        return moved;
    }

    private static P[] rotatedCorners(P[] quad, int turns, boolean mirror) {
        int shift = Math.floorMod(turns, 4);
        P[] turned = new P[4];
        for (int i = 0; i < 4; i++) {
            turned[i] = quad[(i + shift) % 4];
        }
        if (mirror) {
            P swap = turned[1];
            turned[1] = turned[3];
            turned[3] = swap;
        }
        return turned;
    }

    private static int findRotation(int bits, boolean mirror) {
        int mask = mirror ? 0b111_000_001_110 : 0b111_011_100_000;
        for (int i = 0; i < 4; i++) {
            if (Integer.bitCount((mask ^ bits) & 0xfff) <= 2) {
                return i;
            }
            bits = ((bits << 3) & 0xfff) | ((bits >> 9) & 0b111);
        }
        return -1;
    }

    private static int orientationBits(Bits image, Warp warp, int radius) {
        if (warp == null || !warp.valid()) {
            return 0;
        }
        int bits = 0;
        for (int[] direction : QUAD_DIRS) {
            int cx = radius * direction[0];
            int cy = radius * direction[1];
            int leftX = cx;
            int leftY = cy - direction[1];
            int rightX = cx - direction[0];
            int rightY = cy;
            if (direction[0] != direction[1]) {
                int swapX = leftX;
                int swapY = leftY;
                leftX = rightX;
                leftY = rightY;
                rightX = swapX;
                rightY = swapY;
            }
            int[][] samples = {{leftX, leftY}, {cx, cy}, {rightX, rightY}};
            for (int[] sample : samples) {
                P pixel = warp.apply(sample[0], sample[1]);
                if (!image.isIn(pixel)) {
                    return 0;
                }
                bits = (bits << 1) | (image.get(pixel) ? 1 : 0);
            }
        }
        return bits;
    }

    private static int modeMessage(Bits image, Warp warp, int radius, boolean[] rune) {
        rune[0] = false;
        if (warp == null || !warp.valid()) {
            return -1;
        }
        boolean compact = radius == 5;
        long bits = 0;
        for (int[] direction : QUAD_DIRS) {
            int cx = radius * direction[0];
            int cy = radius * direction[1];
            int stepX = direction[0] == direction[1] ? -direction[0] : 0;
            int stepY = direction[0] == direction[1] ? 0 : -direction[1];
            for (int i = 2; i <= 2 * radius - 2; i++) {
                if (!compact && i == 7) {
                    continue;
                }
                P pixel = warp.apply(cx + (long) i * stepX, cy + (long) i * stepY);
                if (!image.isIn(pixel)) {
                    return -1;
                }
                bits = (bits << 1) | (image.get(pixel) ? 1 : 0);
            }
        }
        int words = compact ? 7 : 10;
        int dataWords = compact ? 2 : 4;
        int[] nibbles = new int[words];
        for (int i = words - 1; i >= 0; i--) {
            nibbles[i] = (int) (bits & 0xF);
            bits >>= 4;
        }
        int[] original = nibbles.clone();
        ReedSolomonDecoder reedSolomon = new ReedSolomonDecoder(GenericGF.AZTEC_PARAM);
        try {
            reedSolomon.decode(nibbles, words - dataWords);
        } catch (ReedSolomonException failed) {
            if (!compact) {
                return -1;
            }
            nibbles = original;
            for (int i = 0; i < nibbles.length; i++) {
                nibbles[i] ^= 0b1010;
            }
            try {
                reedSolomon.decode(nibbles, words - dataWords);
            } catch (ReedSolomonException again) {
                return -1;
            }
            rune[0] = true;
        }
        int value = 0;
        for (int i = 0; i < dataWords; i++) {
            value = (value << 4) + nibbles[i];
        }
        return value;
    }

    private static int orientation(P a, P b) {
        int degrees = (int) Math.round(Math.toDegrees(Math.atan2(b.y - a.y, b.x - a.x)));
        return Math.floorMod(degrees, 360);
    }

    private static double distance(double ax, double ay, double bx, double by) {
        double dx = ax - bx;
        double dy = ay - by;
        return Math.sqrt(dx * dx + dy * dy);
    }

    /**
     * Orientation search. A failed {@code ModeMessage} call stores {@code -1}
     * and can erase an earlier success, matching {@code AZDetector.cpp}.
     */
    private static final class Mode {
        private int radius = -1;
        private int rotate;
        private boolean mirror;
        private int message = -1;
        private boolean rune;

        private boolean parse(Bits image, P[] source, P[] destination) {
            boolean[] runeFlag = new boolean[1];
            for (radius = 5; radius <= 7; radius += 2) {
                int bits = orientationBits(image, Warp.between(source, destination), radius);
                if (bits == 0) {
                    continue;
                }
                for (int reflected = 0; reflected <= 1; reflected++) {
                    rotate = findRotation(bits, reflected == 1);
                    if (rotate < 0) {
                        continue;
                    }
                    mirror = reflected == 1;
                    message = modeMessage(
                            image,
                            Warp.between(source, rotatedCorners(destination, rotate, mirror)),
                            radius,
                            runeFlag
                    );
                    rune = runeFlag[0];
                    if (message >= 0) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    private record Grid(Bits bits, P[] corners) {
    }
}
