/*
 * Copyright 2009 ZXing authors
 * Copyright 2010 ZXing authors
 * Copyright (c) 2025 Viktor Wedel
 * Copyright 2026 Kamil Krzywański
 */
// SPDX-License-Identifier: Apache-2.0
package io.github.kamilkrzywanski.barleader.encode;

/**
 * EAN-13, EAN-8, UPC-A and UPC-E, with their check digit added when it is left out, a 2- or 5-digit
 * add-on, and ISBN-13 and ISMN as hyphenated EAN-13.
 *
 * <p>The symbols are ported from ZXing 3.5.4 {@code EAN13Writer}, {@code EAN8Writer},
 * {@code UPCAWriter} and {@code UPCEWriter} with the tables of their readers (Apache License 2.0,
 * the ZXing authors); the add-on, the quiet zones and the ISBN and ISMN rules from Barcode-Lib4J
 * 3.2.0 {@code UPCEANFamily}, {@code ImplISBN13} and {@code ImplISMN} (Apache License 2.0, Copyright
 * 2025 Viktor Wedel).
 */
public final class UpcEanEncoder {
    private static final int[] START_END = {1, 1, 1};
    private static final int[] MIDDLE = {1, 1, 1, 1, 1};
    private static final int[] UPCE_END = {1, 1, 1, 1, 1, 1};
    private static final int[][] L_PATTERNS = {
            {3, 2, 1, 1}, {2, 2, 2, 1}, {2, 1, 2, 2}, {1, 4, 1, 1}, {1, 1, 3, 2},
            {1, 2, 3, 1}, {1, 1, 1, 4}, {1, 3, 1, 2}, {1, 2, 1, 3}, {3, 1, 1, 2}
    };
    private static final int[][] L_AND_G_PATTERNS = new int[20][];
    private static final int[] FIRST_DIGIT_ENCODINGS = {0x00, 0x0B, 0x0D, 0xE, 0x13, 0x19, 0x1C, 0x15, 0x16, 0x1A};
    private static final int[][] NUMSYS_AND_CHECK_DIGIT_PATTERNS = {
            {0x38, 0x34, 0x32, 0x31, 0x2C, 0x26, 0x23, 0x2A, 0x29, 0x25},
            {0x07, 0x0B, 0x0D, 0x0E, 0x13, 0x19, 0x1C, 0x15, 0x16, 0x1A}
    };
    private static final int[] ADDON5_PARITIES = {24, 20, 18, 17, 12, 6, 3, 10, 9, 5};
    /** The light modules after an add-on. */
    public static final int ADDON_QUIET_ZONE = 5;

    static {
        System.arraycopy(L_PATTERNS, 0, L_AND_G_PATTERNS, 0, 10);
        for (int i = 10; i < 20; i++) {
            int[] widths = L_PATTERNS[i - 10];
            int[] reversed = new int[widths.length];
            for (int j = 0; j < widths.length; j++) {
                reversed[j] = widths[widths.length - j - 1];
            }
            L_AND_G_PATTERNS[i] = reversed;
        }
    }

    public enum Kind {
        EAN_13(11, 7), EAN_8(7, 7), UPC_A(9, 9), UPC_E(9, 7);

        /** The quiet zones of the symbol, in modules; an add-on sits after the right one. */
        public final int quietLeft;
        public final int quietRight;

        Kind(int quietLeft, int quietRight) {
            this.quietLeft = quietLeft;
            this.quietRight = quietRight;
        }
    }

    private UpcEanEncoder() {
    }

    /**
     * The symbol of {@code digits}, its check digit added when it is one digit short, and the add-on
     * after the gap of the symbol's right quiet zone when {@code addOn} is not null.
     */
    public static Linear.Bars encode(Kind kind, String digits, String addOn) {
        boolean[] main;
        String text;
        switch (kind) {
            case EAN_13 -> {
                text = Linear.withGtinCheckDigit(digits, 13, "EAN-13");
                main = ean13(text);
            }
            case EAN_8 -> {
                text = Linear.withGtinCheckDigit(digits, 8, "EAN-8");
                main = ean8(text);
            }
            case UPC_A -> {
                text = Linear.withGtinCheckDigit(digits, 12, "UPC-A");
                main = ean13('0' + text);
            }
            default -> {
                text = upceWithCheckDigit(digits);
                main = upce(text);
            }
        }
        if (addOn == null) {
            return new Linear.Bars(main, text);
        }
        boolean[] extra = addOn(addOn);
        boolean[] all = new boolean[main.length + kind.quietRight + extra.length];
        System.arraycopy(main, 0, all, 0, main.length);
        System.arraycopy(extra, 0, all, main.length + kind.quietRight, extra.length);
        return new Linear.Bars(all, text + " " + addOn);
    }

