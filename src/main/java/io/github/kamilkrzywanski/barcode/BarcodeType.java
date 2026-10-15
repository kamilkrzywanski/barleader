package io.github.kamilkrzywanski.barcode;

/**
 * What {@link BarcodeWriter} can write: the symbologies of ZXing's writers and of Barcode-Lib4J.
 * The content each takes is described at the constant.
 */
public enum BarcodeType {
    /** Any text; see {@link QrCode}. */
    QR_CODE(false),
    /** GS1 element strings with FNC1 in first position, written as {@code (01)09501101530003(10)AB-123}. */
    GS1_QR_CODE(false),
    /** ASCII text, the code sets A, B and C chosen as the text goes. */
    CODE_128(true),
    /** ASCII up to underscore, control characters included, all in code set A. */
    CODE_128_A(true),
    /** Printable ASCII, all in code set B. */
    CODE_128_B(true),
    /** An even number of digits, all in code set C; an odd number gets a leading zero. */
    CODE_128_C(true),
    /** GS1 element strings, written as {@code (01)09501101530003(17)140704(10)AB-123}. */
    GS1_128(true),
    /** A GTIN-14 in GS1-128 with AI 01: 13 digits, or 14 with the check digit. */
    EAN_14(true),
    /** A serial shipping container code in GS1-128 with AI 00: 17 digits, or 18 with the check digit. */
    SSCC_18(true),
    /** Digits, capitals, space and {@code -.$/+%}. */
    CODE_39(true),
    /** All of ASCII, through Code 39's shift pairs. */
    CODE_39_EXTENDED(true),
    /** A German pharmacy number in Code 39: 6 digits, or 7 with the check digit. */
    PZN(true),
    /** An 8-digit German pharmacy number in Code 39: 7 digits, or 8 with the check digit. */
    PZN_8(true),
    /** Digits, capitals, space and {@code -.$/+%}, with Code 93's two check characters. */
    CODE_93(true),
    /** All of ASCII, through Code 93's shift characters. */
    CODE_93_EXTENDED(true),
    /** Digits and {@code -$:/.+}, with or without the start and stop characters A to D. */
    CODABAR(true),
    /** Digits and the dash. */
    CODE_11(true),
    /** Interleaved 2 of 5: an even number of digits. */
    ITF(true),
    /** 12 digits, or 13 with the check digit. */
    EAN_13(true),
    /** 7 digits, or 8 with the check digit. */
    EAN_8(true),
    /** 11 digits, or 12 with the check digit. */
    UPC_A(true),
    /** The number system 0 or 1 and 6 digits, or 8 with the check digit. */
    UPC_E(true),
    /** An ISBN-13 in EAN-13, hyphenated: {@code 978-1-2345-6789-7}, or without the check digit. */
    ISBN_13(true),
    /** An ISMN in EAN-13, hyphenated: {@code 979-0-1234-5678-5}, or without the check digit. */
    ISMN(true);

    private final boolean linear;

    BarcodeType(boolean linear) {
        this.linear = linear;
    }

    /** True for a row of bars, false for a matrix of modules. */
    public boolean isLinear() {
        return linear;
    }
}
