package io.github.kamilkrzywanski.barcode.ecc;

import java.util.Arrays;

/**
 * Reed-Solomon decoding with errors and erasures, as zxing-cpp 3.1.1 does it: the syndromes, an
 * erasure locator that takes the known positions out of them, the Sugiyama form of the extended
 * Euclidean algorithm for the error locator, Forney's formula, and a last check that the corrected
 * codeword has no syndrome left. A codeword with e erasures and t errors is corrected while
 * 2t + e does not exceed the error correction symbols, where ZXing treats every erasure as an
 * unknown error.
 *
 * <p>Ported from zxing-cpp 3.1.1 {@code core/src/librscpp} (Apache License 2.0, Copyright 2016 ZXing
 * authors, 2017-2026 Axel Waggershauser).
 */
public final class ReedSolomon {
    /** GF(256) of QR Code, x^8 + x^4 + x^3 + x^2 + 1, first root 0. */
    public static final Field QR_CODE = Field.gf2n(0x011D, 0);
    /** GF(256) of Data Matrix and 8-bit Aztec, x^8 + x^5 + x^3 + x^2 + 1, first root 1. */
    public static final Field DATA_MATRIX = Field.gf2n(0x012D, 1);
    public static final Field AZTEC_4 = Field.gf2n(0x0013, 1);
    public static final Field AZTEC_6 = Field.gf2n(0x0043, 1);
    public static final Field AZTEC_8 = DATA_MATRIX;
    public static final Field AZTEC_10 = Field.gf2n(0x0409, 1);
    public static final Field AZTEC_12 = Field.gf2n(0x1069, 1);
    public static final Field MAXICODE = AZTEC_6;
    /** GF(929) of PDF417, generator 3, first root 1. */
    public static final Field PDF417 = Field.gfp(929, 3, 1);

    private ReedSolomon() {
    }

    /** A finite field with exp and log tables. GF(2^n) adds with XOR; GF(p) modulo p. */
    public static final class Field {
        private final int size;
        private final int fcr;
        private final boolean prime;
        private final int[] exp;
        private final int[] log;

        private Field(int size, int fcr, boolean prime, int generator, int poly) {
            this.size = size;
            this.fcr = fcr;
            this.prime = prime;
            this.exp = new int[size * 2];
            this.log = new int[size];
            int x = 1;
            for (int i = 0; i < size; i++) {
                exp[i] = x;
                if (prime) {
                    x = (x * generator) % size;
                } else {
                    x *= 2;
                    if (x >= size) {
                        x ^= poly;
                    }
                }
            }
            for (int i = size - 1; i < size * 2; i++) {
                exp[i] = exp[i - (size - 1)];
            }
            for (int i = 0; i < size - 1; i++) {
                log[exp[i]] = i;
            }
        }

        static Field gf2n(int poly, int fcr) {
            return new Field(Integer.highestOneBit(poly), fcr, false, 2, poly);
        }

        static Field gfp(int prime, int generator, int fcr) {
            return new Field(prime, fcr, true, generator, 0);
        }

        /** The field for an Aztec word size of 4, 6, 8, 10 or 12 bits. */
        public static Field aztec(int wordSize) {
            return switch (wordSize) {
                case 4 -> AZTEC_4;
                case 6 -> AZTEC_6;
                case 8 -> AZTEC_8;
                case 10 -> AZTEC_10;
                case 12 -> AZTEC_12;
                default -> throw new IllegalArgumentException("Unsupported word size " + wordSize);
            };
        }

        public int size() {
            return size;
        }

        int exp(int a) {
            return exp[a];
        }

        int log(int a) {
            return log[a];
        }

        int inv(int a) {
            return exp[size - 1 - log[a]];
        }

        int mul(int a, int b) {
            return a == 0 || b == 0 ? 0 : exp[log[a] + log[b]];
        }

        int add(int a, int b) {
            if (!prime) {
                return a ^ b;
            }
            int sum = a + b;
            return sum < size ? sum : sum - size;
        }

        int sub(int a, int b) {
            if (!prime) {
                return a ^ b;
            }
            int difference = size + a - b;
            return difference < size ? difference : difference - size;
        }

        int neg(int a) {
            return sub(0, a);
        }
    }

    private static final int[] NO_ERASURES = new int[0];

    /** {@link #decode} without erasures that leaves {@code codeword} as it was when it fails. */
    public static int correct(Field field, int[] codeword, int numEcc) throws ReedSolomonException {
        return correct(field, codeword, numEcc, NO_ERASURES);
    }

