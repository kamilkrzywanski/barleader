/*
 * Copyright 2026 Kamil Krzywański
 */
// SPDX-License-Identifier: Apache-2.0
/**
 * Barcode reader in pure Java. {@link io.github.kamilkrzywanski.barcode.BarcodeReader} is the entry point.
 * Only {@code io.github.kamilkrzywanski.barcode} is exported. The other packages are internal and may
 * change in any release.
 */
module io.github.kamilkrzywanski.barcode {
    requires transitive java.desktop;

    exports io.github.kamilkrzywanski.barcode;
}
