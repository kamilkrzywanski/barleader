package io.github.kamilkrzywanski.barleader.decode;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.oned.Code128Writer;
import io.github.kamilkrzywanski.barleader.image.BinaryBitmap;
import io.github.kamilkrzywanski.barleader.image.GrayLuminanceSource;
import io.github.kamilkrzywanski.barleader.image.GrayU8;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Code128FitTest {
    /**
     * A parcel code with modules of one pixel at eight phases of the bars against the pixels. The
     * row scan, with its edges in quarter pixels too, reads only some phases; the fit reads every
     * one, the right way round and upside down.
     */
    @Test
    void readsModulesOfOnePixelAtEveryPhase() {
        String text = "ED482267729TH";
        // Twelve modules of quiet zone on each side, as on the parcel labels.
        BitMatrix bars = new Code128Writer().encode(text, BarcodeFormat.CODE_128, 1, 1, Map.of(EncodeHintType.MARGIN, 24));
        int rowScan = 0;
        for (int eighth = 0; eighth < 8; eighth++) {
            GrayU8 strip = BlurredBars.render(bars, 1.0, eighth / 8.0);
            if (Code128Scanner.read(BinaryBitmap.global(new GrayLuminanceSource(strip))) != null) {
                rowScan++;
            }
            Code128Fit.Found found = Code128Fit.read(strip);
            assertNotNull(found, "phase " + eighth);
            assertEquals(text, found.text());
            assertFalse(found.upsideDown());
            assertEquals(12 + eighth / 8.0, found.left(), 0.75);
            Code128Fit.Found turned = Code128Fit.read(mirror(strip));
            assertNotNull(turned, "mirrored phase " + eighth);
            assertEquals(text, turned.text());
            assertTrue(turned.upsideDown());
        }
        assertTrue(rowScan < 8, rowScan + " phases read by the row scan");
    }

    /** Bars of random widths between quiet zones fit no Code 128 well enough to be returned. */
    @Test
    void returnsNothingForBarsThatAreNoSymbol() {
        Random random = new Random(128);
        for (int trial = 0; trial < 20; trial++) {
            BitMatrix bars = new BitMatrix(180, 1);
            int x = 12;
            boolean bar = true;
            while (x < 168) {
                int width = 1 + random.nextInt(4);
                if (bar) {
                    bars.setRegion(x, 0, Math.min(width, 168 - x), 1);
                }
                x += width;
                bar = !bar;
            }
            assertNull(Code128Fit.read(BlurredBars.render(bars, 1.0, 0.3)), "trial " + trial);
        }
    }

    /** Its bands, spans and directions side by side on a pool, the fit returns what it returns one after another, read or not. */
    @Test
    void sideBySideFitsWhatOneAfterAnotherFits() {
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(4);
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
        try {
            BitMatrix code = new Code128Writer().encode("ED482267729TH", BarcodeFormat.CODE_128, 1, 1, Map.of(EncodeHintType.MARGIN, 24));
            Random random = new Random(129);
            int read = 0;
            for (int trial = 0; trial < 16; trial++) {
                BitMatrix bars = code;
                if (trial >= 8) {
                    bars = new BitMatrix(180, 1);
                    for (int x = 12, width, bar = 1; x < 168; x += width, bar ^= 1) {
                        width = 1 + random.nextInt(4);
                        if (bar == 1) {
                            bars.setRegion(x, 0, Math.min(width, 168 - x), 1);
                        }
                    }
                }
                GrayU8 strip = BlurredBars.render(bars, 1.0, (trial % 8) / 8.0);
                for (GrayU8 image : new GrayU8[]{strip, mirror(strip)}) {
                    Code128Fit.Found inTurn = Code128Fit.read(image);
                    assertEquals(inTurn, Code128Fit.read(new StripBands(image), runAll), "trial " + trial);
                    read += inTurn == null ? 0 : 1;
                }
            }
            assertEquals(16, read);
        } finally {
            pool.shutdown();
        }
    }

    private static GrayU8 mirror(GrayU8 strip) {
        GrayU8 out = new GrayU8(strip.width, strip.height);
        for (int y = 0; y < strip.height; y++) {
            for (int x = 0; x < strip.width; x++) {
                out.set(strip.width - 1 - x, y, strip.get(x, y));
            }
        }
        return out;
    }
}
