package io.github.kamilkrzywanski.barcode.locate.dm;

import io.github.kamilkrzywanski.barcode.image.GrayU8;
import io.github.kamilkrzywanski.barcode.ImagePoint;
import io.github.kamilkrzywanski.barcode.decode.Decoded;
import io.github.kamilkrzywanski.barcode.locate.dm.DmGeometry.Bits;
import io.github.kamilkrzywanski.barcode.locate.dm.DmGeometry.Line;
import io.github.kamilkrzywanski.barcode.locate.dm.DmGeometry.P;
import io.github.kamilkrzywanski.barcode.locate.dm.DmGeometry.Versions;
import io.github.kamilkrzywanski.barcode.locate.dm.DmGeometry.Warp;

import java.util.ArrayList;
import java.util.List;

/**
 * Data Matrix localization ported from zxing-cpp 3.1.1 {@code DMDetector.cpp}
 * ({@code DetectPure}, the edge-tracing {@code DetectNew} path, and the
 * white-rectangle {@code DetectOld} fallback).
 *
 * <p>The image is binarized with the zxing-cpp local-average matrix binarizer.
 * The sampled module matrix is decoded by {@link DmDecoder}. There is no ZXing fallback on this path.
 * {@code DetectOld} runs only when the edge tracer yields no grid.
 * Alignment-pattern refinement from {@code LocalGrid} is not in this slice:
 * a flat symbol is sampled from the four border lines. When at least one side
 * is 42 modules or smaller, the symbol also gets the timing-pattern lookup table.
 */
public final class DataMatrixDetector {
    private static final int MIN_SYMBOL_PIXELS = 16;
    private static final int MAX_GRIDS = 8;
    private static final int MAX_EDGE_FOLLOWS = 2500;

    private DataMatrixDetector() {
    }

    /** One decoded symbol. Corners are TL, TR, BR, BL in the gray image. */
    public record Found(String text, byte[] bytes, List<ImagePoint> corners, int orientationDegrees) {
    }

    /**
     * Binarize {@code gray} and run the detector.
     * An empty list means no Data Matrix decoded from this image.
     */
    public static List<Found> read(GrayU8 gray) {
        return read(binarize(gray));
    }

    /** Edge trace on an existing matrix. {@code Scan} calls {@link #read(GrayU8)} once. */
    static List<Found> read(Bits bits) {
        List<Grid> grids = new ArrayList<>();
        Grid pure = detectPure(bits);
        if (pure != null) {
            Found pureHit = decode(pure);
            if (pureHit != null) {
                return List.of(pureHit);
            }
        }
        // zxing-cpp stops when DetectPure returns a grid, even if that grid does not decode.
        // A non-decoding pure candidate falls through here so the edge tracer can still run.
        detectNew(bits, true, true, grids);
        // zxing-cpp runs the white-rectangle detector only when the edge tracer yielded nothing.
        if (grids.isEmpty()) {
            Grid legacy = DmLegacy.detect(bits);
            if (legacy != null) {
                grids.add(legacy);
            }
        }
        List<Found> found = new ArrayList<>();
        for (Grid grid : grids) {
            Found hit = decode(grid);
            if (hit != null) {
                found.add(hit);
            }
        }
        return found;
    }

    private static Bits binarize(GrayU8 gray) {
        return DmBinarizer.blackMatrix(gray);
    }

