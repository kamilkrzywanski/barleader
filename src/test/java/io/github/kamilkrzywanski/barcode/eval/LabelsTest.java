package io.github.kamilkrzywanski.barcode.eval;

import io.github.kamilkrzywanski.barcode.ImagePoint;
import io.github.kamilkrzywanski.barcode.eval.Labels.Code;
import io.github.kamilkrzywanski.barcode.eval.Labels.Codes;
import io.github.kamilkrzywanski.barcode.eval.Labels.Gtin;
import io.github.kamilkrzywanski.barcode.eval.Labels.Negative;
import io.github.kamilkrzywanski.barcode.eval.Labels.Outcome;
import io.github.kamilkrzywanski.barcode.eval.Labels.Read;
import io.github.kamilkrzywanski.barcode.eval.Labels.RegionScore;
import io.github.kamilkrzywanski.barcode.eval.Labels.Regions;
import io.github.kamilkrzywanski.barcode.eval.Labels.Text;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LabelsTest {
    private static Read read(String symbology, String text, ImagePoint... corners) {
        return new Read(symbology, text, "fast", -1, 1.0, List.of(corners));
    }

    @Test
    void gtinLabelsAcceptUpcAWithTheLeadingZero() {
        assertEquals(Outcome.HIT, Labels.judge(new Gtin("0051122414831"), List.of(read("UPC_A", "051122414831"))));
        assertEquals(Outcome.HIT, Labels.judge(new Gtin("8002330112752"), List.of(read("EAN_13", "8002330112752"))));
        assertEquals(Outcome.WRONG, Labels.judge(new Gtin("8002330112752"), List.of(read("EAN_13", "8002330112753"))));
        assertEquals(Outcome.MISS, Labels.judge(new Gtin("8002330112752"), List.of()));
    }

    @Test
    void textLabelsAllowTheUsualPrintingDifferences() {
        assertEquals(Outcome.HIT, Labels.judge(new Text("0012345000065"), List.of(read("UPC_E", "01234565"))));
        assertEquals(Outcome.HIT, Labels.judge(new Text("(01)90012345678908"), List.of(read("DATABAR_EXPANDED", "0190012345678908"))));
        assertEquals(Outcome.HIT, Labels.judge(new Text("A1234567890A"), List.of(read("CODABAR", "1234567890"))));
        assertEquals(Outcome.HIT, Labels.judge(new Text("hello\n"), List.of(read("QR_CODE", "hello"))));
        assertEquals(Outcome.PARTIAL, Labels.judge(new Text("978073520044951299"), List.of(read("EAN_13", "9780735200449"))));
    }

    @Test
    void anyReadOnANegativeIsFalse() {
        assertEquals(Outcome.CLEAN, Labels.judge(new Negative(), List.of()));
        assertEquals(Outcome.FALSE_READ, Labels.judge(new Negative(), List.of(read("CODE_39", "X"))));
    }

    @Test
    void cornerLabelsMatchQrReadsThatOverlapThem() {
        List<ImagePoint> code = List.of(new ImagePoint(10, 10), new ImagePoint(50, 10), new ImagePoint(50, 50), new ImagePoint(10, 50));
        Regions regions = new Regions(Regions.QR, List.of(code));
        Read on = read("QR_CODE", "a", new ImagePoint(12, 12), new ImagePoint(48, 12), new ImagePoint(48, 48), new ImagePoint(12, 48));
        Read off = read("QR_CODE", "b", new ImagePoint(100, 100), new ImagePoint(140, 100), new ImagePoint(140, 140), new ImagePoint(100, 140));
        assertEquals(new RegionScore(1, 1, 1), Labels.judgeRegions(regions, List.of(on, off)));
        assertEquals(new RegionScore(0, 1, 0), Labels.judgeRegions(regions, List.of(read("EAN_13", "1"))));
    }

    @Test
    void boxLabelsMatchLinearReadsByTheCentreOfTheirScanLine() {
        Regions box = new Regions(Regions.EAN, List.of(List.of(
                new ImagePoint(100, 50), new ImagePoint(200, 50), new ImagePoint(200, 120), new ImagePoint(100, 120))));
        Read inside = read("EAN_13", "4006313001167", new ImagePoint(110, 85), new ImagePoint(190, 85));
        Read outside = read("UPC_A", "012345678905", new ImagePoint(310, 85), new ImagePoint(390, 85));
        assertEquals(new RegionScore(1, 1, 1), Labels.judgeRegions(box, List.of(inside, outside)));
        assertEquals(new RegionScore(0, 1, 0), Labels.judgeRegions(box, List.of(read("QR_CODE", "x", new ImagePoint(150, 85)))));
    }

    @Test
    void barberCodesMatchByTextAndCountOnlyReadableOnes() {
        Codes codes = new Codes(List.of(
                new Code("EAN13", "8005200006304", List.of()),
                new Code("C39", "*S015610095C*", List.of()),
                new Code("UPCS", "734646964449", List.of()),
                new Code("1D", null, List.of()),
                new Code("ROYALMAILCODE", "0123456ABCF", List.of())));
        List<Read> reads = List.of(read("EAN_13", "8005200006304"), read("CODE_39", "S015610095C"),
                read("EAN_13", "0734646964449"), read("QR_CODE", "elsewhere"));
        assertEquals(new RegionScore(3, 3, 1), Labels.judgeCodes(codes, reads));
    }

    @Test
    void barberTextsLoseTheEscapingOfVia() {
        assertEquals("http://finland.fi/?culture=ru-RU&contentlan=15", Labels.unescapeVia("http://finland.fi/?culture=ru-RU&amp;contentlan=15"));
        assertEquals("[)>06:AS:PN<x/>", Labels.unescapeVia("[)&gt;06:AS:PN&lt;x/&gt;"));
        assertEquals("&lt;", Labels.unescapeVia("&amp;lt;"));
        assertEquals("8005200006304", Labels.unescapeVia("8005200006304"));
    }

    @Test
    void jsonKeepsTheShapesTheLabelsUse() {
        Object parsed = Json.parse("{\"a\": [1, 2.5, -3e2], \"b\": {\"c\": \"x\\\"\\u0041\"}, \"d\": null, \"e\": true}");
        assertEquals("{a=[1.0, 2.5, -300.0], b={c=x\"A}, d=null, e=true}", parsed.toString());
    }

    @Test
    void kaistFilesAreNamedAfterTheirCode() {
        assertEquals("0016000502666", Labels.kaistDigits("0016000502666"));
        assertEquals("4006313001167", Labels.kaistDigits("4006313001167-01"));
        assertEquals("8801043015653", Labels.kaistDigits("8801043015653_2"));
        assertEquals("00050555", Labels.kaistDigits("00050555"));
        assertEquals("", Labels.kaistDigits("IMG_0001"));
    }

    @Test
    void textLabelsIgnoreTheLineEndingsOfTheLabelFile() {
        assertEquals(Outcome.HIT, Labels.judge(new Text("BEGIN:VCARD\r\nN:Gump\r\n"), List.of(read("QR_CODE", "BEGIN:VCARD\nN:Gump"))));
    }

    @Test
    void foldersGroupWithoutTheirNumber() {
        assertEquals("qrcode", Labels.family("datasets/zxing-cpp-3.1.1/samples/qrcode-2/10.png"));
        assertEquals("zxing-cpp", Labels.set("src/test/resources/official/qrcode-1/17.png"));
        assertEquals("kaist-deal", Labels.set("external/kaist-deal/barcode_bb/barcode_bb/single_test/0016000502666.jpg"));
        assertEquals("barcode_bb/single_test", Labels.family("external/kaist-deal/barcode_bb/barcode_bb/single_test/0016000502666.jpg"));
        assertEquals("nominal", Labels.family("external/boofcv-qr/qrcodes/detection/nominal/image001.jpg"));
        assertEquals("Dataset2", Labels.family("datasets/artelab-medium-barcode-1d/Dataset2/Foto(312).jpg"));
    }
}
