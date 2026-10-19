/*
 * Copyright 2019 Axel Waggershauser
 * Copyright 2020 Axel Waggershauser
 * Copyright 2026 Kamil Krzywański
 */
// SPDX-License-Identifier: Apache-2.0
package io.github.kamilkrzywanski.barleader.image;

import io.github.kamilkrzywanski.barleader.ImagePoint;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Scale, rotation, and perspective sampling. Orthogonal rotations stay on whole pixels. */
public final class Geometry {
    private static final long MAX_PIXELS = 1_600_000L;

    /**
     * Separate from {@link #MAX_PIXELS}. One Hybrid pass on a rotated frame was measured
     * under 200 ms at 12.6 Mpx (2976×2232 at 30°). Scale stays on the smaller cap so FAST
     * does not double a 1152×864 frame.
     */
    private static final long MAX_ROTATED_PIXELS = 13_000_000L;

    private Geometry() {
    }

    public static boolean withinPixelBudget(int width, int height, double scale) {
        double pixels = width * (double) height * scale * scale;
        return pixels >= 16.0 && pixels <= MAX_PIXELS;
    }

    /** Bounding box of a clockwise rotation stays inside {@link #MAX_ROTATED_PIXELS}. */
    public static boolean rotationFits(int width, int height, double degreesClockwise) {
        double radians = Math.toRadians(degreesClockwise);
        double cos = Math.abs(Math.cos(radians));
        double sin = Math.abs(Math.sin(radians));
        double newWidth = width * cos + height * sin;
        double newHeight = width * sin + height * cos;
        return newWidth >= 2.0 && newHeight >= 2.0 && newWidth * newHeight <= MAX_ROTATED_PIXELS;
    }

    /** Axis-aligned crop. The pixel map adds the crop origin. */
    public static Transformed crop(GrayU8 source, int x, int y, int width, int height) {
        if (x < 0 || y < 0 || width < 1 || height < 1 || x + width > source.width || y + height > source.height) {
            throw new IllegalArgumentException("crop rectangle does not fit");
        }
        GrayU8 dest = new GrayU8(width, height);
        for (int row = 0; row < height; row++) {
            System.arraycopy(
                    source.data,
                    source.startIndex + (y + row) * source.stride + x,
                    dest.data,
                    row * dest.stride,
                    width
            );
        }
        PixelMap map = (px, py) -> new ImagePoint(px + x, py + y);
        return new Transformed(dest, map, 0);
    }

    /** White margin around the image. Shifts ZXing's 8×8 blocks off a bad phase. */
    public static Transformed pad(GrayU8 source, int margin) {
        if (margin <= 0) {
            return new Transformed(source, PixelMap.IDENTITY, 0);
        }
        int width = source.width + margin * 2;
        int height = source.height + margin * 2;
        GrayU8 dest = white(width, height);
        for (int y = 0; y < source.height; y++) {
            for (int x = 0; x < source.width; x++) {
                dest.set(x + margin, y + margin, source.get(x, y));
            }
        }
        PixelMap map = (x, y) -> new ImagePoint(x - margin, y - margin);
        return new Transformed(dest, map, 0);
    }

    public static Transformed scale(GrayU8 source, double scale) {
        int width = Math.max(1, (int) Math.round(source.width * scale));
        int height = Math.max(1, (int) Math.round(source.height * scale));
        GrayU8 dest = new GrayU8(width, height);
        double scaleX = width / (double) source.width;
        double scaleY = height / (double) source.height;
        for (int y = 0; y < height; y++) {
            int sy = (int) Math.floor((y + 0.5) / scaleY);
            if (sy < 0) {
                sy = 0;
            } else if (sy >= source.height) {
                sy = source.height - 1;
            }
            for (int x = 0; x < width; x++) {
                int sx = (int) Math.floor((x + 0.5) / scaleX);
                if (sx < 0) {
                    sx = 0;
                } else if (sx >= source.width) {
                    sx = source.width - 1;
                }
                dest.set(x, y, source.get(sx, sy));
            }
        }
        PixelMap map = (x, y) -> new ImagePoint(
                (x + 0.5) * source.width / width - 0.5,
                (y + 0.5) * source.height / height - 0.5
        );
        return new Transformed(dest, map, 0);
    }

