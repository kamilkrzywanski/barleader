/*
 * Copyright 2006-2007 Jeremias Maerki.
 * Copyright 2026 Kamil Krzywański
 */
// SPDX-License-Identifier: Apache-2.0
package io.github.kamilkrzywanski.barleader.encode.datamatrix;


import java.nio.charset.StandardCharsets;

/**
 * Ported from ZXing 3.5.4 {@code com.google.zxing.datamatrix.encoder.EncoderContext} (Apache License 2.0, the ZXing authors).
 */
final class EncoderContext {

    private final String msg;
    private SymbolShapeHint shape;
    private Dimension minSize;
    private Dimension maxSize;
    private final StringBuilder codewords;
    int pos;
    private int newEncoding;
    private SymbolInfo symbolInfo;
    private int skipAtEnd;

    EncoderContext(String msg) {
        //From this point on Strings are not Unicode anymore!
        byte[] msgBinary = msg.getBytes(StandardCharsets.ISO_8859_1);
        StringBuilder sb = new StringBuilder(msgBinary.length);
        for (int i = 0, c = msgBinary.length; i < c; i++) {
            char ch = (char) (msgBinary[i] & 0xff);
            if (ch == '?' && msg.charAt(i) != '?') {
                throw new IllegalArgumentException("Message contains characters outside ISO-8859-1 encoding.");
            }
            sb.append(ch);
        }
        this.msg = sb.toString(); //Not Unicode here!
        shape = SymbolShapeHint.FORCE_NONE;
        this.codewords = new StringBuilder(msg.length());
        newEncoding = -1;
    }

    public void setSymbolShape(SymbolShapeHint shape) {
        this.shape = shape;
    }

    public void setSizeConstraints(Dimension minSize, Dimension maxSize) {
        this.minSize = minSize;
        this.maxSize = maxSize;
    }

    public String getMessage() {
        return this.msg;
    }

    public void setSkipAtEnd(int count) {
        this.skipAtEnd = count;
    }

    public char getCurrentChar() {
        return msg.charAt(pos);
    }

    public char getCurrent() {
        return msg.charAt(pos);
    }

    public StringBuilder getCodewords() {
        return codewords;
    }

    public void writeCodewords(String codewords) {
        this.codewords.append(codewords);
    }

    public void writeCodeword(char codeword) {
        this.codewords.append(codeword);
    }

    public int getCodewordCount() {
        return this.codewords.length();
    }

    public int getNewEncoding() {
        return newEncoding;
    }

    public void signalEncoderChange(int encoding) {
        this.newEncoding = encoding;
    }

    public void resetEncoderSignal() {
        this.newEncoding = -1;
    }

    public boolean hasMoreCharacters() {
        return pos < getTotalMessageCharCount();
    }

    private int getTotalMessageCharCount() {
        return msg.length() - skipAtEnd;
    }

    public int getRemainingCharacters() {
        return getTotalMessageCharCount() - pos;
    }

    public SymbolInfo getSymbolInfo() {
        return symbolInfo;
    }

    public void updateSymbolInfo() {
        updateSymbolInfo(getCodewordCount());
    }

    public void updateSymbolInfo(int len) {
        if (this.symbolInfo == null || len > this.symbolInfo.getDataCapacity()) {
            this.symbolInfo = SymbolInfo.lookup(len, shape, minSize, maxSize, true);
        }
    }

    public void resetSymbolInfo() {
        this.symbolInfo = null;
    }
}
