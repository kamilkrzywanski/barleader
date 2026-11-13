package io.github.kamilkrzywanski.barleader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import io.github.kamilkrzywanski.barleader.image.Samples;
import io.github.kamilkrzywanski.barleader.image.Samples.Kind;
import io.github.kamilkrzywanski.barleader.image.Samples.Sample;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BarcodeReaderTest {
    private final BarcodeReader reader = new BarcodeReader();

    @Test
    void cleanSymbolsRoundTripOnTheFirstAttempts() {
        for (Sample sample : Samples.all()) {
            if (sample.kind() != Kind.CLEAN) {
                continue;
            }
            List<BarcodeHit> hits = reader.read(sample.image(), ReadOptions.fast());
            assertCovers(sample, texts(hits));
            assertFalse(hits.isEmpty());
            assertTrue(
                    hits.get(0).attempt().startsWith("original"),
                    sample.name() + " used " + hits.get(0).attempt()
            );
        }
    }

    /** The shortest ladder reads what the image as it is and the located linear symbols give, with the budget of fast mode. */
    @Test
    void quickReadsCleanSymbolsAndLocatedLinearOnes() {
        ReadOptions quick = ReadOptions.quick();
        assertEquals(DecodeMode.QUICK, quick.mode());
        assertEquals(ReadOptions.FAST_ATTEMPTS, quick.maxAttempts());
        assertEquals(ReadOptions.FAST_BUDGET_MILLIS, quick.timeBudgetMillis());
        for (Sample sample : Samples.all()) {
            if (sample.kind() == Kind.CLEAN) {
                assertCovers(sample, texts(reader.read(sample.image(), quick)));
            }
        }
        // No upright row crosses a Code 128 at 30 degrees: the lines across its box or its strip read it.
        Sample tilted = sample("code128-rotated-30");
        List<BarcodeHit> hits = reader.read(tilted.image(), quick);
        assertCovers(tilted, texts(hits));
        assertFalse(hits.get(0).attempt().startsWith("original"), hits.get(0).attempt());
        // With findAll the located symbols are read as well.
        assertCovers(tilted, texts(reader.read(tilted.image(), quick.findAll(true))));
    }

    /**
     * What only a later step reads is left to fast mode: a Code 128 printed light on dark, which
     * the inverted frame reads, a QR Code under glare, which the frame without its shading reads,
     * and one in noise, which the finder trace reads.
     */
    @Test
    void quickLeavesWhatOnlyALaterStepReads() {
        for (String name : List.of("inverted-code128", "qr-glare", "qr-noise")) {
            Sample sample = sample(name);
            assertTrue(reader.read(sample.image(), ReadOptions.quick()).isEmpty(), name);
            assertCovers(sample, texts(reader.read(sample.image(), ReadOptions.fast())));
        }
    }

    @Test
    void rotatedCode128ReportsCornersInsideTheImage() {
        Sample sample = sample("code128");
        BufferedImage rotated = io.github.kamilkrzywanski.barleader.image.Samples.rotate(sample.image(), 90);
        List<BarcodeHit> hits = reader.read(rotated, ReadOptions.fast());
        assertCovers(sample, texts(hits));
        BarcodeHit hit = hits.get(0);
        assertFalse(hit.corners().isEmpty(), hit.attempt());
        for (ImagePoint corner : hit.corners()) {
            assertTrue(corner.x() >= -2 && corner.x() <= rotated.getWidth() + 1, corner.toString());
            assertTrue(corner.y() >= -2 && corner.y() <= rotated.getHeight() + 1, corner.toString());
        }
    }

    /**
     * No upright row crosses a Code 128 at 30° or 45°; fast finds its strip once the ladder misses,
     * from the lines across its box, the patches of bars or the band search.
     */
    @Test
    void fastReadsATiltedCode128FromItsStrip() {
        for (String name : List.of("code128-rotated-30", "code128-rotated-45")) {
            Sample sample = sample(name);
            List<BarcodeHit> hits = reader.read(sample.image(), ReadOptions.fast());
            assertCovers(sample, texts(hits));
            String attempt = hits.get(0).attempt();
            assertTrue(attempt.startsWith("scan-line") || attempt.startsWith("bar-strip") || attempt.startsWith("linear-band"),
                    name + " used " + attempt);
        }
    }

    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void beatsStandardZxingWithoutFalsePositives() {
        List<Sample> samples = new ArrayList<>(Samples.all());
        BarcodeReader reader = this.reader;
        ReadOptions fast = ReadOptions.fast();
        ReadOptions fastAll = ReadOptions.fast().findAll(true);
        ReadOptions aggressive = ReadOptions.aggressive();

        int fastAdvantageFound = 0;
        int baselineAdvantageFound = 0;
        int fastAdvantageTotal = 0;
        StringBuilder report = new StringBuilder();
        report.append("| case | kind | baseline | fast | aggressive | fast ms | aggressive ms |\n");
        report.append("| --- | --- | --- | --- | --- | ---: | ---: |\n");

        for (Sample sample : samples) {
            Outcome baseline = time(() -> ZxingBaseline.read(sample.image()));
            Outcome fastOutcome = time(() -> texts(reader.read(sample.image(), fast)));
            Outcome aggressiveOutcome = time(() -> texts(reader.read(sample.image(), aggressive)));
            report.append("| ")
                    .append(sample.name()).append(" | ")
                    .append(sample.kind()).append(" | ")
                    .append(score(sample, baseline.texts())).append(" | ")
                    .append(score(sample, fastOutcome.texts())).append(" | ")
                    .append(score(sample, aggressiveOutcome.texts())).append(" | ")
                    .append(fastOutcome.millis()).append(" | ")
                    .append(aggressiveOutcome.millis()).append(" |\n");

            if (sample.kind() == Kind.CLEAN) {
                assertCovers(sample, fastOutcome.texts());
            }
            if (sample.kind() == Kind.NEGATIVE) {
                assertTrue(fastOutcome.texts().isEmpty(), sample.name() + " fast " + fastOutcome.texts());
                assertTrue(aggressiveOutcome.texts().isEmpty(), sample.name() + " aggressive " + aggressiveOutcome.texts());
            }
            if (sample.kind() == Kind.FAST_ADVANTAGE) {
                fastAdvantageTotal += sample.expected().size();
                fastAdvantageFound += found(sample, fastOutcome.texts());
                baselineAdvantageFound += found(sample, baseline.texts());
                assertNoExtras(sample, fastOutcome.texts());
            }
            if (sample.kind() == Kind.AGGRESSIVE_ADVANTAGE) {
                assertCovers(sample, aggressiveOutcome.texts());
                assertNoExtras(sample, aggressiveOutcome.texts());
                assertTrue(
                        found(sample, baseline.texts()) < sample.expected().size()
                                || found(sample, fastOutcome.texts()) < sample.expected().size(),
                        sample.name() + " was already solved by the cheaper readers"
                );
            }
        }

        for (Sample pair : List.of(Samples.twoQrCodes(), Samples.qrAndCode128())) {
            Outcome baselinePair = time(() -> ZxingBaseline.read(pair.image()));
            Outcome fastPair = time(() -> texts(reader.read(pair.image(), fastAll)));
            report.append("| ").append(pair.name()).append(" | MULTI | ")
                    .append(score(pair, baselinePair.texts())).append(" | ")
                    .append(score(pair, fastPair.texts())).append(" | — | ")
                    .append(fastPair.millis()).append(" | — |\n");
            assertCovers(pair, fastPair.texts());
            assertNoExtras(pair, fastPair.texts());
            assertTrue(found(pair, baselinePair.texts()) < pair.expected().size(), pair.name() + " " + baselinePair.texts());
        }
        System.out.println(report);

        assertTrue(
                fastAdvantageFound > baselineAdvantageFound,
                "FAST recall on advantage cases was " + fastAdvantageFound + "/" + fastAdvantageTotal
                        + ", baseline " + baselineAdvantageFound + "/" + fastAdvantageTotal + "\n" + report
        );
    }

    private static Sample sample(String name) {
        return Samples.all().stream().filter(sample -> sample.name().equals(name)).findFirst().orElseThrow();
    }

    private static void assertCovers(Sample sample, List<String> actual) {
        for (String expected : sample.expected()) {
            assertTrue(actual.contains(expected), sample.name() + " missing " + expected + " in " + actual);
        }
    }

    private static void assertNoExtras(Sample sample, List<String> actual) {
        for (String text : actual) {
            assertTrue(sample.expected().contains(text), sample.name() + " false positive " + text);
        }
    }

    private static int found(Sample sample, List<String> actual) {
        int count = 0;
        for (String expected : sample.expected()) {
            if (actual.contains(expected)) {
                count++;
            }
        }
        return count;
    }

    private static String score(Sample sample, List<String> actual) {
        if (sample.expected().isEmpty()) {
            return actual.isEmpty() ? "ok" : "FP " + actual;
        }
        return found(sample, actual) + "/" + sample.expected().size()
                + (extras(sample, actual) == 0 ? "" : " FP " + actual);
    }

    private static int extras(Sample sample, List<String> actual) {
        int count = 0;
        for (String text : actual) {
            if (!sample.expected().contains(text)) {
                count++;
            }
        }
        return count;
    }

    private static List<String> texts(List<BarcodeHit> hits) {
        List<String> texts = new ArrayList<>();
        for (BarcodeHit hit : hits) {
            texts.add(hit.text());
        }
        return texts;
    }

    private static Outcome time(java.util.function.Supplier<List<String>> call) {
        long start = System.nanoTime();
        List<String> texts = call.get();
        long millis = (System.nanoTime() - start) / 1_000_000L;
        return new Outcome(texts, millis);
    }

    private record Outcome(List<String> texts, long millis) {
    }
}
