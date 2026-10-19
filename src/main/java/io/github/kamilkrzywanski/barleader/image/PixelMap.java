/*
 * Copyright 2026 Kamil Krzywański
 */
// SPDX-License-Identifier: Apache-2.0
package io.github.kamilkrzywanski.barleader.image;

import io.github.kamilkrzywanski.barleader.ImagePoint;

/**
 * Maps a pixel in a derived image back to the source image that was passed to the reader.
 */
@FunctionalInterface
public interface PixelMap {
    PixelMap IDENTITY = ImagePoint::new;

    ImagePoint toSource(double x, double y);

    default PixelMap before(PixelMap next) {
        return (x, y) -> {
            ImagePoint mid = toSource(x, y);
            return next.toSource(mid.x(), mid.y());
        };
    }
}
