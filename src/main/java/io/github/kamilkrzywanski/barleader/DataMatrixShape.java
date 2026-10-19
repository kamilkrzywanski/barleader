/*
 * Copyright 2026 Kamil Krzywański
 */
// SPDX-License-Identifier: Apache-2.0
package io.github.kamilkrzywanski.barleader;

/** The shape of a written Data Matrix. */
public enum DataMatrixShape {
    /** The smallest symbol that fits, square or rectangular. */
    ANY,
    SQUARE,
    RECTANGLE
}
