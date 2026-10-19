package io.github.kamilkrzywanski.barleader.decode;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Locked to zxing-cpp {@code TextDecoderTest}. The straight-through flags are on.
 * {@code GuessTextEncoding} is not part of this contract.
 */
class TextDecoderTest {
    @Test
    void readsBinaryAndAsciiStraightThrough() {
        byte[] all = new byte[256];
        for (int i = 0; i < all.length; i++) {
            all[i] = (byte) i;
        }
        String identity = new String(all, java.nio.charset.StandardCharsets.ISO_8859_1);
        assertEquals(identity, TextDecoder.decode(all, 899));
        assertEquals(identity, TextDecoder.decode(all, 27));
        assertEquals(identity, TextDecoder.decode(all, 170));
        assertEquals(identity, TextDecoder.decode(all, -1));
        assertEquals("", TextDecoder.decode(new byte[0], 3));
        assertNull(TextDecoder.decode(null, 3));
        assertNull(TextDecoder.decode(new byte[] {1}, 14));
        assertNull(TextDecoder.decode(new byte[] {1}, 19));
        assertNull(TextDecoder.decode(new byte[] {1}, 36));
        assertEquals("\u0001", TextDecoder.decodeOrLatin1(new byte[] {1}, 14));
    }

    /** Every character set in the C++ sweep maps {@code 0x00}..{@code 0x7F} to itself. */
    @Test
    void readsAsciiBytesInEveryCharacterSet() {
        byte[] ascii = new byte[0x80];
        StringBuilder expected = new StringBuilder();
        for (int i = 0; i < ascii.length; i++) {
            ascii[i] = (byte) i;
            expected.append((char) i);
        }
        int[] ecis = {
                -1, 27, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 15, 16, 17, 18,
                2, 21, 22, 23, 24, 20, 28, 29, 32, 30, 26, 899
        };
        for (int eci : ecis) {
            assertEquals(expected.toString(), TextDecoder.decode(ascii, eci), "eci " + eci);
        }
        assertEquals(expected.toString(), TextDecoder.decode(wide(ascii, 2, false), 25));
        assertEquals(expected.toString(), TextDecoder.decode(wide(ascii, 2, true), 33));
        assertEquals(expected.toString(), TextDecoder.decode(wide(ascii, 4, false), 34));
        assertEquals(expected.toString(), TextDecoder.decode(wide(ascii, 4, true), 35));
    }

