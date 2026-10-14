package io.github.kamilkrzywanski.barcode.decode;

/**
 * DataBar Expanded bit decoder ported from zxing-cpp 3.1.1 {@code ODDataBarExpandedBitDecoder.cpp}.
 *
 * <p>The text is the GS1 element string. A function-1 between fields is ASCII 29. Parentheses are
 * not added, and there is no {@code ]e0} identifier. A short read or a format error is null.
 * {@link DataBarExpandedReader} calls this. Neither class is on the scan ladder.
 */
final class DataBarExpandedDecoder {
    private static final char GS = 29;
    private static final String ALPHA_58_TO_62 = "*,-./";
    private static final String ISO_232_TO_252 = "!\"%&'()*+,-./:;<=>?_ ";

    static {
        if (ALPHA_58_TO_62.length() != 5 || ISO_232_TO_252.length() != 21) {
            throw new IllegalStateException("table size mismatch");
        }
    }

    private DataBarExpandedDecoder() {
    }

    /** Element string of one expanded bit stream, linkage bit included. Null when it does not decode. */
    static String decode(boolean[] bits) {
        if (bits == null || bits.length == 0) {
            return null;
        }
        try {
            Bits cursor = new Bits(bits);
            cursor.read(1);
            String text;
            if (cursor.peek(1) == 1) {
                text = ai01AndOther(cursor.skip(1));
            } else if (cursor.peek(2) == 0) {
                text = anyAi(cursor.skip(2));
            } else {
                switch (cursor.peek(4)) {
                    case 4 -> text = ai013103(cursor.skip(4));
                    case 5 -> text = ai01320x(cursor.skip(4));
                    default -> {
                        switch (cursor.peek(5)) {
                            case 12 -> text = ai0139yx(cursor.skip(5), '2');
                            case 13 -> text = ai0139yx(cursor.skip(5), '3');
                            default -> text = dated(cursor);
                        }
                    }
                }
            }
            return text == null || text.isEmpty() ? null : text;
        } catch (Miss ignored) {
            return null;
        }
    }

    private static String dated(Bits bits) {
        return switch (bits.read(7)) {
            case 56 -> ai013x0x1x(bits, "310", "11");
            case 57 -> ai013x0x1x(bits, "320", "11");
            case 58 -> ai013x0x1x(bits, "310", "13");
            case 59 -> ai013x0x1x(bits, "320", "13");
            case 60 -> ai013x0x1x(bits, "310", "15");
            case 61 -> ai013x0x1x(bits, "320", "15");
            case 62 -> ai013x0x1x(bits, "310", "17");
            case 63 -> ai013x0x1x(bits, "320", "17");
            default -> null;
        };
    }

    private static String ai01AndOther(Bits bits) {
        bits.skip(2);
        return compressedGtin("01" + bits.read(4), bits) + general(bits);
    }

    private static String anyAi(Bits bits) {
        bits.skip(2);
        return general(bits);
    }

    private static String ai013103(Bits bits) {
        return gtin(bits) + "3103" + digits(bits.read(15), 6);
    }

    private static String ai01320x(Bits bits) {
        StringBuilder text = new StringBuilder(gtin(bits));
        int weight = bits.read(15);
        text.append(weight < 10000 ? "3202" : "3203");
        text.append(digits(weight < 10000 ? weight : weight - 10000, 6));
        return text.toString();
    }

    private static String ai0139yx(Bits bits, char y) {
        bits.skip(2);
        StringBuilder text = new StringBuilder(gtin(bits));
        text.append("39").append(y).append(bits.read(2));
        if (y == '3') {
            text.append(digits(bits.read(10), 3));
        }
        String trailer = general(bits);
        if (trailer.isEmpty()) {
            return null;
        }
        return text.append(trailer).toString();
    }

    private static String ai013x0x1x(Bits bits, String aiPrefix, String dateCode) {
        StringBuilder text = new StringBuilder(gtin(bits));
        text.append(aiPrefix);
        int weight = bits.read(20);
        text.append(weight / 100000);
        text.append(digits(weight % 100000, 6));
        int date = bits.read(16);
        if (date != 38400) {
            text.append(dateCode);
            int day = date % 32;
            date /= 32;
            int month = date % 12 + 1;
            date /= 12;
            text.append(digits(date, 2));
            text.append(digits(month, 2));
            text.append(digits(day, 2));
        }
        return text.toString();
    }

    private static String gtin(Bits bits) {
        return compressedGtin("019", bits);
    }

    /** Four 10-bit groups, then the GTIN check of everything after the {@code 01} prefix. */
    private static String compressedGtin(String prefix, Bits bits) {
        StringBuilder text = new StringBuilder(prefix);
        for (int i = 0; i < 4; i++) {
            text.append(digits(bits.read(10), 3));
        }
        text.append(checkDigit(text.substring(2)));
        return text.toString();
    }

