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
}
