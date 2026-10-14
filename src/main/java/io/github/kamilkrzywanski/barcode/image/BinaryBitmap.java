package io.github.kamilkrzywanski.barcode.image;

/**
 * One luminance image and its binarization, for one attempt and one thread. A row for the linear
 * readers is always thresholded on its own histogram and sharpened; the matrix for the 2D readers
 * comes from the hybrid local threshold, or from one global histogram.
 *
 * <p>The thresholds are those of ZXing's {@code GlobalHistogramBinarizer} and
 * {@code HybridBinarizer} (Apache License 2.0, the ZXing authors), bit for bit, which zxing-cpp
 * ports as well. Where ZXing throws {@code NotFoundException} for too little contrast, this
 * returns null.
 */
public final class BinaryBitmap {
    private static final int LUMINANCE_BITS = 5;
    private static final int LUMINANCE_SHIFT = 8 - LUMINANCE_BITS;
    private static final int LUMINANCE_BUCKETS = 1 << LUMINANCE_BITS;

    private static final int BLOCK_SIZE_POWER = 3;
    private static final int BLOCK_SIZE = 1 << BLOCK_SIZE_POWER;
    private static final int BLOCK_SIZE_MASK = BLOCK_SIZE - 1;
    private static final int MINIMUM_DIMENSION = BLOCK_SIZE * 5;
    private static final int MIN_DYNAMIC_RANGE = 24;

    private final GrayLuminanceSource source;
    private final boolean hybrid;
    private final int[] buckets = new int[LUMINANCE_BUCKETS];
    private byte[] luminances = new byte[0];
    private BlackMatrix matrix;
    private boolean matrixDone;

    private BinaryBitmap(GrayLuminanceSource source, boolean hybrid) {
        this.source = source;
        this.hybrid = hybrid;
    }

    /** Local thresholds for the matrix, as ZXing's {@code HybridBinarizer}. */
    public static BinaryBitmap hybrid(GrayLuminanceSource source) {
        return new BinaryBitmap(source, true);
    }

    /** One threshold for the whole matrix, as ZXing's {@code GlobalHistogramBinarizer}. */
    public static BinaryBitmap global(GrayLuminanceSource source) {
        return new BinaryBitmap(source, false);
    }

    public int getWidth() {
        return source.getWidth();
    }

    public int getHeight() {
        return source.getHeight();
    }

    /**
     * Row {@code y}, thresholded on its own histogram after a -1 4 -1 sharpening; the first and
     * last pixel stay white. {@code reuse} is filled when it has the right length. Null when the row
     * has too little contrast.
     */
    public boolean[] getBlackRow(int y, boolean[] reuse) {
        int width = source.getWidth();
        boolean[] row = reuse != null && reuse.length == width ? reuse : new boolean[width];
        java.util.Arrays.fill(row, false);
        initArrays(width);
        byte[] local = source.getRow(y, luminances);
        int[] localBuckets = buckets;
        for (int x = 0; x < width; x++) {
            localBuckets[(local[x] & 0xff) >> LUMINANCE_SHIFT]++;
        }
        int blackPoint = estimateBlackPoint(localBuckets);
        if (blackPoint < 0) {
            return null;
        }
        if (width < 3) {
            for (int x = 0; x < width; x++) {
                if ((local[x] & 0xff) < blackPoint) {
                    row[x] = true;
                }
            }
        } else {
            int left = local[0] & 0xff;
            int center = local[1] & 0xff;
            for (int x = 1; x < width - 1; x++) {
                int right = local[x + 1] & 0xff;
                if (((center * 4) - left - right) / 2 < blackPoint) {
                    row[x] = true;
                }
                left = center;
                center = right;
            }
        }
        return row;
    }

    /** The binarized image, computed once. Null when a global threshold finds too little contrast. */
    public BlackMatrix getBlackMatrix() {
        if (!matrixDone) {
            int width = source.getWidth();
            int height = source.getHeight();
            matrix = hybrid && width >= MINIMUM_DIMENSION && height >= MINIMUM_DIMENSION ? hybridMatrix() : globalMatrix();
            matrixDone = true;
        }
        return matrix;
    }

