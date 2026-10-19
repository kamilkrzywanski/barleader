package io.github.kamilkrzywanski.barleader.locate.dm;

import io.github.kamilkrzywanski.barleader.image.GrayU8;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Locked to {@code GlobalHistogramBinarizer::getPatternRow} at rotation 0 and 90. */
class RowHistogramTest {
    @Test
    void rejectsAShortRowAndAFlatBlackRow() {
        assertNull(RowHistogram.bits(new GrayU8(2, 1), 0));
        assertNull(RowHistogram.bits(fill(8, 0), 0));
        assertNull(RowHistogram.bits(fill(8, 0), 1));
        assertNull(RowHistogram.bits(null, 0));
        assertNull(RowHistogram.bits(new GrayU8(8, 2), 0, 90));
        assertNull(RowHistogram.bits(fill(8, 0), 0, 180));
        assertNull(RowHistogram.bits(fill(8, 0), 0, 270));
    }

    @Test
    void sharpensAnInteriorPixelTheRawThresholdWouldLeaveWhite() {
        GrayU8 gray = new GrayU8(64, 1);
        for (int x = 32; x < 64; x++) {
            gray.set(x, 0, 255);
        }
        // 200 is above the valley 168. Bright neighbors pull the sharpened value to 145.
        gray.set(40, 0, 200);
        assertEquals(
                "################################........#.......................",
                picture(RowHistogram.bits(gray, 0)));
    }

    /** Rotation 90 reads the column from the bottom, so the same pattern sits on x = 1. */
    @Test
    void readsAColumnFromTheBottomUp() {
        GrayU8 gray = new GrayU8(3, 64);
        for (int y = 0; y < 32; y++) {
            gray.set(1, y, 255);
        }
        gray.set(1, 23, 200);
        assertEquals(
                "################################........#.......................",
                picture(RowHistogram.bits(gray, 1, 90)));
        assertNull(RowHistogram.bits(gray, 3, 90));
    }

    private static GrayU8 fill(int width, int value) {
        GrayU8 gray = new GrayU8(width, 1);
        for (int x = 0; x < width; x++) {
            gray.set(x, 0, value);
        }
        return gray;
    }

    private static String picture(boolean[] row) {
        StringBuilder picture = new StringBuilder(row.length);
        for (boolean black : row) {
            picture.append(black ? '#' : '.');
        }
        return picture.toString();
    }
}