    private static boolean[] ean13(String contents) {
        int parities = FIRST_DIGIT_ENCODINGS[contents.charAt(0) - '0'];
        boolean[] result = new boolean[3 + 7 * 6 + 5 + 7 * 6 + 3];
        int pos = Linear.appendPattern(result, 0, START_END, true);
        for (int i = 1; i <= 6; i++) {
            int digit = contents.charAt(i) - '0';
            if ((parities >> (6 - i) & 1) == 1) {
                digit += 10;
            }
            pos += Linear.appendPattern(result, pos, L_AND_G_PATTERNS[digit], false);
        }
        pos += Linear.appendPattern(result, pos, MIDDLE, false);
        for (int i = 7; i <= 12; i++) {
            pos += Linear.appendPattern(result, pos, L_PATTERNS[contents.charAt(i) - '0'], true);
        }
        Linear.appendPattern(result, pos, START_END, true);
        return result;
    }

    private static boolean[] ean8(String contents) {
        boolean[] result = new boolean[3 + 7 * 4 + 5 + 7 * 4 + 3];
        int pos = Linear.appendPattern(result, 0, START_END, true);
        for (int i = 0; i <= 3; i++) {
            pos += Linear.appendPattern(result, pos, L_PATTERNS[contents.charAt(i) - '0'], false);
        }
        pos += Linear.appendPattern(result, pos, MIDDLE, false);
        for (int i = 4; i <= 7; i++) {
            pos += Linear.appendPattern(result, pos, L_PATTERNS[contents.charAt(i) - '0'], true);
        }
        Linear.appendPattern(result, pos, START_END, true);
        return result;
    }

    private static String upceWithCheckDigit(String digits) {
        Linear.checkNumeric(digits);
        if (digits.length() != 7 && digits.length() != 8) {
            throw new IllegalArgumentException("UPC-E has 7 or 8 digits, not " + digits.length());
        }
        if (digits.charAt(0) != '0' && digits.charAt(0) != '1') {
            throw new IllegalArgumentException("The number system of UPC-E must be 0 or 1");
        }
        String upca = upceToUpca(digits.substring(0, 7));
        int check = Linear.gtinCheckDigit(upca.substring(0, 11));
        if (digits.length() == 7) {
            return digits + check;
        }
        if (digits.charAt(7) - '0' != check) {
            throw new IllegalArgumentException("The check digit of " + digits + " should be " + check);
        }
        return digits;
    }

    private static boolean[] upce(String contents) {
        int parities = NUMSYS_AND_CHECK_DIGIT_PATTERNS[contents.charAt(0) - '0'][contents.charAt(7) - '0'];
        boolean[] result = new boolean[3 + 7 * 6 + 6];
        int pos = Linear.appendPattern(result, 0, START_END, true);
        for (int i = 1; i <= 6; i++) {
            int digit = contents.charAt(i) - '0';
            if ((parities >> (6 - i) & 1) == 1) {
                digit += 10;
            }
            pos += Linear.appendPattern(result, pos, L_AND_G_PATTERNS[digit], false);
        }
        Linear.appendPattern(result, pos, UPCE_END, false);
        return result;
    }

    /** The UPC-A of the seven UPC-E digits before the check digit, without its own check digit. */
    static String upceToUpca(String upce) {
        char[] upceChars = new char[6];
        upce.getChars(1, 7, upceChars, 0);
        StringBuilder result = new StringBuilder(12);
        result.append(upce.charAt(0));
        char lastChar = upceChars[5];
        switch (lastChar) {
            case '0', '1', '2' -> result.append(upceChars, 0, 2).append(lastChar).append("0000").append(upceChars, 2, 3);
            case '3' -> result.append(upceChars, 0, 3).append("00000").append(upceChars, 3, 2);
            case '4' -> result.append(upceChars, 0, 4).append("00000").append(upceChars[4]);
            default -> result.append(upceChars, 0, 5).append("0000").append(lastChar);
        }
        return result.toString();
    }