    private BlackMatrix globalMatrix() {
        int width = source.getWidth();
        int height = source.getHeight();
        // A histogram of four rows, as ZXing samples it; the whole image is read only when it works.
        initArrays(width);
        int[] localBuckets = buckets;
        for (int y = 1; y < 5; y++) {
            int row = height * y / 5;
            byte[] local = source.getRow(row, luminances);
            int right = (width * 4) / 5;
            for (int x = width / 5; x < right; x++) {
                localBuckets[(local[x] & 0xff) >> LUMINANCE_SHIFT]++;
            }
        }
        int blackPoint = estimateBlackPoint(localBuckets);
        if (blackPoint < 0) {
            return null;
        }
        BlackMatrix out = new BlackMatrix(width, height);
        byte[] local = source.getMatrix();
        for (int y = 0; y < height; y++) {
            int offset = y * width;
            for (int x = 0; x < width; x++) {
                if ((local[offset + x] & 0xff) < blackPoint) {
                    out.set(x, y);
                }
            }
        }
        return out;
    }

    private BlackMatrix hybridMatrix() {
        int width = source.getWidth();
        int height = source.getHeight();
        byte[] local = source.getMatrix();
        int subWidth = width >> BLOCK_SIZE_POWER;
        if ((width & BLOCK_SIZE_MASK) != 0) {
            subWidth++;
        }
        int subHeight = height >> BLOCK_SIZE_POWER;
        if ((height & BLOCK_SIZE_MASK) != 0) {
            subHeight++;
        }
        int[][] blackPoints = calculateBlackPoints(local, subWidth, subHeight, width, height);
        BlackMatrix out = new BlackMatrix(width, height);
        calculateThresholdForBlock(local, subWidth, subHeight, width, height, blackPoints, out);
        return out;
    }

    /** The threshold of each 8 by 8 block is the average black point of the 5 by 5 blocks around it. */
    private static void calculateThresholdForBlock(byte[] luminances, int subWidth, int subHeight, int width, int height,
                                                   int[][] blackPoints, BlackMatrix out) {
        int maxYOffset = height - BLOCK_SIZE;
        int maxXOffset = width - BLOCK_SIZE;
        for (int y = 0; y < subHeight; y++) {
            int yoffset = y << BLOCK_SIZE_POWER;
            if (yoffset > maxYOffset) {
                yoffset = maxYOffset;
            }
            int top = cap(y, subHeight - 3);
            for (int x = 0; x < subWidth; x++) {
                int xoffset = x << BLOCK_SIZE_POWER;
                if (xoffset > maxXOffset) {
                    xoffset = maxXOffset;
                }
                int left = cap(x, subWidth - 3);
                int sum = 0;
                for (int z = -2; z <= 2; z++) {
                    int[] blackRow = blackPoints[top + z];
                    sum += blackRow[left - 2] + blackRow[left - 1] + blackRow[left] + blackRow[left + 1] + blackRow[left + 2];
                }
                thresholdBlock(luminances, xoffset, yoffset, sum / 25, width, out);
            }
        }
    }

    private static int cap(int value, int max) {
        return value < 2 ? 2 : Math.min(value, max);
    }

    /** Blocks at the right and bottom edges overlap their neighbours; a pixel once black stays black. */
    private static void thresholdBlock(byte[] luminances, int xoffset, int yoffset, int threshold, int stride, BlackMatrix out) {
        for (int y = 0, offset = yoffset * stride + xoffset; y < BLOCK_SIZE; y++, offset += stride) {
            for (int x = 0; x < BLOCK_SIZE; x++) {
                if ((luminances[offset + x] & 0xFF) <= threshold) {
                    out.set(xoffset + x, yoffset + y);
                }
            }
        }
    }

