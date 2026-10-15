package io.github.kamilkrzywanski.barcode;

import io.github.kamilkrzywanski.barcode.encode.Code128Encoder;
import io.github.kamilkrzywanski.barcode.encode.Code128Encoder.CodeSet;
import io.github.kamilkrzywanski.barcode.encode.Code93Encoder;
import io.github.kamilkrzywanski.barcode.encode.Gs1;
import io.github.kamilkrzywanski.barcode.encode.Linear;
import io.github.kamilkrzywanski.barcode.encode.UpcEanEncoder;
import io.github.kamilkrzywanski.barcode.encode.UpcEanEncoder.Kind;
import io.github.kamilkrzywanski.barcode.encode.WideNarrowEncoders;
import io.github.kamilkrzywanski.barcode.encode.aztec.Encoder;
import io.github.kamilkrzywanski.barcode.encode.datamatrix.DataMatrixEncoder;
import io.github.kamilkrzywanski.barcode.encode.datamatrix.SymbolShapeHint;
import io.github.kamilkrzywanski.barcode.encode.pdf417.Pdf417Encoder;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import java.util.Objects;

/**
 * Writes barcodes of every {@link BarcodeType}.
 *
 * <pre>
 * Barcode label = BarcodeWriter.encode(BarcodeType.GS1_128, "(01)09501101530003(17)250630(10)AB-123");
 * Barcode book = BarcodeWriter.encode(BarcodeType.ISBN_13, "978-3-16-148410", WriteOptions.defaults().addOn("51299"));
 * </pre>
 *
 * <p>The linear symbologies follow ZXing's writers where ZXing has one, module for module at its
 * wide-to-narrow ratio, and Barcode-Lib4J's for the rest; the GS1 element strings are checked
 * against the application identifiers as Barcode-Lib4J checks them.
 */
public final class BarcodeWriter {
    private static final char GS = '\u001D';
    private static final int LINEAR_QUIET_ZONE = 10;

    private BarcodeWriter() {
    }

    public static Barcode encode(BarcodeType type, String content) {
        return encode(type, content, WriteOptions.defaults());
    }

