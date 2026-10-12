package org.krzywanski.barcode.locate.dm;

import org.krzywanski.barcode.image.GrayU8;
import org.krzywanski.barcode.ImagePoint;
import org.krzywanski.barcode.locate.dm.DmGeometry.Bits;
import org.krzywanski.barcode.locate.dm.DmGeometry.P;
import org.krzywanski.barcode.locate.dm.DmGeometry.Warp;
import org.krzywanski.barcode.locate.dm.MicroQrDecoder.Format;
import org.krzywanski.barcode.locate.dm.QrConcentric.Ring;

import java.util.ArrayList;
import java.util.List;

/**
 * Micro QR sampler ported from zxing-cpp 3.1.1 {@code QRDetector.cpp} {@code SampleMQR}.
 *
 * <p>The image is binarized with the zxing-cpp local-average matrix binarizer.
 * Finder patterns are the same centers the QR reader uses. Each center is sampled
 * on its own and decoded by {@link MicroQrDecoder}. {@code DetectPureMQR} is not
 * used. rMQR is not in this slice.
 *
 * <p>zxing-cpp skips a finder that already decoded as a QR symbol. This attempt
 * does not see that set. The quiet-zone check below is what rejects a corner of
 * an ordinary QR symbol.
 */
public final class MicroQrDetector {
    /** Format-info samples, including the three timing checks at 0, 8, and 16. */
    private static final int[][] FORMAT = {
            {0, 8}, {1, 8}, {2, 8}, {3, 8}, {4, 8}, {5, 8}, {6, 8}, {7, 8}, {8, 8},
            {8, 7}, {8, 6}, {8, 5}, {8, 4}, {8, 3}, {8, 2}, {8, 1}, {8, 0}
    };

    private MicroQrDetector() {
    }

    /** One decoded symbol. Corners are TL, TR, BR, BL in the gray image. */
    public record Found(String text, List<ImagePoint> corners, int orientationDegrees) {
    }

    /**
     * Binarize {@code gray} and run the sampler.
     * An empty list means no Micro QR decoded from this image.
     */
    public static List<Found> read(GrayU8 gray) {
        if (gray.width < 11 || gray.height < 11) {
            return List.of();
        }
        return read(DmBinarizer.blackMatrix(gray));
    }

    /** Sampler on an existing matrix. {@code Scan} calls {@link #read(GrayU8)} once. */
    static List<Found> read(Bits bits) {
        if (bits == null || bits.width < 11 || bits.height < 11) {
            return List.of();
        }
        List<Found> found = new ArrayList<>();
        for (Ring pattern : QrDetector.findFinderPatterns(bits, true)) {
            Found hit = readPattern(bits, pattern);
            if (hit != null) {
                found.add(hit);
            }
        }
        return found;
    }

    private static Found readPattern(Bits image, Ring pattern) {
        P[] quad = QrConcentric.corners(image, pattern.x, pattern.y, (int) pattern.size, 2);
        if (quad == null) {
            return null;
        }
        P[] source = {
                new P(0.5, 0.5),
                new P(6.5, 0.5),
                new P(6.5, 6.5),
                new P(0.5, 6.5)
        };
        Format best = new Format();
        Warp bestWarp = null;
        for (int rotation = 0; rotation < 4; rotation++) {
            Warp warp = Warp.between(source, rotated(quad, rotation));
            if (!warp.valid()) {
                continue;
            }
            if (!timing(image, warp, 0, true)
                    || !timing(image, warp, 8, false)
                    || !timing(image, warp, 16, true)) {
                continue;
            }
            int raw = 0;
            for (int i = 1; i <= 15; i++) {
                P point = warp.apply(P.centered(FORMAT[i][0], FORMAT[i][1]));
                raw = (raw << 1) | (blackAt(image, point) ? 1 : 0);
            }
            Format candidate = MicroQrDecoder.decodeFormat(raw);
            if (candidate.hamming < best.hamming) {
                best = candidate;
                bestWarp = warp;
            }
        }
        if (!best.valid() || bestWarp == null) {
            return null;
        }
        int dimension = 9 + 2 * best.version;
        int blackPairs = 0;
        for (int i = 0; i < dimension; i++) {
            // Both samples black. SampleMQR's comment says one third; the test is this one.
            if (blackAt(image, bestWarp.apply(P.centered(i, dimension)))
                    && blackAt(image, bestWarp.apply(P.centered(dimension, i)))) {
                blackPairs++;
            }
        }
        if (blackPairs > 2 * dimension / 3) {
            return null;
        }
        Grid grid = sample(image, dimension, bestWarp);
        if (grid == null) {
            return null;
        }
        String text = MicroQrDecoder.decode(grid.bits);
        if (text == null) {
            return null;
        }
        List<ImagePoint> corners = new ArrayList<>(4);
        for (P corner : grid.corners) {
            if (!Double.isFinite(corner.x) || !Double.isFinite(corner.y)) {
                return null;
            }
            corners.add(new ImagePoint(corner.x, corner.y));
        }
        return new Found(text, List.copyOf(corners), orientation(grid.corners[0], grid.corners[1]));
    }

    private static boolean timing(Bits image, Warp warp, int index, boolean requireBlack) {
        P point = warp.apply(P.centered(FORMAT[index][0], FORMAT[index][1]));
        if (!image.isIn(point)) {
            return false;
        }
        return !requireBlack || image.get(point);
    }

    private static boolean blackAt(Bits image, P point) {
        return image.isIn(point) && image.get(point);
    }

    private static P[] rotated(P[] quad, int turns) {
        int shift = Math.floorMod(turns, 4);
        P[] turned = new P[4];
        for (int i = 0; i < 4; i++) {
            turned[i] = quad[(i + shift) % 4];
        }
        return turned;
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

    private static int orientation(P a, P b) {
        int degrees = (int) Math.round(Math.toDegrees(Math.atan2(b.y - a.y, b.x - a.x)));
        return Math.floorMod(degrees, 360);
    }

    private record Grid(Bits bits, P[] corners) {
    }
}