    /**
     * Layers of zxing-cpp {@code LumImagePyramid}, without the original image.
     * Each layer is an integer box average of the previous one. The destination size drops the
     * leftover edge ({@code width / factor} by {@code height / factor}). The sum of each box starts
     * at {@code (factor * factor) / 2}, so the division rounds instead of truncating.
     * A layer is added while the longer side is above {@code threshold} and the shorter side is
     * at least {@code factor}. {@code threshold <= 0} yields no layers, matching a disabled
     * {@code tryDownscale}.
     * <p>
     * The map multiplies both axes by the integer ratio of the original width to the layer width.
     * That is {@code Quadrilateral::Scale} in ReadBarcode.cpp. It is not the product of the
     * per-step factors when a side does not divide evenly.
     */
    public static List<Transformed> boxPyramid(GrayU8 source, int threshold, int factor) {
        if (factor < 2 || factor > 4) {
            throw new IllegalArgumentException("downscale factor must be 2, 3, or 4");
        }
        List<Transformed> layers = new ArrayList<>();
        GrayU8 current = source;
        while (threshold > 0
                && Math.max(current.width, current.height) > threshold
                && Math.min(current.width, current.height) >= factor) {
            GrayU8 smaller = boxDownscale(current, factor);
            int scale = source.width / smaller.width;
            PixelMap map = (x, y) -> new ImagePoint(x * scale, y * scale);
            layers.add(new Transformed(smaller, map, 0));
            current = smaller;
        }
        return layers;
    }

    /** {@link #boxDownscale} with the map back to {@code source}. */
    public static Transformed boxDownscaled(GrayU8 source, int factor) {
        GrayU8 smaller = boxDownscale(source, factor);
        PixelMap map = (x, y) -> new ImagePoint(x * factor, y * factor);
        return new Transformed(smaller, map, 0);
    }

    /**
     * One box-average layer. {@code factor} is 2, 3, or 4. The caller guarantees both sides
     * are at least {@code factor}.
     */
    static GrayU8 boxDownscale(GrayU8 source, int factor) {
        int width = source.width / factor;
        int height = source.height / factor;
        GrayU8 dest = new GrayU8(width, height);
        int bias = (factor * factor) / 2;
        int area = factor * factor;
        for (int dy = 0; dy < height; dy++) {
            int originY = dy * factor;
            for (int dx = 0; dx < width; dx++) {
                int sum = bias;
                int originX = dx * factor;
                for (int ty = 0; ty < factor; ty++) {
                    for (int tx = 0; tx < factor; tx++) {
                        sum += source.get(originX + tx, originY + ty);
                    }
                }
                dest.set(dx, dy, sum / area);
            }
        }
        return dest;
    }

