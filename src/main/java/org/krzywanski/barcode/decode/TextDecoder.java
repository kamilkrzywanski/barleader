package org.krzywanski.barcode.decode;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * ECI bytes to text, from zxing-cpp 3.1.1 {@code TextDecoder.cpp}.
 *
 * <p>The two libzueci flags used there are always on: a single-byte gap is copied
 * through, and Shift-JIS keeps U+005C and U+007E. {@code GuessTextEncoding} is not
 * used. An ECI number that is not a character set returns null. ECI -1 is binary.
 * Shift-JIS pairs {@code 81 5C} and {@code 81 5F}, and the user-area leads
 * {@code F0}..{@code F9}, follow libzueci rather than the JDK. ISO/IEC 8859-10 and
 * ISO/IEC 8859-14 are tables because this JDK has no charset for them. ECI 31 uses
 * the JDK GBK repertoire, which accepts some pairs libzueci replaces.
 */
public final class TextDecoder {
    private static final Charset CP437 = Charset.forName("IBM437");
    private static final Charset ISO_8859_2 = Charset.forName("ISO-8859-2");
    private static final Charset ISO_8859_3 = Charset.forName("ISO-8859-3");
    private static final Charset ISO_8859_4 = Charset.forName("ISO-8859-4");
    private static final Charset ISO_8859_5 = Charset.forName("ISO-8859-5");
    private static final Charset ISO_8859_6 = Charset.forName("ISO-8859-6");
    private static final Charset ISO_8859_7 = Charset.forName("ISO-8859-7");
    private static final Charset ISO_8859_8 = Charset.forName("ISO-8859-8");
    private static final Charset ISO_8859_9 = Charset.forName("ISO-8859-9");
    private static final Charset ISO_8859_11 = Charset.forName("ISO-8859-11");
    private static final Charset ISO_8859_13 = Charset.forName("ISO-8859-13");
    private static final Charset ISO_8859_15 = Charset.forName("ISO-8859-15");
    private static final Charset ISO_8859_16 = Charset.forName("ISO-8859-16");
    private static final Charset SHIFT_JIS = Charset.forName("Shift_JIS");
    private static final Charset WINDOWS_1250 = Charset.forName("windows-1250");
    private static final Charset WINDOWS_1251 = Charset.forName("windows-1251");
    private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");
    private static final Charset WINDOWS_1256 = Charset.forName("windows-1256");
    private static final Charset BIG5 = Charset.forName("Big5");
    private static final Charset GB2312 = Charset.forName("GB2312");
    private static final Charset GBK = Charset.forName("GBK");
    private static final Charset GB18030 = Charset.forName("GB18030");
    private static final Charset EUC_KR = Charset.forName("EUC-KR");

    /** Bytes {@code 0xA0}..{@code 0xFF} of ISO/IEC 8859-10. */
    private static final String ISO_8859_10 =
            "\u00a0\u0104\u0112\u0122\u012a\u0128\u0136\u00a7\u013b\u0110\u0160\u0166\u017d\u00ad\u016a\u014a"
                    + "\u00b0\u0105\u0113\u0123\u012b\u0129\u0137\u00b7\u013c\u0111\u0161\u0167\u017e\u2015\u016b\u014b"
                    + "\u0100\u00c1\u00c2\u00c3\u00c4\u00c5\u00c6\u012e\u010c\u00c9\u0118\u00cb\u0116\u00cd\u00ce\u00cf"
                    + "\u00d0\u0145\u014c\u00d3\u00d4\u00d5\u00d6\u0168\u00d8\u0172\u00da\u00db\u00dc\u00dd\u00de\u00df"
                    + "\u0101\u00e1\u00e2\u00e3\u00e4\u00e5\u00e6\u012f\u010d\u00e9\u0119\u00eb\u0117\u00ed\u00ee\u00ef"
                    + "\u00f0\u0146\u014d\u00f3\u00f4\u00f5\u00f6\u0169\u00f8\u0173\u00fa\u00fb\u00fc\u00fd\u00fe\u0138";

    /** Bytes {@code 0xA0}..{@code 0xFF} of ISO/IEC 8859-14. */
    private static final String ISO_8859_14 =
            "\u00a0\u1e02\u1e03\u00a3\u010a\u010b\u1e0a\u00a7\u1e80\u00a9\u1e82\u1e0b\u1ef2\u00ad\u00ae\u0178"
                    + "\u1e1e\u1e1f\u0120\u0121\u1e40\u1e41\u00b6\u1e56\u1e81\u1e57\u1e83\u1e60\u1ef3\u1e84\u1e85\u1e61"
                    + "\u00c0\u00c1\u00c2\u00c3\u00c4\u00c5\u00c6\u00c7\u00c8\u00c9\u00ca\u00cb\u00cc\u00cd\u00ce\u00cf"
                    + "\u0174\u00d1\u00d2\u00d3\u00d4\u00d5\u00d6\u1e6a\u00d8\u00d9\u00da\u00db\u00dc\u00dd\u0176\u00df"
                    + "\u00e0\u00e1\u00e2\u00e3\u00e4\u00e5\u00e6\u00e7\u00e8\u00e9\u00ea\u00eb\u00ec\u00ed\u00ee\u00ef"
                    + "\u0175\u00f1\u00f2\u00f3\u00f4\u00f5\u00f6\u1e6b\u00f8\u00f9\u00fa\u00fb\u00fc\u00fd\u0177\u00ff";

