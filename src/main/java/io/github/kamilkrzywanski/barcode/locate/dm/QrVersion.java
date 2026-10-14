package io.github.kamilkrzywanski.barcode.locate.dm;

/**
 * A model 2 QR Code version: its size and the centres of its alignment patterns
 * ({@code QRVersion.cpp}; the same table as ZXing's {@code Version}).
 */
final class QrVersion {
    private static final int[][] ALIGNMENT_CENTERS = {
            {},
            {6, 18},
            {6, 22},
            {6, 26},
            {6, 30},
            {6, 34},
            {6, 22, 38},
            {6, 24, 42},
            {6, 26, 46},
            {6, 28, 50},
            {6, 30, 54},
            {6, 32, 58},
            {6, 34, 62},
            {6, 26, 46, 66},
            {6, 26, 48, 70},
            {6, 26, 50, 74},
            {6, 30, 54, 78},
            {6, 30, 56, 82},
            {6, 30, 58, 86},
            {6, 34, 62, 90},
            {6, 28, 50, 72, 94},
            {6, 26, 50, 74, 98},
            {6, 30, 54, 78, 102},
            {6, 28, 54, 80, 106},
            {6, 32, 58, 84, 110},
            {6, 30, 58, 86, 114},
            {6, 34, 62, 90, 118},
            {6, 26, 50, 74, 98, 122},
            {6, 30, 54, 78, 102, 126},
            {6, 26, 52, 78, 104, 130},
            {6, 30, 56, 82, 108, 134},
            {6, 34, 60, 86, 112, 138},
            {6, 30, 58, 86, 114, 142},
            {6, 34, 62, 90, 118, 146},
            {6, 30, 54, 78, 102, 126, 150},
            {6, 24, 50, 76, 102, 128, 154},
            {6, 28, 54, 80, 106, 132, 158},
            {6, 32, 58, 84, 110, 136, 162},
            {6, 26, 54, 82, 110, 138, 166},
            {6, 30, 58, 86, 114, 142, 170},
    };

    private final int number;

    private QrVersion(int number) {
        this.number = number;
    }

    /** Version 1 to 40, else null. */
    static QrVersion of(int number) {
        return number < 1 || number > 40 ? null : new QrVersion(number);
    }

    int number() {
        return number;
    }

    /** Modules on a side: 17 plus 4 a version. */
    int dimension() {
        return 17 + 4 * number;
    }

    /** Shared; callers only read it. */
    int[] alignmentPatternCenters() {
        return ALIGNMENT_CENTERS[number - 1];
    }
}
