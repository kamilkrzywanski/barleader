package io.github.kamilkrzywanski.barcode.ecc;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReedSolomonTest {
    /** A systematic codeword: the data, then the negated remainder of data times x^numEcc by the generator. */
    private static int[] encode(ReedSolomon.Field field, int[] data, int numEcc) {
        int[] generator = {1};
        for (int i = 0; i < numEcc; i++) {
            int root = field.exp(i + fcr(field));
            int[] next = new int[generator.length + 1];
            for (int j = 0; j < generator.length; j++) {
                next[j] = field.add(next[j], generator[j]);
                next[j + 1] = field.sub(next[j + 1], field.mul(generator[j], root));
            }
            generator = next;
        }
        int[] work = Arrays.copyOf(data, data.length + numEcc);
        for (int i = 0; i < data.length; i++) {
            int coefficient = work[i];
            if (coefficient != 0) {
                for (int j = 1; j < generator.length; j++) {
                    work[i + j] = field.sub(work[i + j], field.mul(generator[j], coefficient));
                }
            }
        }
        int[] codeword = Arrays.copyOf(data, data.length + numEcc);
        for (int i = data.length; i < codeword.length; i++) {
            codeword[i] = field.neg(work[i]);
        }
        return codeword;
    }

    private static int fcr(ReedSolomon.Field field) {
        return field == ReedSolomon.QR_CODE ? 0 : 1;
    }

    private static int[] random(Random random, ReedSolomon.Field field, int length) {
        int[] data = new int[length];
        for (int i = 0; i < length; i++) {
            data[i] = random.nextInt(field.size());
        }
        return data;
    }

    @Test
    void correctsErrorsAndErasuresUpToTheBound() {
        Random random = new Random(929);
        for (ReedSolomon.Field field : new ReedSolomon.Field[]{ReedSolomon.QR_CODE, ReedSolomon.DATA_MATRIX, ReedSolomon.PDF417,
                ReedSolomon.AZTEC_6, ReedSolomon.AZTEC_10}) {
            int numEcc = 16;
            int[] clean = encode(field, random(random, field, 30), numEcc);
            assertEquals(0, ReedSolomon.decode(field, clean.clone(), numEcc, new int[0]));
            for (int errors = 0; errors <= numEcc / 2; errors++) {
                for (int erasures = 0; 2 * errors + erasures <= numEcc; erasures += 3) {
                    int[] damaged = clean.clone();
                    int[] positions = random.ints(0, clean.length).distinct().limit(errors + erasures).toArray();
                    for (int position : positions) {
                        damaged[position] = (damaged[position] + 1 + random.nextInt(field.size() - 1)) % field.size();
                    }
                    int[] erased = Arrays.copyOfRange(positions, errors, positions.length);
                    assertEquals(2 * errors + erasures, ReedSolomon.decode(field, damaged, numEcc, erased),
                            field.size() + " errors " + errors + " erasures " + erasures);
                    assertArrayEquals(clean, damaged);
                }
            }
        }
    }

    @Test
    void erasuresBeyondHalfTheParityAreStillCorrected() {
        Random random = new Random(7);
        int numEcc = 16;
        int[] clean = encode(ReedSolomon.PDF417, random(random, ReedSolomon.PDF417, 40), numEcc);
        int[] damaged = clean.clone();
        int[] erasures = new int[numEcc - 2];
        for (int i = 0; i < erasures.length; i++) {
            erasures[i] = 3 * i;
            damaged[3 * i] = 0;
        }
        assertEquals(numEcc - 2, ReedSolomon.decode(ReedSolomon.PDF417, damaged, numEcc, erasures));
        assertArrayEquals(clean, damaged);
    }

    @Test
    void aFailureLeavesTheCodewordAsItWas() throws Exception {
        Random random = new Random(3);
        int numEcc = 8;
        int[] clean = encode(ReedSolomon.QR_CODE, random(random, ReedSolomon.QR_CODE, 20), numEcc);
        int[] damaged = clean.clone();
        for (int i = 0; i < 9; i++) {
            damaged[2 * i] ^= 0x5A;
        }
        int[] before = damaged.clone();
        assertThrows(ReedSolomonException.class, () -> ReedSolomon.correct(ReedSolomon.QR_CODE, damaged, numEcc));
        assertArrayEquals(before, damaged);
        int[] fixable = clean.clone();
        fixable[0] ^= 1;
        assertEquals(2, ReedSolomon.correct(ReedSolomon.QR_CODE, fixable, numEcc));
        assertArrayEquals(clean, fixable);
        assertThrows(IllegalArgumentException.class, () -> ReedSolomon.decode(ReedSolomon.QR_CODE, clean.clone(), 2, new int[]{0, 1, 2}));
    }

    @Test
    void agreesWithZxingOnErrorsAlone() throws Exception {
        Random random = new Random(11);
        com.google.zxing.common.reedsolomon.ReedSolomonDecoder zxing =
                new com.google.zxing.common.reedsolomon.ReedSolomonDecoder(com.google.zxing.common.reedsolomon.GenericGF.QR_CODE_FIELD_256);
        int agreed = 0;
        for (int trial = 0; trial < 2000; trial++) {
            int numEcc = 2 + 2 * random.nextInt(10);
            int[] clean = encode(ReedSolomon.QR_CODE, random(random, ReedSolomon.QR_CODE, 1 + random.nextInt(60)), numEcc);
            int[] damaged = clean.clone();
            int errors = random.nextInt(numEcc);
            for (int i = 0; i < errors; i++) {
                damaged[random.nextInt(damaged.length)] = random.nextInt(256);
            }
            int[] ours = damaged.clone();
            int[] theirs = damaged.clone();
            boolean oursOk = ReedSolomon.decode(ReedSolomon.QR_CODE, ours, numEcc, new int[0]) >= 0;
            boolean theirsOk;
            try {
                zxing.decode(theirs, numEcc);
                theirsOk = true;
            } catch (com.google.zxing.common.reedsolomon.ReedSolomonException failed) {
                theirsOk = false;
            }
            // Both are bounded-distance decoders; this one also rejects a correction that leaves a syndrome.
            if (oursOk) {
                assertTrue(theirsOk, "trial " + trial);
                assertArrayEquals(theirs, ours, "trial " + trial);
            }
            agreed += oursOk == theirsOk ? 1 : 0;
        }
        assertTrue(agreed > 1990, "agreed " + agreed);
    }
}
