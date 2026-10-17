/*
 * Copyright 2011 ZXing authors
 * Copyright 2026 Kamil Krzywański
 */
// SPDX-License-Identifier: Apache-2.0
package io.github.kamilkrzywanski.barcode.encode.pdf417;

/**
 * Represents possible PDF417 barcode compaction types.
 *
 * <p>Ported from ZXing 3.5.4 {@code com.google.zxing.pdf417.encoder.Compaction} (Apache License 2.0, the ZXing authors).
 */
public enum Compaction {

    AUTO,
    TEXT,
    BYTE,
    NUMERIC

}