    private static Found decode(Grid grid) {
        Decoded decoded = DmDecoder.decodeContent(grid.bits);
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

    private static int orientation(P a, P b) {
        int degrees = (int) Math.round(Math.toDegrees(Math.atan2(b.y - a.y, b.x - a.x)));
        return Math.floorMod(degrees, 360);
    }

    record Grid(Bits bits, P[] corners) {
    }

    private static Grid detectPure(Bits image) {
        int[] box = boundingBox(image, 8);
        if (box == null) {
            return null;
        }
        int left = box[0];
        int top = box[1];
        int width = box[2];
        int height = box[3];
        Cursor cur = new Cursor(image, left, top, 0, 1);
        if (cur.countEdges(height - 1) != 0) {
            return null;
        }
        cur.turnLeft();
        if (cur.countEdges(width - 1) != 0) {
            return null;
        }
        cur.turnLeft();
        int dimR = cur.countEdges(height - 1) + 1;
        cur.turnLeft();
        int dimT = cur.countEdges(width - 1) + 1;
        double modX = (double) width / dimT;
        double modY = (double) height / dimR;
        double mod = (modX + modY) / 2.0;
        if (dimT % 2 != 0 || dimR % 2 != 0 || dimT < 10 || dimT > 144 || dimR < 8 || dimR > 144
                || Math.abs(modX - modY) > 1
                || !image.isIn(left + modX / 2 + (dimT - 1) * mod, top + modY / 2 + (dimR - 1) * mod)) {
            return null;
        }
        Bits sampled = new Bits(dimT, dimR);
        for (int y = 0; y < dimR; y++) {
            double yOffset = top + modY / 2 + y * mod;
            for (int x = 0; x < dimT; x++) {
                if (image.get(new P(left + modX / 2 + x * mod, yOffset))) {
                    sampled.set(x, y);
                }
            }
        }
        int right = left + width - 1;
        int bottom = top + height - 1;
        return new Grid(sampled, new P[]{
                new P(left, top), new P(right, top), new P(right, bottom), new P(left, bottom)
        });
    }

    /** {@code BitMatrix::findBoundingBox}. Returns left, top, width, height. */
    private static int[] boundingBox(Bits image, int minSize) {
        int first = -1;
        int last = -1;
        int n = image.width * image.height;
        for (int i = 0; i < n; i++) {
            if (image.get(i % image.width, i / image.width)) {
                first = i;
                break;
            }
        }
        if (first < 0) {
            return null;
        }
        for (int i = n - 1; i >= 0; i--) {
            if (image.get(i % image.width, i / image.width)) {
                last = i;
                break;
            }
        }
        int top = first / image.width;
        int left = first % image.width;
        int bottom = last / image.width;
        int right = last % image.width;
        if (bottom - top + 1 < minSize) {
            return null;
        }
        for (int y = top; y <= bottom; y++) {
            for (int x = 0; x < left; x++) {
                if (image.get(x, y)) {
                    left = x;
                    break;
                }
            }
            for (int x = image.width - 1; x > right; x--) {
                if (image.get(x, y)) {
                    right = x;
                    break;
                }
            }
        }
        int width = right - left + 1;
        int height = bottom - top + 1;
        if (width < minSize || height < minSize) {
            return null;
        }
        return new int[]{left, top, width, height};
    }

    private static void detectNew(Bits image, boolean tryHarder, boolean tryRotate, List<Grid> out) {
        P[] dirs = tryRotate
                ? new P[]{new P(-1, 0), new P(1, 0), new P(0, -1), new P(0, 1)}
                : new P[]{new P(-1, 0)};
        Line[] lines = {new Line(), new Line(), new Line(), new Line()};
        // zxing-cpp clears the history for every direction. Each entry carries the number of the
        // direction that wrote it instead, so an entry of an earlier direction reads as unvisited.
        byte[] history = tryHarder ? new byte[image.width * image.height] : null;
        int direction = 0;
        P center = new P(image.width / 2.0, image.height / 2.0);
        for (P dir : dirs) {
            direction++;
            P start = P.centered(P.add(P.sub(center, P.mul(center, dir)), P.mul(dir, MIN_SYMBOL_PIXELS / 2.0)));
            for (int i = 1; ; i++) {
                Tracer tracer = new Tracer(image, start, dir);
                double scalar = (i / 2) * (double) MIN_SYMBOL_PIXELS * ((i & 1) == 1 ? -1 : 1);
                tracer.p = P.add(tracer.p, P.mul(tracer.right(), scalar));
                tracer.history = history;
                tracer.direction = direction;
                if (!image.isIn(tracer.p)) {
                    break;
                }
                scan(tracer, lines, out);
                if (!tryHarder || out.size() >= MAX_GRIDS) {
                    break;
                }
            }
            if (!tryRotate || out.size() >= MAX_GRIDS) {
                return;
            }
        }
    }

    private static void scan(Tracer start, Line[] lines, List<Grid> out) {
        int follows = 0;
        while (start.moveToNextWhiteAfterBlack()) {
            if (++follows > MAX_EDGE_FOLLOWS || out.size() >= MAX_GRIDS) {
                return;
            }
            for (Line line : lines) {
                line.reset();
            }
            traceSymbol(start, lines, out);
        }
    }

    /** One L-shape attempt. CHECK failures return without a grid, and the scan continues. */
    private static void traceSymbol(Tracer start, Line[] lines, List<Grid> out) {
        Line lineL = lines[0];
        Line lineB = lines[1];
        Line lineR = lines[2];
        Line lineT = lines[3];
        P[] corner = new P[4];

        Tracer t = start.copy();
        t.turnRight();
        t.state = 1;
        if (!t.traceLine(t.right(), lineL)) {
            return;
        }
        if (!t.traceCorner(t.right(), corner, 0)) {
            return;
        }
        lineL.reverse();
        Tracer tlTracer = t.copy();

        t = start.copy();
        t.state = 1;
        t.setDirection(tlTracer.right());
        if (!t.traceLine(t.left(), lineL)) {
            return;
        }
        if (lineL.truncateIfL()) {
            t.p = lineL.points.get(lineL.points.size() - 1).copy();
        }
        if (!t.updateFromOrigin(corner[0])) {
            return;
        }
        P up = t.back();
        if (!t.traceCorner(t.left(), corner, 1)) {
            return;
        }

        t.state = 2;
        if (!t.traceLine(t.left(), lineB)) {
            return;
        }
        if (!t.updateFromOrigin(corner[1])) {
            return;
        }
        P right = t.front();
        if (!t.traceCorner(t.left(), corner, 2)) {
            return;
        }
        P tl = corner[0];
        P bl = corner[1];
        P br = corner[2];
        double lenL = P.distance(tl, bl) - 1;
        double lenB = P.distance(bl, br) - 1;
        if (!(lenL >= 8 && lenB >= 10 && lenB >= lenL / 4 && lenB <= lenL * 18)) {
            return;
        }
        int maxStep = (int) (lenB / 5 + 1);
        tlTracer.setDirection(right);
        if (!tlTracer.traceGaps(tlTracer.right(), lineT, maxStep, null, lenB / 2)) {
            return;
        }
        maxStep = Math.min(lineT.length() / 3, (int) (lenL / 5)) * 2;
        t.setDirection(up);
        t.state = 3;
        if (!t.traceGaps(t.left(), lineR, maxStep, lineT, 0)) {
            return;
        }
        if (!t.traceCorner(t.left(), corner, 3)) {
            return;
        }
        P tr = corner[3];
        double lenT = P.distance(tl, tr) - 1;
        double lenR = P.distance(tr, br) - 1;
        if (!(Math.abs(lenT - lenB) / lenB < 0.5 && Math.abs(lenR - lenL) / lenL < 0.5
                && lineT.points.size() >= 5 && lineR.points.size() >= 5)) {
            return;
        }
        if (!tlTracer.traceGaps(tlTracer.right(), lineT, maxStep, lineR, 0)) {
            return;
        }
        for (Line line : lines) {
            line.evaluate(1.0, false);
        }
        bl = DmGeometry.intersect(lineB, lineL);
        tl = DmGeometry.intersect(lineT, lineL);
        tr = DmGeometry.intersect(lineT, lineR);
        br = DmGeometry.intersect(lineB, lineR);
        if (!finite(bl) || !finite(tl) || !finite(tr) || !finite(br)) {
            return;
        }

        double[] splitT = split(lineT.modules(tl, tr));
        double[] splitR = split(lineR.modules(br, tr));
        int dimT = (int) splitT[0] * 2;
        int dimR = (int) splitR[0] * 2;
        double fracT = splitT[1];
        double fracR = splitR[1];
        boolean version = Versions.known(dimR, dimT);
        if (!version && Math.abs(dimT - dimR) < 10) {
            boolean versionT = Versions.known(dimT, dimT);
            boolean versionR = Versions.known(dimR, dimR);
            if (versionT ^ versionR) {
                dimT = dimR = versionT ? dimT : dimR;
            } else {
                dimT = dimR = fracR < fracT ? dimR : dimT;
            }
            version = Versions.known(dimR, dimT);
        }
        if (!version) {
            return;
        }

        P[] source = {
                movedTowards(tl, tr, bl, 0.5),
                movedTowards(tr, br, tl, 0.3),
                movedTowards(br, bl, tr, 0.5),
                movedTowards(bl, tl, br, 0.5)
        };
        P[] rectangle = {
                new P(0, 0), new P(dimT, 0), new P(dimT, dimR), new P(0, dimR)
        };
        Warp mod2Pix = Warp.between(rectangle, source);
        Grid grid = sample(start.img, dimT, dimR, mod2Pix);
        if (grid != null) {
            out.add(grid);
        }
        if (dimT > 42 && dimR > 42) {
            return;
        }
        Warp pix2Mod = Warp.between(source, rectangle);
        if (!pix2Mod.valid()) {
            return;
        }
        P topLeft = source[0];
        P topRight = source[1];
        P bottomRight = source[2];
        double[] topLut = moduleCenters(lineT.points, topLeft, topRight, dimT, frac -> {
            P mod = pix2Mod.apply(P.add(P.mul(topLeft, 1 - frac), P.mul(topRight, frac)));
            return mod.x;
        });
        double[] rightLut = moduleCenters(lineR.points, topRight, bottomRight, dimR, frac -> {
            P mod = pix2Mod.apply(P.add(P.mul(topRight, 1 - frac), P.mul(bottomRight, frac)));
            return mod.y;
        });
        if (topLut.length == 0 && rightLut.length == 0) {
            return;
        }
        Grid corrected = sampleCorrected(start.img, dimT, dimR, mod2Pix, topLut, rightLut);
        if (corrected != null) {
            out.add(corrected);
        }
    }

    private static boolean finite(P p) {
        return p != null && Double.isFinite(p.x) && Double.isFinite(p.y);
    }

    /** Integer part and absolute fractional part, as {@code splitDouble}. */
    private static double[] split(double value) {
        if (!Double.isFinite(value) || value == 0.0) {
            return new double[]{0, Double.POSITIVE_INFINITY};
        }
        int rounded = (int) Math.round(value);
        return new double[]{rounded, Math.abs(value - rounded)};
    }

    private static P movedTowards(P a, P b1, P b2, double distance) {
        P direction = P.normalized(P.add(P.normalized(P.sub(b1, a)), P.normalized(P.sub(b2, a))));
        return P.add(a, P.mul(direction, distance));
    }

    static Grid sample(Bits image, int width, int height, Warp mod2Pix) {
        if (width <= 0 || height <= 0 || !mod2Pix.valid()) {
            return null;
        }
        if (!inside(image, mod2Pix, 0, 0) || !inside(image, mod2Pix, width - 1, 0)
                || !inside(image, mod2Pix, width - 1, height - 1) || !inside(image, mod2Pix, 0, height - 1)) {
            return null;
        }
        Bits sampled = new Bits(width, height);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                P at = mod2Pix.apply(P.centered(x, y));
                if (!image.isIn(at)) {
                    return null;
                }
                if (image.get(at)) {
                    sampled.set(x, y);
                }
            }
        }
        return new Grid(sampled, new P[]{
                pixelCorner(mod2Pix, 0, 0),
                pixelCorner(mod2Pix, width, 0),
                pixelCorner(mod2Pix, width, height),
                pixelCorner(mod2Pix, 0, height)
        });
    }

    private static boolean inside(Bits image, Warp mod2Pix, int x, int y) {
        return image.isIn(mod2Pix.apply(P.centered(x, y)));
    }

    private static P pixelCorner(Warp mod2Pix, int x, int y) {
        P at = mod2Pix.apply(x, y);
        return new P(at.x + 0.5, at.y + 0.5);
    }

    private static Grid sampleCorrected(Bits image, int width, int height, Warp mod2Pix, double[] top, double[] right) {
        if (!mod2Pix.valid()) {
            return null;
        }
        Bits sampled = new Bits(width, height);
        for (int y = 0; y < height; y++) {
            double moduleY = lookup(right, y);
            for (int x = 0; x < width; x++) {
                P at = mod2Pix.apply(lookup(top, x), moduleY);
                if (!image.isIn(at)) {
                    return null;
                }
                if (image.get(at)) {
                    sampled.set(x, y);
                }
            }
        }
        return new Grid(sampled, new P[]{
                pixelCorner(mod2Pix, 0, 0),
                pixelCorner(mod2Pix, width, 0),
                pixelCorner(mod2Pix, width, height),
                pixelCorner(mod2Pix, 0, height)
        });
    }

    private static double lookup(double[] table, int index) {
        if (table == null || table.length == 0) {
            return index + 0.5;
        }
        return table[index];
    }

    @FunctionalInterface
    private interface ModuleMap {
        double map(double edgeFraction);
    }

    /** {@code BuildModuleCenterLUT}. Empty when the edge is close enough to uniform. */
    private static double[] moduleCenters(List<P> points, P start, P end, int modules, ModuleMap edgeToModule) {
        if (points.size() < 5 || modules < 2) {
            return new double[0];
        }
        P edge = P.sub(end, start);
        double edgeLength = P.length(edge);
        if (edgeLength == 0.0) {
            return new double[0];
        }
        P unit = P.normalized(edge);
        double moduleSize = edgeLength / modules;
        double[] projected = new double[points.size()];
        for (int i = 0; i < points.size(); i++) {
            projected[i] = P.dot(P.sub(points.get(i), start), unit);
        }
        boolean startsWithGap = false;
        if (projected[0] > projected[projected.length - 1]) {
            for (int i = 0, j = projected.length - 1; i < j; i++, j--) {
                double swap = projected[i];
                projected[i] = projected[j];
                projected[j] = swap;
            }
            startsWithGap = true;
        }
        double unitPixel = P.length(P.bresenham(edge));
        double gapThreshold = 1.4 * unitPixel;
        List<Double> gapMids = new ArrayList<>();
        for (int i = 1; i < projected.length; i++) {
            if (projected[i] - projected[i - 1] > gapThreshold) {
                double mid = (projected[i] + projected[i - 1]) / 2.0;
                if (gapMids.isEmpty() || mid - gapMids.get(gapMids.size() - 1) > 0.75 * 2 * moduleSize) {
                    gapMids.add(mid);
                }
            }
        }
        if (gapMids.isEmpty()) {
            return new double[0];
        }
        int[] moduleIndex = new int[gapMids.size()];
        double gapFlag = startsWithGap ? 1 : 0;
        moduleIndex[0] = (int) Math.round((gapMids.get(0) / moduleSize - (1.5 + gapFlag)) / 2.0) * 2 + 1 + (int) gapFlag;
        for (int i = 1; i < gapMids.size(); i++) {
            int jumps = Math.max(1, (int) Math.round((gapMids.get(i) - gapMids.get(i - 1)) / (2 * moduleSize)));
            moduleIndex[i] = moduleIndex[i - 1] + jumps * 2;
        }
        int knots = gapMids.size() + 2;
        double[] at = new double[knots];
        double[] corrected = new double[knots];
        for (int k = 0; k < gapMids.size(); k++) {
            at[k + 1] = moduleIndex[k] + 0.5;
            corrected[k + 1] = edgeToModule.map(gapMids.get(k) / edgeLength);
        }
        at[knots - 1] = modules;
        corrected[knots - 1] = modules;
        double deviation = 0;
        for (int i = 0; i < knots; i++) {
            deviation = Math.max(deviation, Math.abs(at[i] - corrected[i]));
        }
        if (!(deviation >= 0.5)) {
            return new double[0];
        }
        double[] centers = new double[modules];
        int knot = 0;
        for (int i = 0; i < modules; i++) {
            double center = i + 0.5;
            while (knot + 1 < knots - 1 && at[knot + 1] < center) {
                knot++;
            }
            double span = at[knot + 1] - at[knot];
            if (span < 0.1) {
                return new double[0];
            }
            double t = (center - at[knot]) / span;
            centers[i] = corrected[knot] + t * (corrected[knot + 1] - corrected[knot]);
        }
        return centers;
    }

    /** Integer cursor used by {@code DetectPure}. */
    private static final class Cursor {
        private final Bits image;
        private int x;
        private int y;
        private int dx;
        private int dy;

        Cursor(Bits image, int x, int y, int dx, int dy) {
            this.image = image;
            this.x = x;
            this.y = y;
            this.dx = dx;
            this.dy = dy;
        }

        void turnLeft() {
            int nextX = dy;
            int nextY = -dx;
            dx = nextX;
            dy = nextY;
        }

        int countEdges(int range) {
            int count = 0;
            while (range > 0) {
                int steps = stepToEdge(range);
                if (steps == 0) {
                    break;
                }
                range -= steps;
                count++;
            }
            return count;
        }

        private int stepToEdge(int range) {
            int steps = 0;
            int level = value(x, y);
            int remaining = 1;
            while (remaining > 0 && steps < range && level != -1) {
                steps++;
                int next = value(x + steps * dx, y + steps * dy);
                if (level != next) {
                    level = next;
                    remaining--;
                }
            }
            x += steps * dx;
            y += steps * dy;
            return remaining == 0 ? steps : 0;
        }

        private int value(int px, int py) {
            if (px < 0 || py < 0 || px >= image.width || py >= image.height) {
                return -1;
            }
            return image.get(px, py) ? 1 : 0;
        }
    }

    /** Float cursor plus the edge trace from {@code EdgeTracer}. */
    private static final class Tracer {
        private enum Step {FOUND, OPEN, CLOSED}

        final Bits img;
        P p;
        P d;
        /** Pixels passed in this scan direction: the direction number times four, plus the state. */
        byte[] history;
        int direction;
        int state;

        Tracer(Bits image, P origin, P direction) {
            img = image;
            p = origin.copy();
            setDirection(direction);
        }

        Tracer copy() {
            Tracer copy = new Tracer(img, p, d);
            copy.d = d.copy();
            copy.history = history;
            copy.direction = direction;
            copy.state = state;
            return copy;
        }

        void setDirection(P direction) {
            d = P.bresenham(direction);
        }

        void step(double scale) {
            p = P.add(p, P.mul(d, scale));
        }

        P front() {
            return d.copy();
        }

        P back() {
            return P.neg(d);
        }

        P left() {
            return P.left(d);
        }

        P right() {
            return P.right(d);
        }

        void turnRight() {
            d = right();
        }

        boolean moveToNextWhiteAfterBlack() {
            EdgeCounter counter = new EdgeCounter(img, P.trunc(p.x), P.trunc(p.y), P.trunc(d.x), P.trunc(d.y));
            int steps = counter.next(Integer.MAX_VALUE);
            if (steps == 0) {
                return false;
            }
            step(steps);
            if (img.value(p) == 0) {
                return true;
            }
            steps = counter.next(Integer.MAX_VALUE);
            if (steps == 0) {
                return false;
            }
            step(steps);
            return img.isIn(p);
        }

        boolean traceLine(P edge, Line line) {
            line.setInward(edge);
            while (true) {
                line.add(p);
                if (line.points.size() % 50 == 10 && !updateFromCentroid(line)) {
                    return false;
                }
                Step stepped = traceStep(edge, 1, line.valid());
                if (stepped != Step.FOUND) {
                    return stepped == Step.OPEN && line.points.size() > 1 && updateFromCentroid(line);
                }
            }
        }

        boolean traceGaps(P edge, Line line, int maxStep, Line finish, double minDistance) {
            line.setInward(edge);
            int gaps = 0;
            int taken = 0;
            int perGap = maxStep;
            P previous = new P(0, 0);
            while (true) {
                boolean stuck = previous.x == p.x && previous.y == p.y;
                previous.set(p);
                int limit = (gaps == 0 ? 2 : gaps + 1) * perGap;
                boolean tooFar = taken > limit;
                taken++;
                if (stuck || tooFar) {
                    return false;
                }
                if (line.valid() && line.signedDistance(p) < -5 && (!line.evaluate() || line.signedDistance(p) < -5)) {
                    return false;
                }
                if (line.valid() && line.signedDistance(p) > 3) {
                    P normal = line.normal();
                    if (Math.abs(P.dot(P.normalized(d), normal)) > 0.7) {
                        return false;
                    }
                    if (!line.evaluate(1.5, false)) {
                        return false;
                    }
                    P projected = line.project(p);
                    P last = line.project(line.points.get(line.points.size() - 1));
                    int guard = 0;
                    while (P.distance(projected, last) < 1 && guard++ < 8) {
                        projected = P.add(projected, d);
                    }
                    p = P.centered(projected);
                } else {
                    P delta = line.points.isEmpty() ? new P(0, 0) : P.sub(p, line.points.get(line.points.size() - 1));
                    double along = line.points.isEmpty() ? 0 : P.dot(P.main(d), delta);
                    line.add(p);
                    if (along > 1 || P.maxAbs(delta) >= 2) {
                        gaps++;
                        if (gaps >= 2 || line.points.size() > 5) {
                            if (!updateFromLine(line)) {
                                return false;
                            }
                            if (minDistance > 0 && gaps >= 4 && P.distance(p, line.points.get(0)) > minDistance) {
                                line.pop();
                                return true;
                            }
                        }
                    } else if (gaps == 0 && line.points.size() >= 2L * maxStep) {
                        return false;
                    }
                }
                if (finish != null && finish.valid()) {
                    maxStep = Math.min(maxStep, (int) finish.signedDistance(p));
                }
                Step stepped = traceStep(edge, maxStep, line.valid());
                if (stepped != Step.FOUND) {
                    return stepped == Step.OPEN && finish != null && finish.valid()
                            && (int) finish.signedDistance(p) <= maxStep + 1;
                }
            }
        }

        boolean traceCorner(P direction, P[] corners, int slot) {
            step(1);
            corners[slot] = p.copy();
            P previous = d;
            d = direction;
            traceStep(P.mul(previous, -1), 2, false);
            return img.isIn(corners[slot]) && img.isIn(p);
        }

        private boolean updateFromOrigin(P origin) {
            P old = d.copy();
            setDirection(P.sub(p, origin));
            if (P.dot(d, old) < 0) {
                return false;
            }
            if (Math.abs(d.x) == Math.abs(d.y)) {
                P main = P.main(old);
                d = P.add(main, P.mul(P.sub(d, main), 0.99));
            } else if (!mainEquals(P.main(d), P.main(old))) {
                d = P.add(P.main(old), P.mul(P.main(d), 0.99));
            }
            return true;
        }

        private boolean updateFromLine(Line line) {
            if (!line.evaluate(1.5, false) || line.points.isEmpty()) {
                return false;
            }
            P origin = P.add(P.sub(p, line.project(p)), line.points.get(0));
            return updateFromOrigin(origin);
        }

        private boolean updateFromCentroid(Line line) {
            if (line.points.isEmpty()) {
                return false;
            }
            return updateFromOrigin(line.centroid());
        }

        private static boolean mainEquals(P a, P b) {
            return a.x == b.x && a.y == b.y;
        }

        /**
         * The point arithmetic is written out on doubles, in the same operations and order as
         * {@code P.add}, {@code P.mul} and {@code P.sub}, so the walk is the same and allocates
         * nothing per probe.
         */
        private Step traceStep(P edge, int maxStep, boolean goodDirection) {
            boolean horizontal = Math.abs(edge.x) > Math.abs(edge.y);
            double outX = horizontal ? edge.x : 0;
            double outY = horizontal ? 0 : edge.y;
            int breadthLimit = maxStep == 1 ? 2 : (goodDirection ? 1 : 3);
            for (int breadth = 1; breadth <= breadthLimit; breadth++) {
                for (int step = 1; step <= maxStep; step++) {
                    int lateral = 2 * (step / 4 + 1) * breadth;
                    // A probe that passes the outward test always returns, so the probes a narrower
                    // breadth already made all failed and give the same answer again: skip them.
                    int first = breadth == 1 ? 0 : 2 * (step / 4 + 1) * (breadth - 1) + 1;
                    for (int i = first; i <= lateral; i++) {
                        double side = (i & 1) == 1 ? (i + 1) / 2.0 : -i / 2.0;
                        double atX = (p.x + d.x * step) + outX * side;
                        double atY = (p.y + d.y * step) + outY * side;
                        if (img.value(atX + outX, atY + outY) != 1) {
                            continue;
                        }
                        int outwardSteps = Math.max(maxStep, 3);
                        for (int j = 0; j < outwardSteps && img.isIn(atX, atY); j++) {
                            if (img.value(atX, atY) == 0) {
                                P next = new P(Math.floor(atX) + 0.5, Math.floor(atY) + 0.5);
                                if (next.x == p.x && next.y == p.y) {
                                    return Step.CLOSED;
                                }
                                p = next;
                                if (history != null && maxStep == 1) {
                                    int hx = P.trunc(p.x);
                                    int hy = P.trunc(p.y);
                                    if (hx >= 0 && hy >= 0 && hx < img.width && hy < img.height) {
                                        int index = hy * img.width + hx;
                                        int stamp = history[index] & 0xFF;
                                        int seen = (stamp >>> 2) == direction ? stamp & 3 : 0;
                                        if (seen == state) {
                                            return Step.CLOSED;
                                        }
                                        history[index] = (byte) (direction << 2 | state);
                                    }
                                }
                                return Step.FOUND;
                            }
                            atX = atX - outX;
                            atY = atY - outY;
                            if (img.value(atX - d.x, atY - d.y) == 1) {
                                atX = atX - d.x;
                                atY = atY - d.y;
                            }
                        }
                        return Step.CLOSED;
                    }
                }
            }
            return Step.OPEN;
        }
    }

    /** {@code FastEdgeToEdgeCounter}, walking one black/white run. */
    private static final class EdgeCounter {
        private final Bits image;
        private final int dx;
        private final int dy;
        private final boolean originBlack;
        private int x;
        private int y;
        private int stepsToBorder;

        EdgeCounter(Bits image, int x, int y, int dx, int dy) {
            this.image = image;
            this.x = x;
            this.y = y;
            this.dx = dx;
            this.dy = dy;
            originBlack = image.isIn(x, y) && image.get(x, y);
            int maxX = dx == 0 ? Integer.MAX_VALUE : (dx > 0 ? image.width - 1 - x : x);
            int maxY = dy == 0 ? Integer.MAX_VALUE : (dy > 0 ? image.height - 1 - y : y);
            stepsToBorder = Math.min(maxX, maxY);
        }

        int next(int range) {
            int maxSteps = Math.min(stepsToBorder, range);
            int steps = 0;
            while (true) {
                if (++steps > maxSteps) {
                    if (maxSteps != stepsToBorder) {
                        return 0;
                    }
                    break;
                }
                int nx = x + steps * dx;
                int ny = y + steps * dy;
                boolean black = image.isIn(nx, ny) && image.get(nx, ny);
                if (black != originBlack) {
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
