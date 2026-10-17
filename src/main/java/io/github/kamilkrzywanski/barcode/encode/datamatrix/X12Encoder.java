/*
 * Copyright 2006-2007 Jeremias Maerki.
 * Copyright 2026 Kamil Krzywański
 */
// SPDX-License-Identifier: Apache-2.0
package io.github.kamilkrzywanski.barcode.encode.datamatrix;

/**
 * Ported from ZXing 3.5.4 {@code com.google.zxing.datamatrix.encoder.X12Encoder} (Apache License 2.0, the ZXing authors).
 */
final class X12Encoder extends C40Encoder {

    @Override
    public int getEncodingMode() {
        return HighLevelEncoder.X12_ENCODATION;
    }

    @Override
    public void encode(EncoderContext context) {
        //step C
        StringBuilder buffer = new StringBuilder();
        while (context.hasMoreCharacters()) {
            char c = context.getCurrentChar();
            context.pos++;

            encodeChar(c, buffer);

            int count = buffer.length();
            if ((count % 3) == 0) {
                writeNextTriplet(context, buffer);

                int newMode = HighLevelEncoder.lookAheadTest(context.getMessage(), context.pos, getEncodingMode());
                if (newMode != getEncodingMode()) {
                    // Return to ASCII encodation, which will actually handle latch to new mode
                    context.signalEncoderChange(HighLevelEncoder.ASCII_ENCODATION);
                    break;
                }
            }
        }
        handleEOD(context, buffer);
    }

    @Override
    int encodeChar(char c, StringBuilder sb) {
        switch (c) {
            case '\r':
                sb.append('\0');
                break;
            case '*':
                sb.append('\1');
                break;
            case '>':
                sb.append('\2');
                break;
            case ' ':
                sb.append('\3');
                break;
            default:
                if (c >= '0' && c <= '9') {
                    sb.append((char) (c - 48 + 4));
                } else if (c >= 'A' && c <= 'Z') {
                    sb.append((char) (c - 65 + 14));
                } else {
                    HighLevelEncoder.illegalCharacter(c);
                }
                break;
        }
        return 1;
    }

    @Override
    void handleEOD(EncoderContext context, StringBuilder buffer) {
        context.updateSymbolInfo();
        int available = context.getSymbolInfo().getDataCapacity() - context.getCodewordCount();
        int count = buffer.length();
        context.pos -= count;
        if (context.getRemainingCharacters() > 1 || available > 1 ||
                context.getRemainingCharacters() != available) {
            context.writeCodeword(HighLevelEncoder.X12_UNLATCH);
        }
        if (context.getNewEncoding() < 0) {
            context.signalEncoderChange(HighLevelEncoder.ASCII_ENCODATION);
        }
    }
}
