/*
 * Copyright 2012 ZXing authors
 * Copyright 2026 Kamil Krzywański
 */
// SPDX-License-Identifier: Apache-2.0
package io.github.kamilkrzywanski.barleader.encode.pdf417;

import java.nio.charset.Charset;

/**
 * A PDF417: the high-level encodation, the error correction codewords and the rows of codewords
 * between the start and stop patterns.
 *
 * <p>Ported from ZXing 3.5.4 {@code com.google.zxing.pdf417.PDF417Writer} (Apache License 2.0,
 * the ZXing authors).
 */
public final class Pdf417Encoder {
    private Pdf417Encoder() {
    }

    /**
     * @param errorCorrectionLevel 0 to 8, which is 2 to 512 error correction codewords
     * @param truncated            the right row indicator and the stop pattern left out
     * @param charset              the character set of byte compaction, announced with its ECI;
     *                             null for ISO-8859-1 without one
     * @param autoEci              text split among character sets for the fewest bytes, ECIs between
     * @return the modules, {@code true} dark, one row of the symbol each, top to bottom
     */
    public static boolean[][] encode(String contents, int errorCorrectionLevel, int minColumns, int maxColumns,
                                     int minRows, int maxRows, boolean truncated, Charset charset, boolean autoEci) {
        PDF417 encoder = new PDF417(truncated);
        encoder.setDimensions(maxColumns, minColumns, maxRows, minRows);
        if (charset != null) {
            encoder.setEncoding(charset);
        }
        encoder.generateBarcodeLogic(contents, errorCorrectionLevel, autoEci);
        // the matrix holds the bottom row first, as ZXing draws it upside down
        byte[][] rows = encoder.getBarcodeMatrix().getMatrix();
        boolean[][] modules = new boolean[rows.length][];
        for (int y = 0; y < rows.length; y++) {
            byte[] row = rows[rows.length - 1 - y];
            modules[y] = new boolean[row.length];
            for (int x = 0; x < row.length; x++) {
                modules[y][x] = row[x] == 1;
            }
        }
        return modules;
    }
}
