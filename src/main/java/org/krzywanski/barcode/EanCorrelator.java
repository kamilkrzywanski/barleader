package org.krzywanski.barcode;

import org.krzywanski.barcode.image.GrayU8;

/**
 * EAN-13 from the sharpest rows, by correlation with the digit templates.
 * A defocused bar never crosses a threshold, so a binarized row merges
 * neighbors, but the module waveform still matches one digit.
 *
 * <p>The end guard is not required. On a tight crop it merges into the dark
 * border and the correlation goes negative while the twelve digits stay
 * readable. Thresholds are the ones that read the former misses and produced
 * no false payload on the other empty Arte-Lab frames.
 *
 * <p>{@link #readLoose} is a second grid for a crop the guarded search missed.
 * A smeared bar can correlate negatively with the start and center guards
 * while every digit still matches. Dropping those guards on the fine grid
 * accepted a checksum-valid wrong code, and it cost seconds per crop. The
 * coarser step, with a slightly higher digit floor and no guard test, kept
 * the smeared reads and rejected that wrong code.
 */
final class EanCorrelator {
    private static final String[] L = {
            "0001101", "0011001", "0010011", "0111101", "0100011",
            "0110001", "0101111", "0111011", "0110111", "0001011"
    };
    private static final String[] G = {
            "0100111", "0110011", "0011011", "0100001", "0011101",
            "0111001", "0000101", "0010001", "0001001", "0010111"
    };
    private static final String[] R = new String[10];
    private static final String[] PARITY = {
            "LLLLLL", "LLGLGG", "LLGGLG", "LLGGGL", "LGLLGG",
            "LGGLLG", "LGGGLL", "LGLGLG", "LGLGGL", "LGGLGL"
    };

    static {
        for (int digit = 0; digit < 10; digit++) {
            R[digit] = flip(L[digit]);
        }
    }

    private EanCorrelator() {
    }

    /** Thirteen digits, or null. {@code upsideDown} means the row was read right to left. */
    record Found(String text, boolean upsideDown) {
    }

    static Found read(GrayU8 image) {
        if (image == null || image.width < 280 || image.height < 24) {
            return null;
        }
        int[] signal = sharpRows(image);
        if (signal.length < 100) {
            return null;
        }
        Candidate forward = search(signal);
        Candidate backward = search(reverse(signal));
        if (backward != null && (forward == null || backward.min > forward.min)) {
            return new Found(backward.text, true);
        }
        return forward == null ? null : new Found(forward.text, false);
    }

    /**
     * Same row choice as {@link #read}, on a coarser module and origin step,
     * with no guard test and a digit floor of 0.55. Callers must try {@link #read}
     * on every crop first. Running this grid first can replace a guarded hit.
     */
    static Found readLoose(GrayU8 image) {
        return readGrid(image, 0.2);
    }

    /**
     * Same origin step and digit floor as {@link #readLoose}, with a module step
     * of 0.1. The coarser module step skips a true module of 3.1. This grid still
     * rejected the checksum-valid wrong code that the fine origin step accepted.
     * Callers must try {@link #read} and {@link #readLoose} on every crop first.
     */
    static Found readMedium(GrayU8 image) {
        return readGrid(image, 0.1);
    }

    private static Found readGrid(GrayU8 image, double moduleStep) {
        if (image == null || image.width < 280 || image.height < 24) {
            return null;
        }
        int[] signal = sharpRows(image);
        if (signal.length < 100) {
            return null;
        }
        Candidate forward = searchLoose(signal, moduleStep);
        Candidate backward = searchLoose(reverse(signal), moduleStep);
        if (backward != null && (forward == null || backward.min > forward.min)) {
            return new Found(backward.text, true);
        }
        return forward == null ? null : new Found(forward.text, false);
    }

