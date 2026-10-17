/*
 * Copyright 2015 ZXing authors
 * Copyright 2010 ZXing authors
 * Copyright 2026 Kamil Krzywański
 */
// SPDX-License-Identifier: Apache-2.0
package io.github.kamilkrzywanski.barcode.encode;

/**
 * Code 93 with its two check characters. The extended form writes all of ASCII through the four
 * shift characters.
 *
 * <p>Ported from ZXing 3.5.4 {@code com.google.zxing.oned.Code93Writer} with the tables of
 * {@code Code93Reader} (Apache License 2.0, the ZXing authors).
 */
public final class Code93Encoder {
    private static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ-. $/+%abcd*";
    private static final int BASIC = 43;
    private static final int[] ENCODINGS = {
            0x114, 0x148, 0x144, 0x142, 0x128, 0x124, 0x122, 0x150, 0x112, 0x10A, 0x1A8, 0x1A4, 0x1A2, 0x194, 0x192,
            0x18A, 0x168, 0x164, 0x162, 0x134, 0x11A, 0x158, 0x14C, 0x146, 0x12C, 0x116, 0x1B4, 0x1B2, 0x1AC, 0x1A6,
            0x196, 0x19A, 0x16C, 0x166, 0x136, 0x13A, 0x12E, 0x1D4, 0x1D2, 0x1CA, 0x16E, 0x176, 0x1AE, 0x126, 0x1DA,
            0x1D6, 0x132, 0x15E
    };
    private static final int ASTERISK = ENCODINGS[47];

    private Code93Encoder() {
    }

    /** @param extended all of ASCII through the shift characters, else only the 43 characters */
    public static Linear.Bars encode(String contents, boolean extended) {
        if (contents.isEmpty()) {
            throw new IllegalArgumentException("Code 93 needs at least one character");
        }
        String text = contents;
        if (!extended) {
            for (int i = 0; i < contents.length(); i++) {
                int index = ALPHABET.indexOf(contents.charAt(i));
                if (index < 0 || index >= BASIC) {
                    throw new IllegalArgumentException("Code 93 cannot hold '" + contents.charAt(i) + "'; Code 93 Extended can");
                }
            }
        }
        contents = convertToExtended(contents);
        int codeWidth = (contents.length() + 2 + 2) * 9 + 1;
        boolean[] result = new boolean[codeWidth];
        int pos = appendPattern(result, 0, ASTERISK);
        for (int i = 0; i < contents.length(); i++) {
            pos += appendPattern(result, pos, ENCODINGS[ALPHABET.indexOf(contents.charAt(i))]);
        }
        int check1 = computeChecksumIndex(contents, 20);
        pos += appendPattern(result, pos, ENCODINGS[check1]);
        contents += ALPHABET.charAt(check1);
        int check2 = computeChecksumIndex(contents, 15);
        pos += appendPattern(result, pos, ENCODINGS[check2]);
        pos += appendPattern(result, pos, ASTERISK);
        result[pos] = true;
        return new Linear.Bars(result, text);
    }

    private static int appendPattern(boolean[] target, int pos, int a) {
        for (int i = 0; i < 9; i++) {
            target[pos + i] = (a & (1 << (8 - i))) != 0;
        }
        return 9;
    }

    private static int computeChecksumIndex(String contents, int maxWeight) {
        int weight = 1;
        int total = 0;
        for (int i = contents.length() - 1; i >= 0; i--) {
            total += ALPHABET.indexOf(contents.charAt(i)) * weight;
            if (++weight > maxWeight) {
                weight = 1;
            }
        }
        return total % 47;
    }

    /** ZXing's {@code convertToExtended}: the shift characters a to d stand for $, %, / and +. */
    static String convertToExtended(String contents) {
        StringBuilder extended = new StringBuilder(contents.length() * 2);
        for (int i = 0; i < contents.length(); i++) {
            char character = contents.charAt(i);
            if (character == 0) {
                extended.append("bU");
            } else if (character <= 26) {
                extended.append('a').append((char) ('A' + character - 1));
            } else if (character <= 31) {
                extended.append('b').append((char) ('A' + character - 27));
            } else if (character == ' ' || character == '$' || character == '%' || character == '+') {
                extended.append(character);
            } else if (character <= ',') {
                extended.append('c').append((char) ('A' + character - '!'));
            } else if (character <= '9') {
                extended.append(character);
            } else if (character == ':') {
                extended.append("cZ");
            } else if (character <= '?') {
                extended.append('b').append((char) ('F' + character - ';'));
            } else if (character == '@') {
                extended.append("bV");
            } else if (character <= 'Z') {
                extended.append(character);
            } else if (character <= '_') {
                extended.append('b').append((char) ('K' + character - '['));
            } else if (character == '`') {
                extended.append("bW");
            } else if (character <= 'z') {
                extended.append('d').append((char) ('A' + character - 'a'));
            } else if (character <= 127) {
                extended.append('b').append((char) ('P' + character - '{'));
            } else {
                throw new IllegalArgumentException("Code 93 Extended cannot hold '" + character + "'");
            }
        }
        return extended.toString();
    }
}
