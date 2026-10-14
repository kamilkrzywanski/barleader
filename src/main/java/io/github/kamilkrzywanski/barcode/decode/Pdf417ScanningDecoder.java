package io.github.kamilkrzywanski.barcode.decode;

import com.google.zxing.ChecksumException;
import io.github.kamilkrzywanski.barcode.ImagePoint;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * PDF417 photo decoder ported from zxing-cpp 3.1.1 {@code PDFScanningDecoder.cpp},
 * {@code PDFDetectionResult.cpp}, {@code PDFDetectionResultColumn.cpp},
 * {@code PDFBoundingBox.cpp} and {@code PDFBarcodeValue.cpp}.
 *
 * <p>The compiled detector supplies the codeword-area corners. Corrected codewords go through
 * {@link Pdf417Decoder#decodeCodewords}, the same parser as the axis-aligned path. The scan
 * ladder reads PDF417 here and leaves a 90 degree turn to the ladder.
 */
final class Pdf417ScanningDecoder {
    private static final int CODEWORD_SKEW = 2;
    private static final int MODULES = 17;
    private static final int STOP_MODULES = 18;
    private static final int MAX_CODEWORDS = 928;
    private static final int NEARBY = 5;
    private static final int ROW_SKIP = 2;
    private static final int MIN_ROWS = 3;
    private static final int MAX_ROWS = 90;

    private Pdf417ScanningDecoder() {
    }

    /** Detect one symbol and decode it. Returns null when the image has nothing readable. */
    static String decode(boolean[][] image, boolean tryRotate) {
        Pdf417Detector.Result found = Pdf417Detector.detect(image, false, tryRotate);
        if (found == null || found.symbols.isEmpty()) {
            return null;
        }
        Decoded decoded = decode(found.bits, found.symbols.get(0).points);
        return decoded == null ? null : decoded.text();
    }

    /**
     * One PDF417 in the input image. {@code rotation} is 0, 90, 180, or 270.
     * Corners are the outer guards, already mapped back from the detector's rotation.
     */
    record Found(String text, byte[] bytes, List<ImagePoint> corners, int rotation) {
    }

    /**
     * Symbols for one ladder attempt. The detector may turn the bitmap 180 degrees.
     * A 90 degree search stays off, because the ladder already has that attempt.
     */
    static List<Found> read(boolean[][] image, boolean multiple) {
        Pdf417Detector.Result found = Pdf417Detector.detect(image, multiple, false);
        if (found == null || found.symbols.isEmpty()) {
            return List.of();
        }
        List<Found> hits = new ArrayList<>();
        for (Pdf417Detector.Symbol symbol : found.symbols) {
            Decoded decoded = decode(found.bits, symbol.points);
            if (decoded == null || decoded.text().isBlank()) {
                continue;
            }
            hits.add(new Found(decoded.text(), decoded.bytes(), outerGuards(found, symbol.points), found.rotation));
            if (!multiple) {
                break;
            }
        }
        return hits;
    }

    /** Same corner order as zxing-cpp {@code DoDecode}: start, stop, stop, start. */
    private static List<ImagePoint> outerGuards(Pdf417Detector.Result found, Pdf417Detector.Point[] points) {
        List<ImagePoint> corners = new ArrayList<>(4);
        if (points == null || found.bits.length == 0 || found.bits[0] == null) {
            return corners;
        }
        int width = found.bits[0].length;
        int height = found.bits.length;
        for (int index : new int[]{0, 2, 3, 1}) {
            if (index >= points.length || points[index] == null) {
                continue;
            }
            int x = points[index].x;
            int y = points[index].y;
            int mappedX = x;
            int mappedY = y;
            if (found.rotation == 90) {
                mappedX = height - y - 1;
                mappedY = x;
            } else if (found.rotation == 180) {
                mappedX = width - x - 1;
                mappedY = height - y - 1;
            } else if (found.rotation == 270) {
                mappedX = y;
                mappedY = width - x - 1;
            }
            corners.add(new ImagePoint(mappedX, mappedY));
        }
        return corners;
    }

    /**
     * Every symbol the detector finds, in detection order.
     * Macro fields stay out of each string, the same way {@link #decode(boolean[][], boolean)}
     * leaves them out of a single symbol. The sequence index is not returned.
     * This method is not called from the scan ladder.
     */
    static List<String> symbolTexts(boolean[][] image) {
        Pdf417Detector.Result found = Pdf417Detector.detect(image, true, true);
        if (found == null || found.symbols.isEmpty()) {
            return List.of();
        }
        List<String> texts = new ArrayList<>();
        for (Pdf417Detector.Symbol symbol : found.symbols) {
            Decoded decoded = decode(found.bits, symbol.points);
            if (decoded != null) {
                texts.add(decoded.text());
            }
        }
        return texts;
    }

    private static Decoded decode(boolean[][] image, Pdf417Detector.Point[] points) {
        int width = image[0].length;
        int height = image.length;
        BoundingBox box = BoundingBox.create(width, height, points[4], points[5], points[6], points[7]);
        if (box == null) {
            return null;
        }
        int minWidth = minCodewordWidth(points);
        int maxWidth = maxCodewordWidth(points);
        Column left = null;
        Column right = null;
        Detection detection = new Detection();
        for (int pass = 0; pass < 2; pass++) {
            if (points[4] != null) {
                left = indicator(image, box, points[4], true, minWidth, maxWidth);
            }
            if (points[6] != null) {
                right = indicator(image, box, points[6], false, minWidth, maxWidth);
            }
            if (!detection.merge(left, right)) {
                return null;
            }
            BoundingBox merged = detection.box;
            if (pass == 0 && merged != null && (merged.minY < box.minY || merged.maxY > box.maxY)) {
                box = merged;
            } else {
                detection.box = box;
                break;
            }
        }
        int lastColumn = detection.meta.columns + 1;
        detection.columns[0] = left;
        detection.columns[lastColumn] = right;
        boolean leftToRight = left != null;
        for (int step = 1; step <= lastColumn; step++) {
            int barcodeColumn = leftToRight ? step : lastColumn - step;
            if (detection.columns[barcodeColumn] != null) {
                continue;
            }
            Side side = barcodeColumn == 0 ? Side.LEFT : barcodeColumn == lastColumn ? Side.RIGHT : Side.NONE;
            Column column = new Column(box, side);
            detection.columns[barcodeColumn] = column;
            int previousStart = -1;
            for (int imageRow = box.minY; imageRow <= box.maxY; imageRow++) {
                int start = startColumn(detection, barcodeColumn, imageRow, leftToRight);
                if (start < 0 || start > box.maxX) {
                    if (previousStart == -1) {
                        continue;
                    }
                    start = previousStart;
                }
                Codeword codeword = detectCodeword(image, box.minX, box.maxX, leftToRight, start, imageRow, minWidth, maxWidth);
                if (codeword != null) {
                    column.set(imageRow, codeword);
                    previousStart = start;
                    minWidth = Math.min(minWidth, codeword.width());
                    maxWidth = Math.max(maxWidth, codeword.width());
                }
            }
        }
        return detection.decodeText();
    }

    /** {@code GetRowIndicatorColumn}. Both passes share the column and overwrite the same rows. */
    private static Column indicator(boolean[][] image, BoundingBox box, Pdf417Detector.Point start, boolean leftToRight,
            int minWidth, int maxWidth) {
        Column column = new Column(box, leftToRight ? Side.LEFT : Side.RIGHT);
        int limit = image[0].length;
        for (int pass = 0; pass < 2; pass++) {
            int increment = pass == 0 ? 1 : -1;
            int startColumn = start.x;
            for (int row = start.y; row <= box.maxY && row >= box.minY; row += increment) {
                Codeword codeword = detectCodeword(image, 0, limit, leftToRight, startColumn, row, minWidth, maxWidth);
                if (codeword != null) {
                    column.set(row, codeword);
                    startColumn = leftToRight ? codeword.startX : codeword.endX;
                }
            }
        }
        return column;
    }

    /** {@code DetectCodeword}. */
    private static Codeword detectCodeword(boolean[][] image, int minColumn, int maxColumn, boolean leftToRight,
            int startColumn, int imageRow, int minWidth, int maxWidth) {
        startColumn = adjustStart(image, minColumn, maxColumn, leftToRight, startColumn, imageRow);
        int[] counts = moduleBits(image, minColumn, maxColumn, leftToRight, startColumn, imageRow);
        if (counts == null) {
            return null;
        }
        int bits = 0;
        for (int count : counts) {
            bits += count;
        }
        int endColumn;
        if (leftToRight) {
            endColumn = startColumn + bits;
        } else {
            reverse(counts);
            endColumn = startColumn;
            startColumn = endColumn - bits;
        }
        if (bits < minWidth - CODEWORD_SKEW || bits > maxWidth + CODEWORD_SKEW) {
            return null;
        }
        int symbol = Pdf417Symbols.decodedValue(counts);
        int value = Pdf417Symbols.codeword(symbol);
        if (value == -1) {
            return null;
        }
        return new Codeword(startColumn, endColumn, Pdf417Symbols.bucket(symbol), value);
    }

    /** {@code AdjustCodewordStartColumn}. A drift of more than two pixels restores the original column. */
    private static int adjustStart(boolean[][] image, int minColumn, int maxColumn, boolean leftToRight, int start,
            int imageRow) {
        int corrected = start;
        int increment = leftToRight ? -1 : 1;
        for (int pass = 0; pass < 2; pass++) {
            while (corrected >= minColumn && corrected < maxColumn && leftToRight == image[imageRow][corrected]) {
                if (Math.abs(start - corrected) > CODEWORD_SKEW) {
                    return start;
                }
                corrected += increment;
            }
            increment = -increment;
            leftToRight = !leftToRight;
        }
        return corrected;
    }

    /** {@code GetModuleBitCount}. The last bar may run off the clipped edge. */
    private static int[] moduleBits(boolean[][] image, int minColumn, int maxColumn, boolean leftToRight, int column,
            int imageRow) {
        int module = 0;
        int increment = leftToRight ? 1 : -1;
        boolean previous = leftToRight;
        int[] counts = new int[8];
        while (column >= minColumn && column < maxColumn && module < counts.length) {
            if (image[imageRow][column] == previous) {
                counts[module]++;
                column += increment;
            } else {
                module++;
                previous = !previous;
            }
        }
        if (module == counts.length || (column == (leftToRight ? maxColumn : minColumn) && module == counts.length - 1)) {
            return counts;
        }
        return null;
    }

    /** {@code GetStartColumn}. */
    private static int startColumn(Detection detection, int barcodeColumn, int imageRow, boolean leftToRight) {
        int offset = leftToRight ? 1 : -1;
        Codeword codeword = null;
        if (validColumn(detection, barcodeColumn - offset)) {
            Column neighbor = detection.columns[barcodeColumn - offset];
            if (neighbor != null) {
                codeword = neighbor.codeword(imageRow);
            }
        }
        if (codeword != null) {
            return leftToRight ? codeword.endX : codeword.startX;
        }
        Column current = detection.columns[barcodeColumn];
        if (current != null) {
            codeword = current.nearby(imageRow);
        }
        if (codeword != null) {
            return leftToRight ? codeword.startX : codeword.endX;
        }
        if (validColumn(detection, barcodeColumn - offset)) {
            Column neighbor = detection.columns[barcodeColumn - offset];
            if (neighbor != null) {
                codeword = neighbor.nearby(imageRow);
            }
        }
        if (codeword != null) {
            return leftToRight ? codeword.endX : codeword.startX;
        }
        int skipped = 0;
        while (validColumn(detection, barcodeColumn - offset)) {
            barcodeColumn -= offset;
            Column previous = detection.columns[barcodeColumn];
            if (previous == null) {
                break;
            }
            for (Codeword earlier : previous.codewords) {
                if (earlier != null) {
                    return (leftToRight ? earlier.endX : earlier.startX) + offset * skipped * earlier.width();
                }
            }
            skipped++;
        }
        return leftToRight ? detection.box.minX : detection.box.maxX;
    }

    private static boolean validColumn(Detection detection, int column) {
        return column >= 0 && column <= detection.meta.columns + 1;
    }

    private static int minCodewordWidth(Pdf417Detector.Point[] points) {
        return Math.min(Math.min(minSpan(points[0], points[4]), scaledStop(minSpan(points[6], points[2]))),
                Math.min(minSpan(points[1], points[5]), scaledStop(minSpan(points[7], points[3]))));
    }

    private static int maxCodewordWidth(Pdf417Detector.Point[] points) {
        return Math.max(Math.max(maxSpan(points[0], points[4]), scaledStop(maxSpan(points[6], points[2]))),
                Math.max(maxSpan(points[1], points[5]), scaledStop(maxSpan(points[7], points[3]))));
    }

    /** A missing guard is too wide to win {@code min} and too narrow to win {@code max}. */
    private static int minSpan(Pdf417Detector.Point first, Pdf417Detector.Point second) {
        if (first == null || second == null) {
            return Integer.MAX_VALUE / MODULES;
        }
        return Math.abs(first.x - second.x);
    }

    private static int maxSpan(Pdf417Detector.Point first, Pdf417Detector.Point second) {
        if (first == null || second == null) {
            return 0;
        }
        return Math.abs(first.x - second.x);
    }

    private static int scaledStop(int span) {
        return span * MODULES / STOP_MODULES;
    }

    private static void reverse(int[] counts) {
        for (int i = 0, j = counts.length - 1; i < j; i++, j--) {
            int swap = counts[i];
            counts[i] = counts[j];
            counts[j] = swap;
        }
    }

    private static int ecWords(int level) {
        return 1 << (level + 1);
    }

    private enum Side {
        NONE, LEFT, RIGHT
    }

    /** One measured codeword. {@code row} stays {@code -1} until a row indicator or a neighbor fills it. */
    private static final class Codeword {
        final int startX;
        final int endX;
        final int bucket;
        final int value;
        int row = -1;

        Codeword(int startX, int endX, int bucket, int value) {
            this.startX = startX;
            this.endX = endX;
            this.bucket = bucket;
            this.value = value;
        }

        int width() {
            return endX - startX;
        }

        boolean hasRow() {
            return accepts(row);
        }

        boolean accepts(int rowNumber) {
            return rowNumber != -1 && bucket == (rowNumber % 3) * 3;
        }

        void setIndicatorRow() {
            row = (value / 30) * 3 + bucket / 3;
        }
    }

    /** Counts of codeword values. Tied winners stay in ascending value order, matching {@code std::map}. */
    private static final class BarcodeValue {
        private final TreeMap<Integer, Integer> counts = new TreeMap<>();

        void add(int value) {
            counts.merge(value, 1, Integer::sum);
        }

        int[] values() {
            if (counts.isEmpty()) {
                return new int[0];
            }
            int best = 0;
            for (int count : counts.values()) {
                best = Math.max(best, count);
            }
            int size = 0;
            for (int count : counts.values()) {
                if (count == best) {
                    size++;
                }
            }
            int[] result = new int[size];
            int at = 0;
            for (var entry : counts.entrySet()) {
                if (entry.getValue() == best) {
                    result[at++] = entry.getKey();
                }
            }
            return result;
        }
    }

    private static final class Metadata {
        final int columns;
        final int upper;
        final int lower;
        final int level;

        Metadata(int columns, int upper, int lower, int level) {
            this.columns = columns;
            this.upper = upper;
            this.lower = lower;
            this.level = level;
        }

        int rows() {
            return upper + lower;
        }
    }

    private static final class BoundingBox {
        final int width;
        final int height;
        final Pdf417Detector.Point topLeft;
        final Pdf417Detector.Point bottomLeft;
        final Pdf417Detector.Point topRight;
        final Pdf417Detector.Point bottomRight;
        final int minX;
        final int maxX;
        final int minY;
        final int maxY;

        private BoundingBox(int width, int height, Pdf417Detector.Point topLeft, Pdf417Detector.Point bottomLeft,
                Pdf417Detector.Point topRight, Pdf417Detector.Point bottomRight) {
            this.width = width;
            this.height = height;
            this.topLeft = topLeft;
            this.bottomLeft = bottomLeft;
            this.topRight = topRight;
            this.bottomRight = bottomRight;
            minX = Math.min(topLeft.x, bottomLeft.x);
            maxX = Math.max(topRight.x, bottomRight.x);
            minY = Math.min(topLeft.y, topRight.y);
            maxY = Math.max(bottomLeft.y, bottomRight.y);
        }

        static BoundingBox create(int width, int height, Pdf417Detector.Point topLeft, Pdf417Detector.Point bottomLeft,
                Pdf417Detector.Point topRight, Pdf417Detector.Point bottomRight) {
            if ((topLeft == null && topRight == null) || (bottomLeft == null && bottomRight == null)
                    || (topLeft != null && bottomLeft == null) || (topRight != null && bottomRight == null)) {
                return null;
            }
            if (topLeft == null) {
                topLeft = new Pdf417Detector.Point(0, topRight.y);
                bottomLeft = new Pdf417Detector.Point(0, bottomRight.y);
            } else if (topRight == null) {
                topRight = new Pdf417Detector.Point(width - 1, topLeft.y);
                bottomRight = new Pdf417Detector.Point(width - 1, bottomLeft.y);
            }
            if (height <= 0 || width <= 0) {
                return null;
            }
            return new BoundingBox(width, height, topLeft, bottomLeft, topRight, bottomRight);
        }

        BoundingBox addMissingRows(int missingStart, int missingEnd, boolean left) {
            Pdf417Detector.Point newTopLeft = topLeft;
            Pdf417Detector.Point newBottomLeft = bottomLeft;
            Pdf417Detector.Point newTopRight = topRight;
            Pdf417Detector.Point newBottomRight = bottomRight;
            if (missingStart > 0) {
                Pdf417Detector.Point top = left ? topLeft : topRight;
                int y = top.y - missingStart;
                if (y < 0) {
                    y = 0;
                }
                Pdf417Detector.Point moved = new Pdf417Detector.Point(top.x, y);
                if (left) {
                    newTopLeft = moved;
                } else {
                    newTopRight = moved;
                }
            }
            if (missingEnd > 0) {
                Pdf417Detector.Point bottom = left ? bottomLeft : bottomRight;
                int y = bottom.y + missingEnd;
                if (y >= height) {
                    y = height - 1;
                }
                Pdf417Detector.Point moved = new Pdf417Detector.Point(bottom.x, y);
                if (left) {
                    newBottomLeft = moved;
                } else {
                    newBottomRight = moved;
                }
            }
            return create(width, height, newTopLeft, newBottomLeft, newTopRight, newBottomRight);
        }
    }

    /** One barcode column, indexed by image row. A null slot is an unread row. */
    private static final class Column {
        final BoundingBox box;
        final Side side;
        final Codeword[] codewords;

        Column(BoundingBox box, Side side) {
            if (box.maxY < box.minY) {
                throw new IllegalArgumentException("Invalid bounding box");
            }
            this.box = box;
            this.side = side;
            codewords = new Codeword[box.maxY - box.minY + 1];
        }

        boolean indicator() {
            return side != Side.NONE;
        }

        void set(int imageRow, Codeword codeword) {
            codewords[imageRow - box.minY] = codeword;
        }

        Codeword codeword(int imageRow) {
            return codewords[imageRow - box.minY];
        }

        /** {@code codewordNearby}. The search stops four rows away. */
        Codeword nearby(int imageRow) {
            int index = imageRow - box.minY;
            if (codewords[index] != null) {
                return codewords[index];
            }
            for (int distance = 1; distance < NEARBY; distance++) {
                int above = index - distance;
                if (above >= 0 && codewords[above] != null) {
                    return codewords[above];
                }
                int below = index + distance;
                if (below < codewords.length && codewords[below] != null) {
                    return codewords[below];
                }
            }
            return null;
        }

        Metadata metadata() {
            if (!indicator()) {
                return null;
            }
            BarcodeValue columns = new BarcodeValue();
            BarcodeValue upper = new BarcodeValue();
            BarcodeValue lower = new BarcodeValue();
            BarcodeValue level = new BarcodeValue();
            for (Codeword codeword : codewords) {
                if (codeword == null) {
                    continue;
                }
                codeword.setIndicatorRow();
                int indicatorValue = codeword.value % 30;
                int row = codeword.row;
                if (side != Side.LEFT) {
                    row += 2;
                }
                switch (row % 3) {
                    case 0 -> upper.add(indicatorValue * 3 + 1);
                    case 1 -> {
                        level.add(indicatorValue / 3);
                        lower.add(indicatorValue % 3);
                    }
                    case 2 -> columns.add(indicatorValue + 1);
                    default -> {
                    }
                }
            }
            int[] columnVote = columns.values();
            int[] upperVote = upper.values();
            int[] lowerVote = lower.values();
            int[] levelVote = level.values();
            if (columnVote.length == 0 || upperVote.length == 0 || lowerVote.length == 0 || levelVote.length == 0
                    || columnVote[0] < 1 || upperVote[0] + lowerVote[0] < MIN_ROWS
                    || upperVote[0] + lowerVote[0] > MAX_ROWS) {
                return null;
            }
            Metadata metadata = new Metadata(columnVote[0], upperVote[0], lowerVote[0], levelVote[0]);
            removeMismatches(metadata);
            return metadata;
        }

        int[] rowHeights() {
            Metadata metadata = metadata();
            if (metadata == null) {
                return null;
            }
            adjustIncomplete(metadata);
            int[] heights = new int[metadata.rows()];
            for (Codeword codeword : codewords) {
                if (codeword != null && codeword.row >= 0 && codeword.row < heights.length) {
                    heights[codeword.row]++;
                }
            }
            return heights;
        }

        /** {@code adjustCompleteIndicatorColumnRowNumbers}. */
        void adjustComplete(Metadata metadata) {
            if (!indicator()) {
                return;
            }
            for (Codeword codeword : codewords) {
                if (codeword != null) {
                    codeword.setIndicatorRow();
                }
            }
            removeMismatches(metadata);
            Pdf417Detector.Point top = side == Side.LEFT ? box.topLeft : box.topRight;
            Pdf417Detector.Point bottom = side == Side.LEFT ? box.bottomLeft : box.bottomRight;
            int first = top.y - box.minY;
            int last = bottom.y - box.minY;
            int barcodeRow = -1;
            int maxHeight = 1;
            int currentHeight = 0;
            int increment = 1;
            for (int row = first; row < last; row++) {
                Codeword codeword = codewords[row];
                if (codeword == null) {
                    continue;
                }
                if (barcodeRow == -1 && codeword.row == metadata.rows() - 1) {
                    increment = -1;
                    barcodeRow = metadata.rows();
                }
                int difference = codeword.row - barcodeRow;
                if (difference == 0) {
                    currentHeight++;
                } else if (difference == increment) {
                    maxHeight = Math.max(maxHeight, currentHeight);
                    currentHeight = 1;
                    barcodeRow = codeword.row;
                } else if (difference < 0 || codeword.row >= metadata.rows() || difference > row) {
                    codewords[row] = null;
                } else {
                    int checked = maxHeight > 2 ? (maxHeight - 2) * difference : difference;
                    boolean blocked = checked >= row;
                    for (int i = 1; i <= checked && !blocked; i++) {
                        blocked = codewords[row - i] != null;
                    }
                    if (blocked) {
                        codewords[row] = null;
                    } else {
                        barcodeRow = codeword.row;
                        currentHeight = 1;
                    }
                }
            }
        }

        /** {@code adjustIncompleteIndicatorColumnRowNumbers}. */
        void adjustIncomplete(Metadata metadata) {
            if (!indicator()) {
                return;
            }
            Pdf417Detector.Point top = side == Side.LEFT ? box.topLeft : box.topRight;
            Pdf417Detector.Point bottom = side == Side.LEFT ? box.bottomLeft : box.bottomRight;
            int first = top.y - box.minY;
            int last = bottom.y - box.minY;
            int barcodeRow = -1;
            int maxHeight = 1;
            int currentHeight = 0;
            for (int row = first; row < last; row++) {
                Codeword codeword = codewords[row];
                if (codeword == null) {
                    continue;
                }
                codeword.setIndicatorRow();
                int difference = codeword.row - barcodeRow;
                if (difference == 0) {
                    currentHeight++;
                } else if (difference == 1) {
                    maxHeight = Math.max(maxHeight, currentHeight);
                    currentHeight = 1;
                    barcodeRow = codeword.row;
                } else if (codeword.row >= metadata.rows()) {
                    codewords[row] = null;
                } else {
                    barcodeRow = codeword.row;
                    currentHeight = 1;
                }
            }
        }

        /** {@code RemoveIncorrectCodewords}. A right indicator's row is compared two clusters later. */
        private void removeMismatches(Metadata metadata) {
            for (int i = 0; i < codewords.length; i++) {
                Codeword codeword = codewords[i];
                if (codeword == null) {
                    continue;
                }
                int indicatorValue = codeword.value % 30;
                int row = codeword.row;
                if (row > metadata.rows()) {
                    codewords[i] = null;
                    continue;
                }
                if (side != Side.LEFT) {
                    row += 2;
                }
                boolean mismatch = switch (row % 3) {
                    case 0 -> indicatorValue * 3 + 1 != metadata.upper;
                    case 1 -> indicatorValue / 3 != metadata.level || indicatorValue % 3 != metadata.lower;
                    case 2 -> indicatorValue + 1 != metadata.columns;
                    default -> false;
                };
                if (mismatch) {
                    codewords[i] = null;
                }
            }
        }
    }

    private static final class Detection {
        Metadata meta;
        BoundingBox box;
        Column[] columns = new Column[0];

        boolean merge(Column left, Column right) {
            if (left == null && right == null) {
                return false;
            }
            Metadata metadata = agree(left, right);
            if (metadata == null) {
                return false;
            }
            BoundingBox leftBox = adjusted(left);
            BoundingBox rightBox = adjusted(right);
            if (leftBox == FAILED || rightBox == FAILED) {
                return false;
            }
            BoundingBox merged = mergeBoxes(leftBox, rightBox);
            if (merged == FAILED) {
                return false;
            }
            init(metadata, merged == EMPTY ? null : merged);
            return true;
        }

        void init(Metadata metadata, BoundingBox boundingBox) {
            meta = metadata;
            box = boundingBox;
            columns = new Column[metadata.columns + 2];
        }

        Decoded decodeText() {
            BarcodeValue[][] matrix = matrix();
            if (matrix == null || !adjustCount(matrix)) {
                return null;
            }
            int rows = meta.rows();
            int columnCount = meta.columns;
            int[] codewords = new int[rows * columnCount];
            List<Integer> erasureList = new ArrayList<>();
            List<Integer> ambiguousAt = new ArrayList<>();
            List<int[]> ambiguousValues = new ArrayList<>();
            for (int row = 0; row < rows; row++) {
                for (int column = 0; column < columnCount; column++) {
                    int[] values = matrix[row][column + 1].values();
                    int index = row * columnCount + column;
                    if (values.length == 0) {
                        erasureList.add(index);
                    } else if (values.length == 1) {
                        codewords[index] = values[0];
                    } else {
                        ambiguousAt.add(index);
                        ambiguousValues.add(values);
                    }
                }
            }
            int[] erasures = new int[erasureList.size()];
            for (int i = 0; i < erasures.length; i++) {
                erasures[i] = erasureList.get(i);
            }
            int[] indexes = new int[ambiguousAt.size()];
            int[][] choices = new int[ambiguousValues.size()][];
            for (int i = 0; i < indexes.length; i++) {
                indexes[i] = ambiguousAt.get(i);
                choices[i] = ambiguousValues.get(i);
            }
            return resolve(meta.level, codewords, erasures, indexes, choices);
        }

        /** {@code DetectionResult::allColumns}. Calling it adjusts row numbers as a side effect. */
        private BarcodeValue[][] matrix() {
            adjustRows();
            int rows = meta.rows();
            BarcodeValue[][] matrix = new BarcodeValue[rows][meta.columns + 2];
            for (int row = 0; row < rows; row++) {
                for (int column = 0; column < matrix[row].length; column++) {
                    matrix[row][column] = new BarcodeValue();
                }
            }
            for (int column = 0; column < columns.length; column++) {
                Column current = columns[column];
                if (current == null) {
                    continue;
                }
                for (Codeword codeword : current.codewords) {
                    if (codeword != null && codeword.row >= 0 && codeword.row < rows) {
                        matrix[codeword.row][column].add(codeword.value);
                    }
                }
            }
            return matrix;
        }

        private void adjustRows() {
            if (columns.length == 0) {
                return;
            }
            if (columns[0] != null) {
                columns[0].adjustComplete(meta);
            }
            if (columns[columns.length - 1] != null) {
                columns[columns.length - 1].adjustComplete(meta);
            }
            int unadjusted = MAX_CODEWORDS;
            int previous;
            do {
                previous = unadjusted;
                unadjusted = adjustAll();
            } while (unadjusted > 0 && unadjusted < previous);
        }

        private int adjustAll() {
            int unadjusted = adjustByRow();
            if (unadjusted == 0) {
                return 0;
            }
            for (int column = 1; column < columns.length - 1; column++) {
                Column current = columns[column];
                if (current == null) {
                    continue;
                }
                for (int row = 0; row < current.codewords.length; row++) {
                    Codeword codeword = current.codewords[row];
                    if (codeword != null && !codeword.hasRow()) {
                        adjustFromNeighbors(column, row);
                    }
                }
            }
            return unadjusted;
        }

        private int adjustByRow() {
            adjustFromBoth();
            return adjustFromEnd(0) + adjustFromEnd(columns.length - 1);
        }

        /** Rows where both indicators agree are copied across the symbol. */
        private void adjustFromBoth() {
            if (columns.length < 2 || columns[0] == null || columns[columns.length - 1] == null) {
                return;
            }
            Codeword[] left = columns[0].codewords;
            Codeword[] right = columns[columns.length - 1].codewords;
            int rows = Math.min(left.length, right.length);
            for (int row = 0; row < rows; row++) {
                if (left[row] == null || right[row] == null || left[row].row != right[row].row) {
                    continue;
                }
                for (int column = 1; column < columns.length - 1; column++) {
                    Column current = columns[column];
                    if (current == null || row >= current.codewords.length) {
                        continue;
                    }
                    Codeword codeword = current.codewords[row];
                    if (codeword == null) {
                        continue;
                    }
                    codeword.row = left[row].row;
                    if (!codeword.hasRow()) {
                        current.codewords[row] = null;
                    }
                }
            }
        }

        /** {@code AdjustRowNumbersFromLRI} and {@code FromRRI}. The right side is not walked backwards. */
        private int adjustFromEnd(int indicator) {
            if (columns.length == 0 || columns[indicator] == null) {
                return 0;
            }
            int unadjusted = 0;
            Codeword[] indicators = columns[indicator].codewords;
            for (int row = 0; row < indicators.length; row++) {
                if (indicators[row] == null) {
                    continue;
                }
                int indicatorRow = indicators[row].row;
                int invalid = 0;
                for (int column = 1; column < columns.length - 1 && invalid < ROW_SKIP; column++) {
                    Column current = columns[column];
                    if (current == null || row >= current.codewords.length) {
                        continue;
                    }
                    Codeword codeword = current.codewords[row];
                    if (codeword == null) {
                        continue;
                    }
                    if (!codeword.hasRow()) {
                        if (codeword.accepts(indicatorRow)) {
                            codeword.row = indicatorRow;
                            invalid = 0;
                        } else {
                            invalid++;
                        }
                    }
                    if (!codeword.hasRow()) {
                        unadjusted++;
                    }
                }
            }
            return unadjusted;
        }

        private void adjustFromNeighbors(int barcodeColumn, int row) {
            Column previous = columns[barcodeColumn - 1];
            if (previous == null) {
                return;
            }
            Column following = columns[barcodeColumn + 1];
            Codeword[] left = previous.codewords;
            Codeword[] right = following != null ? following.codewords : left;
            Column current = columns[barcodeColumn];
            Codeword[] codewords = current.codewords;
            if (row >= left.length || row >= right.length) {
                return;
            }
            Codeword[] other = new Codeword[14];
            other[2] = left[row];
            other[3] = right[row];
            if (row > 0) {
                other[0] = codewords[row - 1];
                other[4] = left[row - 1];
                other[5] = right[row - 1];
            }
            if (row > 1) {
                other[8] = codewords[row - 2];
                other[10] = left[row - 2];
                other[11] = right[row - 2];
            }
            if (row < codewords.length - 1 && row < left.length - 1 && row < right.length - 1) {
                other[1] = codewords[row + 1];
                other[6] = left[row + 1];
                other[7] = right[row + 1];
            }
            if (row < codewords.length - 2 && row < left.length - 2 && row < right.length - 2) {
                other[9] = codewords[row + 2];
                other[12] = left[row + 2];
                other[13] = right[row + 2];
            }
            Codeword codeword = codewords[row];
            for (Codeword candidate : other) {
                if (codeword != null && candidate != null && candidate.hasRow() && candidate.bucket == codeword.bucket) {
                    codeword.row = candidate.row;
                    return;
                }
            }
        }

        /**
         * {@code AdjustCodewordCount}. The calculated length is added beside a disagreeing vote,
         * not written over it. {@code 2 << level} is {@code 1 << (level + 1)}.
         */
        private boolean adjustCount(BarcodeValue[][] matrix) {
            int[] voted = matrix[0][1].values();
            int calculated = meta.columns * meta.rows() - ecWords(meta.level);
            if (calculated < 1 || calculated > MAX_CODEWORDS) {
                calculated = 0;
            }
            if (voted.length == 0) {
                if (calculated == 0) {
                    return false;
                }
                matrix[0][1].add(calculated);
            } else if (calculated != 0 && voted[0] != calculated) {
                matrix[0][1].add(calculated);
            }
            return true;
        }
    }

    /**
     * {@code GetBarcodeMetadata}. The symbol is rejected only when column count, EC level and row
     * count all disagree. A null box from a missing indicator is not a failure.
     */
    private static Metadata agree(Column left, Column right) {
        Metadata leftMetadata = left == null ? null : left.metadata();
        if (leftMetadata == null) {
            return right == null ? null : right.metadata();
        }
        Metadata rightMetadata = right == null ? null : right.metadata();
        if (rightMetadata == null) {
            return leftMetadata;
        }
        if (leftMetadata.columns != rightMetadata.columns && leftMetadata.level != rightMetadata.level
                && leftMetadata.rows() != rightMetadata.rows()) {
            return null;
        }
        return leftMetadata;
    }

    /** Sentinel for a merge that failed, distinct from a successful missing box. */
    private static final BoundingBox FAILED = BoundingBox.create(1, 1, new Pdf417Detector.Point(0, 0),
            new Pdf417Detector.Point(0, 0), new Pdf417Detector.Point(0, 0), new Pdf417Detector.Point(0, 0));
    private static final BoundingBox EMPTY = BoundingBox.create(1, 1, new Pdf417Detector.Point(0, 0),
            new Pdf417Detector.Point(0, 0), new Pdf417Detector.Point(0, 0), new Pdf417Detector.Point(0, 0));

    private static BoundingBox adjusted(Column column) {
        if (column == null) {
            return null;
        }
        int[] heights = column.rowHeights();
        if (heights == null) {
            return null;
        }
        int maxHeight = 0;
        for (int height : heights) {
            maxHeight = Math.max(maxHeight, height);
        }
        int missingStart = 0;
        for (int height : heights) {
            missingStart += maxHeight - height;
            if (height > 0) {
                break;
            }
        }
        for (int row = 0; missingStart > 0 && row < column.codewords.length && column.codewords[row] == null; row++) {
            missingStart--;
        }
        int missingEnd = 0;
        for (int row = heights.length - 1; row >= 0; row--) {
            missingEnd += maxHeight - heights[row];
            if (heights[row] > 0) {
                break;
            }
        }
        for (int row = column.codewords.length - 1; missingEnd > 0 && row >= 0 && column.codewords[row] == null; row--) {
            missingEnd--;
        }
        BoundingBox grown = column.box.addMissingRows(missingStart, missingEnd, column.side == Side.LEFT);
        return grown == null ? FAILED : grown;
    }

    /** {@code BoundingBox::Merge}. Both inputs null is a successful empty box, not a failure. */
    private static BoundingBox mergeBoxes(BoundingBox left, BoundingBox right) {
        if (left == null) {
            return right == null ? EMPTY : right;
        }
        if (right == null) {
            return left;
        }
        BoundingBox merged = BoundingBox.create(left.width, left.height, left.topLeft, left.bottomLeft, right.topRight,
                right.bottomRight);
        return merged == null ? FAILED : merged;
    }

    /** {@code CreateDecoderResultFromAmbiguousValues}. Only a checksum error tries the next vote. */
    private static Decoded resolve(int level, int[] codewords, int[] erasures, int[] ambiguousAt, int[][] choices) {
        // Erased positions are stored as zero. A symbol that was barely read is then the
        // all-zero codeword, and text compaction turns those zeros into a run of 'A'.
        // Official and synthetic symbols keep more than half of their codewords.
        if (erasures.length * 2 > codewords.length) {
            return null;
        }
        int[] cursor = new int[ambiguousAt.length];
        int tries = 100;
        int numEc = ecWords(level);
        while (tries-- > 0) {
            int[] attempt = codewords.clone();
            for (int i = 0; i < cursor.length; i++) {
                attempt[ambiguousAt[i]] = choices[i][cursor[i]];
            }
            try {
                return Pdf417Decoder.decodeCodewordsContent(attempt, numEc, erasures);
            } catch (ChecksumException failed) {
                if (cursor.length == 0) {
                    return null;
                }
                for (int i = 0; i < cursor.length; i++) {
                    if (cursor[i] < choices[i].length - 1) {
                        cursor[i]++;
                        break;
                    }
                    cursor[i] = 0;
                    if (i == cursor.length - 1) {
                        return null;
                    }
                }
            }
        }
        return null;
    }
}
