/*
 * Copyright 2026 Kamil Krzywański
 */
// SPDX-License-Identifier: Apache-2.0
/**
 * Barcode reader in pure Java. {@link io.github.kamilkrzywanski.barleader.BarcodeReader} is the entry point.
 * Only {@code io.github.kamilkrzywanski.barleader} is exported. The other packages are internal and may
 * change in any release.
 */
module io.github.kamilkrzywanski.barleader {
    requires transitive java.desktop;

    exports io.github.kamilkrzywanski.barleader;
}
