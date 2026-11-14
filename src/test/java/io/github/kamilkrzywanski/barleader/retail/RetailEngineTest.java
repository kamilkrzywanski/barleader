package io.github.kamilkrzywanski.barleader.retail;

import io.github.kamilkrzywanski.barleader.decode.ProfileLines;
import io.github.kamilkrzywanski.barleader.image.GrayU8;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetailEngineTest {
    private static ProfileLines.Quad box(GrayU8 image, int margin) {
        return new ProfileLines.Quad(margin - 20, margin + 4, image.width - margin + 20, margin + 4,
                image.width - margin + 20, image.height - margin - 4, margin - 20, image.height - margin - 4);
    }

    @Test
    void readsASymbolOnTheRowsOfItsBox() {
        GrayU8 image = Bars.image(Bars.modules(Bars.TEXT), 3, 80, 60);
        List<RetailEngine.Found> found = RetailEngine.read(image, List.of(box(image, 60)));
        assertEquals(1, found.size());
        RetailEngine.Found symbol = found.get(0);
        assertEquals(Bars.TEXT, symbol.text());
        assertEquals(0, symbol.box());
        assertFalse(symbol.upsideDown());
        assertTrue(symbol.support() >= 4, "rows " + symbol.support());
        assertTrue(symbol.clear());
        // The outline: the first row from the start guard to the end guard, then the last row back.
        double[] p = symbol.polygon();
        assertEquals(60, p[0], 1.5);
        assertEquals(60 + 95 * 3, p[2], 1.5);
        assertTrue(p[1] >= 64 && p[1] < p[5] && p[5] <= 136, "rows " + p[1] + " to " + p[5]);
    }

    @Test
    void readsABoxGivenUpsideDown() {
        GrayU8 image = Bars.image(Bars.modules(Bars.TEXT), 3, 80, 60);
        ProfileLines.Quad box = box(image, 60);
        ProfileLines.Quad turned = new ProfileLines.Quad(box.x2(), box.y2(), box.x3(), box.y3(), box.x0(), box.y0(), box.x1(), box.y1());
        List<RetailEngine.Found> found = RetailEngine.read(image, List.of(turned));
        assertEquals(1, found.size());
        assertEquals(Bars.TEXT, found.get(0).text());
        assertTrue(found.get(0).upsideDown());
    }

    /** At a pixel and a half a module the bars are gray steps, not runs of a width: the rows still agree on the text. */
    @Test
    void readsASymbolOfAPixelAndAHalfAModule() {
        GrayU8 image = Bars.image(Bars.modules(Bars.TEXT), 1.5, 60, 50);
        List<RetailEngine.Found> found = RetailEngine.read(image, List.of(box(image, 50)));
        assertEquals(1, found.size());
        assertEquals(Bars.TEXT, found.get(0).text());
    }

    /** An EAN-8 reads on the rows of its box when it is asked for, and not otherwise. */
    @Test
    void readsAnEan8WhenAskedFor() {
        GrayU8 image = Bars.image(Bars.modules8("96385074"), 3, 80, 60);
        RetailEngine.Seen seen = RetailEngine.look(image, List.of(box(image, 60)), true);
        assertTrue(seen.symbols().isEmpty());
        assertEquals(1, seen.shorts().size());
        RetailEngine.Found symbol = seen.shorts().get(0);
        assertEquals("96385074", symbol.text());
        assertFalse(symbol.upsideDown());
        assertTrue(symbol.support() >= 4, "rows " + symbol.support());
        double[] p = symbol.polygon();
        assertEquals(60, p[0], 1.5);
        assertEquals(60 + 67 * 3, p[2], 1.5);
        assertTrue(RetailEngine.look(image, List.of(box(image, 60))).shorts().isEmpty());
    }

    /** The rows of an EAN-13 read no EAN-8: the long symbol has the place. */
    @Test
    void anEan13GivesNoEan8() {
        GrayU8 image = Bars.image(Bars.modules(Bars.TEXT), 3, 80, 60);
        RetailEngine.Seen seen = RetailEngine.look(image, List.of(box(image, 60)), true);
        assertEquals(1, seen.symbols().size());
        assertTrue(seen.shorts().isEmpty());
    }

    /** Two boxes over one symbol are one reading, the one with more rows. */
    @Test
    void twoBoxesOverOneSymbolGiveItOnce() {
        GrayU8 image = Bars.image(Bars.modules(Bars.TEXT), 3, 80, 60);
        ProfileLines.Quad box = box(image, 60);
        ProfileLines.Quad lower = new ProfileLines.Quad(box.x0(), box.y0() + 10, box.x1(), box.y1() + 10, box.x2(), box.y2(), box.x3(), box.y3());
        assertEquals(1, RetailEngine.read(image, List.of(box, lower)).size());
    }

    /**
     * A box that ends in the middle of the symbol: its rows show the start guard, six digits and
     * the middle guard, and are read again made longer by what the symbol lacks.
     */
    @Test
    void readsASymbolItsBoxCutsOff() {
        GrayU8 image = Bars.image(Bars.modules(Bars.TEXT), 3, 80, 60);
        ProfileLines.Quad half = new ProfileLines.Quad(40, 64, 60 + 157, 64, 60 + 157, 136, 40, 136);
        List<RetailEngine.Found> found = RetailEngine.read(image, List.of(half));
        assertEquals(1, found.size());
        assertEquals(Bars.TEXT, found.get(0).text());
        assertEquals(60 + 95 * 3, found.get(0).polygon()[2], 1.5);
    }

    /** The signs of a symbol the rows showed, for a caller that decides how much further to look. */
    @Test
    void tellsWhetherItsRowsShowedGuards() {
        GrayU8 image = Bars.image(Bars.modules(Bars.TEXT), 3, 80, 60);
        RetailEngine.Seen seen = RetailEngine.look(image, List.of(box(image, 60)));
        assertEquals(1, seen.symbols().size());
        assertTrue(seen.guards() > 0);
        GrayU8 flat = new GrayU8(400, 200);
        java.util.Arrays.fill(flat.data, (byte) 180);
        RetailEngine.Seen nothing = RetailEngine.look(flat, List.of(box(flat, 60)));
        assertTrue(nothing.symbols().isEmpty());
        assertEquals(0, nothing.guards());
        assertEquals(0, nothing.blurredGuards());
    }

    /** The other shapes of a box keep its centre: one longer and higher, four a little longer and turned, none past the frame. */
    @Test
    void theOtherShapesOfABoxLieAboutItsCentre() {
        ProfileLines.Quad box = new ProfileLines.Quad(100, 100, 300, 100, 300, 160, 100, 160);
        List<ProfileLines.Quad> shapes = RetailEngine.alternatives(box, 1000, 1000);
        assertEquals(5, shapes.size());
        ProfileLines.Quad larger = shapes.get(0);
        assertEquals(200 - 115, larger.x0(), 1e-9);
        assertEquals(200 + 115, larger.x1(), 1e-9);
        assertEquals(130 - 42, larger.y0(), 1e-9);
        assertEquals(130 + 42, larger.y3(), 1e-9);
        for (ProfileLines.Quad shape : shapes) {
            assertEquals(200, (shape.x0() + shape.x1() + shape.x2() + shape.x3()) / 4, 1e-9);
            assertEquals(130, (shape.y0() + shape.y1() + shape.y2() + shape.y3()) / 4, 1e-9);
        }
        // Turned by three degrees the first corner moves up along the left side and the second down.
        ProfileLines.Quad turned = shapes.get(4);
        assertEquals(Math.toDegrees(Math.atan2(turned.y1() - turned.y0(), turned.x1() - turned.x0())), 3, 1e-9);
        assertEquals(210, Math.hypot(turned.x1() - turned.x0(), turned.y1() - turned.y0()), 1e-9);
        // In a frame that ends inside the box the shapes end with it.
        for (ProfileLines.Quad shape : RetailEngine.alternatives(box, 280, 150)) {
            assertTrue(shape.x1() <= 279 && shape.x2() <= 279 && shape.y2() <= 149 && shape.y3() <= 149);
        }
    }

    @Test
    void aBoxWithoutASymbolReadsNothing() {
        GrayU8 flat = new GrayU8(400, 200);
        java.util.Arrays.fill(flat.data, (byte) 180);
        assertTrue(RetailEngine.read(flat, List.of(box(flat, 60))).isEmpty());
        GrayU8 noise = new GrayU8(400, 200);
        new Random(7).nextBytes(noise.data);
        assertTrue(RetailEngine.read(noise, List.of(box(noise, 60))).isEmpty());
        // Even stripes, as a fence or a radiator: guards everywhere and no digits.
        GrayU8 stripes = new GrayU8(400, 200);
        for (int y = 0; y < stripes.height; y++) {
            for (int x = 0; x < stripes.width; x++) {
                stripes.data[y * stripes.stride + x] = (byte) (x / 3 % 2 == 0 ? 40 : 200);
            }
        }
        assertTrue(RetailEngine.read(stripes, List.of(box(stripes, 60))).isEmpty());
        assertTrue(RetailEngine.read(flat, List.of()).isEmpty());
    }
}
