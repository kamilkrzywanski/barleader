package io.github.kamilkrzywanski.barcode;

import java.nio.charset.Charset;
import java.util.Objects;

/**
 * How {@link QrCode} encodes. Immutable; every setter returns a copy.
 *
 * <p>By default the level is {@link QrErrorCorrection#M}, the version the smallest that fits, the
 * mask the one with the lowest penalty, and the character set chosen from the text: ISO-8859-1,
 * which QR Code assumes without being told, when every character is in it, else UTF-8 announced
 * with an ECI.
 */
public final class QrOptions {
    private static final QrOptions DEFAULTS = new QrOptions(QrErrorCorrection.M, null, 0, -1, false, false);

    private final QrErrorCorrection errorCorrection;
    private final Charset charset;
    private final int version;
    private final int mask;
    private final boolean gs1;
    private final boolean compact;

    private QrOptions(QrErrorCorrection errorCorrection, Charset charset, int version, int mask, boolean gs1, boolean compact) {
        if (version < 0 || version > 40) {
            throw new IllegalArgumentException("version must be 1 to 40, or 0 for the smallest that fits");
        }
        if (mask < -1 || mask > 7) {
            throw new IllegalArgumentException("mask must be 0 to 7, or -1 for the lowest penalty");
        }
        this.errorCorrection = Objects.requireNonNull(errorCorrection, "errorCorrection");
        this.charset = charset;
        this.version = version;
        this.mask = mask;
        this.gs1 = gs1;
        this.compact = compact;
    }

    public static QrOptions defaults() {
        return DEFAULTS;
    }

    public QrOptions errorCorrection(QrErrorCorrection errorCorrection) {
        return new QrOptions(errorCorrection, charset, version, mask, gs1, compact);
    }

    /**
     * The character set of the text in byte mode, announced with an ECI where it has one. Null
     * chooses from the text. Text in numeric or alphanumeric mode needs none.
     */
    public QrOptions charset(Charset charset) {
        return new QrOptions(errorCorrection, charset, version, mask, gs1, compact);
    }

    /** A fixed version, 1 to 40; data that does not fit throws. 0 is the smallest that fits. */
    public QrOptions version(int version) {
        return new QrOptions(errorCorrection, charset, version, mask, gs1, compact);
    }

    /** A fixed mask, 0 to 7, or -1 for the one with the lowest penalty. */
    public QrOptions mask(int mask) {
        return new QrOptions(errorCorrection, charset, version, mask, gs1, compact);
    }

    /** Marks the data as GS1 element strings with FNC1 in first position; the GS character, U+001D, separates fields. */
    public QrOptions gs1(boolean gs1) {
        return new QrOptions(errorCorrection, charset, version, mask, gs1, compact);
    }

    /**
     * Splits the text into segments of different modes, and character sets where it needs several,
     * for the fewest bits, as ZXing's {@code QR_COMPACT}: {@code "ABC123abc"} is shorter as an
     * alphanumeric segment and a byte segment than as one byte segment. Off by default, since
     * every reader then has to handle mode switches and ECIs within the symbol.
     */
    public QrOptions compact(boolean compact) {
        return new QrOptions(errorCorrection, charset, version, mask, gs1, compact);
    }

    public QrErrorCorrection errorCorrection() {
        return errorCorrection;
    }

    /** The character set asked for, or null when it is chosen from the text. */
    public Charset charset() {
        return charset;
    }

    public int version() {
        return version;
    }

    public int mask() {
        return mask;
    }

    public boolean gs1() {
        return gs1;
    }

    public boolean compact() {
        return compact;
    }
}
