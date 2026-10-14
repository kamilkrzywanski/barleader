package io.github.kamilkrzywanski.barcode;

import io.github.kamilkrzywanski.barcode.image.Geometry;
import io.github.kamilkrzywanski.barcode.image.GrayImages;
import io.github.kamilkrzywanski.barcode.image.GrayU8;
import io.github.kamilkrzywanski.barcode.image.PixelMap;
import io.github.kamilkrzywanski.barcode.image.Transformed;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Aggressive-only locator for one linear symbol that is not horizontal in the frame.
 * The search image is a short-side stretch of a ~420 px view. The crop is sampled from the
 * original so the decoder still sees the modules. Density bands are tried before a second
 * angle and before pitch-ranked bands: pitch alone locks onto text and drops a level code.
 */
final class LinearBand {
    private static final int EDGE = 14;
    private static final int DENSITY_BANDS = 2;
    private static final int SECOND_ANGLE_BANDS = 1;
    private static final int PITCH_BANDS = 6;
    private static final int MAX_CROPS = 9;
    /** A residual above this follows a text line and rotates the bars back into the print. */
    private static final double MAX_RESIDUAL_DEGREES = 8.0;

    private LinearBand() {
    }

    static List<Crop> find(GrayU8 gray, Fanout fanout) {
        int factor = Math.max(1, Math.max(gray.width, gray.height) / 420);
        if (gray.width / factor < 1 || gray.height / factor < 1) {
            return List.of();
        }
        GrayU8 low = factor == 1 ? gray : areaDown(gray, factor);
        if (low.width < 40 || low.height < 32) {
            return List.of();
        }
        GrayU8 view = GrayImages.stretch(low);
        // Density and pitch rank the same runs. Angles do not depend on each other.
        // Scores are merged in sweep order, so a tie stays with the earlier angle.
        int[] sweep = new int[64];
        int sweepCount = 0;
        for (int degrees = -90; degrees < 90; degrees += 3) {
            sweep[sweepCount++] = degrees;
        }
        Band[] densityBands = new Band[sweepCount];
        Band[] pitchBands = new Band[sweepCount];
        List<Runnable> tasks = new ArrayList<>(sweepCount);
        for (int i = 0; i < sweepCount; i++) {
            int index = i;
            tasks.add(() -> {
                GrayU8 rotated = Geometry.rotatePixels(view, sweep[index]);
                List<Band> runs = candidates(rotated);
                densityBands[index] = top(runs, false);
                pitchBands[index] = top(runs, true);
            });
        }
        fanout.runAll(tasks);
        double[] angles = new double[64];
        int[] densityScores = new int[64];
        int[] pitchScores = new int[64];
        int count = 0;
        for (int i = 0; i < sweepCount; i++) {
            Band byDensity = densityBands[i];
            Band byPitch = pitchBands[i];
            if (byDensity == null && byPitch == null) {
                continue;
            }
            angles[count] = sweep[i];
            densityScores[count] = byDensity == null ? -1 : byDensity.score;
            pitchScores[count] = byPitch == null ? -1 : byPitch.pitchScore;
            count++;
        }
        if (count == 0) {
            return List.of();
        }
        int winner = indexOfMax(densityScores, count);
        int pitchWinner = indexOfMax(pitchScores, count);
        double bestAngle = refine(view, angles[winner], false, fanout);
        double pitchAngle = refine(view, angles[pitchWinner], true, fanout);
        int second = -1;
        for (int i = 0; i < count; i++) {
            if (densityScores[i] < 0 || angleDistance(angles[i], bestAngle) < 12.0) {
                continue;
            }
            if (second < 0 || densityScores[i] > densityScores[second]) {
                second = i;
            }
        }
        List<Crop> crops = new ArrayList<>();
        addAtAngle(crops, gray, view, factor, bestAngle, DENSITY_BANDS, false);
        if (second >= 0) {
            addAtAngle(crops, gray, view, factor, angles[second], SECOND_ANGLE_BANDS, false);
        }
        addAtAngle(crops, gray, view, factor, pitchAngle, PITCH_BANDS, true);
        return List.copyOf(crops);
    }

