package io.github.kamilkrzywanski.barcode.encode;

import java.util.Arrays;

/**
 * The symbologies of narrow and wide elements: Code 39, Codabar, Interleaved 2 of 5 and Code 11.
 * Each builds its elements as narrow or wide, bar and space alternating from a bar, and
 * {@link #expand} turns them into modules at the narrow and wide widths of the chosen ratio, so a
 * ratio of 2.0 gives ZXing's Code 39 and Codabar and 3.0 its Interleaved 2 of 5.
 *
 * <p>Code 39, Codabar and Interleaved 2 of 5 are ported from ZXing 3.5.4 {@code Code39Writer},
 * {@code CodaBarWriter} and {@code ITFWriter} with the tables of their readers (Apache License 2.0,
 * the ZXing authors); Code 11 and the Code 39 check character from Barcode-Lib4J 3.2.0
 * {@code ImplCode11} and {@code ImplCode39} (Apache License 2.0, Copyright 2025 Viktor Wedel).
 */
public final class WideNarrowEncoders {
    static final String CODE39_ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ-. $/+%";
    private static final int[] CODE39_ENCODINGS = {
            0x034, 0x121, 0x061, 0x160, 0x031, 0x130, 0x070, 0x025, 0x124, 0x064, 0x109, 0x049, 0x148, 0x019, 0x118,
            0x058, 0x00D, 0x10C, 0x04C, 0x01C, 0x103, 0x043, 0x142, 0x013, 0x112, 0x052, 0x007, 0x106, 0x046, 0x016,
            0x181, 0x0C1, 0x1C0, 0x091, 0x190, 0x0D0, 0x085, 0x184, 0x0C4, 0x0A8, 0x0A2, 0x08A, 0x02A
    };
    private static final int CODE39_ASTERISK = 0x094;

    private static final String CODABAR_ALPHABET = "0123456789-$:/.+ABCD";
    private static final int[] CODABAR_ENCODINGS = {
            0x003, 0x006, 0x009, 0x060, 0x012, 0x042, 0x021, 0x024, 0x030, 0x048, 0x00c, 0x018, 0x045, 0x051, 0x054,
            0x015, 0x01A, 0x029, 0x00B, 0x00E
    };
    private static final char[] CODABAR_START_END = {'A', 'B', 'C', 'D'};
    private static final char[] CODABAR_ALT_START_END = {'T', 'N', '*', 'E'};

    /** Interleaved 2 of 5 digits, five elements each, true wide. */
    private static final boolean[][] ITF_PATTERNS = {
            {false, false, true, true, false}, {true, false, false, false, true}, {false, true, false, false, true},
            {true, true, false, false, false}, {false, false, true, false, true}, {true, false, true, false, false},
            {false, true, true, false, false}, {false, false, false, true, true}, {true, false, false, true, false},
            {false, true, false, true, false}
    };

    private static final String CODE11_ALPHABET = "0123456789-";
    private static final int[] CODE11_BARS = {1, 17, 9, 24, 5, 20, 12, 3, 18, 16, 4};

    private WideNarrowEncoders() {
    }

    /** Elements as they are built: true wide, bars and spaces alternating from a bar. */
    private static final class Elements {
        private boolean[] wide = new boolean[64];
        private int size;

        void add(boolean isWide) {
            if (size == wide.length) {
                wide = Arrays.copyOf(wide, size * 2);
            }
            wide[size++] = isWide;
        }

        /** The low {@code count} bits of {@code bits}, highest first, a set bit wide. */
        void addBits(int bits, int count) {
            for (int i = count - 1; i >= 0; i--) {
                add((bits >> i & 1) != 0);
            }
        }
    }

    /** The modules of the elements at {@code narrow} and {@code wide} modules an element. */
    private static boolean[] expand(Elements elements, int narrow, int wide) {
        int total = 0;
        for (int i = 0; i < elements.size; i++) {
            total += elements.wide[i] ? wide : narrow;
        }
        boolean[] modules = new boolean[total];
        int pos = 0;
        for (int i = 0; i < elements.size; i++) {
            int width = elements.wide[i] ? wide : narrow;
            boolean bar = i % 2 == 0;
            for (int j = 0; j < width; j++) {
                modules[pos++] = bar;
            }
        }
        return modules;
    }

