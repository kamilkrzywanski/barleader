/*
 * Copyright 2011 ZXing authors
 * Copyright 2026 Kamil Krzywański
 */
// SPDX-License-Identifier: Apache-2.0
package io.github.kamilkrzywanski.barcode.encode;

/**
 * What the linear encoders share: element widths turned into modules, the narrow and wide widths
 * of a wide-to-narrow ratio, and the GTIN check digit.
 */
public final class Linear {
    private Linear() {
    }

    /** One encoded linear symbol: its modules without the quiet zones, and the text printed under it. */
    public record Bars(boolean[] modules, String text) {
    }

    /**
     * Writes elements of the given widths from {@code pos}, the first in {@code startColor} and
     * then alternating, and returns the number of modules written (ZXing's {@code appendPattern}).
     */
    static int appendPattern(boolean[] target, int pos, int[] pattern, boolean startColor) {
        boolean color = startColor;
        int numAdded = 0;
        for (int len : pattern) {
            for (int j = 0; j < len; j++) {
                target[pos++] = color;
            }
            numAdded += len;
            color = !color;
        }
        return numAdded;
    }

    /** The modules of a sequence of element widths, starting with a bar. */
    static boolean[] modules(int[] widths, int count) {
        int total = 0;
        for (int i = 0; i < count; i++) {
            total += widths[i];
        }
        boolean[] modules = new boolean[total];
        int pos = 0;
        for (int i = 0; i < count; i++) {
            for (int j = 0; j < widths[i]; j++) {
                modules[pos++] = i % 2 == 0;
            }
        }
        return modules;
    }

    public static void checkNumeric(String contents) {
        for (int i = 0; i < contents.length(); i++) {
            char c = contents.charAt(i);
            if (c < '0' || c > '9') {
                throw new IllegalArgumentException("Input should only contain digits 0-9");
            }
        }
    }

    /**
     * The narrow and wide widths in modules of a wide-to-narrow ratio from 2.0 to 3.0: 2.0 is one
     * and two modules, 2.5 two and five, 3.0 one and three. A ratio between them is the nearest
     * that a narrow element of at most four modules can give.
     */
    public static int[] ratio(double ratio) {
        if (!(ratio >= 2.0 && ratio <= 3.0)) {
            throw new IllegalArgumentException("The wide-to-narrow ratio must be from 2.0 to 3.0, not " + ratio);
        }
        int bestNarrow = 1;
        int bestWide = 2;
        double bestError = Double.MAX_VALUE;
        for (int narrow = 1; narrow <= 4; narrow++) {
            int wide = (int) Math.round(ratio * narrow);
            double error = Math.abs((double) wide / narrow - ratio);
            if (error < bestError - 1e-9) {
                bestError = error;
                bestNarrow = narrow;
                bestWide = wide;
            }
        }
        return new int[]{bestNarrow, bestWide};
    }

    /** The GTIN, EAN and UPC check digit of {@code digits}, the check digit itself left out. */
    static int gtinCheckDigit(CharSequence digits) {
        int sum = 0;
        for (int i = digits.length() - 1, weight = 3; i >= 0; i--, weight = 4 - weight) {
            sum += (digits.charAt(i) - '0') * weight;
        }
        return (10 - sum % 10) % 10;
    }

    /** {@code digits} with its check digit when it is one digit short, else as it is after checking the last. */
    public static String withGtinCheckDigit(String digits, int length, String name) {
        checkNumeric(digits);
        if (digits.length() == length - 1) {
            return digits + gtinCheckDigit(digits);
        }
        if (digits.length() != length) {
            throw new IllegalArgumentException(name + " has " + (length - 1) + " or " + length + " digits, not " + digits.length());
        }
        if (gtinCheckDigit(digits.subSequence(0, length - 1)) != digits.charAt(length - 1) - '0') {
            throw new IllegalArgumentException("The check digit of " + digits + " should be "
                    + gtinCheckDigit(digits.subSequence(0, length - 1)));
        }
        return digits;
    }
}
