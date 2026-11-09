package io.github.kamilkrzywanski.barleader;

import io.github.kamilkrzywanski.barleader.image.GrayImages;
import io.github.kamilkrzywanski.barleader.image.GrayU8;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuietZoneTest {
    private static final int MODULE = 4;

    /** The symbol drawn without its line of digits, blurred over about a module. */
    private static GrayU8 drawn(BarcodeType type, String text) {
        RenderOptions options = RenderOptions.defaults().humanReadable(false);
        return blurred(GrayImages.from(BarcodeWriter.encode(type, text).toImage(MODULE, options)));
    }

    private static GrayU8 blurred(GrayU8 gray) {
        GrayU8 out = new GrayU8(gray.width, gray.height);
        for (int y = 0; y < gray.height; y++) {
            for (int x = 0; x < gray.width; x++) {
                int sum = 0;
                for (int k = -2; k <= 2; k++) {
                    int xx = Math.max(0, Math.min(gray.width - 1, x + k));
                    sum += gray.data[gray.startIndex + y * gray.stride + xx] & 255;
                }
                out.data[y * out.width + x] = (byte) (sum / 5);
            }
        }
        return out;
    }

    /** The first and the last column dark on the middle row. */
    private static int[] bars(GrayU8 gray) {
        int y = gray.height / 2;
        int first = -1;
        int last = -1;
        for (int x = 0; x < gray.width; x++) {
            if ((gray.data[gray.startIndex + y * gray.stride + x] & 255) < 128) {
                if (first < 0) {
                    first = x;
                }
                last = x;
            }
        }
        return new int[]{first, last + 1};
    }

    @Test
    void aWholeShortSymbolHasItsQuietZones() {
        GrayU8 gray = drawn(BarcodeType.UPC_E, "01234565");
        int[] ends = bars(gray);
        int y = gray.height / 2;
        assertTrue(QuietZone.clear(gray, ends[0], y, ends[1], y, 51));
        assertTrue(QuietZone.clear(gray, ends[1], y, ends[0], y, 51));
    }

    /**
     * The left half of an EAN-13 up to the first bar after its middle guard has the modules of a
     * UPC-E, and the right half lies past its end.
     */
    @Test
    void theLeftHalfOfAnEan13IsNotAShortSymbol() {
        GrayU8 gray = drawn(BarcodeType.EAN_13, "4311501625217");
        int[] ends = bars(gray);
        int y = gray.height / 2;
        double end = ends[0] + 51 * MODULE;
        assertFalse(QuietZone.clear(gray, ends[0], y, end, y, 51));
        assertFalse(QuietZone.clear(gray, end, y, ends[0], y, 51));
        assertTrue(QuietZone.clear(gray, ends[0], y, ends[1], y, 95));
    }

    /** A label cut 5 modules past the symbol, on a dark background, has no bar past the symbol. */
    @Test
    void theDarkEdgeOfALabelIsNotABar() {
        GrayU8 gray = drawn(BarcodeType.UPC_E, "01234565");
        int[] ends = bars(gray);
        for (int y = 0; y < gray.height; y++) {
            for (int x = ends[1] + 5 * MODULE; x < gray.width; x++) {
                gray.data[y * gray.stride + x] = (byte) 20;
            }
            for (int x = 0; x < ends[0] - 5 * MODULE; x++) {
                gray.data[y * gray.stride + x] = (byte) 20;
            }
        }
        int y = gray.height / 2;
        assertTrue(QuietZone.clear(gray, ends[0], y, ends[1], y, 51));
    }

    @Test
    void aFaintOrTinyLineIsNotJudged() {
        GrayU8 flat = new GrayU8(200, 50);
        java.util.Arrays.fill(flat.data, (byte) 200);
        assertTrue(QuietZone.clear(flat, 50, 25, 150, 25, 51));
        assertTrue(QuietZone.clear(flat, 50, 25, 60, 25, 51));
    }
}
