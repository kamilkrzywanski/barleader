package io.github.kamilkrzywanski.barleader.locate.dm;

import io.github.kamilkrzywanski.barleader.image.GrayU8;
import org.junit.jupiter.api.Test;
import io.github.kamilkrzywanski.barleader.image.GrayImages;
import io.github.kamilkrzywanski.barleader.image.Samples;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DataMatrixDetectorTest {
    @Test
    void readsCleanSquareDataMatrix() {
        assertReads("datamatrix", "DM-PRO-123");
    }

    @Test
    void readsPerspectiveDataMatrixFromEitherPolarity() {
        assertReads("perspective-datamatrix", "DM-SKEW");
    }

    @Test
    void readsSmallDataMatrixOnALargeLabel() {
        assertReads("datamatrix-on-label", "DM-TILE");
    }

    /**
     * The edge trace with its four directions side by side finds the grids it finds one direction
     * after another, here on pages of twelve to twenty symbols, where the trace stops at eight grids.
     */
    @Test
    void directionsSideBySideReadWhatTheyReadInTurn() throws Exception {
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            java.util.function.Consumer<List<Runnable>> runAll = tasks -> {
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
            java.util.Random random = new java.util.Random(17);
            int capped = 0;
            for (int page = 0; page < 6; page++) {
                GrayU8 gray = page(random, 4 + page % 2, 3 + page % 3);
                DmGeometry.Bits bits = DmBinarizer.blackMatrix(gray);
                List<DataMatrixDetector.Found> inTurn = DataMatrixDetector.read(bits);
                List<DataMatrixDetector.Found> together = DataMatrixDetector.read(bits, runAll);
                org.junit.jupiter.api.Assertions.assertEquals(describe(inTurn), describe(together), "page " + page);
                capped += inTurn.size() >= 4 ? 1 : 0;
            }
            assertTrue(capped >= 4, "pages that read four symbols or more: " + capped);
        } finally {
            pool.shutdown();
        }
    }

    /** {@code columns} by {@code rows} Data Matrix symbols on a speckled page, every other one turned. */
    private static GrayU8 page(java.util.Random random, int columns, int rows) throws Exception {
        int cell = 150;
        GrayU8 gray = new GrayU8(columns * cell + 40, rows * cell + 40);
        for (int i = 0; i < gray.data.length; i++) {
            gray.data[i] = (byte) (215 + random.nextInt(40));
        }
        for (int i = 0; i < columns; i++) {
            for (int j = 0; j < rows; j++) {
                com.google.zxing.common.BitMatrix symbol = new com.google.zxing.datamatrix.DataMatrixWriter().encode(
                        "PAGE-" + i + "-" + j + "-" + random.nextInt(100_000), com.google.zxing.BarcodeFormat.DATA_MATRIX, cell - 30, cell - 30);
                boolean turned = (i + j) % 2 == 1;
                for (int y = 0; y < symbol.getHeight(); y++) {
                    for (int x = 0; x < symbol.getWidth(); x++) {
                        if (symbol.get(x, y)) {
                            gray.set(20 + i * cell + (turned ? y : x), 20 + j * cell + (turned ? symbol.getWidth() - 1 - x : y),
                                    20 + random.nextInt(40));
                        }
                    }
                }
            }
        }
        return gray;
    }

    private static String describe(List<DataMatrixDetector.Found> found) {
        StringBuilder out = new StringBuilder();
        for (DataMatrixDetector.Found hit : found) {
            out.append(hit.text()).append(java.util.Arrays.toString(hit.bytes())).append(hit.corners())
                    .append(hit.orientationDegrees()).append('|');
        }
        return out.toString();
    }

    private static void assertReads(String name, String text) {
        Samples.Sample sample = Samples.all().stream()
                .filter(item -> item.name().equals(name))
                .findFirst()
                .orElseThrow();
        GrayU8 gray = GrayImages.from(sample.image());
        List<DataMatrixDetector.Found> found = DataMatrixDetector.read(gray);
        if (found.stream().noneMatch(hit -> text.equals(hit.text()))) {
            found = DataMatrixDetector.read(GrayImages.invert(gray));
        }
        assertTrue(
                found.stream().anyMatch(hit -> text.equals(hit.text())),
                found.toString()
        );
    }
}