    private static Candidate search(int[] signal) {
        Candidate best = null;
        for (double module = 2.0; module <= 8.0; module += 0.1) {
            double width = 95 * module;
            if (width + 2 > signal.length) {
                continue;
            }
            double step = Math.max(0.5, module * 0.2);
            for (double origin = 0; origin + width < signal.length; origin += step) {
                if (corr(signal, origin, module, "101") < 0.25) {
                    continue;
                }
                if (corr(signal, origin + 45 * module, module, "01010") < 0.45) {
                    continue;
                }
                Assembled left = assemble(signal, origin, module, true);
                if (left == null) {
                    continue;
                }
                int first = parityDigit(left.sets);
                if (first < 0) {
                    continue;
                }
                Assembled right = assemble(signal, origin, module, false);
                if (right == null) {
                    continue;
                }
                int[] digits = new int[13];
                digits[0] = first;
                System.arraycopy(left.digits, 0, digits, 1, 6);
                System.arraycopy(right.digits, 0, digits, 7, 6);
                if (!checksum(digits)) {
                    continue;
                }
                double min = Math.min(left.min, right.min);
                if (best == null || min > best.min) {
                    best = new Candidate(text(digits), min);
                }
            }
        }
        return best;
    }

    /** Coarser than {@link #search}: no guard test, origin step at least one pixel. */
    private static Candidate searchLoose(int[] signal, double moduleStep) {
        Candidate best = null;
        for (double module = 2.0; module <= 8.0; module += moduleStep) {
            double width = 95 * module;
            if (width + 2 > signal.length) {
                continue;
            }
            double step = Math.max(1.0, module * 0.45);
            for (double origin = 0; origin + width < signal.length; origin += step) {
                Assembled left = assemble(signal, origin, module, true, 0.55);
                if (left == null) {
                    continue;
                }
                int first = parityDigit(left.sets);
                if (first < 0) {
                    continue;
                }
                Assembled right = assemble(signal, origin, module, false, 0.55);
                if (right == null) {
                    continue;
                }
                int[] digits = new int[13];
                digits[0] = first;
                System.arraycopy(left.digits, 0, digits, 1, 6);
                System.arraycopy(right.digits, 0, digits, 7, 6);
                if (!checksum(digits)) {
                    continue;
                }
                double min = Math.min(left.min, right.min);
                if (best == null || min > best.min) {
                    best = new Candidate(text(digits), min);
                }
            }
        }
        return best;
    }

    private static Assembled assemble(int[] signal, double origin, double module, boolean left) {
        return assemble(signal, origin, module, left, 0.52);
    }

    private static Assembled assemble(int[] signal, double origin, double module, boolean left, double digitMin) {
        int[] digits = new int[6];
        char[] sets = new char[6];
        double min = 1;
        for (int digit = 0; digit < 6; digit++) {
            double x = origin + ((left ? 3 : 50) + digit * 7) * module;
            Pick pick = pick(signal, x, module, left, digitMin);
            if (pick == null) {
                return null;
            }
            digits[digit] = pick.digit;
            sets[digit] = pick.set;
            min = Math.min(min, pick.score);
        }
        return new Assembled(digits, sets, min);
    }

    private static Pick pick(int[] signal, double origin, double module, boolean left, double digitMin) {
        int bestDigit = -1;
        char bestSet = 'L';
        double best = -1;
        double second = -1;
        String[] families = left ? new String[]{"L", "G"} : new String[]{"R"};
        for (String family : families) {
            for (int digit = 0; digit < 10; digit++) {
                String bits = family.equals("L") ? L[digit] : family.equals("G") ? G[digit] : R[digit];
                double score = corr(signal, origin, module, bits);
                if (score > best) {
                    second = best;
                    best = score;
                    bestDigit = digit;
                    bestSet = family.charAt(0);
                } else if (score > second) {
                    second = score;
                }
            }
        }
        if (bestDigit < 0 || best < digitMin || best - second < 0.02) {
            return null;
        }
        return new Pick(bestDigit, bestSet, best);
    }

