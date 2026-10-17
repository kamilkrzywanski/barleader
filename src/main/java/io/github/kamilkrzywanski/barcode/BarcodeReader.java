/*
 * Copyright 2026 Kamil Krzywański
 */
// SPDX-License-Identifier: Apache-2.0
package io.github.kamilkrzywanski.barcode;

import io.github.kamilkrzywanski.barcode.image.GrayU8;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Reads barcodes from images. One instance can be shared by any number of threads; each
 * {@link #read} call keeps its own state.
 *
 * <p>A read tries a ladder of image variants until one decodes (see {@link ReadOptions}). Many of
 * those variants do not depend on each other. {@code new BarcodeReader()} runs them in parallel
 * on a pool shared by every reader, {@link #BarcodeReader(Executor)} on an executor of your own,
 * and {@link #sequential()} one after another on the calling thread. All three return the same
 * hits when the read is not cut short by its time budget.
 */
public final class BarcodeReader {
    private final Fanout fanout;

    /**
     * Runs independent variants of a read in parallel, on a pool of daemon threads shared by all
     * readers, one thread fewer than the available processors.
     */
    public BarcodeReader() {
        this(Fanout.shared());
    }

    /**
     * Runs independent variants of a read on {@code executor}. The calling thread also runs every
     * variant the executor has not started yet, so a busy executor, or a read called from one of
     * the executor's own threads, does not deadlock. The executor is not shut down by this class.
     */
    public BarcodeReader(Executor executor) {
        this(Fanout.on(Objects.requireNonNull(executor, "executor")));
    }

    private BarcodeReader(Fanout fanout) {
        this.fanout = fanout;
    }

    /**
     * A reader that does all the work on the calling thread and stops at the first hit. It uses
     * the least CPU per image, which suits an application that already reads many images in
     * parallel. A single image takes longer than with the parallel reader.
     */
    public static BarcodeReader sequential() {
        return new BarcodeReader(Fanout.sequential());
    }

    /** {@link #read(BufferedImage, ReadOptions)} with {@link ReadOptions#fast()}. */
    public List<BarcodeHit> read(BufferedImage image) {
        return read(image, ReadOptions.fast());
    }

    /**
     * Every barcode found, in the order the ladder found them. Without
     * {@link ReadOptions#findAll(boolean) findAll} that is at most one. An image without a
     * barcode gives an empty list.
     */
    public List<BarcodeHit> read(BufferedImage image, ReadOptions options) {
        Objects.requireNonNull(image, "image");
        Objects.requireNonNull(options, "options");
        return new Scan(options, fanout).run(image);
    }

    /**
     * {@link #read(byte[], int, int, int, int, ReadOptions)} for rows that follow each other
     * without padding.
     */
    public List<BarcodeHit> read(byte[] luminance, int width, int height, ReadOptions options) {
        return read(luminance, 0, width, width, height, options);
    }

    /**
     * Reads 8-bit luminance, 0 black to 255 white, such as the Y plane of a camera frame or a
     * grayscale buffer that never was a {@link BufferedImage}. The pixels are copied, so the
     * array can be reused as soon as this returns.
     *
     * @param offset    index of the top-left pixel
     * @param rowStride bytes from the start of one row to the start of the next, at least {@code width}
     */
    public List<BarcodeHit> read(byte[] luminance, int offset, int rowStride, int width, int height, ReadOptions options) {
        Objects.requireNonNull(luminance, "luminance");
        Objects.requireNonNull(options, "options");
        if (width < 1 || height < 1) {
            throw new IllegalArgumentException("width and height must be at least 1");
        }
        if (offset < 0 || rowStride < width
                || offset + (long) (height - 1) * rowStride + width > luminance.length) {
            throw new IllegalArgumentException("offset, rowStride, width and height do not fit the array");
        }
        GrayU8 gray = new GrayU8(width, height);
        for (int y = 0; y < height; y++) {
            System.arraycopy(luminance, offset + y * rowStride, gray.data, y * width, width);
        }
        return new Scan(options, fanout).run(gray, gray);
    }
}
