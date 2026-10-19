/*
 * Copyright 2008 ZXing authors
 * Copyright 2026 Kamil Krzywański
 */
// SPDX-License-Identifier: Apache-2.0
package io.github.kamilkrzywanski.barleader.encode.datamatrix;

import java.nio.charset.Charset;

/**
 * An ECC 200 Data Matrix: the high-level encodation, the Reed-Solomon codewords, the module
 * placement, and the finder and timing patterns of each data region.
 *
 * <p>Ported from ZXing 3.5.4 {@code com.google.zxing.datamatrix.DataMatrixWriter} (Apache License 2.0,
 * the ZXing authors).
 */
public final class DataMatrixEncoder {
    private DataMatrixEncoder() {
    }

    /**
     * @param shape   square, rectangular or either, whichever is smallest
     * @param gs1     FNC1 in first position: the message is GS1 element strings separated by GS
     * @param compact the fewest codewords by ZXing's minimal encoder, which also chooses ECIs;
     *                {@code charset} is the one it prefers
     * @return the modules, {@code true} dark, row by row
     */
    public static boolean[][] encode(String contents, SymbolShapeHint shape, boolean gs1, boolean compact, Charset charset) {
        if (contents.isEmpty()) {
            throw new IllegalArgumentException("Found empty contents");
        }
        String encoded = compact
                ? MinimalEncoder.encodeHighLevel(contents, charset, gs1 ? 0x1D : -1, shape)
                : HighLevelEncoder.encodeHighLevel(contents, shape, null, null, false, gs1);
        SymbolInfo symbolInfo = SymbolInfo.lookup(encoded.length(), shape, null, null, true);
        String codewords = ErrorCorrection.encodeECC200(encoded, symbolInfo);
        DefaultPlacement placement = new DefaultPlacement(codewords, symbolInfo.getSymbolDataWidth(), symbolInfo.getSymbolDataHeight());
        placement.place();
        return encodeLowLevel(placement, symbolInfo);
    }

    /**
     * The data regions with a solid line left and below and an alternating one above and right.
     */
    private static boolean[][] encodeLowLevel(DefaultPlacement placement, SymbolInfo symbolInfo) {
        int symbolWidth = symbolInfo.getSymbolDataWidth();
        int symbolHeight = symbolInfo.getSymbolDataHeight();
        boolean[][] matrix = new boolean[symbolInfo.getSymbolHeight()][symbolInfo.getSymbolWidth()];
        int matrixY = 0;
        for (int y = 0; y < symbolHeight; y++) {
            int matrixX;
            if ((y % symbolInfo.matrixHeight) == 0) {
                matrixX = 0;
                for (int x = 0; x < symbolInfo.getSymbolWidth(); x++) {
                    matrix[matrixY][matrixX] = (x % 2) == 0;
                    matrixX++;
                }
                matrixY++;
            }
            matrixX = 0;
            for (int x = 0; x < symbolWidth; x++) {
                if ((x % symbolInfo.matrixWidth) == 0) {
                    matrix[matrixY][matrixX] = true;
                    matrixX++;
                }
                matrix[matrixY][matrixX] = placement.getBit(x, y);
                matrixX++;
                if ((x % symbolInfo.matrixWidth) == symbolInfo.matrixWidth - 1) {
                    matrix[matrixY][matrixX] = (y % 2) == 0;
                    matrixX++;
                }
            }
            matrixY++;
            if ((y % symbolInfo.matrixHeight) == symbolInfo.matrixHeight - 1) {
                matrixX = 0;
                for (int x = 0; x < symbolInfo.getSymbolWidth(); x++) {
                    matrix[matrixY][matrixX] = true;
                    matrixX++;
                }
                matrixY++;
            }
        }
        return matrix;
    }
}
