package io.github.kamilkrzywanski.barleader.image;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BinaryBitmapTest {
    /** Bars of 1 to 4 pixels on white, the same on every row, with some noise. */
    private static GrayU8 bars() {
        java.util.Random random = new java.util.Random(5);
        GrayU8 gray = new GrayU8(120, 48);
        for (int y = 0; y < gray.height; y++) {
            int x = 10;
            boolean bar = true;
            for (int k = 0; x < gray.width - 10; k++) {
                int width = 1 + (k * 7) % 4;
                for (int i = 0; i < width && x < gray.width - 10; i++, x++) {
                    gray.data[y * gray.width + x] = (byte) ((bar ? 30 : 220) + random.nextInt(9));
                }
                bar = !bar;
            }
            for (x = 0; x < gray.width; x++) {
                if (x < 10 || x >= gray.width - 10) {
                    gray.data[y * gray.width + x] = (byte) (220 + random.nextInt(9));
                }
            }
        }
        return gray;
    }

    @Test
    void aRowLeftOutLooksFlatToTheLinearReadersAndTheMatrixKeepsIt() {
        GrayU8 gray = bars();
        BinaryBitmap every = BinaryBitmap.hybrid(new GrayLuminanceSource(gray));
        boolean[] marks = new boolean[gray.height];
        marks[10] = true;
        BinaryBitmap some = BinaryBitmap.hybrid(new GrayLuminanceSource(gray)).restrictRows(marks);

        assertNotNull(some.blackRow(10));
        assertArrayEquals(every.runs(10, false), some.runs(10, false));
        assertArrayEquals(every.runs(10, true, 4), some.runs(10, true, 4));
        assertNotNull(every.blackRow(20));
        assertNull(some.blackRow(20));
        assertNull(some.runs(20, false));
        assertNull(some.runs(20, false, 4));

        BlackMatrix all = every.getBlackMatrix();
        BlackMatrix kept = some.getBlackMatrix();
        for (int y = 0; y < gray.height; y++) {
            for (int x = 0; x < gray.width; x++) {
                assertEquals(all.get(x, y), kept.get(x, y), x + "," + y);
            }
        }
    }

    /** The black point of a row is estimated once; the plain and the fine runs see the same one. */
    @Test
    void aRowReadAgainIsTheSameRow() {
        GrayU8 gray = bars();
        BinaryBitmap fresh = BinaryBitmap.hybrid(new GrayLuminanceSource(gray));
        BinaryBitmap used = BinaryBitmap.hybrid(new GrayLuminanceSource(gray));
        for (int y = 0; y < gray.height; y++) {
            used.runs(y, false);
            used.getBlackRow(y, null);
        }
        for (int y = 0; y < gray.height; y++) {
            assertArrayEquals(fresh.getBlackRow(y, null), used.getBlackRow(y, null));
            assertArrayEquals(BinaryBitmap.hybrid(new GrayLuminanceSource(gray)).runs(y, true, 4), used.runs(y, true, 4));
        }
    }

    @Test
    void aMarkIsNeededForEveryRow() {
        BinaryBitmap bitmap = BinaryBitmap.hybrid(new GrayLuminanceSource(bars()));
        assertThrows(IllegalArgumentException.class, () -> bitmap.restrictRows(new boolean[3]));
    }

    /**
     * The edges of the fine runs lie where the sharpened row crosses the black point between two
     * pixel centres: blurred bars of one to three pixels, a ramp with two dips, and noise around a
     * step. These are the runs the sharpened row gave when every pixel of it was compared as a float.
     */
    @Test
    void fineRunsPutEachEdgeWhereTheSharpenedRowCrossesTheBlackPoint() {
        int width = 48;
        GrayU8 gray = new GrayU8(width, 3);
        java.util.Random random = new java.util.Random(5);
        for (int x = 0; x < width; x++) {
            gray.set(x, 0, (x / 2) % 5 == 0 ? 40 : (x % 3 == 0 ? 120 : 210));
            gray.set(x, 1, x < 16 ? 220 - x * 3 : x == 30 || x == 31 ? 60 : x == 40 ? 90 : 200);
            gray.set(x, 2, (x < 24 ? 60 : 190) + random.nextInt(31) - 15);
        }
        BinaryBitmap bitmap = BinaryBitmap.hybrid(new GrayLuminanceSource(gray));
        assertArrayEquals(new int[]{4, 4, 4, 4, 8, 4, 9, 14, 9, 4, 8, 4, 4, 8, 8, 4, 8, 4, 8, 8, 4, 4, 8, 4, 9, 14, 9, 4, 8},
                bitmap.runs(0, false, 4));
        assertArrayEquals(new int[]{120, 8, 31, 6, 27}, bitmap.runs(1, false, 4));
        assertArrayEquals(new int[]{4, 93, 95}, bitmap.runs(2, false, 4));
    }

    /**
     * The hybrid matrix built in bands side by side is the matrix built in one pass: on noise with
     * flat patches, whose black points come from their neighbours, and on heights and widths that
     * are and are not multiples of a block.
     */
    @Test
    void hybridMatrixInBandsIsTheSameMatrix() throws Exception {
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            java.util.function.Consumer<java.util.List<Runnable>> runAll = tasks -> {
                // As the reader's own pool does, the waiting thread runs what no thread has started, so
                // pieces that run pieces of their own cannot all wait on work queued behind them.
                java.util.List<java.util.concurrent.FutureTask<Void>> futures = new java.util.ArrayList<>();
                for (Runnable task : tasks) {
                    java.util.concurrent.FutureTask<Void> future = new java.util.concurrent.FutureTask<>(task, null);
                    pool.execute(future);
                    futures.add(future);
                }
                for (java.util.concurrent.FutureTask<Void> future : futures) {
                    future.run();
                    try {
                        future.get();
                    } catch (Exception exception) {
                        throw new IllegalStateException(exception);
                    }
                }
            };
            java.util.Random random = new java.util.Random(9);
            int[][] sizes = {{40, 40}, {643, 517}, {200, 1003}, {64, 136}, {517, 64}, {1001, 777}};
            for (int[] size : sizes) {
                GrayU8 gray = new GrayU8(size[0], size[1]);
                for (int i = 0; i < gray.data.length; i++) {
                    gray.data[i] = (byte) random.nextInt(256);
                }
                for (int patch = 0; patch < 30; patch++) {
                    int w = 1 + random.nextInt(size[0] / 2);
                    int h = 1 + random.nextInt(size[1] / 2);
                    int x0 = random.nextInt(size[0] - w + 1);
                    int y0 = random.nextInt(size[1] - h + 1);
                    int shade = random.nextInt(256);
                    for (int y = y0; y < y0 + h; y++) {
                        for (int x = x0; x < x0 + w; x++) {
                            gray.set(x, y, Math.max(0, Math.min(255, shade + random.nextInt(9) - 4)));
                        }
                    }
                }
                BlackMatrix plain = BinaryBitmap.hybrid(new GrayLuminanceSource(gray)).getBlackMatrix();
                BlackMatrix banded = BinaryBitmap.hybrid(new GrayLuminanceSource(gray)).sideBySide(runAll).getBlackMatrix();
                boolean[] a = new boolean[size[0]];
                boolean[] b = new boolean[size[0]];
                for (int y = 0; y < size[1]; y++) {
                    plain.copyRow(y, a, 0);
                    banded.copyRow(y, b, 0);
                    assertArrayEquals(a, b, size[0] + "x" + size[1] + " row " + y);
                }
            }
        } finally {
            pool.shutdown();
        }
    }
}
