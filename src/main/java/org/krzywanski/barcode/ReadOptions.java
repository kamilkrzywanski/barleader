package org.krzywanski.barcode;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable options for one read. {@link #fast()} matches the default ladder.
 * {@link #aggressive()} turns on the extra variants and raises the budgets.
 * {@link #mode(DecodeMode)} changes only the ladder; it does not rewrite budgets
 * already set on this instance.
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

    private ReadOptions(
            DecodeMode mode,
            boolean findAll,
            Collection<Symbology> symbologies,
            int maxAttempts,
            long timeBudgetMillis
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
    }

    public static ReadOptions fast() {
        return new ReadOptions(DecodeMode.FAST, false, Symbology.defaults(), FAST_ATTEMPTS, FAST_BUDGET_MILLIS);
    }

    public static ReadOptions aggressive() {
        return new ReadOptions(
                DecodeMode.AGGRESSIVE,
                false,
                Symbology.defaults(),
                AGGRESSIVE_ATTEMPTS,
                AGGRESSIVE_BUDGET_MILLIS
        );
    }

    public ReadOptions mode(DecodeMode mode) {
        return new ReadOptions(mode, findAll, symbologies, maxAttempts, timeBudgetMillis);
    }

    public ReadOptions findAll(boolean findAll) {
        return new ReadOptions(mode, findAll, symbologies, maxAttempts, timeBudgetMillis);
    }

    public ReadOptions maxAttempts(int maxAttempts) {
        return new ReadOptions(mode, findAll, symbologies, maxAttempts, timeBudgetMillis);
    }

    public ReadOptions timeBudgetMillis(long timeBudgetMillis) {
        return new ReadOptions(mode, findAll, symbologies, maxAttempts, timeBudgetMillis);
    }

    public ReadOptions symbologies(Symbology... symbologies) {
        if (symbologies == null || symbologies.length == 0) {
            throw new IllegalArgumentException("symbologies must not be empty");
        }
        EnumSet<Symbology> set = EnumSet.noneOf(Symbology.class);
        Collections.addAll(set, symbologies);
        return new ReadOptions(mode, findAll, set, maxAttempts, timeBudgetMillis);
    }

    public ReadOptions symbologies(Collection<Symbology> symbologies) {
        return new ReadOptions(mode, findAll, symbologies, maxAttempts, timeBudgetMillis);
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
}