    /** {@code 0x80}..{@code 0x9F} stay those code points. The C++ list repeats Greek and Hebrew. */
    @Test
    void keepsTheIso8859ControlRange() {
        byte[] data = new byte[0x20];
        StringBuilder expected = new StringBuilder();
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (0x80 + i);
            expected.append((char) (0x80 + i));
        }
        for (int eci : new int[] {3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 15, 16, 17, 18}) {
            assertEquals(expected.toString(), TextDecoder.decode(data, eci), "eci " + eci);
        }
        assertEquals("\u00c7", TextDecoder.decode(new byte[] {(byte) 0x80}, 0));
        assertEquals("\u20ac", TextDecoder.decode(new byte[] {(byte) 0x80}, 23));
        assertEquals("\u0449", TextDecoder.decode(new byte[] {(byte) 0xE9}, 7));
        assertEquals("\u0104", TextDecoder.decode(new byte[] {(byte) 0xA1}, 4));
        assertEquals("\u0104", TextDecoder.decode(new byte[] {(byte) 0xA1}, 12));
        assertEquals("\u1e02", TextDecoder.decode(new byte[] {(byte) 0xA1}, 16));
    }

    @Test
    void readsTheOfficialShiftJisVectors() {
        assertEquals("\\", TextDecoder.decode(new byte[] {0x5C}, 20));
        assertEquals("\uff65", TextDecoder.decode(new byte[] {(byte) 0xA5}, 20));
        assertEquals("~", TextDecoder.decode(new byte[] {0x7E}, 20));
        assertEquals("a\u03b2c\u0416\uff65\uff7f\u70b9\u8317\u30c6", TextDecoder.decode(new byte[] {
                'a', (byte) 0x83, (byte) 0xC0, 'c', (byte) 0x84, 0x47, (byte) 0xA5, (byte) 0xBF,
                (byte) 0x93, 0x5F, (byte) 0xE4, (byte) 0xAA, (byte) 0x83, 0x65
        }, 20));
        assertEquals("\\", TextDecoder.decode(new byte[] {(byte) 0x81, 0x5F}, 20));
        assertEquals("\u2015", TextDecoder.decode(new byte[] {(byte) 0x81, 0x5C}, 20));
        assertEquals("\ue000", TextDecoder.decode(new byte[] {(byte) 0xF0, 0x40}, 20));
        assertEquals("\ufffd", TextDecoder.decode(new byte[] {(byte) 0x80}, 20));
        assertEquals("\ufffd", TextDecoder.decode(new byte[] {(byte) 0x80, 0x41}, 20));
    }

    @Test
    void readsTheOfficialBig5GbAndKoreanVectors() {
        assertEquals("\u2013", TextDecoder.decode(new byte[] {(byte) 0xA1, 0x56}, 28));
        assertEquals("\u0001 \u3008@\u9310\u9f98\u007f", TextDecoder.decode(new byte[] {
                0x01, ' ', (byte) 0xA1, 0x71, '@', (byte) 0xC0, 0x40, (byte) 0xF9, (byte) 0xD5, 0x7F
        }, 28));
        assertEquals("\ufffd", TextDecoder.decode(new byte[] {(byte) 0xA1, 0x5A}, 28));
        assertEquals("a\u554a", TextDecoder.decode(new byte[] {'a', (byte) 0xB0, (byte) 0xA1}, 29));
        assertEquals("a\u03b2c\u30fb\u00b7\u2014\u00e9Z", TextDecoder.decode(new byte[] {
                'a', (byte) 0xA6, (byte) 0xC2, 'c', (byte) 0x81, 0x39, (byte) 0xA7, 0x39,
                (byte) 0xA1, (byte) 0xA4, (byte) 0xA1, (byte) 0xAA, (byte) 0xA8, (byte) 0xA6, 'Z'
        }, 32));
        assertEquals(new String(Character.toChars(0x20087)),
                TextDecoder.decode(new byte[] {(byte) 0xFE, 0x51}, 32));
        assertEquals("\u20ac", TextDecoder.decode(new byte[] {(byte) 0xA2, (byte) 0xE6}, 30));
        assertEquals("a\u3131Z", TextDecoder.decode(new byte[] {'a', (byte) 0xA4, (byte) 0xA1, 'Z'}, 30));
    }

    @Test
    void readsTheOfficialUtf16BeVectors() {
        assertEquals("\u0001\u007f\u0080\u00ff\u01ff\u10ff\ufffd", TextDecoder.decode(new byte[] {
                0x00, 0x01, 0x00, 0x7F, 0x00, (byte) 0x80, 0x00, (byte) 0xFF,
                0x01, (byte) 0xFF, 0x10, (byte) 0xFF, (byte) 0xFF, (byte) 0xFD
        }, 25));
        assertEquals(new String(Character.toChars(0x10000)),
                TextDecoder.decode(new byte[] {(byte) 0xD8, 0x00, (byte) 0xDC, 0x00}, 25));
    }

    /**
     * Locked to zxing-cpp {@code TextUtfEncodingTest::FromUtf8}. ECI 26 is UTF-8.
     * The commented broken sequences are not included.
     */
    @Test
    void readsTheOfficialUtf8Sequences() {
        assertEquals("A\u803fG", TextDecoder.decode(new byte[] {
                'A', (byte) 0xE8, (byte) 0x80, (byte) 0xBF, 'G'
        }, 26));
        assertEquals(new String(Character.toChars(0x10000)), TextDecoder.decode(new byte[] {
                (byte) 0xF0, (byte) 0x90, (byte) 0x80, (byte) 0x80
        }, 26));
        assertEquals(new String(Character.toChars(0x10FFF)), TextDecoder.decode(new byte[] {
                (byte) 0xF0, (byte) 0x90, (byte) 0xBF, (byte) 0xBF
        }, 26));
    }

    /**
     * Locked to zxing-cpp {@code TextEncoderTest::FullCycleEncodeDecode}.
     * Each buffer is the published encoder output. ECI 2 is Cp437.
     * Ideographic space is U+3000.
     */
    @Test
    void readsTheOfficialEncoderBytes() {
        assertEquals("\u00c7", TextDecoder.decode(new byte[] {(byte) 0x80}, 2));
        assertEquals("\u00a0", TextDecoder.decode(new byte[] {(byte) 0xA0}, 3));
        assertEquals("\u0126", TextDecoder.decode(new byte[] {(byte) 0xA1}, 5));
        assertEquals("\u0138", TextDecoder.decode(new byte[] {(byte) 0xA2}, 6));
        assertEquals("\u045f", TextDecoder.decode(new byte[] {(byte) 0xFF}, 7));
        assertEquals("\u0652", TextDecoder.decode(new byte[] {(byte) 0xF2}, 8));
        assertEquals("\u03ce", TextDecoder.decode(new byte[] {(byte) 0xFE}, 9));
        assertEquals("\u05ea", TextDecoder.decode(new byte[] {(byte) 0xFA}, 10));
        assertEquals("\u011e", TextDecoder.decode(new byte[] {(byte) 0xD0}, 11));
        assertEquals("\u0138", TextDecoder.decode(new byte[] {(byte) 0xFF}, 12));
        assertEquals("\u0e5b", TextDecoder.decode(new byte[] {(byte) 0xFB}, 13));
        assertEquals("\u2019", TextDecoder.decode(new byte[] {(byte) 0xFF}, 15));
        assertEquals("\u1e6b", TextDecoder.decode(new byte[] {(byte) 0xF7}, 16));
        assertEquals("\u00bf", TextDecoder.decode(new byte[] {(byte) 0xBF}, 17));
        assertEquals("\u017c", TextDecoder.decode(new byte[] {(byte) 0xBF}, 18));
        assertEquals("\u3000", TextDecoder.decode(new byte[] {(byte) 0x81, 0x40}, 20));
        assertEquals("\u20ac", TextDecoder.decode(new byte[] {(byte) 0x80}, 21));
        assertEquals("\u045f", TextDecoder.decode(new byte[] {(byte) 0x9F}, 22));
        assertEquals("\u02dc", TextDecoder.decode(new byte[] {(byte) 0x98}, 23));
        assertEquals("\u0686", TextDecoder.decode(new byte[] {(byte) 0x8D}, 24));
        assertEquals("\u20ac", TextDecoder.decode(new byte[] {0x20, (byte) 0xAC}, 25));
        assertEquals("\u20ac", TextDecoder.decode(new byte[] {(byte) 0xE2, (byte) 0x82, (byte) 0xAC}, 26));
        assertEquals("\u3000", TextDecoder.decode(new byte[] {(byte) 0xA1, 0x40}, 28));
        assertEquals("\u3000", TextDecoder.decode(new byte[] {(byte) 0xA1, (byte) 0xA1}, 29));
        assertEquals("\u3000", TextDecoder.decode(new byte[] {(byte) 0xA1, (byte) 0xA1}, 30));
        assertEquals("\u3000", TextDecoder.decode(new byte[] {(byte) 0xA1, (byte) 0xA1}, 32));
        assertEquals("\u20ac", TextDecoder.decode(new byte[] {(byte) 0xAC, 0x20}, 33));
        assertEquals("\u20ac", TextDecoder.decode(new byte[] {0x00, 0x00, 0x20, (byte) 0xAC}, 34));
        assertEquals("\u20ac", TextDecoder.decode(new byte[] {(byte) 0xAC, 0x20, 0x00, 0x00}, 35));
    }

    /**
     * Locked to the libzueci single-byte tables zxing-cpp decodes with the
     * straight-through flag on. The range is {@code 0x80}..{@code 0xFF} for Cp437
     * and {@code 0xA0}..{@code 0xFF} for the ISO/IEC 8859 sets. Windows bytes that
     * libzueci copies through, and this JDK replaces, stay out of the buffer.
     */
    @Test
    void readsTheOfficialSingleByteRepertoire() {
        assertBytes(0, 0x80, "\u00c7\u00fc\u00e9\u00e2\u00e4\u00e0\u00e5\u00e7\u00ea\u00eb\u00e8\u00ef\u00ee\u00ec\u00c4\u00c5"
                + "\u00c9\u00e6\u00c6\u00f4\u00f6\u00f2\u00fb\u00f9\u00ff\u00d6\u00dc\u00a2\u00a3\u00a5\u20a7\u0192"
                + "\u00e1\u00ed\u00f3\u00fa\u00f1\u00d1\u00aa\u00ba\u00bf\u2310\u00ac\u00bd\u00bc\u00a1\u00ab\u00bb"
                + "\u2591\u2592\u2593\u2502\u2524\u2561\u2562\u2556\u2555\u2563\u2551\u2557\u255d\u255c\u255b\u2510"
                + "\u2514\u2534\u252c\u251c\u2500\u253c\u255e\u255f\u255a\u2554\u2569\u2566\u2560\u2550\u256c\u2567"
                + "\u2568\u2564\u2565\u2559\u2558\u2552\u2553\u256b\u256a\u2518\u250c\u2588\u2584\u258c\u2590\u2580"
                + "\u03b1\u00df\u0393\u03c0\u03a3\u03c3\u00b5\u03c4\u03a6\u0398\u03a9\u03b4\u221e\u03c6\u03b5\u2229"
                + "\u2261\u00b1\u2265\u2264\u2320\u2321\u00f7\u2248\u00b0\u2219\u00b7\u221a\u207f\u00b2\u25a0\u00a0");
        assertBytes(2, 0x80, "\u00c7\u00fc\u00e9\u00e2\u00e4\u00e0\u00e5\u00e7\u00ea\u00eb\u00e8\u00ef\u00ee\u00ec\u00c4\u00c5"
                + "\u00c9\u00e6\u00c6\u00f4\u00f6\u00f2\u00fb\u00f9\u00ff\u00d6\u00dc\u00a2\u00a3\u00a5\u20a7\u0192"
                + "\u00e1\u00ed\u00f3\u00fa\u00f1\u00d1\u00aa\u00ba\u00bf\u2310\u00ac\u00bd\u00bc\u00a1\u00ab\u00bb"
                + "\u2591\u2592\u2593\u2502\u2524\u2561\u2562\u2556\u2555\u2563\u2551\u2557\u255d\u255c\u255b\u2510"
                + "\u2514\u2534\u252c\u251c\u2500\u253c\u255e\u255f\u255a\u2554\u2569\u2566\u2560\u2550\u256c\u2567"
                + "\u2568\u2564\u2565\u2559\u2558\u2552\u2553\u256b\u256a\u2518\u250c\u2588\u2584\u258c\u2590\u2580"
                + "\u03b1\u00df\u0393\u03c0\u03a3\u03c3\u00b5\u03c4\u03a6\u0398\u03a9\u03b4\u221e\u03c6\u03b5\u2229"
                + "\u2261\u00b1\u2265\u2264\u2320\u2321\u00f7\u2248\u00b0\u2219\u00b7\u221a\u207f\u00b2\u25a0\u00a0");
        assertBytes(4, 0xA0, "\u00a0\u0104\u02d8\u0141\u00a4\u013d\u015a\u00a7\u00a8\u0160\u015e\u0164\u0179\u00ad\u017d\u017b"
                + "\u00b0\u0105\u02db\u0142\u00b4\u013e\u015b\u02c7\u00b8\u0161\u015f\u0165\u017a\u02dd\u017e\u017c"
                + "\u0154\u00c1\u00c2\u0102\u00c4\u0139\u0106\u00c7\u010c\u00c9\u0118\u00cb\u011a\u00cd\u00ce\u010e"
                + "\u0110\u0143\u0147\u00d3\u00d4\u0150\u00d6\u00d7\u0158\u016e\u00da\u0170\u00dc\u00dd\u0162\u00df"
                + "\u0155\u00e1\u00e2\u0103\u00e4\u013a\u0107\u00e7\u010d\u00e9\u0119\u00eb\u011b\u00ed\u00ee\u010f"
                + "\u0111\u0144\u0148\u00f3\u00f4\u0151\u00f6\u00f7\u0159\u016f\u00fa\u0171\u00fc\u00fd\u0163\u02d9");
        assertBytes(5, 0xA0, "\u00a0\u0126\u02d8\u00a3\u00a4\u00a5\u0124\u00a7\u00a8\u0130\u015e\u011e\u0134\u00ad\u00ae\u017b"
                + "\u00b0\u0127\u00b2\u00b3\u00b4\u00b5\u0125\u00b7\u00b8\u0131\u015f\u011f\u0135\u00bd\u00be\u017c"
                + "\u00c0\u00c1\u00c2\u00c3\u00c4\u010a\u0108\u00c7\u00c8\u00c9\u00ca\u00cb\u00cc\u00cd\u00ce\u00cf"
                + "\u00d0\u00d1\u00d2\u00d3\u00d4\u0120\u00d6\u00d7\u011c\u00d9\u00da\u00db\u00dc\u016c\u015c\u00df"
                + "\u00e0\u00e1\u00e2\u00e3\u00e4\u010b\u0109\u00e7\u00e8\u00e9\u00ea\u00eb\u00ec\u00ed\u00ee\u00ef"
                + "\u00f0\u00f1\u00f2\u00f3\u00f4\u0121\u00f6\u00f7\u011d\u00f9\u00fa\u00fb\u00fc\u016d\u015d\u02d9");
        assertBytes(6, 0xA0, "\u00a0\u0104\u0138\u0156\u00a4\u0128\u013b\u00a7\u00a8\u0160\u0112\u0122\u0166\u00ad\u017d\u00af"
                + "\u00b0\u0105\u02db\u0157\u00b4\u0129\u013c\u02c7\u00b8\u0161\u0113\u0123\u0167\u014a\u017e\u014b"
                + "\u0100\u00c1\u00c2\u00c3\u00c4\u00c5\u00c6\u012e\u010c\u00c9\u0118\u00cb\u0116\u00cd\u00ce\u012a"
                + "\u0110\u0145\u014c\u0136\u00d4\u00d5\u00d6\u00d7\u00d8\u0172\u00da\u00db\u00dc\u0168\u016a\u00df"
                + "\u0101\u00e1\u00e2\u00e3\u00e4\u00e5\u00e6\u012f\u010d\u00e9\u0119\u00eb\u0117\u00ed\u00ee\u012b"
                + "\u0111\u0146\u014d\u0137\u00f4\u00f5\u00f6\u00f7\u00f8\u0173\u00fa\u00fb\u00fc\u0169\u016b\u02d9");
        assertBytes(7, 0xA0, "\u00a0\u0401\u0402\u0403\u0404\u0405\u0406\u0407\u0408\u0409\u040a\u040b\u040c\u00ad\u040e\u040f"
                + "\u0410\u0411\u0412\u0413\u0414\u0415\u0416\u0417\u0418\u0419\u041a\u041b\u041c\u041d\u041e\u041f"
                + "\u0420\u0421\u0422\u0423\u0424\u0425\u0426\u0427\u0428\u0429\u042a\u042b\u042c\u042d\u042e\u042f"
                + "\u0430\u0431\u0432\u0433\u0434\u0435\u0436\u0437\u0438\u0439\u043a\u043b\u043c\u043d\u043e\u043f"
                + "\u0440\u0441\u0442\u0443\u0444\u0445\u0446\u0447\u0448\u0449\u044a\u044b\u044c\u044d\u044e\u044f"
                + "\u2116\u0451\u0452\u0453\u0454\u0455\u0456\u0457\u0458\u0459\u045a\u045b\u045c\u00a7\u045e\u045f");
        assertBytes(8, 0xA0, "\u00a0\u00a1\u00a2\u00a3\u00a4\u00a5\u00a6\u00a7\u00a8\u00a9\u00aa\u00ab\u060c\u00ad\u00ae\u00af"
                + "\u00b0\u00b1\u00b2\u00b3\u00b4\u00b5\u00b6\u00b7\u00b8\u00b9\u00ba\u061b\u00bc\u00bd\u00be\u061f"
                + "\u00c0\u0621\u0622\u0623\u0624\u0625\u0626\u0627\u0628\u0629\u062a\u062b\u062c\u062d\u062e\u062f"
                + "\u0630\u0631\u0632\u0633\u0634\u0635\u0636\u0637\u0638\u0639\u063a\u00db\u00dc\u00dd\u00de\u00df"
                + "\u0640\u0641\u0642\u0643\u0644\u0645\u0646\u0647\u0648\u0649\u064a\u064b\u064c\u064d\u064e\u064f"
                + "\u0650\u0651\u0652\u00f3\u00f4\u00f5\u00f6\u00f7\u00f8\u00f9\u00fa\u00fb\u00fc\u00fd\u00fe\u00ff");
        assertBytes(9, 0xA0, "\u00a0\u2018\u2019\u00a3\u20ac\u20af\u00a6\u00a7\u00a8\u00a9\u037a\u00ab\u00ac\u00ad\u00ae\u2015"
                + "\u00b0\u00b1\u00b2\u00b3\u0384\u0385\u0386\u00b7\u0388\u0389\u038a\u00bb\u038c\u00bd\u038e\u038f"
                + "\u0390\u0391\u0392\u0393\u0394\u0395\u0396\u0397\u0398\u0399\u039a\u039b\u039c\u039d\u039e\u039f"
                + "\u03a0\u03a1\u00d2\u03a3\u03a4\u03a5\u03a6\u03a7\u03a8\u03a9\u03aa\u03ab\u03ac\u03ad\u03ae\u03af"
                + "\u03b0\u03b1\u03b2\u03b3\u03b4\u03b5\u03b6\u03b7\u03b8\u03b9\u03ba\u03bb\u03bc\u03bd\u03be\u03bf"
                + "\u03c0\u03c1\u03c2\u03c3\u03c4\u03c5\u03c6\u03c7\u03c8\u03c9\u03ca\u03cb\u03cc\u03cd\u03ce\u00ff");
        assertBytes(10, 0xA0, "\u00a0\u00a1\u00a2\u00a3\u00a4\u00a5\u00a6\u00a7\u00a8\u00a9\u00d7\u00ab\u00ac\u00ad\u00ae\u00af"
                + "\u00b0\u00b1\u00b2\u00b3\u00b4\u00b5\u00b6\u00b7\u00b8\u00b9\u00f7\u00bb\u00bc\u00bd\u00be\u00bf"
                + "\u00c0\u00c1\u00c2\u00c3\u00c4\u00c5\u00c6\u00c7\u00c8\u00c9\u00ca\u00cb\u00cc\u00cd\u00ce\u00cf"
                + "\u00d0\u00d1\u00d2\u00d3\u00d4\u00d5\u00d6\u00d7\u00d8\u00d9\u00da\u00db\u00dc\u00dd\u00de\u2017"
                + "\u05d0\u05d1\u05d2\u05d3\u05d4\u05d5\u05d6\u05d7\u05d8\u05d9\u05da\u05db\u05dc\u05dd\u05de\u05df"
                + "\u05e0\u05e1\u05e2\u05e3\u05e4\u05e5\u05e6\u05e7\u05e8\u05e9\u05ea\u00fb\u00fc\u200e\u200f\u00ff");
        assertBytes(11, 0xA0, "\u00a0\u00a1\u00a2\u00a3\u00a4\u00a5\u00a6\u00a7\u00a8\u00a9\u00aa\u00ab\u00ac\u00ad\u00ae\u00af"
                + "\u00b0\u00b1\u00b2\u00b3\u00b4\u00b5\u00b6\u00b7\u00b8\u00b9\u00ba\u00bb\u00bc\u00bd\u00be\u00bf"
                + "\u00c0\u00c1\u00c2\u00c3\u00c4\u00c5\u00c6\u00c7\u00c8\u00c9\u00ca\u00cb\u00cc\u00cd\u00ce\u00cf"
                + "\u011e\u00d1\u00d2\u00d3\u00d4\u00d5\u00d6\u00d7\u00d8\u00d9\u00da\u00db\u00dc\u0130\u015e\u00df"
                + "\u00e0\u00e1\u00e2\u00e3\u00e4\u00e5\u00e6\u00e7\u00e8\u00e9\u00ea\u00eb\u00ec\u00ed\u00ee\u00ef"
                + "\u011f\u00f1\u00f2\u00f3\u00f4\u00f5\u00f6\u00f7\u00f8\u00f9\u00fa\u00fb\u00fc\u0131\u015f\u00ff");
        assertBytes(12, 0xA0, "\u00a0\u0104\u0112\u0122\u012a\u0128\u0136\u00a7\u013b\u0110\u0160\u0166\u017d\u00ad\u016a\u014a"
                + "\u00b0\u0105\u0113\u0123\u012b\u0129\u0137\u00b7\u013c\u0111\u0161\u0167\u017e\u2015\u016b\u014b"
                + "\u0100\u00c1\u00c2\u00c3\u00c4\u00c5\u00c6\u012e\u010c\u00c9\u0118\u00cb\u0116\u00cd\u00ce\u00cf"
                + "\u00d0\u0145\u014c\u00d3\u00d4\u00d5\u00d6\u0168\u00d8\u0172\u00da\u00db\u00dc\u00dd\u00de\u00df"
                + "\u0101\u00e1\u00e2\u00e3\u00e4\u00e5\u00e6\u012f\u010d\u00e9\u0119\u00eb\u0117\u00ed\u00ee\u00ef"
                + "\u00f0\u0146\u014d\u00f3\u00f4\u00f5\u00f6\u0169\u00f8\u0173\u00fa\u00fb\u00fc\u00fd\u00fe\u0138");
        assertBytes(13, 0xA0, "\u00a0\u0e01\u0e02\u0e03\u0e04\u0e05\u0e06\u0e07\u0e08\u0e09\u0e0a\u0e0b\u0e0c\u0e0d\u0e0e\u0e0f"
                + "\u0e10\u0e11\u0e12\u0e13\u0e14\u0e15\u0e16\u0e17\u0e18\u0e19\u0e1a\u0e1b\u0e1c\u0e1d\u0e1e\u0e1f"
                + "\u0e20\u0e21\u0e22\u0e23\u0e24\u0e25\u0e26\u0e27\u0e28\u0e29\u0e2a\u0e2b\u0e2c\u0e2d\u0e2e\u0e2f"
                + "\u0e30\u0e31\u0e32\u0e33\u0e34\u0e35\u0e36\u0e37\u0e38\u0e39\u0e3a\u00db\u00dc\u00dd\u00de\u0e3f"
                + "\u0e40\u0e41\u0e42\u0e43\u0e44\u0e45\u0e46\u0e47\u0e48\u0e49\u0e4a\u0e4b\u0e4c\u0e4d\u0e4e\u0e4f"
                + "\u0e50\u0e51\u0e52\u0e53\u0e54\u0e55\u0e56\u0e57\u0e58\u0e59\u0e5a\u0e5b\u00fc\u00fd\u00fe\u00ff");
        assertBytes(15, 0xA0, "\u00a0\u201d\u00a2\u00a3\u00a4\u201e\u00a6\u00a7\u00d8\u00a9\u0156\u00ab\u00ac\u00ad\u00ae\u00c6"
                + "\u00b0\u00b1\u00b2\u00b3\u201c\u00b5\u00b6\u00b7\u00f8\u00b9\u0157\u00bb\u00bc\u00bd\u00be\u00e6"
                + "\u0104\u012e\u0100\u0106\u00c4\u00c5\u0118\u0112\u010c\u00c9\u0179\u0116\u0122\u0136\u012a\u013b"
                + "\u0160\u0143\u0145\u00d3\u014c\u00d5\u00d6\u00d7\u0172\u0141\u015a\u016a\u00dc\u017b\u017d\u00df"
                + "\u0105\u012f\u0101\u0107\u00e4\u00e5\u0119\u0113\u010d\u00e9\u017a\u0117\u0123\u0137\u012b\u013c"
                + "\u0161\u0144\u0146\u00f3\u014d\u00f5\u00f6\u00f7\u0173\u0142\u015b\u016b\u00fc\u017c\u017e\u2019");
        assertBytes(16, 0xA0, "\u00a0\u1e02\u1e03\u00a3\u010a\u010b\u1e0a\u00a7\u1e80\u00a9\u1e82\u1e0b\u1ef2\u00ad\u00ae\u0178"
                + "\u1e1e\u1e1f\u0120\u0121\u1e40\u1e41\u00b6\u1e56\u1e81\u1e57\u1e83\u1e60\u1ef3\u1e84\u1e85\u1e61"
                + "\u00c0\u00c1\u00c2\u00c3\u00c4\u00c5\u00c6\u00c7\u00c8\u00c9\u00ca\u00cb\u00cc\u00cd\u00ce\u00cf"
                + "\u0174\u00d1\u00d2\u00d3\u00d4\u00d5\u00d6\u1e6a\u00d8\u00d9\u00da\u00db\u00dc\u00dd\u0176\u00df"
                + "\u00e0\u00e1\u00e2\u00e3\u00e4\u00e5\u00e6\u00e7\u00e8\u00e9\u00ea\u00eb\u00ec\u00ed\u00ee\u00ef"
                + "\u0175\u00f1\u00f2\u00f3\u00f4\u00f5\u00f6\u1e6b\u00f8\u00f9\u00fa\u00fb\u00fc\u00fd\u0177\u00ff");
        assertBytes(17, 0xA0, "\u00a0\u00a1\u00a2\u00a3\u20ac\u00a5\u0160\u00a7\u0161\u00a9\u00aa\u00ab\u00ac\u00ad\u00ae\u00af"
                + "\u00b0\u00b1\u00b2\u00b3\u017d\u00b5\u00b6\u00b7\u017e\u00b9\u00ba\u00bb\u0152\u0153\u0178\u00bf"
                + "\u00c0\u00c1\u00c2\u00c3\u00c4\u00c5\u00c6\u00c7\u00c8\u00c9\u00ca\u00cb\u00cc\u00cd\u00ce\u00cf"
                + "\u00d0\u00d1\u00d2\u00d3\u00d4\u00d5\u00d6\u00d7\u00d8\u00d9\u00da\u00db\u00dc\u00dd\u00de\u00df"
                + "\u00e0\u00e1\u00e2\u00e3\u00e4\u00e5\u00e6\u00e7\u00e8\u00e9\u00ea\u00eb\u00ec\u00ed\u00ee\u00ef"
                + "\u00f0\u00f1\u00f2\u00f3\u00f4\u00f5\u00f6\u00f7\u00f8\u00f9\u00fa\u00fb\u00fc\u00fd\u00fe\u00ff");
        assertBytes(18, 0xA0, "\u00a0\u0104\u0105\u0141\u20ac\u201e\u0160\u00a7\u0161\u00a9\u0218\u00ab\u0179\u00ad\u017a\u017b"
                + "\u00b0\u00b1\u010c\u0142\u017d\u201d\u00b6\u00b7\u017e\u010d\u0219\u00bb\u0152\u0153\u0178\u017c"
                + "\u00c0\u00c1\u00c2\u0102\u00c4\u0106\u00c6\u00c7\u00c8\u00c9\u00ca\u00cb\u00cc\u00cd\u00ce\u00cf"
                + "\u0110\u0143\u00d2\u00d3\u00d4\u0150\u00d6\u015a\u0170\u00d9\u00da\u00db\u00dc\u0118\u021a\u00df"
                + "\u00e0\u00e1\u00e2\u0103\u00e4\u0107\u00e6\u00e7\u00e8\u00e9\u00ea\u00eb\u00ec\u00ed\u00ee\u00ef"
                + "\u0111\u0144\u00f2\u00f3\u00f4\u0151\u00f6\u015b\u0171\u00f9\u00fa\u00fb\u00fc\u0119\u021b\u00ff");
        assertMapped(21, new int[] {0x80, 0x82, 0x84, 0x85, 0x86, 0x87, 0x89, 0x8A, 0x8B, 0x8C, 0x8D, 0x8E, 0x8F, 0x91, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x99, 0x9A, 0x9B, 0x9C, 0x9D, 0x9E, 0x9F}, "\u20ac\u201a\u201e\u2026\u2020\u2021\u2030\u0160\u2039\u015a\u0164\u017d\u0179\u2018\u2019\u201c"
                + "\u201d\u2022\u2013\u2014\u2122\u0161\u203a\u015b\u0165\u017e\u017a");
        assertBytes(21, 0xA0, "\u00a0\u02c7\u02d8\u0141\u00a4\u0104\u00a6\u00a7\u00a8\u00a9\u015e\u00ab\u00ac\u00ad\u00ae\u017b"
                + "\u00b0\u00b1\u02db\u0142\u00b4\u00b5\u00b6\u00b7\u00b8\u0105\u015f\u00bb\u013d\u02dd\u013e\u017c"
                + "\u0154\u00c1\u00c2\u0102\u00c4\u0139\u0106\u00c7\u010c\u00c9\u0118\u00cb\u011a\u00cd\u00ce\u010e"
                + "\u0110\u0143\u0147\u00d3\u00d4\u0150\u00d6\u00d7\u0158\u016e\u00da\u0170\u00dc\u00dd\u0162\u00df"
                + "\u0155\u00e1\u00e2\u0103\u00e4\u013a\u0107\u00e7\u010d\u00e9\u0119\u00eb\u011b\u00ed\u00ee\u010f"
                + "\u0111\u0144\u0148\u00f3\u00f4\u0151\u00f6\u00f7\u0159\u016f\u00fa\u0171\u00fc\u00fd\u0163\u02d9");
        assertMapped(22, new int[] {0x80, 0x81, 0x82, 0x83, 0x84, 0x85, 0x86, 0x87, 0x88, 0x89, 0x8A, 0x8B, 0x8C, 0x8D, 0x8E, 0x8F, 0x90, 0x91, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x99, 0x9A, 0x9B, 0x9C, 0x9D, 0x9E, 0x9F}, "\u0402\u0403\u201a\u0453\u201e\u2026\u2020\u2021\u20ac\u2030\u0409\u2039\u040a\u040c\u040b\u040f"
                + "\u0452\u2018\u2019\u201c\u201d\u2022\u2013\u2014\u2122\u0459\u203a\u045a\u045c\u045b\u045f");
        assertBytes(22, 0xA0, "\u00a0\u040e\u045e\u0408\u00a4\u0490\u00a6\u00a7\u0401\u00a9\u0404\u00ab\u00ac\u00ad\u00ae\u0407"
                + "\u00b0\u00b1\u0406\u0456\u0491\u00b5\u00b6\u00b7\u0451\u2116\u0454\u00bb\u0458\u0405\u0455\u0457"
                + "\u0410\u0411\u0412\u0413\u0414\u0415\u0416\u0417\u0418\u0419\u041a\u041b\u041c\u041d\u041e\u041f"
                + "\u0420\u0421\u0422\u0423\u0424\u0425\u0426\u0427\u0428\u0429\u042a\u042b\u042c\u042d\u042e\u042f"
                + "\u0430\u0431\u0432\u0433\u0434\u0435\u0436\u0437\u0438\u0439\u043a\u043b\u043c\u043d\u043e\u043f"
                + "\u0440\u0441\u0442\u0443\u0444\u0445\u0446\u0447\u0448\u0449\u044a\u044b\u044c\u044d\u044e\u044f");
        assertMapped(23, new int[] {0x80, 0x82, 0x83, 0x84, 0x85, 0x86, 0x87, 0x88, 0x89, 0x8A, 0x8B, 0x8C, 0x8E, 0x91, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9A, 0x9B, 0x9C, 0x9E, 0x9F}, "\u20ac\u201a\u0192\u201e\u2026\u2020\u2021\u02c6\u2030\u0160\u2039\u0152\u017d\u2018\u2019\u201c"
                + "\u201d\u2022\u2013\u2014\u02dc\u2122\u0161\u203a\u0153\u017e\u0178");
        assertBytes(23, 0xA0, "\u00a0\u00a1\u00a2\u00a3\u00a4\u00a5\u00a6\u00a7\u00a8\u00a9\u00aa\u00ab\u00ac\u00ad\u00ae\u00af"
                + "\u00b0\u00b1\u00b2\u00b3\u00b4\u00b5\u00b6\u00b7\u00b8\u00b9\u00ba\u00bb\u00bc\u00bd\u00be\u00bf"
                + "\u00c0\u00c1\u00c2\u00c3\u00c4\u00c5\u00c6\u00c7\u00c8\u00c9\u00ca\u00cb\u00cc\u00cd\u00ce\u00cf"
                + "\u00d0\u00d1\u00d2\u00d3\u00d4\u00d5\u00d6\u00d7\u00d8\u00d9\u00da\u00db\u00dc\u00dd\u00de\u00df"
                + "\u00e0\u00e1\u00e2\u00e3\u00e4\u00e5\u00e6\u00e7\u00e8\u00e9\u00ea\u00eb\u00ec\u00ed\u00ee\u00ef"
                + "\u00f0\u00f1\u00f2\u00f3\u00f4\u00f5\u00f6\u00f7\u00f8\u00f9\u00fa\u00fb\u00fc\u00fd\u00fe\u00ff");
        assertMapped(24, new int[] {0x80, 0x81, 0x82, 0x83, 0x84, 0x85, 0x86, 0x87, 0x88, 0x89, 0x8A, 0x8B, 0x8C, 0x8D, 0x8E, 0x8F, 0x90, 0x91, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9A, 0x9B, 0x9C, 0x9D, 0x9E, 0x9F}, "\u20ac\u067e\u201a\u0192\u201e\u2026\u2020\u2021\u02c6\u2030\u0679\u2039\u0152\u0686\u0698\u0688"
                + "\u06af\u2018\u2019\u201c\u201d\u2022\u2013\u2014\u06a9\u2122\u0691\u203a\u0153\u200c\u200d\u06ba");
        assertBytes(24, 0xA0, "\u00a0\u060c\u00a2\u00a3\u00a4\u00a5\u00a6\u00a7\u00a8\u00a9\u06be\u00ab\u00ac\u00ad\u00ae\u00af"
                + "\u00b0\u00b1\u00b2\u00b3\u00b4\u00b5\u00b6\u00b7\u00b8\u00b9\u061b\u00bb\u00bc\u00bd\u00be\u061f"
                + "\u06c1\u0621\u0622\u0623\u0624\u0625\u0626\u0627\u0628\u0629\u062a\u062b\u062c\u062d\u062e\u062f"
                + "\u0630\u0631\u0632\u0633\u0634\u0635\u0636\u00d7\u0637\u0638\u0639\u063a\u0640\u0641\u0642\u0643"
                + "\u00e0\u0644\u00e2\u0645\u0646\u0647\u0648\u00e7\u00e8\u00e9\u00ea\u00eb\u0649\u064a\u00ee\u00ef"
                + "\u064b\u064c\u064d\u064e\u00f4\u064f\u0650\u00f7\u0651\u00f9\u0652\u00fb\u00fc\u200e\u200f\u06d2");
    }

    /** {@code first} is the first byte. Each following byte is one higher. */
    private static void assertBytes(int eci, int first, String expected) {
        byte[] raw = new byte[expected.length()];
        for (int i = 0; i < raw.length; i++) {
            raw[i] = (byte) (first + i);
        }
        assertEquals(expected, TextDecoder.decode(raw, eci), "eci " + eci);
    }

    /** The bytes are the defined Windows positions. Gaps are not in the buffer. */
    private static void assertMapped(int eci, int[] bytes, String expected) {
        byte[] raw = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            raw[i] = (byte) bytes[i];
        }
        assertEquals(expected, TextDecoder.decode(raw, eci), "eci " + eci);
    }

    private static byte[] wide(byte[] ascii, int width, boolean little) {
        byte[] raw = new byte[ascii.length * width];
        for (int i = 0; i < ascii.length; i++) {
            raw[i * width + (little ? 0 : width - 1)] = ascii[i];
        }
        return raw;
    }
}