    /** Clockwise rotation by 90, 180, or 270 degrees. Other values are rejected. */
    public static Transformed rotateOrthogonal(GrayU8 source, int degreesClockwise) {
        int turns = Math.floorMod(degreesClockwise, 360);
        if (turns == 0) {
            return new Transformed(source, PixelMap.IDENTITY, 0);
        }
        if (turns != 90 && turns != 180 && turns != 270) {
            throw new IllegalArgumentException("orthogonal rotation must be a multiple of 90 degrees");
        }
        int width = source.width;
        int height = source.height;
        int newWidth = turns == 180 ? width : height;
        int newHeight = turns == 180 ? height : width;
        GrayU8 dest = new GrayU8(newWidth, newHeight);
        PixelMap map;
        if (turns == 90) {
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    dest.set(height - 1 - y, x, source.get(x, y));
                }
            }
            map = (x, y) -> new ImagePoint(y, height - 1 - x);
        } else if (turns == 180) {
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    dest.set(width - 1 - x, height - 1 - y, source.get(x, y));
                }
            }
            map = (x, y) -> new ImagePoint(width - 1 - x, height - 1 - y);
        } else {
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    dest.set(y, width - 1 - x, source.get(x, y));
                }
            }
            map = (x, y) -> new ImagePoint(width - 1 - y, x);
        }
        return new Transformed(dest, map, turns);
    }

    /**
     * Clockwise rotation about the image center. The canvas grows so the source is not clipped.
     * Pixels outside the source are white.
     */
    public static Transformed rotate(GrayU8 source, double degreesClockwise) {
        Rotation rotation = plan(source, degreesClockwise);
        if (rotation == null) {
            return new Transformed(source, PixelMap.IDENTITY, 0);
        }
        GrayU8 dest = new GrayU8(rotation.width, rotation.height);
        paint(source, dest, rotation);
        PixelMap map = (x, y) -> new ImagePoint(
                rotation.srcCx + rotation.cos * (x - rotation.dstCx) + rotation.sin * (y - rotation.dstCy),
                rotation.srcCy - rotation.sin * (x - rotation.dstCx) + rotation.cos * (y - rotation.dstCy)
        );
        return new Transformed(dest, map, (int) Math.round(rotation.normalized));
    }

    /**
     * Same pixels as {@link #rotate}. A near-zero angle returns {@code source}.
     * The image may alias a thread-local buffer that the next call on this thread overwrites.
     */
    public static GrayU8 rotatePixels(GrayU8 source, double degreesClockwise) {
        Rotation rotation = plan(source, degreesClockwise);
        if (rotation == null) {
            return source;
        }
        int need = Math.multiplyExact(rotation.width, rotation.height);
        byte[] storage = ROTATE_STORAGE.get();
        if (storage.length < need) {
            storage = new byte[need];
            ROTATE_STORAGE.set(storage);
        }
        GrayU8 dest = new GrayU8(storage, rotation.width, rotation.height);
        paint(source, dest, rotation);
        return dest;
    }

    private static final ThreadLocal<byte[]> ROTATE_STORAGE = ThreadLocal.withInitial(() -> new byte[0]);

    private static Rotation plan(GrayU8 source, double degreesClockwise) {
        double normalized = degreesClockwise % 360.0;
        if (normalized < 0) {
            normalized += 360.0;
        }
        if (normalized < 0.01 || normalized > 359.99) {
            return null;
        }
        double radians = Math.toRadians(normalized);
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        int newWidth = (int) Math.ceil(source.width * Math.abs(cos) + source.height * Math.abs(sin));
        int newHeight = (int) Math.ceil(source.width * Math.abs(sin) + source.height * Math.abs(cos));
        newWidth = Math.max(1, newWidth);
        newHeight = Math.max(1, newHeight);
        return new Rotation(
                normalized,
                cos,
                sin,
                (source.width - 1) / 2.0,
                (source.height - 1) / 2.0,
                (newWidth - 1) / 2.0,
                (newHeight - 1) / 2.0,
                newWidth,
                newHeight
        );
    }

    /**
     * Every destination pixel is written. The formula matches {@link #sample}. Along a destination
     * row the source point moves monotonically, so the pixels inside the source form one run: it is
     * found per row, the pixels outside it are filled white, and those inside are sampled without
     * bounds checks.
     */
    private static void paint(GrayU8 source, GrayU8 dest, Rotation rotation) {
        byte[] out = dest.data;
        int destStride = dest.stride;
        int destBase = dest.startIndex;
        int width = dest.width;
        int height = dest.height;
        double limitX = source.width - 1;
        double limitY = source.height - 1;
        for (int y = 0; y < height; y++) {
            double dy = y - rotation.dstCy;
            double sinDy = rotation.sin * dy;
            double cosDy = rotation.cos * dy;
            int row = destBase + y * destStride;
            // The estimate from the bounds is a pixel short on each side; the exact test then settles the ends.
            int first = (int) Math.max(0, Math.ceil(rowBound(rotation, sinDy, cosDy, limitX, limitY, true)) + 1);
            int last = (int) Math.min(width - 1, Math.floor(rowBound(rotation, sinDy, cosDy, limitX, limitY, false)) - 1);
            while (first <= last && !inside(rotation, first, sinDy, cosDy, limitX, limitY)) {
                first++;
            }
            while (last >= first && !inside(rotation, last, sinDy, cosDy, limitX, limitY)) {
                last--;
            }
            if (first > last) {
                paintChecked(source, out, row, width, rotation, sinDy, cosDy);
                continue;
            }
            while (first > 0 && inside(rotation, first - 1, sinDy, cosDy, limitX, limitY)) {
                first--;
            }
            while (last < width - 1 && inside(rotation, last + 1, sinDy, cosDy, limitX, limitY)) {
                last++;
            }
            Arrays.fill(out, row, row + first, (byte) 255);
            paintInside(source, out, row, first, last + 1, rotation, sinDy, cosDy);
            Arrays.fill(out, row + last + 1, row + width, (byte) 255);
        }
    }

    /** About the lowest ({@code lower}) or the highest x of a destination row whose source point is inside. */
    private static double rowBound(Rotation rotation, double sinDy, double cosDy, double limitX, double limitY, boolean lower) {
        double bound = lower ? -1e9 : 1e9;
        bound = narrow(bound, rotation.cos, rotation.srcCx + sinDy, limitX, rotation.dstCx, lower);
        return narrow(bound, -rotation.sin, rotation.srcCy + cosDy, limitY, rotation.dstCx, lower);
    }

    /**
     * {@code bound} narrowed to the x where {@code offset + slope * (x - center)} lies in 0 to
     * {@code limit}. A flat line gives no bound; the exact test finds a row it keeps out.
     */
    private static double narrow(double bound, double slope, double offset, double limit, double center, boolean lower) {
        if (Math.abs(slope) < 1e-9) {
            return bound;
        }
        double a = center - offset / slope;
        double b = center + (limit - offset) / slope;
        return lower ? Math.max(bound, Math.min(a, b)) : Math.min(bound, Math.max(a, b));
    }

    private static boolean inside(Rotation rotation, int x, double sinDy, double cosDy, double limitX, double limitY) {
        double dx = x - rotation.dstCx;
        double sx = rotation.srcCx + rotation.cos * dx + sinDy;
        double sy = rotation.srcCy - rotation.sin * dx + cosDy;
        return sx >= 0 && sy >= 0 && sx <= limitX && sy <= limitY;
    }

    /** Pixels {@code from} to {@code to} of a row, all with their source point inside the source. */
    private static void paintInside(GrayU8 source, byte[] out, int row, int from, int to, Rotation rotation, double sinDy, double cosDy) {
        byte[] src = source.data;
        int sourceBase = source.startIndex;
        int sourceStride = source.stride;
        int limitX = source.width - 1;
        int limitY = source.height - 1;
        double srcCx = rotation.srcCx;
        double srcCy = rotation.srcCy;
        double dstCx = rotation.dstCx;
        double cos = rotation.cos;
        double sin = rotation.sin;
        for (int x = from; x < to; x++) {
            double dx = x - dstCx;
            double sx = srcCx + cos * dx + sinDy;
            double sy = srcCy - sin * dx + cosDy;
            // Neither is negative, so truncation is the floor.
            int x0 = (int) sx;
            int y0 = (int) sy;
            int x1 = Math.min(x0 + 1, limitX);
            int y1 = Math.min(y0 + 1, limitY);
            double fx = sx - x0;
            double fy = sy - y0;
            int row0 = sourceBase + y0 * sourceStride;
            int row1 = sourceBase + y1 * sourceStride;
            int p00 = src[row0 + x0] & 255;
            int p10 = src[row0 + x1] & 255;
            int p01 = src[row1 + x0] & 255;
            int p11 = src[row1 + x1] & 255;
            double sampled = p00 * (1.0 - fx) * (1.0 - fy)
                    + p10 * fx * (1.0 - fy)
                    + p01 * (1.0 - fx) * fy
                    + p11 * fx * fy;
            out[row + x] = (byte) GrayImages.clamp((int) Math.round(sampled));
        }
    }

    /** A row whose run inside the source the estimate missed, every pixel tested. */
    private static void paintChecked(GrayU8 source, byte[] out, int row, int width, Rotation rotation, double sinDy, double cosDy) {
        double limitX = source.width - 1;
        double limitY = source.height - 1;
        for (int x = 0; x < width; x++) {
            if (inside(rotation, x, sinDy, cosDy, limitX, limitY)) {
                paintInside(source, out, row, x, x + 1, rotation, sinDy, cosDy);
            } else {
                out[row + x] = (byte) 255;
            }
        }
    }

    private static final class Rotation {
        final double normalized;
        final double cos;
        final double sin;
        final double srcCx;
        final double srcCy;
        final double dstCx;
        final double dstCy;
        final int width;
        final int height;

        Rotation(
                double normalized,
                double cos,
                double sin,
                double srcCx,
                double srcCy,
                double dstCx,
                double dstCy,
                int width,
                int height
        ) {
            this.normalized = normalized;
            this.cos = cos;
            this.sin = sin;
            this.srcCx = srcCx;
            this.srcCy = srcCy;
            this.dstCx = dstCx;
            this.dstCy = dstCy;
            this.width = width;
            this.height = height;
        }
    }

    /**
     * Samples {@code source} so that the expanded quadrilateral lands on a square with a white quiet zone.
     * The returned map sends rectified pixels back through {@code toSource}.
     * Returns null when the quad cannot be deskewed.
     */
    /**
     * Paints {@code source} into {@code outputQuad} on a white canvas.
     * {@code outputQuad} is top-left, top-right, bottom-right, bottom-left in the destination.
     */
    static GrayU8 warpToQuad(GrayU8 source, int outWidth, int outHeight, ImagePoint[] outputQuad) {
        ImagePoint[] sourceCorners = new ImagePoint[]{
                new ImagePoint(0, 0),
                new ImagePoint(source.width - 1, 0),
                new ImagePoint(source.width - 1, source.height - 1),
                new ImagePoint(0, source.height - 1)
        };
        Homography outputToSource = Homography.mapping(outputQuad, sourceCorners);
        if (outputToSource == null) {
            throw new IllegalArgumentException("quadrilateral is degenerate");
        }
        GrayU8 dest = white(outWidth, outHeight);
        for (int y = 0; y < outHeight; y++) {
            for (int x = 0; x < outWidth; x++) {
                ImagePoint p = outputToSource.apply(x, y);
                dest.set(x, y, sample(source, p.x(), p.y()));
            }
        }
        return dest;
    }

    public static Transformed rectify(GrayU8 source, ImagePoint[] quad, PixelMap toSource, double expand) {
        if (quad == null || quad.length != 4) {
            return null;
        }
        ImagePoint[] expanded = expand(quad, expand);
        double edge = averageEdge(expanded);
        if (edge < 12.0) {
            return null;
        }
        int side = (int) Math.round(Math.max(edge, 240.0));
        if (side > 640) {
            side = 640;
        }
        ImagePoint[] output = new ImagePoint[]{
                new ImagePoint(0, 0),
                new ImagePoint(side - 1, 0),
                new ImagePoint(side - 1, side - 1),
                new ImagePoint(0, side - 1)
        };
        Homography outputToWork = Homography.mapping(output, expanded);
        if (outputToWork == null) {
            return null;
        }
        GrayU8 rectified = white(side, side);
        for (int y = 0; y < side; y++) {
            for (int x = 0; x < side; x++) {
                ImagePoint p = outputToWork.apply(x, y);
                rectified.set(x, y, sample(source, p.x(), p.y()));
            }
        }
        PixelMap map = (x, y) -> {
            ImagePoint inWork = outputToWork.apply(x, y);
            if (!Double.isFinite(inWork.x()) || !Double.isFinite(inWork.y())) {
                return inWork;
            }
            return toSource.toSource(inWork.x(), inWork.y());
        };
        return new Transformed(rectified, map, 0);
    }

    public static ImagePoint[] cornersOf(ImagePoint[] quad, PixelMap toSource) {
        ImagePoint[] points = new ImagePoint[quad.length];
        for (int i = 0; i < quad.length; i++) {
            points[i] = toSource.toSource(quad[i].x(), quad[i].y());
        }
        return points;
    }

    public static int orientationOfTopEdge(ImagePoint[] quad) {
        if (quad == null || quad.length < 2) {
            return -1;
        }
        ImagePoint a = quad[0];
        ImagePoint b = quad[1];
        int degrees = (int) Math.round(Math.toDegrees(Math.atan2(b.y() - a.y(), b.x() - a.x())));
        return Math.floorMod(degrees, 360);
    }

    private static ImagePoint[] expand(ImagePoint[] quad, double factor) {
        double cx = 0.0;
        double cy = 0.0;
        int n = quad.length;
        for (int i = 0; i < n; i++) {
            cx += quad[i].x();
            cy += quad[i].y();
        }
        cx /= n;
        cy /= n;
        ImagePoint[] points = new ImagePoint[n];
        for (int i = 0; i < n; i++) {
            ImagePoint p = quad[i];
            points[i] = new ImagePoint(cx + (p.x() - cx) * factor, cy + (p.y() - cy) * factor);
        }
        return points;
    }

    private static double averageEdge(ImagePoint[] quad) {
        double sum = 0.0;
        for (int i = 0; i < quad.length; i++) {
            ImagePoint a = quad[i];
            ImagePoint b = quad[(i + 1) % quad.length];
            double dx = a.x() - b.x();
            double dy = a.y() - b.y();
            sum += Math.hypot(dx, dy);
        }
        return sum / quad.length;
    }

    private static int sample(GrayU8 source, double x, double y) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || x < 0 || y < 0 || x > source.width - 1 || y > source.height - 1) {
            return 255;
        }
        int x0 = (int) Math.floor(x);
        int y0 = (int) Math.floor(y);
        int x1 = Math.min(x0 + 1, source.width - 1);
        int y1 = Math.min(y0 + 1, source.height - 1);
        double fx = x - x0;
        double fy = y - y0;
        double value = source.get(x0, y0) * (1.0 - fx) * (1.0 - fy)
                + source.get(x1, y0) * fx * (1.0 - fy)
                + source.get(x0, y1) * (1.0 - fx) * fy
                + source.get(x1, y1) * fx * fy;
        return GrayImages.clamp((int) Math.round(value));
    }

    private static GrayU8 white(int width, int height) {
        GrayU8 image = new GrayU8(width, height);
        Arrays.fill(image.data, (byte) 255);
        return image;
    }
}