    /**
     * {@link #decode} that leaves {@code codeword} as it was when it fails, as ZXing's decoder does.
     *
     * @throws ReedSolomonException when the codeword cannot be corrected
     */
    public static int correct(Field field, int[] codeword, int numEcc, int[] erasures) throws ReedSolomonException {
        int[] work = codeword.clone();
        int used = decode(field, work, numEcc, erasures);
        if (used < 0) {
            throw new ReedSolomonException("Reed-Solomon correction failed");
        }
        System.arraycopy(work, 0, codeword, 0, work.length);
        return used;
    }

    /**
     * Corrects {@code codeword} in place, data and error correction symbols together.
     *
     * @param erasures positions known to be wrong; null or empty for none
     * @return the error correction symbols the correction used, 2 an error and 1 an erasure, or -1
     *         when the codeword cannot be corrected
     * @throws IllegalArgumentException for more erasures than error correction symbols, or a position
     *                                  outside the codeword
     */
    public static int decode(Field field, int[] codeword, int numEcc, int[] erasures) {
        if (erasures == null) {
            erasures = NO_ERASURES;
        }
        int length = codeword.length;
        int numErasures = erasures.length;
        if (numErasures > numEcc) {
            throw new IllegalArgumentException("Too many erasures to correct");
        }
        for (int erasure : erasures) {
            if (erasure < 0 || erasure >= length) {
                throw new IllegalArgumentException("Erasure position out of range");
            }
        }
        if (field.prime) {
            // A value past the field would index past its tables.
            for (int i = 0; i < length; i++) {
                codeword[i] = Math.max(0, Math.min(codeword[i], field.size - 1));
            }
        }
        int[] syndromes = syndromes(field, codeword, numEcc);
        if (isZero(syndromes)) {
            return 0;
        }
        int[] original = null;
        int[] erasureLocator = null;
        if (numErasures > 0) {
            original = syndromes.clone();
            // The erasure locator is the product of (1 - Xi x), Xi = a^(length - 1 - position).
            erasureLocator = new int[]{1};
            for (int position : erasures) {
                erasureLocator = mul(field, erasureLocator, new int[]{field.neg(field.exp(length - 1 - position)), 1});
            }
            // S'(x) = erasure locator times S(x), its numEcc highest coefficients without the
            // numErasures highest: the syndromes of the errors alone.
            int[] product = mul(field, syndromes, erasureLocator);
            syndromes = Arrays.copyOfRange(product, numErasures, numEcc);
        }
        int[][] result = sugiyama(field, syndromes);
        if (result == null) {
            return -1;
        }
        int[] locator = result[0];
        int[] evaluator = result[1];
        int numErrors = locator.length - 1;
        if (2 * numErrors + numErasures > numEcc) {
            return -1;
        }
        if (numErasures > 0) {
            locator = mul(field, locator, erasureLocator);
            // The evaluator of the combined locator: its numEcc lowest coefficients times the syndromes.
            int[] product = mul(field, original, locator);
            evaluator = Arrays.copyOfRange(product, product.length - numEcc, product.length);
        }
        int[] locations = locations(field, locator);
        if (locations.length != numErrors + numErasures) {
            return -1;
        }
        int[] magnitudes = magnitudes(field, evaluator, locations);
        for (int i = 0; i < locations.length; i++) {
            int position = length - 1 - field.log(locations[i]);
            if (position < 0) {
                return -1;
            }
            codeword[position] = field.sub(codeword[position], magnitudes[i]);
        }
        // A wrong correction can still leave a codeword with syndromes: check again.
        if (!isZero(syndromes(field, codeword, numEcc))) {
            return -1;
        }
        return 2 * numErrors + numErasures;
    }

    /** The syndromes, highest power first: the codeword evaluated at a^(numEcc - 1 - i + fcr). */
    private static int[] syndromes(Field field, int[] codeword, int numEcc) {
        int[] roots = new int[numEcc];
        for (int i = 0; i < numEcc; i++) {
            roots[i] = field.exp(numEcc - 1 - i + field.fcr);
        }
        int[] result = new int[numEcc];
        for (int coefficient : codeword) {
            for (int i = 0; i < numEcc; i++) {
                result[i] = field.add(field.mul(roots[i], result[i]), coefficient);
            }
        }
        return result;
    }

