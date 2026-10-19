package io.github.kamilkrzywanski.barcode.decode;

import io.github.kamilkrzywanski.barcode.BarcodeHit;
import io.github.kamilkrzywanski.barcode.BarcodeReader;
import io.github.kamilkrzywanski.barcode.ReadOptions;
import io.github.kamilkrzywanski.barcode.Symbology;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Royal Mail and Japan Post symbols drawn bar by bar, upright, upside down and tilted, read back;
 * random bars of four heights read as nothing.
 */
class FourStateReaderTest {
    private static final String ROYAL = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final int[] HALVES = {0b0011, 0b0101, 0b0110, 0b1001, 0b1010, 0b1100};
    private static final String JAPAN = "1234567890-abcdefgh";
    private static final String JAPAN_CHECK = "0123456789-abcdefgh";
    private static final String[] JAPAN_BARS = {
            "114", "132", "312", "123", "141", "321", "213", "231", "411", "144",
            "414", "324", "342", "234", "432", "243", "423", "441", "111"};

    @Test
    void readsRoyalMailWithItsCheckCharacter() {
        // BarBeR's RoyalMail_0001 is labelled 0123456ABCF.
        assertEquals("0123456ABCF", royalMailText("0123456ABC"));
        for (double degrees : new double[]{0, 180, 4, -6}) {
            assertEquals(List.of("ROYAL_MAIL 0123456ABCF"), read(draw(royalMail("0123456ABC"), degrees), Symbology.ROYAL_MAIL), "at " + degrees);
            assertEquals(List.of("ROYAL_MAIL SN34RD1AK"), read(draw(royalMail("SN34RD1A"), degrees), Symbology.ROYAL_MAIL), "at " + degrees);
        }
    }

    @Test
    void readsJapanPostWithItsLettersAndWithoutItsPadding() {
        for (double degrees : new double[]{0, 180, 3}) {
            assertEquals(List.of("JAPAN_POST 1348790075"), read(draw(japanPost("1348790075"), degrees), Symbology.JAPAN_POST), "at " + degrees);
            assertEquals(List.of("JAPAN_POST 15400233-16-4-205"), read(draw(japanPost("15400233-16-4-205"), degrees), Symbology.JAPAN_POST), "at " + degrees);
            assertEquals(List.of("JAPAN_POST 0640941-3-2A1102"), read(draw(japanPost("0640941-3-2A1102"), degrees), Symbology.JAPAN_POST), "at " + degrees);
        }
    }

    @Test
    void randomBarsReadAsNothing() {
        Random random = new Random(7);
        for (int trial = 0; trial < 200; trial++) {
            int[] states = new int[14 + random.nextInt(60)];
            for (int i = 0; i < states.length; i++) {
                states[i] = random.nextInt(4);
            }
            List<String> hits = read(draw(states, 0), Symbology.ROYAL_MAIL, Symbology.JAPAN_POST);
            assertTrue(hits.isEmpty(), trial + " " + hits);
        }
    }

    @Test
    void staysOffByDefault() {
        BufferedImage image = draw(royalMail("0123456ABC"), 0);
        assertTrue(new BarcodeReader().read(image, ReadOptions.fast()).isEmpty());
    }

    private static List<String> read(BufferedImage image, Symbology... symbologies) {
        ReadOptions options = ReadOptions.fast().symbologies(symbologies).timeBudgetMillis(Long.MAX_VALUE);
        return new BarcodeReader().read(image, options).stream().map(FourStateReaderTest::name).toList();
    }

    private static String name(BarcodeHit hit) {
        return hit.symbology() + " " + hit.text();
    }

    /** States 3 full, 2 ascender, 1 descender, 0 tracker, drawn with 4-pixel bars at a 9-pixel pitch. */
    private static BufferedImage draw(int[] states, double degrees) {
        int pitch = 9;
        int width = states.length * pitch + 80;
        int height = 140;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);
        g.setTransform(AffineTransform.getRotateInstance(Math.toRadians(degrees), width / 2.0, height / 2.0));
        g.setColor(Color.BLACK);
        int middle = height / 2;
        for (int i = 0; i < states.length; i++) {
            int x = 40 + i * pitch;
            int top = (states[i] & 2) != 0 ? middle - 16 : middle - 4;
            int bottom = (states[i] & 1) != 0 ? middle + 16 : middle + 4;
            g.fillRect(x, top, 4, bottom - top);
        }
        g.dispose();
        return image;
    }

    private static String royalMailText(String data) {
        int top = 0;
        int bottom = 0;
        for (char c : data.toCharArray()) {
            int v = ROYAL.indexOf(c);
            top += v / 6 + 1;
            bottom += v % 6 + 1;
        }
        return data + ROYAL.charAt(6 * ((top - 1) % 6) + (bottom - 1) % 6);
    }

    private static int[] royalMail(String data) {
        String text = royalMailText(data);
        int[] states = new int[2 + 4 * text.length()];
        states[0] = 2;
        states[states.length - 1] = 3;
        for (int c = 0; c < text.length(); c++) {
            int v = ROYAL.indexOf(text.charAt(c));
            int top = HALVES[v / 6];
            int bottom = HALVES[v % 6];
            for (int k = 0; k < 4; k++) {
                int bit = 3 - k;
                states[1 + 4 * c + k] = ((top >> bit) & 1) << 1 | (bottom >> bit) & 1;
            }
        }
        return states;
    }

    private static int[] japanPost(String text) {
        StringBuilder chars = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (c >= 'A' && c <= 'J') {
                chars.append('a').append((char) ('0' + c - 'A'));
            } else if (c >= 'K' && c <= 'T') {
                chars.append('b').append((char) ('0' + c - 'K'));
            } else if (c >= 'U' && c <= 'Z') {
                chars.append('c').append((char) ('0' + c - 'U'));
            } else {
                chars.append(c);
            }
        }
        while (chars.length() < 20) {
            chars.append('d');
        }
        int sum = 0;
        for (int i = 0; i < 20; i++) {
            sum += JAPAN_CHECK.indexOf(chars.charAt(i));
        }
        int check = 19 - sum % 19;
        chars.append(JAPAN_CHECK.charAt(check == 19 ? 0 : check));
        StringBuilder bars = new StringBuilder("13");
        for (int i = 0; i < 21; i++) {
            bars.append(JAPAN_BARS[JAPAN.indexOf(chars.charAt(i))]);
        }
        bars.append("31");
        int[] states = new int[bars.length()];
        for (int i = 0; i < states.length; i++) {
            states[i] = switch (bars.charAt(i)) {
                case '1' -> 3;
                case '2' -> 2;
                case '3' -> 1;
                default -> 0;
            };
        }
        return states;
    }
}
