package io.github.kamilkrzywanski.barcode;

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
}