    /**
     * The error locator and evaluator from the syndromes, by the extended Euclidean algorithm on
     * x^R and S(x) until the remainder has degree below R / 2. Null when the locator has no constant.
     */
    private static int[][] sugiyama(Field field, int[] syndromes) {
        int degreeLimit = syndromes.length;
        int[] r = normalize(syndromes);
        int[] rLast = new int[degreeLimit + 1];
        rLast[0] = 1;
        int[] tLast = new int[0];
        int[] t = new int[]{1};
        while (r.length - 1 >= degreeLimit / 2) {
            int[] swap = tLast;
            tLast = t;
            t = swap;
            swap = rLast;
            rLast = r;
            r = swap;
            int[][] division = divide(field, r, rLast);
            int[] q = division[0];
            r = division[1];
            q = mul(field, q, tLast);
            t = sub(field, t, q);
        }
        if (t.length == 0 || t[t.length - 1] == 0) {
            return null;
        }
        int inverse = field.inv(t[t.length - 1]);
        return new int[][]{scale(field, t, inverse), scale(field, r, inverse)};
    }

    /** The roots of the locator by trying every non-zero element, as their inverses. */
    private static int[] locations(Field field, int[] locator) {
        int wanted = locator.length - 1;
        int[] found = new int[Math.max(wanted, 0)];
        int count = 0;
        for (int i = 1; i < field.size && count < wanted; i++) {
            if (evaluate(field, locator, i) == 0) {
                found[count++] = field.inv(i);
            }
        }
        return Arrays.copyOf(found, count);
    }

    /** Forney's formula. */
    private static int[] magnitudes(Field field, int[] evaluator, int[] locations) {
        int numErrors = locations.length;
        int[] result = new int[numErrors];
        for (int i = 0; i < numErrors; i++) {
            int xiInverse = field.inv(locations[i]);
            int denominator = 1;
            for (int j = 0; j < numErrors; j++) {
                if (i != j) {
                    denominator = field.mul(denominator, field.sub(1, field.mul(locations[j], xiInverse)));
                }
            }
            result[i] = field.mul(evaluate(field, evaluator, xiInverse), field.inv(denominator));
            if (field.fcr != 0) {
                result[i] = field.mul(result[i], xiInverse);
            }
        }
        return result;
    }

    // Polynomials are coefficient arrays, highest power first; the empty array is zero.

    private static boolean isZero(int[] values) {
        for (int value : values) {
            if (value != 0) {
                return false;
            }
        }
        return true;
    }

    private static int[] normalize(int[] poly) {
        int first = 0;
        while (first < poly.length && poly[first] == 0) {
            first++;
        }
        return first == 0 ? poly : Arrays.copyOfRange(poly, first, poly.length);
    }

    private static int evaluate(Field field, int[] poly, int a) {
        int result = 0;
        for (int coefficient : poly) {
            result = field.add(field.mul(a, result), coefficient);
        }
        return result;
    }

    private static int[] mul(Field field, int[] left, int[] right) {
        if (left.length == 0 || right.length == 0) {
            return new int[0];
        }
        int[] product = new int[left.length + right.length - 1];
        for (int i = 0; i < left.length; i++) {
            for (int j = 0; j < right.length; j++) {
                product[i + j] = field.add(product[i + j], field.mul(left[i], right[j]));
            }
        }
        return product;
    }

    private static int[] sub(Field field, int[] left, int[] right) {
        int size = Math.max(left.length, right.length);
        int[] result = new int[size];
        for (int degree = 0; degree < size; degree++) {
            int a = degree < left.length ? left[left.length - 1 - degree] : 0;
            int b = degree < right.length ? right[right.length - 1 - degree] : 0;
            result[size - 1 - degree] = field.sub(a, b);
        }
        return normalize(result);
    }

    private static int[] scale(Field field, int[] poly, int factor) {
        int[] result = new int[poly.length];
        for (int i = 0; i < poly.length; i++) {
            result[i] = field.mul(poly[i], factor);
        }
        return result;
    }

    /** Synthetic division: the quotient and the normalized remainder. */
    private static int[][] divide(Field field, int[] dividend, int[] divisor) {
        int[] work = dividend.clone();
        int quotientSize = dividend.length < divisor.length ? 0 : dividend.length - divisor.length + 1;
        int[] quotient = new int[quotientSize];
        int normalizer = field.inv(divisor[0]);
        for (int i = 0; i < quotientSize; i++) {
            int coefficient = work[i];
            if (coefficient != 0) {
                coefficient = field.mul(coefficient, normalizer);
                work[i] = coefficient;
                for (int j = 1; j < divisor.length; j++) {
                    work[i + j] = field.sub(work[i + j], field.mul(divisor[j], coefficient));
                }
            }
            quotient[i] = coefficient;
        }
        int[] remainder = Arrays.copyOfRange(work, quotientSize, work.length);
        return new int[][]{quotient, normalize(remainder)};
    }
}
