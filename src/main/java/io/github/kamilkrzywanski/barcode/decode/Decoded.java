/*
 * Copyright 2026 Kamil Krzywański
 */
// SPDX-License-Identifier: Apache-2.0
package io.github.kamilkrzywanski.barcode.decode;

/**
 * A decoded payload: its text and the bytes the symbol stores, before any character set is
 * applied and without ECI designators, as zxing-cpp's {@code Content::bytes}.
 */
public record Decoded(String text, byte[] bytes) {
}
