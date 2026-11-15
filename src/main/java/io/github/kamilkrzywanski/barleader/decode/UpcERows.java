/*
 * Copyright 2026 Kamil Krzywański
 */
// SPDX-License-Identifier: Apache-2.0
package io.github.kamilkrzywanski.barleader.decode;

/** The row decoder as a second opinion on a UPC-E read from the widths of its 33 runs. */
public final class UpcERows {
    private UpcERows() {
    }

    /**
     * Whether the row decoder reads {@code text}, eight digits, from {@code widths}, the 33 runs
     * of the symbol from its first bar, between quiet zones of eight modules.
     */
    public static boolean reads(float[] widths, String text) {
        float sum = 0f;
        for (float width : widths) {
            sum += width;
        }
        float module = sum / 51f;
        int[] runs = new int[widths.length + 2];
        runs[0] = Math.round(8f * module * 16f);
        for (int i = 0; i < widths.length; i++) {
            runs[i + 1] = Math.max(1, Math.round(widths[i] * 16f));
        }
        runs[widths.length + 1] = runs[0];
        UpcEanDecoder.Span span = UpcEanDecoder.decodeSpan(runs, false, false);
        return span != null && span.kind() == UpcEanDecoder.Kind.UPC_E && span.text().equals(text);
    }
}
