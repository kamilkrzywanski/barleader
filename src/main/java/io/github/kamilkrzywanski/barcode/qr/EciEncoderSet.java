package io.github.kamilkrzywanski.barcode.qr;

import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.util.ArrayList;
import java.util.List;

/**
 * The character sets a text needs, in the order the minimal encoder tries them: ISO-8859-1 first,
 * then the first of a fixed list that encodes each character ISO-8859-1 cannot, and UTF-8 and
 * UTF-16BE when some character needs them.
 *
 * <p>Ported from ZXing 3.5.4 {@code com.google.zxing.common.ECIEncoderSet} (Apache License 2.0, the
 * ZXing authors).
 */
final class EciEncoderSet {
    private static final List<CharsetEncoder> ENCODERS = new ArrayList<>();

    static {
        String[] names = {"IBM437", "ISO-8859-2", "ISO-8859-3", "ISO-8859-4", "ISO-8859-5", "ISO-8859-6", "ISO-8859-7",
                "ISO-8859-8", "ISO-8859-9", "ISO-8859-10", "ISO-8859-11", "ISO-8859-13", "ISO-8859-14", "ISO-8859-15",
                "ISO-8859-16", "windows-1250", "windows-1251", "windows-1252", "windows-1256", "Shift_JIS"};
        for (String name : names) {
            // As in ZXing, only a set its ECI table knows by this name: IBM437 is not.
            if (QrEncoder.eciValue(name) >= 0) {
                try {
                    ENCODERS.add(Charset.forName(name).newEncoder());
                } catch (UnsupportedCharsetException absent) {
                    // not on this JDK
                }
            }
        }
    }

    private final CharsetEncoder[] encoders;
    private final int priorityEncoderIndex;

    /**
     * @param priorityCharset the set to use for every character it can encode, or null
     * @param fnc1            the character that stands for FNC1 in GS1 text, or -1
     */
    EciEncoderSet(String stringToEncode, Charset priorityCharset, int fnc1) {
        List<CharsetEncoder> neededEncoders = new ArrayList<>();
        neededEncoders.add(StandardCharsets.ISO_8859_1.newEncoder());
        boolean needUnicodeEncoder = priorityCharset != null && priorityCharset.name().startsWith("UTF");
        for (int i = 0; i < stringToEncode.length(); i++) {
            boolean canEncode = false;
            for (CharsetEncoder encoder : neededEncoders) {
                char c = stringToEncode.charAt(i);
                if (c == fnc1 || encoder.canEncode(c)) {
                    canEncode = true;
                    break;
                }
            }
            if (!canEncode) {
                for (CharsetEncoder encoder : ENCODERS) {
                    if (encoder.canEncode(stringToEncode.charAt(i))) {
                        neededEncoders.add(encoder);
                        canEncode = true;
                        break;
                    }
                }
            }
            if (!canEncode) {
                needUnicodeEncoder = true;
            }
        }
        if (neededEncoders.size() == 1 && !needUnicodeEncoder) {
            encoders = new CharsetEncoder[]{neededEncoders.get(0)};
        } else {
            encoders = new CharsetEncoder[neededEncoders.size() + 2];
            int index = 0;
            for (CharsetEncoder encoder : neededEncoders) {
                encoders[index++] = encoder;
            }
            encoders[index] = StandardCharsets.UTF_8.newEncoder();
            encoders[index + 1] = StandardCharsets.UTF_16BE.newEncoder();
        }
        int priority = -1;
        if (priorityCharset != null) {
            for (int i = 0; i < encoders.length; i++) {
                if (encoders[i] != null && priorityCharset.name().equals(encoders[i].charset().name())) {
                    priority = i;
                    break;
                }
            }
        }
        priorityEncoderIndex = priority;
    }

    int length() {
        return encoders.length;
    }

    String getCharsetName(int index) {
        return encoders[index].charset().name();
    }

    Charset getCharset(int index) {
        return encoders[index].charset();
    }

    int getEciValue(int encoderIndex) {
        return QrEncoder.eciValue(encoders[encoderIndex].charset().name());
    }

    /** -1 when no priority set was given, or it is not among these. */
    int getPriorityEncoderIndex() {
        return priorityEncoderIndex;
    }

    boolean canEncode(char c, int encoderIndex) {
        return encoders[encoderIndex].canEncode("" + c);
    }

    byte[] encode(char c, int encoderIndex) {
        return ("" + c).getBytes(encoders[encoderIndex].charset());
    }

    byte[] encode(String s, int encoderIndex) {
        return s.getBytes(encoders[encoderIndex].charset());
    }
}