    /**
     * Code 39 between its asterisks. {@code extended} writes all of ASCII with ZXing's shift pairs
     * ({@code $A} for 1, {@code +A} for {@code a}, and so on) when a character needs one;
     * {@code checksum} appends the modulo 43 check character.
     *
     * @return the characters written, shifts and check character included, and the modules
     */
    public static Linear.Bars code39(String contents, boolean extended, boolean checksum, int narrow, int wide) {
        String text = contents;
        for (int i = 0; i < contents.length(); i++) {
            if (CODE39_ALPHABET.indexOf(contents.charAt(i)) < 0) {
                if (!extended) {
                    throw new IllegalArgumentException("Code 39 cannot hold '" + contents.charAt(i) + "'; Code 39 Extended can");
                }
                contents = toCode39Extended(contents);
                break;
            }
        }
        if (contents.isEmpty()) {
            throw new IllegalArgumentException("Code 39 needs at least one character");
        }
        if (checksum) {
            int sum = 0;
            for (int i = 0; i < contents.length(); i++) {
                sum += CODE39_ALPHABET.indexOf(contents.charAt(i));
            }
            char check = CODE39_ALPHABET.charAt(sum % 43);
            contents += check;
            text += check;
        }
        Elements elements = new Elements();
        elements.addBits(CODE39_ASTERISK, 9);
        for (int i = 0; i < contents.length(); i++) {
            elements.add(false);
            elements.addBits(CODE39_ENCODINGS[CODE39_ALPHABET.indexOf(contents.charAt(i))], 9);
        }
        elements.add(false);
        elements.addBits(CODE39_ASTERISK, 9);
        return new Linear.Bars(expand(elements, narrow, wide), text);
    }

    /** ZXing's {@code tryToConvertToExtendedMode}: every ASCII character as one or two Code 39 characters. */
    static String toCode39Extended(String contents) {
        StringBuilder extended = new StringBuilder();
        for (int i = 0; i < contents.length(); i++) {
            char character = contents.charAt(i);
            switch (character) {
                case '\u0000' -> extended.append("%U");
                case ' ', '-', '.' -> extended.append(character);
                case '@' -> extended.append("%V");
                case '`' -> extended.append("%W");
                default -> {
                    if (character <= 26) {
                        extended.append('$').append((char) ('A' + (character - 1)));
                    } else if (character < ' ') {
                        extended.append('%').append((char) ('A' + (character - 27)));
                    } else if (character <= ',' || character == '/' || character == ':') {
                        extended.append('/').append((char) ('A' + (character - 33)));
                    } else if (character <= '9') {
                        extended.append((char) ('0' + (character - 48)));
                    } else if (character <= '?') {
                        extended.append('%').append((char) ('F' + (character - 59)));
                    } else if (character <= 'Z') {
                        extended.append((char) ('A' + (character - 65)));
                    } else if (character <= '_') {
                        extended.append('%').append((char) ('K' + (character - 91)));
                    } else if (character <= 'z') {
                        extended.append('+').append((char) ('A' + (character - 97)));
                    } else if (character <= 127) {
                        extended.append('%').append((char) ('P' + (character - 123)));
                    } else {
                        throw new IllegalArgumentException("Code 39 Extended cannot hold '" + character + "'");
                    }
                }
            }
        }
        return extended.toString();
    }

    /**
     * Codabar. The text may carry its start and stop characters, A to D or T, N, * and E; without
     * them both are A, as in ZXing.
     */
    public static Linear.Bars codabar(String contents, int narrow, int wide) {
        String text = contents;
        if (contents.length() < 2) {
            contents = 'A' + contents + 'A';
        } else {
            char first = Character.toUpperCase(contents.charAt(0));
            char last = Character.toUpperCase(contents.charAt(contents.length() - 1));
            boolean startsNormal = contains(CODABAR_START_END, first);
            boolean endsNormal = contains(CODABAR_START_END, last);
            boolean startsAlt = contains(CODABAR_ALT_START_END, first);
            boolean endsAlt = contains(CODABAR_ALT_START_END, last);
            if (startsNormal) {
                if (!endsNormal) {
                    throw new IllegalArgumentException("Invalid start/end guards: " + contents);
                }
            } else if (startsAlt) {
                if (!endsAlt) {
                    throw new IllegalArgumentException("Invalid start/end guards: " + contents);
                }
            } else {
                if (endsNormal || endsAlt) {
                    throw new IllegalArgumentException("Invalid start/end guards: " + contents);
                }
                contents = 'A' + contents + 'A';
            }
        }
        for (int i = 1; i < contents.length() - 1; i++) {
            char c = contents.charAt(i);
            if (!Character.isDigit(c) && "-$/:+.".indexOf(c) < 0) {
                throw new IllegalArgumentException("Cannot encode : '" + c + '\'');
            }
        }
        Elements elements = new Elements();
        for (int index = 0; index < contents.length(); index++) {
            char c = Character.toUpperCase(contents.charAt(index));
            if (index == 0 || index == contents.length() - 1) {
                c = switch (c) {
                    case 'T' -> 'A';
                    case 'N' -> 'B';
                    case '*' -> 'C';
                    case 'E' -> 'D';
                    default -> c;
                };
            }
            if (index > 0) {
                elements.add(false);
            }
            elements.addBits(CODABAR_ENCODINGS[CODABAR_ALPHABET.indexOf(c)], 7);
        }
        return new Linear.Bars(expand(elements, narrow, wide), text);
    }