    /**
     * The densest module window, when the band has glued the bars to nearby print.
     * Returns null when the window is not clearly tighter than the crop.
     */
    static Crop tighten(Crop crop) {
        GrayU8 gray = crop.image();
        if (gray.width < 160 || gray.height < 16) {
            return null;
        }
        int window = Math.min(360, gray.width / 2);
        if (window < 80) {
            return null;
        }
        int best = -1;
        int bestX = 0;
        int y0 = Math.max(0, gray.height / 5);
        int y1 = Math.max(y0 + 1, gray.height * 4 / 5);
        for (int y = y0; y < y1; y += 4) {
            for (int x = 0; x + window < gray.width; x += 8) {
                int changes = rowChanges(gray, x, x + window, y);
                if (changes > best) {
                    best = changes;
                    bestX = x;
                }
            }
        }
        if (best < 28) {
            return null;
        }
        int x1 = Math.min(gray.width, bestX + window + 80);
        if (x1 - bestX > gray.width * 0.85) {
            return null;
        }
        Transformed cut = Geometry.crop(gray, bestX, 0, x1 - bestX, gray.height);
        return new Crop(cut.image(), cut.toSource().before(crop.toSource()), crop.degrees());
    }

    private static int rowChanges(GrayU8 gray, int x0, int x1, int y) {
        int count = 0;
        int previous = 0;
        for (int x = x0 + 1; x < x1; x++) {
            int delta = gray.get(x, y) - gray.get(x - 1, y);
            int absolute = delta < 0 ? -delta : delta;
            if (absolute < 18) {
                continue;
            }
            int sign = delta > 0 ? 1 : -1;
            if (previous != 0 && sign != previous) {
                count++;
            }
            previous = sign;
        }
        return count;
    }

    private static int indexOfMax(int[] scores, int count) {
        int winner = 0;
        for (int i = 1; i < count; i++) {
            if (scores[i] > scores[winner]) {
                winner = i;
            }
        }
        return winner;
    }

    private static double refine(GrayU8 view, double angle, boolean pitch, Fanout fanout) {
        int rounded = (int) Math.round(angle);
        int offsets = 9;
        int[] scores = new int[offsets];
        Arrays.fill(scores, -1);
        List<Runnable> tasks = new ArrayList<>(offsets);
        for (int i = 0; i < offsets; i++) {
            int index = i;
            int degrees = rounded - 4 + i;
            tasks.add(() -> {
                Band band = topBand(Geometry.rotatePixels(view, degrees), pitch);
                scores[index] = band == null ? -1 : active(band, pitch);
            });
        }
        fanout.runAll(tasks);
        // A complete miss keeps the unrounded angle. A tie keeps the lower offset.
        double bestAngle = angle;
        int bestScore = -1;
        for (int i = 0; i < offsets; i++) {
            if (scores[i] > bestScore) {
                bestScore = scores[i];
                bestAngle = rounded - 4 + i;
            }
        }
        return bestAngle;
    }

    private static int active(Band band, boolean pitch) {
        return pitch ? band.pitchScore : band.score;
    }

    /** Up to {@code limit} y-separated bands. A parallel text line must not hide the bars. */
    private static void addAtAngle(
            List<Crop> crops,
            GrayU8 gray,
            GrayU8 view,
            int factor,
            double angle,
            int limit,
            boolean pitch
    ) {
        if (crops.size() >= MAX_CROPS) {
            return;
        }
        Transformed rotated = Geometry.rotate(view, angle);
        List<Band> bands = rankBands(rotated.image(), limit, pitch);
        if (bands.isEmpty()) {
            return;
        }
        double used = angle;
        double residual = gradientAngle(rotated.image(), bands.get(0));
        if (Math.abs(residual) >= 1.5 && Math.abs(residual) <= MAX_RESIDUAL_DEGREES) {
            double turned = angle - residual;
            Transformed again = Geometry.rotate(view, turned);
            List<Band> againBands = rankBands(again.image(), limit, pitch);
            if (!againBands.isEmpty()
                    && active(againBands.get(0), pitch) >= active(bands.get(0), pitch) * 0.8) {
                rotated = again;
                bands = againBands;
                used = turned;
            }
        }
        int take = Math.min(limit, Math.min(bands.size(), MAX_CROPS - crops.size()));
        int degrees = clockwiseDegrees(used);
        for (int i = 0; i < take; i++) {
            Crop crop = warp(gray, factor, rotated, pad(rotated.image(), bands.get(i)), degrees);
            if (crop != null) {
                crops.add(crop);
            }
        }
    }