    private TextDecoder() {
    }

    /**
     * {@code BytesToUtf8}. Null when {@code eci} is not a character-set ECI.
     * ECI -1 is binary. An empty buffer of a known ECI is an empty string.
     */
    public static String decode(byte[] raw, int eci) {
        if (raw == null) {
            return null;
        }
        if (eci == -1) {
            eci = 899;
        }
        if (!known(eci)) {
            return null;
        }
        if (raw.length == 0) {
            return "";
        }
        return switch (eci) {
            case 1, 3, 27, 170, 899 -> new String(raw, StandardCharsets.ISO_8859_1);
            case 0, 2 -> new String(raw, CP437);
            case 4 -> iso8859(raw, ISO_8859_2);
            case 5 -> iso8859(raw, ISO_8859_3);
            case 6 -> iso8859(raw, ISO_8859_4);
            case 7 -> iso8859(raw, ISO_8859_5);
            case 8 -> iso8859(raw, ISO_8859_6);
            case 9 -> iso8859(raw, ISO_8859_7);
            case 10 -> iso8859(raw, ISO_8859_8);
            case 11 -> iso8859(raw, ISO_8859_9);
            case 12 -> iso8859(raw, ISO_8859_10);
            case 13 -> iso8859(raw, ISO_8859_11);
            case 15 -> iso8859(raw, ISO_8859_13);
            case 16 -> iso8859(raw, ISO_8859_14);
            case 17 -> iso8859(raw, ISO_8859_15);
            case 18 -> iso8859(raw, ISO_8859_16);
            case 20 -> shiftJis(raw);
            case 21 -> new String(raw, WINDOWS_1250);
            case 22 -> new String(raw, WINDOWS_1251);
            case 23 -> new String(raw, WINDOWS_1252);
            case 24 -> new String(raw, WINDOWS_1256);
            case 25 -> utf(raw, StandardCharsets.UTF_16BE);
            case 26 -> utf(raw, StandardCharsets.UTF_8);
            case 28 -> multibyte(raw, BIG5, true, false);
            case 29 -> multibyte(raw, GB2312, false, false);
            case 30 -> multibyte(raw, EUC_KR, false, false);
            case 31 -> multibyte(raw, GBK, false, false);
            case 32 -> multibyte(raw, GB18030, false, true);
            case 33 -> utf(raw, StandardCharsets.UTF_16LE);
            case 34 -> utf(raw, Charset.forName("UTF-32BE"));
            case 35 -> utf(raw, Charset.forName("UTF-32LE"));
            default -> null;
        };
    }

    /**
     * {@link #decode}, except an ECI this class does not know is read as ISO-8859-1.
     * That is the decoder policy. ECI -1 stays binary, which has the same repertoire.
     */
    public static String decodeOrLatin1(byte[] raw, int eci) {
        if (raw == null) {
            return null;
        }
        String text = decode(raw, eci);
        return text != null ? text : new String(raw, StandardCharsets.ISO_8859_1);
    }

    private static boolean known(int eci) {
        return eci == 170 || eci == 899
                || (eci >= 0 && eci <= 13)
                || (eci >= 15 && eci <= 18)
                || (eci >= 20 && eci <= 35);
    }

    /** Bytes below {@code 0xA0} stay themselves. An unmapped high byte stays itself. */
    private static String iso8859(byte[] raw, Charset charset) {
        CharsetDecoder decoder = reporting(charset);
        StringBuilder text = new StringBuilder(raw.length);
        for (byte value : raw) {
            int b = value & 0xFF;
            if (b < 0xA0) {
                text.append((char) b);
                continue;
            }
            text.append(one(decoder, new byte[] {value}, (char) b));
        }
        return text.toString();
    }

    private static String iso8859(byte[] raw, String high) {
        StringBuilder text = new StringBuilder(raw.length);
        for (byte value : raw) {
            int b = value & 0xFF;
            text.append(b < 0xA0 ? (char) b : high.charAt(b - 0xA0));
        }
        return text.toString();
    }