    /**
     * @throws IllegalArgumentException when the content does not fit the symbology: a character it
     *                                  cannot hold, a wrong length, a wrong check digit, or too much data
     */
    public static Barcode encode(BarcodeType type, String content, WriteOptions options) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(options, "options");
        int[] ratio = Linear.ratio(options.wideRatio());
        int narrow = ratio[0];
        int wide = ratio[1];
        return switch (type) {
            case QR_CODE -> qr(type, content, null, options);
            case GS1_QR_CODE -> {
                Gs1 gs1 = new Gs1(content, GS);
                yield qr(type, gs1.getContent(), gs1.getText(), options);
            }
            case DATA_MATRIX -> dataMatrix(type, content, content, false, options);
            case GS1_DATA_MATRIX -> {
                Gs1 gs1 = new Gs1(content, GS);
                yield dataMatrix(type, gs1.getContent(), gs1.getText(), true, options);
            }
            case PDF_417 -> pdf417(type, content, options);
            case AZTEC -> aztec(type, content, options);
            case CODE_128 -> code128(type, content, content, CodeSet.AUTO, options);
            case CODE_128_A -> code128(type, content, content, CodeSet.A, options);
            case CODE_128_B -> code128(type, content, content, CodeSet.B, options);
            case CODE_128_C -> {
                String digits = content.length() % 2 == 0 ? content : "0" + content;
                yield code128(type, digits, digits, CodeSet.C, options);
            }
            case GS1_128 -> gs1128(type, content, options);
            case EAN_14 -> gs1128(type, "(01)" + Linear.withGtinCheckDigit(content, 14, "EAN-14"), options);
            case SSCC_18 -> gs1128(type, "(00)" + Linear.withGtinCheckDigit(content, 18, "SSCC-18"), options);
            case CODE_39 -> linear(type, WideNarrowEncoders.code39(content, false, options.checksum(), narrow, wide), options, LINEAR_QUIET_ZONE * narrow);
            case CODE_39_EXTENDED -> linear(type, WideNarrowEncoders.code39(content, true, options.checksum(), narrow, wide), options,
                    LINEAR_QUIET_ZONE * narrow);
            case PZN -> pzn(type, content, 7, options, narrow, wide);
            case PZN_8 -> pzn(type, content, 8, options, narrow, wide);
            case CODE_93 -> linear(type, Code93Encoder.encode(content, false), options, LINEAR_QUIET_ZONE);
            case CODE_93_EXTENDED -> linear(type, Code93Encoder.encode(content, true), options, LINEAR_QUIET_ZONE);
            case CODABAR -> linear(type, WideNarrowEncoders.codabar(content, narrow, wide), options, LINEAR_QUIET_ZONE * narrow);
            case CODE_11 -> linear(type, WideNarrowEncoders.code11(content, options.checksum(), narrow, wide), options, LINEAR_QUIET_ZONE * narrow);
            case ITF -> linear(type, WideNarrowEncoders.itf(content, narrow, wide), options, LINEAR_QUIET_ZONE * narrow);
            case EAN_13 -> upcEan(type, Kind.EAN_13, content, null, options);
            case EAN_8 -> upcEan(type, Kind.EAN_8, content, null, options);
            case UPC_A -> upcEan(type, Kind.UPC_A, content, null, options);
            case UPC_E -> upcEan(type, Kind.UPC_E, content, null, options);
            case ISBN_13 -> {
                String[] isbn = UpcEanEncoder.isxn(content, "ISBN");
                yield upcEan(type, Kind.EAN_13, isbn[0], isbn[1], options);
            }
            case ISMN -> {
                String[] ismn = UpcEanEncoder.isxn(content, "ISMN");
                yield upcEan(type, Kind.EAN_13, ismn[0], ismn[1], options);
            }
        };
    }

    private static Barcode qr(BarcodeType type, String content, String text, WriteOptions options) {
        QrOptions qr = options.qr();
        if (options.charset() != null) {
            qr = qr.charset(options.charset());
        }
        if (options.compact()) {
            qr = qr.compact(true);
        }
        if (type == BarcodeType.GS1_QR_CODE) {
            qr = qr.gs1(true);
        }
        QrCode code = QrCode.encode(content, qr);
        boolean[][] modules = new boolean[code.size()][code.size()];
        for (int y = 0; y < code.size(); y++) {
            for (int x = 0; x < code.size(); x++) {
                modules[y][x] = code.isDark(x, y);
            }
        }
        int quiet = options.quietZone() >= 0 ? options.quietZone() : QrCode.QUIET_ZONE;
        return new Barcode(type, modules, text == null ? content : text, quiet, quiet, quiet, quiet);
    }

    /**
     * Data Matrix through ZXing's high-level encoder, or its minimal one when asked for, when a
     * character set is given, or when the text has a character outside ISO-8859-1, which the
     * high-level encoder cannot write; that text is then UTF-8 with an ECI.
     */
    private static Barcode dataMatrix(BarcodeType type, String content, String text, boolean gs1, WriteOptions options) {
        SymbolShapeHint shape = switch (options.dataMatrixShape()) {
            case SQUARE -> SymbolShapeHint.FORCE_SQUARE;
            case RECTANGLE -> SymbolShapeHint.FORCE_RECTANGLE;
            default -> SymbolShapeHint.FORCE_NONE;
        };
        Charset charset = options.charset();
        boolean latin1 = StandardCharsets.ISO_8859_1.newEncoder().canEncode(content);
        boolean compact = options.compact() || charset != null || !latin1;
        if (charset == null && !latin1) {
            charset = StandardCharsets.UTF_8;
        }
        boolean[][] modules = DataMatrixEncoder.encode(content, shape, gs1, compact, charset);
        int quiet = options.quietZone() >= 0 ? options.quietZone() : 1;
        return new Barcode(type, modules, text, quiet, quiet, quiet, quiet);
    }

    /**
     * PDF417 through ZXing's encoder: text in ISO-8859-1 without an ECI, other text UTF-8 with
     * one, a given character set with its own; each row {@code rowHeight} modules high.
     */
    private static Barcode pdf417(BarcodeType type, String content, WriteOptions options) {
        Pdf417Options pdf417 = options.pdf417();
        Charset charset = options.charset();
        if (charset == null && !StandardCharsets.ISO_8859_1.newEncoder().canEncode(content)) {
            charset = StandardCharsets.UTF_8;
        }
        int columns = pdf417.columns();
        int rows = pdf417.rows();
        boolean[][] symbolRows = Pdf417Encoder.encode(content, pdf417.errorCorrection(), columns == 0 ? 1 : columns,
                columns == 0 ? 30 : columns, rows == 0 ? 3 : rows, rows == 0 ? 90 : rows, pdf417.truncated(), charset,
                options.compact());
        boolean[][] modules = new boolean[symbolRows.length * pdf417.rowHeight()][];
        for (int y = 0; y < modules.length; y++) {
            modules[y] = symbolRows[y / pdf417.rowHeight()];
        }
        int quiet = options.quietZone() >= 0 ? options.quietZone() : 2;
        return new Barcode(type, modules, content, quiet, quiet, quiet, quiet);
    }

    /** Aztec through ZXing's encoder: text in ISO-8859-1 without an ECI, other text UTF-8 with one. */
    private static Barcode aztec(BarcodeType type, String content, WriteOptions options) {
        if (content.isEmpty()) {
            throw new IllegalArgumentException("Found empty contents");
        }
        Charset charset = options.charset();
        if (charset == null && !StandardCharsets.ISO_8859_1.newEncoder().canEncode(content)) {
            charset = StandardCharsets.UTF_8;
        }
        AztecOptions aztec = options.aztec();
        boolean[][] modules = Encoder.encode(content, aztec.errorCorrection(), aztec.layers(), charset).getMatrix();
        int quiet = Math.max(options.quietZone(), 0);
        return new Barcode(type, modules, content, quiet, quiet, quiet, quiet);
    }

    private static Barcode code128(BarcodeType type, String content, String text, CodeSet codeSet, WriteOptions options) {
        return linear(type, new Linear.Bars(Code128Encoder.encode(content, codeSet, options.compact()), text), options, LINEAR_QUIET_ZONE);
    }

    private static Barcode gs1128(BarcodeType type, String content, WriteOptions options) {
        Gs1 gs1 = new Gs1(content.replace(GS, Code128Encoder.FNC_1), Code128Encoder.FNC_1);
        return code128(type, Code128Encoder.FNC_1 + gs1.getContent(), gs1.getText(), CodeSet.AUTO, options);
    }

    /** PZN and PZN8 as Barcode-Lib4J writes them: a dash and the digits in Code 39, the check digit modulo 11. */
    private static Barcode pzn(BarcodeType type, String content, int length, WriteOptions options, int narrow, int wide) {
        Linear.checkNumeric(content);
        String digits = content;
        int offset = length == 7 ? 2 : 1;
        if (digits.length() == length - 1) {
            int check = pznCheckDigit(digits, offset);
            if (check == 10) {
                throw new IllegalArgumentException("This " + (length == 7 ? "PZN" : "PZN8") + " cannot have a check digit");
            }
            digits += check;
        } else if (digits.length() != length) {
            throw new IllegalArgumentException((length == 7 ? "PZN" : "PZN8") + " has " + (length - 1) + " or " + length + " digits, not "
                    + digits.length());
        } else if (pznCheckDigit(digits.substring(0, length - 1), offset) != digits.charAt(length - 1) - '0') {
            throw new IllegalArgumentException("The check digit of " + digits + " is wrong");
        }
        Linear.Bars bars = WideNarrowEncoders.code39("-" + digits, false, false, narrow, wide);
        return linear(type, new Linear.Bars(bars.modules(), "PZN -" + digits), options, LINEAR_QUIET_ZONE * narrow);
    }

    private static int pznCheckDigit(String digits, int firstWeight) {
        int sum = 0;
        for (int i = 0; i < digits.length(); i++) {
            sum += (i + firstWeight) * (digits.charAt(i) - '0');
        }
        return sum % 11;
    }

    private static Barcode upcEan(BarcodeType type, Kind kind, String digits, String text, WriteOptions options) {
        Linear.Bars bars = UpcEanEncoder.encode(kind, digits, options.addOn());
        int left = options.quietZone() >= 0 ? options.quietZone() : kind.quietLeft;
        int right = options.quietZone() >= 0 ? options.quietZone()
                : options.addOn() != null ? UpcEanEncoder.ADDON_QUIET_ZONE : kind.quietRight;
        String shown = text == null ? bars.text() : options.addOn() == null ? text : text + " " + options.addOn();
        String withCheckDigit = bars.text().split(" ", 2)[0];
        return Barcode.linear(type, bars.modules(), shown, left, right, new Barcode.RetailLabel(kind, withCheckDigit, options.addOn(), text));
    }

    private static Barcode linear(BarcodeType type, Linear.Bars bars, WriteOptions options, int defaultQuietZone) {
        int quiet = options.quietZone() >= 0 ? options.quietZone() : defaultQuietZone;
        return Barcode.linear(type, bars.modules(), bars.text(), quiet, quiet);
    }
}
