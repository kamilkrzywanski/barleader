package io.github.kamilkrzywanski.barcode.encode;

import java.util.HashMap;
import java.util.Map;

/** The ECI assignment numbers of character sets, by the names ZXing's {@code CharacterSetECI} knows them by. */
public final class Eci {
    /**
     * The ECI a character set is announced with, keyed by its canonical name and its other names,
     * as ZXing's {@code CharacterSetECI} looks them up. ISO-8859-1 is announced as 1, not 3, and
     * {@code IBM437}, the canonical name of Cp437, has none.
     */
    private static final Map<String, Integer> ECI = new HashMap<>();

    static {
        eci(0, "Cp437");
        eci(1, "ISO8859_1", "ISO-8859-1");
        for (int part : new int[]{2, 3, 4, 5, 6, 7, 8, 9, 10, 11}) {
            eci(part + 2, "ISO8859_" + part, "ISO-8859-" + part);
        }
        for (int part : new int[]{13, 14, 15, 16}) {
            eci(part + 2, "ISO8859_" + part, "ISO-8859-" + part);
        }
        eci(20, "SJIS", "Shift_JIS");
        eci(21, "Cp1250", "windows-1250");
        eci(22, "Cp1251", "windows-1251");
        eci(23, "Cp1252", "windows-1252");
        eci(24, "Cp1256", "windows-1256");
        eci(25, "UnicodeBigUnmarked", "UTF-16BE", "UnicodeBig");
        eci(26, "UTF8", "UTF-8");
        eci(27, "ASCII", "US-ASCII");
        eci(28, "Big5");
        eci(29, "GB18030", "GB2312", "EUC_CN", "GBK");
        eci(30, "EUC_KR", "EUC-KR");
    }

    private Eci() {
    }

    private static void eci(int value, String... names) {
        for (String name : names) {
            ECI.put(name, value);
        }
    }

    /** The ECI a character set of this name is announced with, or -1 when the table has none. */
    public static int value(String charsetName) {
        Integer value = ECI.get(charsetName);
        return value == null ? -1 : value;
    }
}
