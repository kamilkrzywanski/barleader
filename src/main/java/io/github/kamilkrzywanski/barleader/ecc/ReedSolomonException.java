/*
 * Copyright 2026 Kamil Krzywański
 */
// SPDX-License-Identifier: Apache-2.0
package io.github.kamilkrzywanski.barleader.ecc;

/**
 * A Reed-Solomon codeword that cannot be corrected. Readers try many candidates and most fail, so
 * the exception carries no stack trace, as zxing-cpp's decoder returns a value instead of throwing.
 */
public final class ReedSolomonException extends Exception {
    public ReedSolomonException(String message) {
        super(message, null, false, false);
    }
}