    private static int clockwiseDegrees(double angle) {
        double normalized = angle % 360.0;
        if (normalized < 0) {
            normalized += 360.0;
        }
        int rounded = (int) Math.round(normalized);
        return rounded == 360 ? 0 : rounded;
    }

    /** Bars have no direction, so 0° and 180° are the same strip. */
    private static double angleDistance(double left, double right) {
        double distance = Math.abs(left - right) % 180.0;
        return distance > 90.0 ? 180.0 - distance : distance;
    }

    private static Band topBand(GrayU8 image, boolean pitch) {
        return top(candidates(image), pitch);
    }

    /** Highest run. Ties keep the earlier candidate, matching a stable sort of that list. */
    private static Band top(List<Band> runs, boolean pitch) {
        List<Band> picked = pick(runs, 1, pitch);
        return picked.isEmpty() ? null : picked.get(0);
    }

    private static List<Band> rankBands(GrayU8 image, int limit, boolean pitch) {
        return pick(candidates(image), limit, pitch);
    }

    private static List<Band> candidates(GrayU8 image) {
        int width = image.width;
        int height = image.height;
        Scratch scratch = SCRATCH.get();
        int changeNeed = Math.multiplyExact(height, width);
        int prefixStride = width + 1;
        int prefixNeed = Math.multiplyExact(height, prefixStride);
        if (scratch.change.length < changeNeed) {
            scratch.change = new byte[changeNeed];
        } else {
            Arrays.fill(scratch.change, 0, changeNeed, (byte) 0);
        }
        if (scratch.prefix.length < prefixNeed) {
            scratch.prefix = new int[prefixNeed];
        } else {
            Arrays.fill(scratch.prefix, 0, prefixNeed, 0);
        }
        if (scratch.column.length != width) {
            scratch.column = new int[width];
        }
        byte[] change = scratch.change;
        int[] prefix = scratch.prefix;
        for (int y = 0; y < height; y++) {
            int row = image.startIndex + y * image.stride;
            int previous = 0;
            int count = 0;
            int changeRow = y * width;
            int prefixRow = y * prefixStride;
            for (int x = 1; x < width; x++) {
                int delta = (image.data[row + x] & 255) - (image.data[row + x - 1] & 255);
                int absolute = delta < 0 ? -delta : delta;
                if (absolute < EDGE) {
                    prefix[prefixRow + x + 1] = count;
                    continue;
                }
                int sign = delta > 0 ? 1 : -1;
                if (previous != 0 && sign != previous) {
                    change[changeRow + x] = 1;
                    count++;
                }
                previous = sign;
                prefix[prefixRow + x + 1] = count;
            }
        }
        List<Band> all = new ArrayList<>();
        int[] column = scratch.column;
        for (int bandHeight : new int[]{8, 12, 18, 28, 40}) {
            if (bandHeight >= height * 0.75) {
                continue;
            }
            Arrays.fill(column, 0);
            for (int y = 0; y < bandHeight; y++) {
                addRow(column, change, y * width);
            }
            addBand(all, consider(image, prefix, prefixStride, column, 0, bandHeight));
            for (int y0 = 0; y0 + bandHeight < height; y0++) {
                subRow(column, change, y0 * width);
                addRow(column, change, (y0 + bandHeight) * width);
                if ((y0 & 1) == 1) {
                    continue;
                }
                addBand(all, consider(image, prefix, prefixStride, column, y0 + 1, bandHeight));
            }
        }
        return all;
    }

    private static List<Band> pick(List<Band> runs, int limit, boolean pitch) {
        List<Band> sorted = new ArrayList<>(runs);
        sorted.sort((left, right) -> Integer.compare(active(right, pitch), active(left, pitch)));
        List<Band> picked = new ArrayList<>();
        for (Band candidate : sorted) {
            boolean overlaps = false;
            for (Band kept : picked) {
                if (overlapsY(candidate, kept)) {
                    overlaps = true;
                    break;
                }
            }
            if (overlaps) {
                continue;
            }
            picked.add(candidate);
            if (picked.size() == limit) {
                break;
            }
        }
        return picked;
    }

    private static void addBand(List<Band> all, Band band) {
        if (band != null) {
            all.add(band);
        }
    }

