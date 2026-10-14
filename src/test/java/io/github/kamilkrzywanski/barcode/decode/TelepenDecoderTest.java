package io.github.kamilkrzywanski.barcode.decode;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Locked to zxing-cpp {@code ODTelepenReaderTest.cpp}. The leading zero is the quiet zone. */
class TelepenDecoderTest {
    @Test
    void readsFullAscii() {
        int[] body = {1, 1, 3, 1, 3, 1, 3, 3, 1, 3, 1, 1, 1, 1, 1, 1, 1, 3, 1, 1};
        assertSymbol(symbol(1, body), "A", '0');
        assertSymbol(symbol(1, body, 1), "A", '0');
        assertSymbol(symbol(1, body, 100), "A", '0');
        assertSymbol(symbol(1, body, 100, 5), "A", '0');
        assertSymbol(TelepenDecoder.decode(pixels(row(1, body, new int[] {100, 5}))), "A", '0');
    }

    @Test
    void readsCompressedNumericAndMixedModes() {
        assertSymbol(symbol(1, new int[] {
                1, 1, 3, 1, 1, 1, 3, 1, 1, 1, 1, 1,
                1, 1, 1, 1, 1, 1, 3, 3, 3, 1,
                3, 1, 1, 1, 1, 1, 1, 1, 1, 1, 3, 1,
                3, 3, 3, 1, 1, 1, 1, 1, 1, 1,
        }), "466X33", '1');

        assertSymbol(symbol(1, new int[] {
                1, 1, 1, 1, 1, 1, 3, 1, 1, 1, 3, 1,
                3, 1, 3, 1, 1, 1, 3, 1, 1, 1,
                1, 1, 1, 1, 3, 1, 1, 1, 1, 1, 3, 1,
                1, 1, 3, 3, 1, 1, 3, 1, 1, 1,
        }), "123", '2');

        // ASCII 15 is SI. zxing-cpp prints it as "<SI>" because the content type is binary.
        assertSymbol(TelepenDecoder.decode(row(1, new int[] {
                1, 1, 1, 1, 1, 1, 3, 1, 1, 1, 3, 1,
                1, 1, 1, 1, 1, 1, 1, 1, 3, 1, 3, 1,
                1, 1, 1, 3, 1, 1, 1, 1, 1, 3, 1, 1,
                3, 1, 1, 1, 1, 1, 3, 1, 3, 1,
        }, new int[] {0}), false, true), "12\u000f34", '1');

        assertSymbol(symbol(2, new int[] {
                1, 1, 1, 1, 1, 1, 3, 1, 1, 1, 3, 1,
                3, 1, 1, 3, 1, 3, 1, 1, 1, 1,
        }), "12", '1');

        assertSymbol(symbol(3, new int[] {
                1, 1, 3, 1, 3, 1, 3, 3,
                3, 1, 3, 1, 1, 1, 3, 1, 1, 1,
                1, 1, 1, 1, 1, 1, 3, 1, 1, 1, 3, 1,
                1, 1, 1, 1, 1, 1, 3, 1, 3, 1, 1, 1,
        }), "A12", '4');
    }

    @Test
    void rejectsABadChecksumAndABlankRow() {
        assertNull(symbol(1, new int[] {1, 1, 3, 1, 3, 1, 3, 3, 1, 1, 3, 1, 3, 1, 3, 3}));
        assertNull(TelepenDecoder.decode((int[]) null));
        assertNull(TelepenDecoder.decode((boolean[]) null));
        assertNull(TelepenDecoder.decode(new int[] {0}));
        assertNull(TelepenDecoder.decode(new boolean[40]));
        assertNull(TelepenDecoder.decode(row(2, new int[] {
                1, 1, 1, 1, 1, 1, 3, 1, 1, 1, 3, 1,
                3, 1, 1, 3, 1, 3, 1, 1, 1, 1,
        }, new int[] {0}), true, false));
    }

    private static void assertSymbol(TelepenDecoder.Symbol symbol, String text, char modifier) {
        assertEquals(text, symbol.text);
        assertEquals(modifier, symbol.modifier);
    }

    private static TelepenDecoder.Symbol symbol(int start, int[] body, int... tail) {
        if (tail.length == 0) {
            tail = new int[] {0};
        }
        return TelepenDecoder.decode(row(start, body, tail));
    }

    private static int[] row(int start, int[] body, int[] tail) {
        int[] startRun = switch (start) {
            case 1 -> new int[] {1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 3, 3};
            case 2 -> new int[] {1, 1, 1, 1, 1, 1, 1, 1, 3, 1, 1, 3};
            default -> new int[] {1, 1, 1, 1, 1, 1, 3, 1, 1, 1, 1, 3};
        };
        int[] stopRun = switch (start) {
            case 1 -> new int[] {3, 3, 1, 1, 1, 1, 1, 1, 1, 1, 1};
            case 2 -> new int[] {3, 1, 1, 3, 1, 1, 1, 1, 1, 1, 1};
            default -> new int[] {3, 1, 1, 1, 1, 3, 1, 1, 1, 1, 1};
        };
        int[] row = new int[1 + startRun.length + body.length + stopRun.length + tail.length];
        int cursor = 0;
        row[cursor++] = 0;
        cursor = copy(row, cursor, startRun);
        cursor = copy(row, cursor, body);
        cursor = copy(row, cursor, stopRun);
        copy(row, cursor, tail);
        return row;
    }

    private static int copy(int[] target, int cursor, int[] source) {
        System.arraycopy(source, 0, target, cursor, source.length);
        return cursor + source.length;
    }

    /** One pixel per module. The row starts and ends on white, matching {@code runs}. */
    private static boolean[] pixels(int[] runs) {
        int width = 0;
        for (int run : runs) {
            width += run;
        }
        boolean[] black = new boolean[width];
        boolean ink = false;
        int cursor = 0;
        for (int run : runs) {
            for (int i = 0; i < run; i++) {
                black[cursor++] = ink;
            }
            ink = !ink;
        }
        return black;
    }
}