    /** The 2- or 5-digit add-on: its start guard, the digits in parities set by their value, and separators. */
    static boolean[] addOn(String digits) {
        Linear.checkNumeric(digits);
        if (digits.length() != 2 && digits.length() != 5) {
            throw new IllegalArgumentException("An EAN/UPC add-on has 2 or 5 digits, not " + digits.length());
        }
        StringBuilder modules = new StringBuilder("1011");
        if (digits.length() == 2) {
            int m = Integer.parseInt(digits) % 4;
            modules.append(digitModules(digits.charAt(0) - '0', m >= 2));
            modules.append("01");
            modules.append(digitModules(digits.charAt(1) - '0', m % 2 != 0));
        } else {
            int m = 0;
            for (int i = 4; i >= 0; i--) {
                m += (i % 2 == 0 ? 3 : 9) * (digits.charAt(i) - '0');
            }
            int parity = ADDON5_PARITIES[m % 10];
            for (int i = 4; i >= 0; i--) {
                modules.append(digitModules(digits.charAt(4 - i) - '0', ((parity >> i) & 1) != 0));
                if (i != 0) {
                    modules.append("01");
                }
            }
        }
        boolean[] result = new boolean[modules.length()];
        for (int i = 0; i < result.length; i++) {
            result[i] = modules.charAt(i) == '1';
        }
        return result;
    }

    /** A left-hand digit, odd parity (set A) or even (set B), as seven modules starting light. */
    private static String digitModules(int digit, boolean even) {
        int[] widths = L_AND_G_PATTERNS[even ? digit + 10 : digit];
        StringBuilder out = new StringBuilder(7);
        boolean dark = false;
        for (int width : widths) {
            out.append((dark ? "1" : "0").repeat(width));
            dark = !dark;
        }
        return out.toString();
    }

    /**
     * ISBN-13 or ISMN as Barcode-Lib4J reads them: five hyphenated parts, prefix, group, publisher,
     * item and check digit, or four when the check digit is to be computed.
     *
     * @param kind {@code "ISBN"}, whose prefix is 978 or 979, or {@code "ISMN"}, which starts 979-0
     * @return the 13 digits, and the text, such as {@code ISBN 978-1-2345-6789-7}
     */
    public static String[] isxn(String content, String kind) {
        if (kind.equals("ISBN") ? !(content.startsWith("978-") || content.startsWith("979-")) : !content.startsWith("979-0-")) {
            throw new IllegalArgumentException(kind.equals("ISBN") ? "An ISBN-13 starts with 978- or 979-" : "An ISMN starts with 979-0-");
        }
        java.util.List<String> parts = new java.util.ArrayList<>(5);
        StringBuilder part = new StringBuilder();
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c >= '0' && c <= '9') {
                part.append(c);
            } else if (c == '-') {
                if (parts.size() == 4) {
                    throw new IllegalArgumentException(kind + " has more than five parts");
                }
                parts.add(part.toString());
                part.setLength(0);
            } else {
                throw new IllegalArgumentException("Invalid character at position " + i + " of the " + kind);
            }
        }
        if (part.length() != 0) {
            parts.add(part.toString());
        }
        if (parts.size() != 5 && parts.size() != 4) {
            throw new IllegalArgumentException(kind + " has five hyphenated parts, or four without the check digit, not " + parts.size());
        }
        if (parts.get(1).length() > 5 || parts.get(2).length() > 7 || parts.get(3).length() > 6) {
            throw new IllegalArgumentException("Some parts of the " + kind + " have the wrong length");
        }
        String digits = parts.get(0) + parts.get(1) + parts.get(2) + parts.get(3);
        if (digits.length() != 12) {
            throw new IllegalArgumentException(kind + " has 12 digits before the check digit, not " + digits.length());
        }
        String text = content;
        if (parts.size() == 5) {
            if (parts.get(4).length() != 1) {
                throw new IllegalArgumentException("The check digit of an " + kind + " is one digit");
            }
            digits = Linear.withGtinCheckDigit(digits + parts.get(4), 13, kind);
        } else {
            int check = Linear.gtinCheckDigit(digits);
            digits += check;
            text = (content.endsWith("-") ? content : content + "-") + check;
        }
        return new String[]{digits, kind + " " + text};
    }
}
