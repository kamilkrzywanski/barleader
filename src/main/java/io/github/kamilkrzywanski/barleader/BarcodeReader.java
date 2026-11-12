/*
 * Copyright 2026 Kamil Krzywański
 */
// SPDX-License-Identifier: Apache-2.0
package io.github.kamilkrzywanski.barleader;

import io.github.kamilkrzywanski.barleader.image.GrayU8;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Reads barcodes from images. One instance can be shared by any number of threads; each
 * {@link #read} call keeps its own state.
 *
 * <p>A read tries a ladder of image variants until one decodes (see {@link ReadOptions}). Many of
 * those variants do not depend on each other. {@code new BarcodeReader()} reads them one after
 * another on the calling thread, which uses the least CPU; {@link #parallel()} runs them side by
 * side on a small pool shared by every reader, which returns a single image sooner; and
 * {@link #BarcodeReader(Executor)} on an executor of your own. All three return the same hits when
 * the read is not cut short by its time budget.
 */
public final class BarcodeReader {
    private final Fanout fanout;

    /**
     * Does all the work on the calling thread, one variant after another, and stops at the first
     * hit. It uses the least CPU per image, which suits a server that reads one image per request
     * or an application that reads many images at once; {@link #parallel()} returns a single image
     * sooner.
     */
    public BarcodeReader() {
        this(Fanout.sequential());
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
     * A reader that does all the work on the calling thread, the same as {@code new BarcodeReader()}.
     */
    public static BarcodeReader sequential() {
        return new BarcodeReader(Fanout.sequential());
    }

    /**
     * A reader that runs the independent variants of a read side by side, on the calling thread
     * and a pool of daemon threads shared by every such reader, at most three. On the Arte-Lab
     * photos it returned in about 60% of the time of {@code new BarcodeReader()}, for about one and
     * a half times its CPU time; more threads saved little more time for much more CPU.
     */
    public static BarcodeReader parallel() {
        return new BarcodeReader(Fanout.shared());
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