    private static String general(Bits bits) {
        State state = State.NUMERIC;
        StringBuilder text = new StringBuilder();
        while (bits.size() >= 3) {
            switch (state) {
                case NUMERIC -> {
                    if (padding(state, bits)) {
                        break;
                    }
                    if (bits.size() < 7) {
                        int value = bits.read(4);
                        if (value > 0) {
                            text.append(digit(value - 1));
                        }
                    } else if (bits.peek(4) == 0) {
                        bits.skip(4);
                        state = State.ALPHA;
                    } else {
                        int value = bits.read(7) - 8;
                        for (int part : new int[]{value / 11, value % 11}) {
                            text.append(part == 10 ? GS : digit(part));
                        }
                    }
                }
                case ALPHA -> {
                    if (padding(state, bits)) {
                        break;
                    }
                    if (bits.peek(1) == 1) {
                        int value = bits.read(6);
                        if (value < 58) {
                            text.append((char) (value + 33));
                        } else if (value < 63) {
                            text.append(ALPHA_58_TO_62.charAt(value - 58));
                        } else {
                            throw new Miss();
                        }
                    } else if (bits.peek(3) == 0) {
                        bits.skip(3);
                        state = State.NUMERIC;
                    } else {
                        state = fiveBits(state, text, bits);
                    }
                }
                case ISO_IEC_646 -> {
                    if (padding(state, bits)) {
                        break;
                    }
                    if (bits.peek(3) == 0) {
                        bits.skip(3);
                        state = State.NUMERIC;
                    } else {
                        int value = bits.peek(5);
                        if (value < 16) {
                            state = fiveBits(state, text, bits);
                        } else if (value < 29) {
                            value = bits.read(7);
                            text.append((char) (value < 90 ? value + 1 : value + 7));
                        } else {
                            value = bits.read(8);
                            if (value < 232 || value > 252) {
                                throw new Miss();
                            }
                            text.append(ISO_232_TO_252.charAt(value - 232));
                        }
                    }
                }
            }
        }
        if (text.length() > 0 && text.charAt(text.length() - 1) == GS) {
            text.setLength(text.length() - 1);
        }
        return text.toString();
    }

    /** Five-bit alpha or ISO character. Returns the state after a possible latch. */
    private static State fiveBits(State state, StringBuilder text, Bits bits) {
        int value = bits.read(5);
        if (value == 4) {
            return state == State.ALPHA ? State.ISO_IEC_646 : State.ALPHA;
        }
        if (value == 15) {
            text.append(GS);
            if (bits.size() >= 7 && bits.peek(7) < 8) {
                bits.skip(3);
            }
            return State.NUMERIC;
        }
        text.append((char) (value + 43));
        return state;
    }

    private static boolean padding(State state, Bits bits) {
        boolean pad = state == State.NUMERIC
                ? bits.size() < 4
                : bits.size() < 5 && (0b00100 >> (5 - bits.size())) == bits.peek(bits.size());
        if (pad) {
            bits.skip(bits.size());
        }
        return pad;
    }

    /** {@code ToDigit}. A value outside 0..9 is a format error. */
    private static char digit(int value) {
        if (value < 0 || value > 9) {
            throw new Miss();
        }
        return (char) ('0' + value);
    }

    /** {@code ToString}. High digits that do not fit {@code len} are dropped, matching the C++ loop. */
    private static String digits(int value, int len) {
        if (value < 0) {
            throw new Miss();
        }
        char[] out = new char[len];
        for (int i = 0; i < len; i++) {
            out[i] = '0';
        }
        for (int i = len - 1; i >= 0 && value != 0; i--, value /= 10) {
            out[i] = (char) ('0' + value % 10);
        }
        return new String(out);
    }

    /** {@code GTIN::ComputeCheckDigit} on the digits that precede the check. */
    private static char checkDigit(String payload) {
        int sum = 0;
        for (int i = payload.length() - 1; i >= 0; i -= 2) {
            sum += payload.charAt(i) - '0';
        }
        sum *= 3;
        for (int i = payload.length() - 2; i >= 0; i -= 2) {
            sum += payload.charAt(i) - '0';
        }
        return (char) ('0' + (10 - sum % 10) % 10);
    }

    private enum State {
        NUMERIC, ALPHA, ISO_IEC_646
    }

    /** Cursor over a bit stream. Reading past the end is a miss, as {@code BitArrayView} throws. */
    private static final class Bits {
        private final boolean[] data;
        private int at;

        Bits(boolean[] data) {
            this.data = data;
        }

        int size() {
            return data.length - at;
        }

        Bits skip(int n) {
            if (n < 0 || at + n > data.length) {
                throw new Miss();
            }
            at += n;
            return this;
        }

        int peek(int n) {
            if (n < 0 || at + n > data.length) {
                throw new Miss();
            }
            int value = 0;
            for (int i = 0; i < n; i++) {
                value = (value << 1) | (data[at + i] ? 1 : 0);
            }
            return value;
        }

        int read(int n) {
            int value = peek(n);
            at += n;
            return value;
        }
    }

    private static final class Miss extends RuntimeException {
        @Override
        public synchronized Throwable fillInStackTrace() {
            return this;
        }
    }
}
