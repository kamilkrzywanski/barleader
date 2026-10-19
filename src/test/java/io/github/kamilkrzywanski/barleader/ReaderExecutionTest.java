package io.github.kamilkrzywanski.barleader;

import io.github.kamilkrzywanski.barleader.image.Samples;
import io.github.kamilkrzywanski.barleader.image.Samples.Sample;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** The parallel, caller-supplied and sequential readers return the same hits, and none of them deadlocks. */
class ReaderExecutionTest {
    private static final ReadOptions FAST = ReadOptions.fast().timeBudgetMillis(Long.MAX_VALUE);
    private static final ReadOptions AGGRESSIVE = ReadOptions.aggressive().timeBudgetMillis(Long.MAX_VALUE);

    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void everyExecutionModeReturnsTheSameHits() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            BarcodeReader shared = new BarcodeReader();
            BarcodeReader own = new BarcodeReader(pool);
            BarcodeReader sequential = BarcodeReader.sequential();
            for (Sample sample : Samples.all()) {
                for (ReadOptions options : List.of(FAST, AGGRESSIVE, FAST.findAll(true))) {
                    List<BarcodeHit> expected = shared.read(sample.image(), options);
                    assertEquals(expected, own.read(sample.image(), options), sample.name());
                    assertEquals(expected, sequential.read(sample.image(), options), sample.name());
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /** Every read runs on the only thread of the executor it hands its variants to. */
    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void readFromInsideItsOwnSingleThreadExecutorDoesNotDeadlock() throws Exception {
        ExecutorService single = Executors.newSingleThreadExecutor();
        try {
            BarcodeReader reader = new BarcodeReader(single);
            Sample sample = Samples.all().stream()
                    .filter(s -> s.name().equals("code128-rotated-30"))
                    .findFirst()
                    .orElseThrow();
            Future<List<BarcodeHit>> nested = single.submit(() -> reader.read(sample.image(), AGGRESSIVE));
            List<BarcodeHit> hits = nested.get(90, TimeUnit.SECONDS);
            assertFalse(hits.isEmpty());
            assertEquals(sample.expected().get(0), hits.get(0).text());
        } finally {
            single.shutdownNow();
        }
    }

    @Test
    void anExecutorThatRejectsWorkStillReads() {
        ExecutorService closed = Executors.newSingleThreadExecutor();
        closed.shutdown();
        Sample sample = Samples.all().get(0);
        List<BarcodeHit> hits = new BarcodeReader(closed).read(sample.image(), FAST);
        assertEquals(new BarcodeReader().read(sample.image(), FAST), hits);
    }

    @Test
    void manyCallersShareOneReader() throws Exception {
        BarcodeReader reader = new BarcodeReader();
        List<Sample> samples = Samples.all();
        List<List<BarcodeHit>> expected = new ArrayList<>();
        for (Sample sample : samples) {
            expected.add(reader.read(sample.image(), FAST));
        }
        ExecutorService callers = Executors.newFixedThreadPool(8);
        try {
            List<Future<List<BarcodeHit>>> futures = new ArrayList<>();
            for (int round = 0; round < 3; round++) {
                for (Sample sample : samples) {
                    futures.add(callers.submit(() -> reader.read(sample.image(), FAST)));
                }
            }
            for (int i = 0; i < futures.size(); i++) {
                assertEquals(expected.get(i % samples.size()), futures.get(i).get(), samples.get(i % samples.size()).name());
            }
        } finally {
            callers.shutdownNow();
        }
    }
}