    /** A few rows of gap still belong to the same strip. The next band has to clear it. */
    private static boolean overlapsY(Band left, Band right) {
        int separation = Math.max(left.y0, right.y0) - Math.min(left.y1, right.y1);
        int allowance = Math.max(4, Math.max(left.y1 - left.y0, right.y1 - right.y0) / 4);
        return separation < allowance;
    }

    /**
     * Inside one window the run is chosen by density, even when the list is later sorted by pitch.
     * Ranking the run itself by pitch moves the barcode window onto text.
     */
    private static Band better(Band current, Band candidate) {
        if (candidate == null) {
            return current;
        }
        if (current == null || candidate.score > current.score) {
            return candidate;
        }
        return current;
    }

    private static void addRow(int[] column, byte[] change, int offset) {
        for (int x = 0; x < column.length; x++) {
            column[x] += change[offset + x];
        }
    }

    private static void subRow(int[] column, byte[] change, int offset) {
        for (int x = 0; x < column.length; x++) {
            column[x] -= change[offset + x];
        }
    }

    private static Band consider(
            GrayU8 image,
            int[] prefix,
            int prefixStride,
            int[] column,
            int y0,
            int bandHeight
    ) {
        int width = column.length;
        int max = 0;
        for (int value : column) {
            if (value > max) {
                max = value;
            }
        }
        if (max < 3) {
            return null;
        }
        int gate = Math.max(2, max / 5);
        int gapLimit = Math.max(6, width / 18);
        Band best = null;
        int runX0 = -1;
        int gap = 0;
        int runChanges = 0;
        for (int x = 1; x <= width; x++) {
            boolean hot = x < width && column[x] >= gate;
            if (hot) {
                if (runX0 < 0) {
                    runX0 = x;
                    runChanges = 0;
                }
                runChanges += column[x];
                gap = 0;
            } else if (runX0 >= 0) {
                gap++;
                if (x == width || gap > gapLimit) {
                    int x1 = x - gap;
                    double gapCv = spacingCv(column, runX0, x1);
                    best = better(best, scoreRun(
                            image, prefix, prefixStride, y0, bandHeight, runX0, x1, runChanges, gapCv));
                    runX0 = -1;
                    gap = 0;
                }
            }
        }
        return best;
    }

    private static Band scoreRun(
            GrayU8 image,
            int[] prefix,
            int prefixStride,
            int y0,
            int bandHeight,
            int x0,
            int x1,
            int runChanges,
            double gapCv
    ) {
        int y1 = y0 + bandHeight;
        int bandWidth = x1 - x0;
        if (bandWidth < 28 || bandWidth < bandHeight) {
            return null;
        }
        int changes = 0;
        int bestRow = y0;
        int bestRowChanges = -1;
        int last = prefixStride - 1;
        for (int y = y0; y < y1; y++) {
            int row = y * prefixStride;
            int end = Math.min(last, x1);
            int start = Math.min(last, x0);
            int rowChanges = prefix[row + end] - prefix[row + start];
            changes += rowChanges;
            if (rowChanges > bestRowChanges) {
                bestRowChanges = rowChanges;
                bestRow = y;
            }
        }
        int mean = changes / bandHeight;
        if (runChanges < mean || mean < 16 || bestRowChanges < 18) {
            return null;
        }
        double regularity = regularity(image, bestRow, x0, x1);
        if (regularity < 0.62) {
            return null;
        }
        double density = mean / (double) bandWidth;
        int rows = Math.min(bandHeight, 32);
        int densityScore = (int) Math.round(density * 10000.0 * rows * regularity);
        int pitchScore = (int) Math.round(densityScore / (0.35 + gapCv));
        return new Band(y0, y1, x0, x1, densityScore, pitchScore);
    }

    /** Coefficient of variation of distances between column-edge peaks. Fewer than eight gaps is flat. */
    private static double spacingCv(int[] column, int x0, int x1) {
        int from = Math.max(1, x0);
        int to = Math.min(column.length - 1, x1);
        int max = 0;
        for (int x = from; x < to; x++) {
            if (column[x] > max) {
                max = column[x];
            }
        }
        int threshold = Math.max(2, max / 5);
        int peaks = 0;
        int previous = -1;
        double sum = 0;
        double square = 0;
        for (int x = from; x < to; x++) {
            if (column[x] >= column[x - 1] && column[x] > column[x + 1] && column[x] >= threshold) {
                if (previous >= 0) {
                    double distance = x - previous;
                    sum += distance;
                    square += distance * distance;
                }
                previous = x;
                peaks++;
            }
        }
        int gaps = peaks - 1;
        if (gaps < 8 || sum <= 0) {
            return 1.0;
        }
        double mean = sum / gaps;
        double variance = square / gaps - mean * mean;
        return Math.sqrt(Math.max(0.0, variance)) / mean;
    }

