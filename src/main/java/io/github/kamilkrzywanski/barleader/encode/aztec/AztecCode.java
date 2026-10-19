/*
 * Copyright 2013 ZXing authors
 * Copyright 2026 Kamil Krzywański
 */
// SPDX-License-Identifier: Apache-2.0
package io.github.kamilkrzywanski.barleader.encode.aztec;


/**
 * Aztec 2D code representation
 *
 * <p>Ported from ZXing 3.5.4 {@code com.google.zxing.aztec.encoder.AztecCode} (Apache License 2.0, the ZXing authors).
 *
 * @author Rustam Abdullaev
 */
public final class AztecCode {

    private boolean compact;
    private int size;
    private int layers;
    private int codeWords;
    private boolean[][] matrix;

    /**
     * @return {@code true} if compact instead of full mode
     */
    public boolean isCompact() {
        return compact;
    }

    public void setCompact(boolean compact) {
        this.compact = compact;
    }

    /**
     * @return size in pixels (width and height)
     */
    public int getSize() {
        return size;
    }

    public void setSize(int size) {
        this.size = size;
    }

    /**
     * @return number of levels
     */
    public int getLayers() {
        return layers;
    }

    public void setLayers(int layers) {
        this.layers = layers;
    }

    /**
     * @return number of data codewords
     */
    public int getCodeWords() {
        return codeWords;
    }

    public void setCodeWords(int codeWords) {
        this.codeWords = codeWords;
    }

    /**
     * @return the symbol image
     */
    public boolean[][] getMatrix() {
        return matrix;
    }

    public void setMatrix(boolean[][] matrix) {
        this.matrix = matrix;
    }

}
