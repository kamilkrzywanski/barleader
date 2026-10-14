package io.github.kamilkrzywanski.barcode.eval;

import org.junit.jupiter.api.Test;
import io.github.kamilkrzywanski.barcode.eval.ArteLabScoring.Frame;
import io.github.kamilkrzywanski.barcode.eval.ArteLabScoring.Kind;
import io.github.kamilkrzywanski.barcode.eval.ArteLabScoring.Tally;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ArteLabScoringTest {
    @Test
    void exactAndUpcAMatchTheLabel() {
        assertEquals(Kind.EXACT, ArteLabScoring.classify("Dataset2/Foto(542).jpg", "8002205319804", "8002205319804"));
        assertEquals(Kind.UPCA, ArteLabScoring.classify("Dataset1/05102009148.jpg", "0734646964449", "734646964449"));
        assertEquals(Kind.EXACT, ArteLabScoring.classify("Dataset1/05102009148.jpg", "0734646964449", "0734646964449"));
    }

    @Test
    void upcEIsNotALabelMatch() {
        assertEquals(Kind.FP, ArteLabScoring.classify("Dataset1/05102009159.jpg", "4005808801046", "10058084"));
        assertEquals(Kind.FP, ArteLabScoring.classify("Dataset2/Foto(542).jpg", "8002205319804", "2094297032262"));
        assertEquals(Kind.EMPTY, ArteLabScoring.classify("Dataset2/Foto(725).jpg", "8023222032262", ""));
        assertEquals(Kind.EMPTY, ArteLabScoring.classify("Dataset2/Foto(725).jpg", "8023222032262", null));
    }

    @Test
    void emptyUpstreamLabelKeepsOnlyThePrintedCode() {
        assertEquals(Kind.ALLOWED, ArteLabScoring.classify("Dataset2/Foto(844).jpg", "", "7321925005431"));
        assertEquals(Kind.ALLOWED, ArteLabScoring.classify("Dataset1/05102009199.jpg", "", "8002330112752"));
        assertEquals(Kind.ALLOWED, ArteLabScoring.classify("Dataset2/Foto(849).jpg", "", "7321925005431"));
        assertEquals(Kind.FP, ArteLabScoring.classify("Dataset2/Foto(844).jpg", "", "8002205319804"));
        assertEquals(Kind.FP, ArteLabScoring.classify("Dataset2/Foto(542).jpg", "8002205319804", "7321925005431"));
        assertEquals(Kind.EMPTY, ArteLabScoring.classify("Dataset2/Foto(849).jpg", "", ""));
    }

    @Test
    void cellTruncatesLongPayloads() {
        String text = "a".repeat(90);
        String cell = ArteLabScoring.cell("QR_CODE", "original-hybrid", 0, text);
        assertEquals("QR_CODE@original-hybrid/0:" + "a".repeat(80) + "...len=90", cell);
        assertEquals("", ArteLabScoring.cell("EAN_13", "illumination", -1, ""));
    }

    @Test
    void tallyPartitionsTheDecisiveRead() {
        List<Frame> frames = List.of(
                frame("Dataset1/a.jpg", "Dataset1", Kind.EXACT, Kind.EXACT, Kind.SKIPPED, false, 10, 0),
                frame("Dataset1/b.jpg", "Dataset1", Kind.EMPTY, Kind.UPCA, Kind.SKIPPED, false, 20, 0),
                frame("Dataset2/c.jpg", "Dataset2", Kind.EMPTY, Kind.EMPTY, Kind.EXACT, true, 30, 400),
                frame("Dataset2/d.jpg", "Dataset2", Kind.EMPTY, Kind.EMPTY, Kind.EMPTY, true, 40, 500),
                frame("Dataset2/e.jpg", "Dataset2", Kind.EMPTY, Kind.EMPTY, Kind.ALLOWED, true, 50, 600),
                frame("Dataset1/f.jpg", "Dataset1", Kind.EXACT, Kind.FP, Kind.FP, true, 60, 70)
        );
        Tally all = ArteLabScoring.tally(frames).get(2);
        assertEquals("ALL", all.setName());
        assertEquals(6, all.n());
        assertEquals(2, all.baselineHit());
        assertEquals(2, all.fastHit());
        assertEquals(3, all.unionHit());
        assertEquals(1, all.empty());
        assertEquals(1, all.allowed());
        assertEquals(1, all.fp());
        assertEquals(0, all.error());
        assertEquals(60, all.maxFastMs());
        assertEquals(600, all.maxAggressiveMs());
        assertEquals(all.n(), all.unionHit() + all.empty() + all.allowed() + all.fp() + all.error());
        assertFalse(ArteLabScoring.labeledHit(Kind.ALLOWED));
        assertEquals(Kind.EXACT, ArteLabScoring.decisive(frames.get(0)));
        assertEquals(Kind.FP, ArteLabScoring.decisive(frames.get(5)));
    }

    private static Frame frame(
            String file,
            String setName,
            Kind baseline,
            Kind fast,
            Kind aggressive,
            boolean aggressiveRan,
            long fastMs,
            long aggressiveMs
    ) {
        return new Frame(file, setName, baseline, fast, aggressive, aggressiveRan, fastMs, aggressiveMs);
    }
}