    /**
     * ASCII is itself, including backslash and tilde. Half-width katakana is one byte.
     * A rejected pair becomes U+FFFD and consumes two bytes when a second byte is present.
     */
    private static String shiftJis(byte[] raw) {
        CharsetDecoder decoder = reporting(SHIFT_JIS);
        StringBuilder text = new StringBuilder(raw.length);
        for (int i = 0; i < raw.length; ) {
            int c1 = raw[i] & 0xFF;
            if (c1 < 0x80) {
                text.append((char) c1);
                i++;
                continue;
            }
            if (c1 >= 0xA1 && c1 <= 0xDF) {
                text.append((char) (0xFEC0 + c1));
                i++;
                continue;
            }
            if (i + 1 >= raw.length
                    || c1 == 0x80
                    || c1 == 0xA0
                    || (c1 > 0xEA && c1 < 0xF0)
                    || c1 > 0xF9) {
                text.append('\uFFFD');
                i += i + 1 >= raw.length ? 1 : 2;
                continue;
            }
            int c2 = raw[i + 1] & 0xFF;
            if (c2 < 0x40 || c2 == 0x7F || c2 > 0xFC) {
                text.append('\uFFFD');
                i += 2;
                continue;
            }
            if (c1 == 0x81 && c2 == 0x5C) {
                text.append('\u2015');
                i += 2;
                continue;
            }
            if (c1 == 0x81 && c2 == 0x5F) {
                text.append('\\');
                i += 2;
                continue;
            }
            if (c1 >= 0xF0 && c1 <= 0xF9) {
                int code = 0xE000 + 188 * (c1 - 0xF0) + c2 - 0x40 - (c2 > 0x7F ? 1 : 0);
                text.append((char) code);
                i += 2;
                continue;
            }
            text.append(one(decoder, new byte[] {raw[i], raw[i + 1]}, '\uFFFD'));
            i += 2;
        }
        return text.toString();
    }

    private static String multibyte(byte[] raw, Charset charset, boolean big5, boolean gb18030) {
        CharsetDecoder decoder = reporting(charset);
        StringBuilder text = new StringBuilder(raw.length);
        for (int i = 0; i < raw.length; ) {
            int c1 = raw[i] & 0xFF;
            if (c1 < 0x80) {
                text.append((char) c1);
                i++;
                continue;
            }
            int width = 2;
            if (gb18030 && i + 1 < raw.length) {
                int c2 = raw[i + 1] & 0xFF;
                if (c1 >= 0x81 && c1 <= 0xE3 && c2 >= 0x30 && c2 <= 0x39 && i + 4 <= raw.length) {
                    width = 4;
                }
            }
            if (i + width > raw.length) {
                text.append('\uFFFD');
                i += raw.length - i;
                continue;
            }
            if (width == 2) {
                int c2 = raw[i + 1] & 0xFF;
                if (big5 && big5Gap(c1, c2)) {
                    text.append('\uFFFD');
                    i += 2;
                    continue;
                }
                int supplementary = gb18030 ? gb18030Supplementary(c1, c2) : -1;
                if (supplementary >= 0) {
                    text.append(Character.toChars(supplementary));
                    i += 2;
                    continue;
                }
            }
            byte[] slice = new byte[width];
            System.arraycopy(raw, i, slice, 0, width);
            text.append(one(decoder, slice, '\uFFFD'));
            i += width;
        }
        return text.toString();
    }

    /** Pairs the JDK maps inside the BMP and libzueci maps outside it. */
    private static int gb18030Supplementary(int c1, int c2) {
        if (c1 != 0xFE) {
            return -1;
        }
        return switch (c2) {
            case 0x51 -> 0x20087;
            case 0x52 -> 0x20089;
            case 0x53 -> 0x200CC;
            case 0x6C -> 0x215D7;
            case 0x76 -> 0x2298F;
            case 0x91 -> 0x241FE;
            default -> -1;
        };
    }

    /** Big5 pairs the JDK decodes and libzueci leaves unmapped. */
    private static boolean big5Gap(int c1, int c2) {
        return (c1 == 0xA1 && (c2 == 0x5A || c2 == 0xFE))
                || (c1 == 0xA2 && (c2 == 0x40 || c2 == 0xCC || c2 == 0xCE));
    }

    private static String utf(byte[] raw, Charset charset) {
        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
                .replaceWith("\uFFFD");
        try {
            return decoder.decode(ByteBuffer.wrap(raw)).toString();
        } catch (CharacterCodingException failed) {
            return "\uFFFD";
        }
    }

    private static CharsetDecoder reporting(Charset charset) {
        return charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
    }

    private static String one(CharsetDecoder decoder, byte[] raw, char fallback) {
        try {
            decoder.reset();
            return decoder.decode(ByteBuffer.wrap(raw)).toString();
        } catch (CharacterCodingException unmapped) {
            decoder.reset();
            return String.valueOf(fallback);
        }
    }
}
