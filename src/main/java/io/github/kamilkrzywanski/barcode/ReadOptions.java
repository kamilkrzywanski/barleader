/*
 * Copyright 2026 Kamil Krzywański
 */
// SPDX-License-Identifier: Apache-2.0
package io.github.kamilkrzywanski.barcode;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable options for one read. {@link #fast()} is the default ladder with a 2.5 s, 40-attempt
 * budget. {@link #aggressive()} adds the expensive variants and raises the budget to 8 s and 100
 * attempts. {@link #mode(DecodeMode)} changes only the ladder; it does not rewrite budgets already
 * set on this instance.
 *
 * <p>The time budget makes the result depend on how fast the machine is and how busy it is: a read
 * that runs out of time returns what it found so far. Pass {@code Long.MAX_VALUE} to
 * {@link #timeBudgetMillis(long)} for a read that only the attempt budget stops, which returns the
 * same hits on every machine.
 */
public final class ReadOptions {
    public static final int FAST_ATTEMPTS = 40;
    public static final int AGGRESSIVE_ATTEMPTS = 100;
    public static final long FAST_BUDGET_MILLIS = 2_500L;
    public static final long AGGRESSIVE_BUDGET_MILLIS = 8_000L;

    private final DecodeMode mode;
    private final boolean findAll;
    private final Set<Symbology> symbologies;
    private final int maxAttempts;
    private final long timeBudgetMillis;
    private final EanAddOn eanAddOn;

    private ReadOptions(
            DecodeMode mode,
            boolean findAll,
            Collection<Symbology> symbologies,
            int maxAttempts,
            long timeBudgetMillis,
            EanAddOn eanAddOn
    ) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
        if (timeBudgetMillis < 1L) {
            throw new IllegalArgumentException("timeBudgetMillis must be >= 1");
        }
        if (symbologies == null || symbologies.isEmpty()) {
            throw new IllegalArgumentException("symbologies must not be empty");
        }
        this.mode = Objects.requireNonNull(mode, "mode");
        this.findAll = findAll;
        this.symbologies = EnumSet.copyOf(symbologies);
        this.maxAttempts = maxAttempts;
        this.timeBudgetMillis = timeBudgetMillis;
        this.eanAddOn = Objects.requireNonNull(eanAddOn, "eanAddOn");
    }

    public static ReadOptions fast() {
        return new ReadOptions(
                DecodeMode.FAST,
                false,
                Symbology.defaults(),
                FAST_ATTEMPTS,
                FAST_BUDGET_MILLIS,
                EanAddOn.IGNORE
        );
    }

    public static ReadOptions aggressive() {
        return new ReadOptions(
                DecodeMode.AGGRESSIVE,
                false,
                Symbology.defaults(),
                AGGRESSIVE_ATTEMPTS,
                AGGRESSIVE_BUDGET_MILLIS,
                EanAddOn.IGNORE
        );
    }

    public ReadOptions mode(DecodeMode mode) {
        return new ReadOptions(mode, findAll, symbologies, maxAttempts, timeBudgetMillis, eanAddOn);
    }

    /**
     * With {@code true} the ladder does not stop at the first hit and returns every distinct symbol
     * it finds. This costs the whole ladder on every image.
     */
    public ReadOptions findAll(boolean findAll) {
        return new ReadOptions(mode, findAll, symbologies, maxAttempts, timeBudgetMillis, eanAddOn);
    }

    /** The most image variants one read decodes, at least 1. */
    public ReadOptions maxAttempts(int maxAttempts) {
        return new ReadOptions(mode, findAll, symbologies, maxAttempts, timeBudgetMillis, eanAddOn);
    }

    /**
     * Wall-clock budget of one read, at least 1 ms. A single decoder call is not interrupted, so
     * a read can end somewhat later. {@code Long.MAX_VALUE} means no time limit.
     */
    public ReadOptions timeBudgetMillis(long timeBudgetMillis) {
        return new ReadOptions(mode, findAll, symbologies, maxAttempts, timeBudgetMillis, eanAddOn);
    }

    /**
     * The symbologies to look for, replacing {@link Symbology#defaults()}. Fewer symbologies make a
     * read faster: without any 2D symbology the matrix detectors do not run at all.
     */
    public ReadOptions symbologies(Symbology... symbologies) {
        if (symbologies == null || symbologies.length == 0) {
            throw new IllegalArgumentException("symbologies must not be empty");
        }
        EnumSet<Symbology> set = EnumSet.noneOf(Symbology.class);
        Collections.addAll(set, symbologies);
        return new ReadOptions(mode, findAll, set, maxAttempts, timeBudgetMillis, eanAddOn);
    }

    public ReadOptions symbologies(Collection<Symbology> symbologies) {
        return new ReadOptions(mode, findAll, symbologies, maxAttempts, timeBudgetMillis, eanAddOn);
    }

    /** What to do with an EAN/UPC supplement. {@link EanAddOn#IGNORE} unless set. */
    public ReadOptions eanAddOn(EanAddOn eanAddOn) {
        return new ReadOptions(mode, findAll, symbologies, maxAttempts, timeBudgetMillis, eanAddOn);
    }

    public DecodeMode mode() {
        return mode;
    }

    public boolean findAll() {
        return findAll;
    }

    public Set<Symbology> symbologies() {
        return EnumSet.copyOf(symbologies);
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    public long timeBudgetMillis() {
        return timeBudgetMillis;
    }

    public EanAddOn eanAddOn() {
        return eanAddOn;
    }
}