    /** A few rows around the strongest horizontal edge. Averaging the whole crop mixes in the digits. */
    private static int[] sharpRows(GrayU8 image) {
        int bestY = image.height / 2;
        long best = -1;
        int y0 = image.height / 8;
        int y1 = Math.max(y0 + 1, image.height * 7 / 8);
        for (int y = y0; y < y1; y++) {
            int row = image.startIndex + y * image.stride;
            long energy = 0;
            int previous = image.data[row] & 255;
            for (int x = 1; x < image.width; x++) {
                int value = image.data[row + x] & 255;
                int delta = value - previous;
                energy += (long) delta * delta;
                previous = value;
            }
            if (energy > best) {
                best = energy;
                bestY = y;
            }
        }
        int from = Math.max(0, bestY - 2);
        int to = Math.min(image.height, bestY + 3);
        int[] sum = new int[image.width];
        int rows = Math.max(1, to - from);
        for (int y = from; y < to; y++) {
            int row = image.startIndex + y * image.stride;
            for (int x = 0; x < image.width; x++) {
                sum[x] += image.data[row + x] & 255;
            }
        }
        for (int x = 0; x < image.width; x++) {
            sum[x] /= rows;
        }
        return sum;
    }

    private static int parityDigit(char[] sets) {
        String pattern = new String(sets);
        for (int digit = 0; digit < PARITY.length; digit++) {
            if (PARITY[digit].equals(pattern)) {
                return digit;
            }
        }
        return -1;
    }

    private static boolean checksum(int[] digits) {
        int sum = 0;
        for (int i = 0; i < 12; i++) {
            sum += digits[i] * ((i % 2 == 0) ? 1 : 3);
        }
        return digits[12] == (10 - sum % 10) % 10;
    }

    /** Pearson correlation of darkness with a bar mask. One means a perfect match. */
    private static double corr(int[] signal, double origin, double module, String bits) {
        int modules = bits.length();
        int samples = Math.max(4, (int) Math.round(modules * module));
        double sumS = 0;
        double sumE = 0;
        double sumSS = 0;
        double sumEE = 0;
        double sumSE = 0;
        for (int i = 0; i < samples; i++) {
            int ix = (int) Math.floor(origin + (i + 0.5) * modules * module / samples);
            if (ix < 0 || ix >= signal.length) {
                return -2;
            }
            int bit = (int) ((i + 0.5) * modules / samples);
            if (bit >= modules) {
                bit = modules - 1;
            }
            double expected = bits.charAt(bit) == '1' ? 1.0 : 0.0;
            double dark = 255 - signal[ix];
            sumS += dark;
            sumE += expected;
            sumSS += dark * dark;
            sumEE += expected * expected;
            sumSE += dark * expected;
        }
        double num = samples * sumSE - sumS * sumE;
        double left = samples * sumSS - sumS * sumS;
        double right = samples * sumEE - sumE * sumE;
        if (left <= 1e-6 || right <= 1e-6) {
            return -2;
        }
        return num / Math.sqrt(left * right);
    }

    private static int[] reverse(int[] signal) {
        int[] out = new int[signal.length];
        for (int x = 0; x < signal.length; x++) {
            out[x] = signal[signal.length - 1 - x];
        }
        return out;
    }

    private static String flip(String bits) {
        StringBuilder out = new StringBuilder(bits.length());
        for (int i = 0; i < bits.length(); i++) {
            out.append(bits.charAt(i) == '1' ? '0' : '1');
        }
        return out.toString();
    }

    private static String text(int[] digits) {
        StringBuilder out = new StringBuilder(13);
        for (int digit : digits) {
            out.append(digit);
        }
        return out.toString();
    }

    private record Pick(int digit, char set, double score) {
    }

    private record Assembled(int[] digits, char[] sets, double min) {
    }

    private record Candidate(String text, double min) {
    }
}