    private static boolean contains(char[] array, char key) {
        for (char c : array) {
            if (c == key) {
                return true;
            }
        }
        return false;
    }

    /** Interleaved 2 of 5 of an even number of digits. */
    public static Linear.Bars itf(String contents, int narrow, int wide) {
        if (contents.isEmpty() || contents.length() % 2 != 0) {
            throw new IllegalArgumentException("Interleaved 2 of 5 holds an even number of digits, not " + contents.length());
        }
        Linear.checkNumeric(contents);
        Elements elements = new Elements();
        for (int i = 0; i < 4; i++) {
            elements.add(false);
        }
        for (int i = 0; i < contents.length(); i += 2) {
            boolean[] one = ITF_PATTERNS[contents.charAt(i) - '0'];
            boolean[] two = ITF_PATTERNS[contents.charAt(i + 1) - '0'];
            for (int j = 0; j < 5; j++) {
                elements.add(one[j]);
                elements.add(two[j]);
            }
        }
        elements.add(true);
        elements.add(false);
        elements.add(false);
        return new Linear.Bars(expand(elements, narrow, wide), contents);
    }

    /**
     * Code 11 of digits and dashes. {@code checksum} appends the check characters C and K, as
     * Barcode-Lib4J does.
     */
    public static Linear.Bars code11(String contents, boolean checksum, int narrow, int wide) {
        if (contents.isEmpty()) {
            throw new IllegalArgumentException("Code 11 needs at least one character");
        }
        for (int i = 0; i < contents.length(); i++) {
            if (CODE11_ALPHABET.indexOf(contents.charAt(i)) < 0) {
                throw new IllegalArgumentException("Code 11 holds digits and '-', not '" + contents.charAt(i) + "'");
            }
        }
        String text = contents;
        if (checksum) {
            contents += code11Checksum(contents);
            text = contents;
        }
        Elements elements = new Elements();
        addCode11StartStop(elements);
        elements.add(false);
        for (int i = 0; i < contents.length(); i++) {
            elements.addBits(CODE11_BARS[CODE11_ALPHABET.indexOf(contents.charAt(i))], 5);
            elements.add(false);
        }
        addCode11StartStop(elements);
        return new Linear.Bars(expand(elements, narrow, wide), text);
    }

    /** Bar, space, wide bar, wide space, bar: the start and stop character. */
    private static void addCode11StartStop(Elements elements) {
        elements.add(false);
        elements.add(false);
        elements.add(true);
        elements.add(true);
        elements.add(false);
    }

    /** The check characters C, weights 1 to 10, and K, weights 1 to 9 with C included. */
    private static String code11Checksum(String content) {
        int sum = 0;
        int count = 0;
        for (int i = content.length() - 1; i >= 0; i--) {
            sum += (++count) * CODE11_ALPHABET.indexOf(content.charAt(i));
            if (count == 10) {
                count = 0;
            }
        }
        char c = CODE11_ALPHABET.charAt(sum % 11);
        sum = CODE11_ALPHABET.indexOf(c);
        count = 1;
        for (int i = content.length() - 1; i >= 0; i--) {
            sum += (++count) * CODE11_ALPHABET.indexOf(content.charAt(i));
            if (count == 9) {
                count = 0;
            }
        }
        char k = CODE11_ALPHABET.charAt(sum % 11);
        return "" + c + k;
    }
}