    /** Share of bar-to-bar gaps that fit in four modules. Text spaces fall outside that. */
    private static double regularity(GrayU8 image, int y, int x0, int x1) {
        int row = image.startIndex + y * image.stride;
        int[] runs = new int[96];
        int count = 0;
        int last = x0;
        int previous = 0;
        for (int x = x0 + 1; x < x1; x++) {
            int delta = (image.data[row + x] & 255) - (image.data[row + x - 1] & 255);
            int absolute = delta < 0 ? -delta : delta;
            if (absolute < EDGE) {
                continue;
            }
            int sign = delta > 0 ? 1 : -1;
            if (previous != 0 && sign != previous) {
                if (count < runs.length) {
                    runs[count] = x - last;
                }
                count++;
                last = x;
            }
            previous = sign;
        }
        if (count < 16) {
            return 0;
        }
        int used = Math.min(count, runs.length);
        int[] sorted = Arrays.copyOf(runs, used);
        Arrays.sort(sorted);
        int median = Math.max(1, sorted[used / 2]);
        int limit = median * 4 + 2;
        int ok = 0;
        for (int i = 0; i < used; i++) {
            if (runs[i] <= limit) {
                ok++;
            }
        }
        return ok / (double) used;
    }

    private static Rect pad(GrayU8 image, Band band) {
        int bandWidth = band.x1 - band.x0;
        int bandHeight = band.y1 - band.y0;
        int padX = Math.max(6, (int) Math.round(bandWidth * 0.16));
        int padY = Math.max(6, (int) Math.round(bandHeight * 0.45));
        return new Rect(
                Math.max(0, band.x0 - padX),
                Math.max(0, band.y0 - padY),
                Math.min(image.width, band.x1 + padX),
                Math.min(image.height, band.y1 + padY)
        );
    }

