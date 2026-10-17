/*
 * Copyright 2006-2007 Jeremias Maerki.
 * Copyright 2026 Kamil Krzywański
 */
// SPDX-License-Identifier: Apache-2.0
package io.github.kamilkrzywanski.barcode.encode.datamatrix;

/**
 * Ported from ZXing 3.5.4 {@code com.google.zxing.datamatrix.encoder.ASCIIEncoder} (Apache License 2.0, the ZXing authors).
 */
final class ASCIIEncoder implements Encoder {

    @Override
    public int getEncodingMode() {
        return HighLevelEncoder.ASCII_ENCODATION;
    }

    @Override
    public void encode(EncoderContext context) {
        //step B
        int n = HighLevelEncoder.determineConsecutiveDigitCount(context.getMessage(), context.pos);
        if (n >= 2) {
            context.writeCodeword(encodeASCIIDigits(context.getMessage().charAt(context.pos),
                    context.getMessage().charAt(context.pos + 1)));
            context.pos += 2;
        } else {
            char c = context.getCurrentChar();
            int newMode = HighLevelEncoder.lookAheadTest(context.getMessage(), context.pos, getEncodingMode());
            if (newMode != getEncodingMode()) {
                switch (newMode) {
                    case HighLevelEncoder.BASE256_ENCODATION:
                        context.writeCodeword(HighLevelEncoder.LATCH_TO_BASE256);
                        context.signalEncoderChange(HighLevelEncoder.BASE256_ENCODATION);
                        return;
                    case HighLevelEncoder.C40_ENCODATION:
                        context.writeCodeword(HighLevelEncoder.LATCH_TO_C40);
                        context.signalEncoderChange(HighLevelEncoder.C40_ENCODATION);
                        return;
                    case HighLevelEncoder.X12_ENCODATION:
                        context.writeCodeword(HighLevelEncoder.LATCH_TO_ANSIX12);
                        context.signalEncoderChange(HighLevelEncoder.X12_ENCODATION);
                        break;
                    case HighLevelEncoder.TEXT_ENCODATION:
                        context.writeCodeword(HighLevelEncoder.LATCH_TO_TEXT);
                        context.signalEncoderChange(HighLevelEncoder.TEXT_ENCODATION);
                        break;
                    case HighLevelEncoder.EDIFACT_ENCODATION:
                        context.writeCodeword(HighLevelEncoder.LATCH_TO_EDIFACT);
                        context.signalEncoderChange(HighLevelEncoder.EDIFACT_ENCODATION);
                        break;
                    default:
                        throw new IllegalStateException("Illegal mode: " + newMode);
                }
            } else if (HighLevelEncoder.isExtendedASCII(c)) {
                context.writeCodeword(HighLevelEncoder.UPPER_SHIFT);
                context.writeCodeword((char) (c - 128 + 1));
                context.pos++;
            } else {
                context.writeCodeword((char) (c + 1));
                context.pos++;
            }

        }
    }

    private static char encodeASCIIDigits(char digit1, char digit2) {
        if (HighLevelEncoder.isDigit(digit1) && HighLevelEncoder.isDigit(digit2)) {
            int num = (digit1 - 48) * 10 + (digit2 - 48);
            return (char) (num + 130);
        }
        throw new IllegalArgumentException("not digits: " + digit1 + digit2);
    }

}