    /**
     * One black point a block: its average, or half its minimum when the block is flat, raised to
     * its neighbours' when they are darker. The loop over rows is ZXing's, quirks included.
     */
    private static int[][] calculateBlackPoints(byte[] luminances, int subWidth, int subHeight, int width, int height) {
        int maxYOffset = height - BLOCK_SIZE;
        int maxXOffset = width - BLOCK_SIZE;
        int[][] blackPoints = new int[subHeight][subWidth];
        for (int y = 0; y < subHeight; y++) {
            int yoffset = y << BLOCK_SIZE_POWER;
            if (yoffset > maxYOffset) {
                yoffset = maxYOffset;
            }
            for (int x = 0; x < subWidth; x++) {
                int xoffset = x << BLOCK_SIZE_POWER;
                if (xoffset > maxXOffset) {
                    xoffset = maxXOffset;
                }
                int sum = 0;
                int min = 0xFF;
                int max = 0;
                for (int yy = 0, offset = yoffset * width + xoffset; yy < BLOCK_SIZE; yy++, offset += width) {
                    for (int xx = 0; xx < BLOCK_SIZE; xx++) {
                        int pixel = luminances[offset + xx] & 0xFF;
                        sum += pixel;
                        if (pixel < min) {
                            min = pixel;
                        }
                        if (pixel > max) {
                            max = pixel;
                        }
                    }
                    // Once the block has contrast, the rest of it only adds to the sum.
                    if (max - min > MIN_DYNAMIC_RANGE) {
                        for (yy++, offset += width; yy < BLOCK_SIZE; yy++, offset += width) {
                            for (int xx = 0; xx < BLOCK_SIZE; xx++) {
                                sum += luminances[offset + xx] & 0xFF;
                            }
                        }
                    }
                }
                int average = sum >> (BLOCK_SIZE_POWER * 2);
                if (max - min <= MIN_DYNAMIC_RANGE) {
                    average = min / 2;
                    if (y > 0 && x > 0) {
                        int averageNeighborBlackPoint =
                                (blackPoints[y - 1][x] + (2 * blackPoints[y][x - 1]) + blackPoints[y - 1][x - 1]) / 4;
                        if (min < averageNeighborBlackPoint) {
                            average = averageNeighborBlackPoint;
                        }
                    }
                }
                blackPoints[y][x] = average;
            }
        }
        return blackPoints;
    }

    private void initArrays(int luminanceSize) {
        if (luminances.length < luminanceSize) {
            luminances = new byte[luminanceSize];
        }
        java.util.Arrays.fill(buckets, 0);
    }

    /**
     * The valley between the two peaks of a 32-bucket histogram, closer to the white one, or -1 when
     * the peaks are too close to tell black from white.
     */
    private static int estimateBlackPoint(int[] buckets) {
        int numBuckets = buckets.length;
        int maxBucketCount = 0;
        int firstPeak = 0;
        int firstPeakSize = 0;
        for (int x = 0; x < numBuckets; x++) {
            if (buckets[x] > firstPeakSize) {
                firstPeak = x;
                firstPeakSize = buckets[x];
            }
            if (buckets[x] > maxBucketCount) {
                maxBucketCount = buckets[x];
            }
        }
        // The second peak is scored by its height times the square of its distance from the first.
        int secondPeak = 0;
        int secondPeakScore = 0;
        for (int x = 0; x < numBuckets; x++) {
            int distanceToBiggest = x - firstPeak;
            int score = buckets[x] * distanceToBiggest * distanceToBiggest;
            if (score > secondPeakScore) {
                secondPeak = x;
                secondPeakScore = score;
            }
        }
        if (firstPeak > secondPeak) {
            int temp = firstPeak;
            firstPeak = secondPeak;
            secondPeak = temp;
        }
        if (secondPeak - firstPeak <= numBuckets / 16) {
            return -1;
        }
        int bestValley = secondPeak - 1;
        int bestValleyScore = -1;
        for (int x = secondPeak - 1; x > firstPeak; x--) {
            int fromFirst = x - firstPeak;
            int score = fromFirst * fromFirst * (secondPeak - x) * (maxBucketCount - buckets[x]);
            if (score > bestValleyScore) {
                bestValley = x;
                bestValleyScore = score;
            }
        }
        return bestValley << LUMINANCE_SHIFT;
    }
}
