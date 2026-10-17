/*
 * Copyright 2026 Kamil Krzywański
 */
// SPDX-License-Identifier: Apache-2.0
package io.github.kamilkrzywanski.barcode;

/**
 * A point in source-image pixels. The origin is the top-left corner and Y grows downward.
 */
public record ImagePoint(double x, double y) {
}