    private static double gradientAngle(GrayU8 image, Band band) {
        double gxx = 0;
        double gyy = 0;
        double gxy = 0;
        int y0 = Math.max(1, band.y0);
        int y1 = Math.min(image.height - 1, band.y1);
        int x0 = Math.max(1, band.x0);
        int x1 = Math.min(image.width - 1, band.x1);
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int gx = image.get(x + 1, y) - image.get(x - 1, y);
                int gy = image.get(x, y + 1) - image.get(x, y - 1);
                gxx += gx * (double) gx;
                gyy += gy * (double) gy;
                gxy += gx * (double) gy;
            }
        }
        return Math.toDegrees(0.5 * Math.atan2(2 * gxy, gxx - gyy));
    }

    private static Crop warp(GrayU8 source, int factor, Transformed rotated, Rect rect, int degrees) {
        double midY = (rect.y0 + rect.y1) / 2.0;
        double midX = (rect.x0 + rect.x1) / 2.0;
        ImagePoint left = sourcePoint(rotated, rect.x0, midY, factor);
        ImagePoint right = sourcePoint(rotated, rect.x1, midY, factor);
        ImagePoint top = sourcePoint(rotated, midX, rect.y0, factor);
        ImagePoint bottom = sourcePoint(rotated, midX, rect.y1, factor);
        int outWidth = (int) Math.round(Math.hypot(right.x() - left.x(), right.y() - left.y()));
        int outHeight = (int) Math.round(Math.hypot(bottom.x() - top.x(), bottom.y() - top.y()));
        if (outWidth < 24 || outHeight < 12) {
            return null;
        }
        if (outWidth > 1800) {
            outHeight = Math.max(12, outHeight * 1800 / outWidth);
            outWidth = 1800;
        }
        if (outHeight > 900) {
            outWidth = Math.max(24, outWidth * 900 / outHeight);
            outHeight = 900;
        }
        double spanX = rect.x1 - rect.x0;
        double spanY = rect.y1 - rect.y0;
        double originX = rect.x0;
        double originY = rect.y0;
        int width = outWidth;
        int height = outHeight;
        GrayU8 dest = new GrayU8(width, height);
        PixelMap toLow = rotated.toSource();
        for (int y = 0; y < height; y++) {
            double lowY = originY + (y + 0.5) * spanY / height - 0.5;
            for (int x = 0; x < width; x++) {
                double lowX = originX + (x + 0.5) * spanX / width - 0.5;
                ImagePoint low = toLow.toSource(lowX, lowY);
                dest.set(x, y, bilinear(source, (low.x() + 0.5) * factor - 0.5, (low.y() + 0.5) * factor - 0.5));
            }
        }
        PixelMap warpMap = (x, y) -> {
            double lowX = originX + (x + 0.5) * spanX / width - 0.5;
            double lowY = originY + (y + 0.5) * spanY / height - 0.5;
            ImagePoint low = toLow.toSource(lowX, lowY);
            return new ImagePoint((low.x() + 0.5) * factor - 0.5, (low.y() + 0.5) * factor - 0.5);
        };
        Transformed padded = Geometry.pad(dest, 18);
        return new Crop(padded.image(), padded.toSource().before(warpMap), degrees);
    }

    private static ImagePoint sourcePoint(Transformed rotated, double x, double y, int factor) {
        ImagePoint low = rotated.toSource().toSource(x, y);
        return new ImagePoint((low.x() + 0.5) * factor - 0.5, (low.y() + 0.5) * factor - 0.5);
    }

    private static int bilinear(GrayU8 source, double x, double y) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || x < 0 || y < 0
                || x > source.width - 1 || y > source.height - 1) {
            return 255;
        }
        int x0 = (int) Math.floor(x);
        int y0 = (int) Math.floor(y);
        int x1 = Math.min(x0 + 1, source.width - 1);
        int y1 = Math.min(y0 + 1, source.height - 1);
        double fractionX = x - x0;
        double fractionY = y - y0;
        double value = source.get(x0, y0) * (1 - fractionX) * (1 - fractionY)
                + source.get(x1, y0) * fractionX * (1 - fractionY)
                + source.get(x0, y1) * (1 - fractionX) * fractionY
                + source.get(x1, y1) * fractionX * fractionY;
        return Math.max(0, Math.min(255, (int) Math.round(value)));
    }

    private static GrayU8 areaDown(GrayU8 source, int factor) {
        int width = source.width / factor;
        int height = source.height / factor;
        GrayU8 dest = new GrayU8(width, height);
        int area = factor * factor;
        int bias = area / 2;
        for (int y = 0; y < height; y++) {
            int originY = y * factor;
            for (int x = 0; x < width; x++) {
                int sum = bias;
                int originX = x * factor;
                for (int tileY = 0; tileY < factor; tileY++) {
                    int row = source.startIndex + (originY + tileY) * source.stride + originX;
                    for (int tileX = 0; tileX < factor; tileX++) {
                        sum += source.data[row + tileX] & 255;
                    }
                }
                dest.set(x, y, sum / area);
            }
        }
        return dest;
    }

    static final class Crop {
        private final GrayU8 image;
        private final PixelMap toSource;
        private final int degrees;

        Crop(GrayU8 image, PixelMap toSource, int degrees) {
            this.image = image;
            this.toSource = toSource;
            this.degrees = degrees;
        }

        GrayU8 image() {
            return image;
        }

        PixelMap toSource() {
            return toSource;
        }

        int degrees() {
            return degrees;
        }
    }

    /** Per-thread edge buffers. {@link #candidates} does not call itself. */
    private static final class Scratch {
        byte[] change = new byte[0];
        int[] prefix = new int[0];
        int[] column = new int[0];
    }

    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    private static final class Band {
        final int y0;
        final int y1;
        final int x0;
        final int x1;
        final int score;
        final int pitchScore;

        Band(int y0, int y1, int x0, int x1, int score, int pitchScore) {
            this.y0 = y0;
            this.y1 = y1;
            this.x0 = x0;
            this.x1 = x1;
            this.score = score;
            this.pitchScore = pitchScore;
        }
    }

    private static final class Rect {
        final int x0;
        final int y0;
        final int x1;
        final int y1;

        Rect(int x0, int y0, int x1, int y1) {
            this.x0 = x0;
            this.y0 = y0;
            this.x1 = x1;
            this.y1 = y1;
        }
    }
}
