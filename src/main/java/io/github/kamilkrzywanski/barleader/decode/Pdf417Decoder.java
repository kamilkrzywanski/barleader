/*
 * Copyright 2016 Nu-book Inc.
 * Copyright 2016 ZXing authors
 * Copyright 2020 Axel Waggershauser
 * Copyright 2026 Kamil Krzywański
 */
// SPDX-License-Identifier: Apache-2.0
package io.github.kamilkrzywanski.barleader.decode;

import io.github.kamilkrzywanski.barleader.decode.OneDRow.View;
import io.github.kamilkrzywanski.barleader.ecc.ReedSolomon;
import io.github.kamilkrzywanski.barleader.ecc.ReedSolomonException;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * PDF417 decoder for an axis-aligned pure symbol, ported from zxing-cpp 3.1.1
 * {@code PDFReader.cpp} ({@code DecodePure}), {@code PDFCodewordDecoder.cpp},
 * {@code PDFScanningDecoder.cpp} ({@code DecodeCodewords}) and {@code PDFDecoder.cpp}.
 *
 * <p>{@code decode} is the axis-aligned pure path. {@code decodeCodewords} is the
 * shared correction and parser used by the photo scanner. The scan ladder reads PDF417
 * with {@link Pdf417ScanningDecoder}, so a clean symbol still keeps an {@code original-*}
 * attempt. Without any ECI the bytes are UTF-8 when they are valid UTF-8, else ISO-8859-1
 * ({@link TextDecoder#guessUtf8OrLatin1}).
 * Modulus-929 correction uses the same field as {@code GFp(929, 3)}.
 */
final class Pdf417Decoder {
    private static final int START_SUM = 17;
    private static final int[] START = {8, 1, 1, 1, 1, 1, 1, 3};
    private static final int MAX_ROWS = 90;
    private static final int MAX_CODEWORDS = 32;
    private static final int MIN_SIDE = 3 * START_SUM;
    private static final int UNKNOWN = -1;
    private static final int CONFLICT = -2;
    private static final int MAX_EC_CODEWORDS = 512;

    private static final int TEXT_LATCH = 900;
    private static final int BYTE_LATCH = 901;
    private static final int NUMERIC_LATCH = 902;
    private static final int BYTE_SHIFT = 913;
    private static final int LINKAGE_OTHER = 918;
    private static final int READER_INIT = 921;
    private static final int MACRO_TERMINATOR = 922;
    private static final int MACRO_OPTIONAL = 923;
    private static final int BYTE_LATCH_6 = 924;
    private static final int ECI_USER = 925;
    private static final int ECI_GENERAL = 926;
    private static final int ECI_CHARSET = 927;
    private static final int MACRO_BEGIN = 928;
    private static final int MAX_NUMERIC = 15;
    private static final String PUNCT = ";<>@[\\]_`~!\r\t,:\n-.$/\"|*()?{}'";
    private static final String MIXED = "0123456789&\r\t,:#-.$/+%*=^";

    private Pdf417Decoder() {
    }

    static {
        if (PUNCT.length() != 29 || MIXED.length() != 25) {
            throw new ExceptionInInitializerError("PDF417 text tables");
        }
    }

    /** {@code true} is black. Returns null when the symbol cannot be read. */
    static String decode(boolean[][] image) {
        if (!rectangular(image)) {
            return null;
        }
        boolean[][] current = image;
        for (int turn = 0; turn < 4; turn++) {
            String text = decodeOriented(current);
            if (text != null) {
                return text;
            }
            current = rotate(current);
        }
        return null;
    }

    private static boolean rectangular(boolean[][] image) {
        if (image == null || image.length == 0 || image[0] == null || image[0].length == 0) {
            return false;
        }
        int width = image[0].length;
        for (boolean[] row : image) {
            if (row == null || row.length != width) {
                return false;
            }
        }
        return true;
    }

    private static boolean[][] rotate(boolean[][] source) {
        int height = source.length;
        int width = source[0].length;
        boolean[][] turned = new boolean[width][height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                turned[x][height - 1 - y] = source[y][x];
            }
        }
        return turned;
    }

    private static String decodeOriented(boolean[][] image) {
        Box box = bounds(image);
        if (box == null || (box.width() < MIN_SIDE && box.height() < MIN_SIDE)) {
            return null;
        }
        List<Line> lines = new ArrayList<>();
        int y = box.top;
        while (y <= box.bottom) {
            int[] runs = runs(image, y, box);
            int next = y + 1;
            while (next <= box.bottom && Arrays.equals(runs, runs(image, next, box))) {
                next++;
            }
            Line line = readLine(runs, next - y);
            if (line != null) {
                lines.add(line);
            }
            y = next;
        }
        return assemble(lines);
    }

    private static int[] runs(boolean[][] image, int y, Box box) {
        boolean[] row = new boolean[box.width()];
        System.arraycopy(image[y], box.left, row, 0, row.length);
        return OneDRow.runs(row);
    }

    /**
     * One symbol row. A codeword that does not match the row cluster is kept as
     * -1 and the cursor still advances eight runs, matching {@code ReadCodeWords}.
     */
    private static Line readLine(int[] runs, int votes) {
        View view = OneDRow.View.of(runs);
        View start = OneDRow.findLeftGuard(view, START.length, START, START_SUM, 0);
        if (start == null) {
            return null;
        }
        int[] codes = new int[MAX_CODEWORDS];
        int count = 0;
        int cluster = -1;
        for (int step = 1; count < MAX_CODEWORDS; step++) {
            View window = start.sub(START.length * step, START.length);
            if (!window.isValid()) {
                break;
            }
            int[] norm = normalize(window);
            int code = -1;
            int wordCluster = -1;
            if (norm != null) {
                wordCluster = clusterOf(norm);
                code = Pdf417Symbols.codeword(OneDRow.pack(norm));
            }
            if (count == 0) {
                if (code < 0 || (wordCluster != 0 && wordCluster != 3 && wordCluster != 6)) {
                    return null;
                }
                cluster = wordCluster;
            } else if (wordCluster != cluster) {
                code = -1;
            }
            codes[count++] = code;
        }
        if (count < 2) {
            return null;
        }
        int row = (codes[0] / 30) * 3 + cluster / 3;
        return new Line(row, cluster, votes, count, codes);
    }

    /** {@code NormalizedPattern<8, 17>}. */
    private static int[] normalize(View view) {
        int total = view.sum(START.length);
        if (total <= 0) {
            return null;
        }
        double module = (double) total / START_SUM;
        int error = START_SUM;
        int[] rounded = new int[START.length];
        double[] fraction = new double[START.length];
        for (int i = 0; i < START.length; i++) {
            double value = view.at(i) / module;
            rounded[i] = (int) (value + 0.5);
            fraction[i] = value - rounded[i];
            error -= rounded[i];
        }
        if (Math.abs(error) > 1) {
            return null;
        }
        if (error != 0) {
            int at = 0;
            for (int i = 1; i < fraction.length; i++) {
                if (error > 0 ? fraction[i] > fraction[at] : fraction[i] < fraction[at]) {
                    at = i;
                }
            }
            rounded[at] += error;
        }
        return rounded;
    }

    private static int clusterOf(int[] bars) {
        return (bars[0] - bars[2] + bars[4] - bars[6] + 9) % 9;
    }

    private static String assemble(List<Line> lines) {
        if (lines.isEmpty()) {
            return null;
        }
        int maxVotes = 0;
        for (Line line : lines) {
            maxVotes = Math.max(maxVotes, line.votes);
        }
        int rows0 = UNKNOWN;
        int rows1 = UNKNOWN;
        int level = UNKNOWN;
        int columns = UNKNOWN;
        Line[] byRow = new Line[MAX_ROWS];
        for (Line line : lines) {
            if (line.votes != maxVotes || line.row < 0 || line.row >= MAX_ROWS) {
                continue;
            }
            int meta = Math.floorMod(line.codes[0], 30);
            switch (line.cluster) {
                case 0 -> rows0 = agree(rows0, meta);
                case 3 -> {
                    rows1 = agree(rows1, meta % 3);
                    level = agree(level, meta / 3);
                }
                case 6 -> columns = agree(columns, meta + 1);
                default -> {
                    return null;
                }
            }
            Line previous = byRow[line.row];
            if (previous == null) {
                byRow[line.row] = line;
            }
        }
        if (rows0 < 0 || rows1 < 0 || level < 0 || columns < 0) {
            return null;
        }
        int rowCount = 3 * rows0 + rows1 + 1;
        int columnCount = columns;
        if (rowCount < 3 || rowCount > MAX_ROWS || columnCount < 1 || columnCount > 30 || level < 0 || level > 8) {
            return null;
        }
        int numEc = 1 << (level + 1);
        int total = rowCount * columnCount;
        if (total < 4 || numEc >= total || numEc < 2) {
            return null;
        }
        int[] codewords = new int[total];
        List<Integer> erasures = new ArrayList<>();
        for (int row = 0; row < rowCount; row++) {
            Line line = byRow[row];
            for (int column = 0; column < columnCount; column++) {
                int index = row * columnCount + column;
                int value = -1;
                if (line != null && column + 1 < line.count) {
                    value = line.codes[column + 1];
                }
                if (value < 0 || value >= Pdf417Symbols.MODULUS) {
                    codewords[index] = 0;
                    erasures.add(index);
                } else {
                    codewords[index] = value;
                }
            }
        }
        if (erasures.size() > numEc - 2) {
            return null;
        }
        int[] erasureIndex = new int[erasures.size()];
        for (int i = 0; i < erasures.size(); i++) {
            erasureIndex[i] = erasures.get(i);
        }
        try {
            ReedSolomon.correct(ReedSolomon.PDF417, codewords, numEc, erasureIndex);
        } catch (ReedSolomonException failed) {
            return null;
        }
        if (!verifyCount(codewords, numEc)) {
            return null;
        }
        try {
            return new Parser(codewords).decode();
        } catch (Format failed) {
            return null;
        }
    }

    private static int agree(int current, int value) {
        if (current == CONFLICT) {
            return CONFLICT;
        }
        if (current == UNKNOWN) {
            return value;
        }
        return current == value ? current : CONFLICT;
    }

    /** {@code VerifyCodewordCount}. The length descriptor excludes the error codewords. */
    private static boolean verifyCount(int[] codewords, int numEc) {
        if (codewords.length < 4) {
            return false;
        }
        int declared = codewords[0];
        if (declared > codewords.length) {
            return false;
        }
        if (declared + numEc != codewords.length) {
            if (numEc >= codewords.length) {
                return false;
            }
            codewords[0] = codewords.length - numEc;
        }
        return true;
    }

    /**
     * {@code PDFDecoder::Decode}. The codewords are already corrected. Null is a format error.
     * An empty string is a symbol whose bytes cannot be turned into text, such as a charset
     * assignment above 899.
     */
    static String decodeData(int[] codewords) {
        if (codewords == null) {
            return null;
        }
        try {
            return new Parser(codewords).decode();
        } catch (Format failed) {
            return null;
        }
    }

    /**
     * {@code DecodeCodewords}. Corrects {@code codewords} in place. A checksum failure throws.
     * A format failure returns null. The ambiguous-value search lives in the photo scanner.
     */
    static String decodeCodewords(int[] codewords, int numEc, int[] erasures) throws ReedSolomonException {
        Decoded decoded = decodeCodewordsContent(codewords, numEc, erasures);
        return decoded == null ? null : decoded.text();
    }

    /** {@link #decodeCodewords} with the content bytes. */
    static Decoded decodeCodewordsContent(int[] codewords, int numEc, int[] erasures) throws ReedSolomonException {
        if (codewords.length == 0) {
            return null;
        }
        // ReedSolomonDecode: two error correction codewords have to stay to find or detect an error.
        // A damaged row indicator can also give level 9, 1024 codewords, more than any symbol has.
        if (erasures.length > numEc - 2 || numEc > MAX_EC_CODEWORDS) {
            throw new ReedSolomonException("PDF417 error correction failed");
        }
        int used = ReedSolomon.correct(ReedSolomon.PDF417, codewords, numEc, erasures);
        // The two have to stay after the errors found beside the erasures too: with every
        // codeword spent, a misread row corrects to some other codeword just as well.
        if (erasures.length > 0 && used > numEc - 2) {
            throw new ReedSolomonException("PDF417 error correction failed");
        }
        if (!verifyCount(codewords, numEc)) {
            return null;
        }
        try {
            Parser parser = new Parser(codewords);
            String text = parser.decode();
            return text == null ? null : new Decoded(text, parser.text.raw());
        } catch (Format failed) {
            return null;
        }
    }

    private static Box bounds(boolean[][] image) {
        int top = image.length;
        int bottom = -1;
        int left = image[0].length;
        int right = -1;
        for (int y = 0; y < image.length; y++) {
            for (int x = 0; x < image[0].length; x++) {
                if (image[y][x]) {
                    top = Math.min(top, y);
                    bottom = Math.max(bottom, y);
                    left = Math.min(left, x);
                    right = Math.max(right, x);
                }
            }
        }
        if (bottom < 0) {
            return null;
        }
        return new Box(left, top, right, bottom);
    }

    private record Box(int left, int top, int right, int bottom) {
        int width() {
            return right - left + 1;
        }

        int height() {
            return bottom - top + 1;
        }
    }

    private record Line(int row, int cluster, int votes, int count, int[] codes) {
    }

    private enum SubMode {
        ALPHA, LOWER, MIXED, PUNCT, ALPHA_SHIFT, PUNCT_SHIFT
    }

    private static final class Format extends RuntimeException {
        @Override
        public synchronized Throwable fillInStackTrace() {
            return this;
        }
    }

    /** High-level codeword parser. Macro fields stay out of the text, as in {@code PDFDecoder.cpp}. */
    private static final class Parser {
        private final int[] codewords;
        private final Out text = new Out();
        private Out out;

        Parser(int[] codewords) {
            this.codewords = codewords;
            this.out = text;
        }

        String decode() {
            if (codewords.length == 0 || codewords[0] < 1 || codewords[0] > codewords.length) {
                throw new Format();
            }
            int guard = codewords.length + 1;
            for (int codeIndex = 1; codeIndex < codewords[0]; ) {
                if (guard-- == 0) {
                    throw new Format();
                }
                int code = codewords[codeIndex++];
                codeIndex = switch (code) {
                    case TEXT_LATCH -> textCompaction(codeIndex);
                    case BYTE_SHIFT -> textCompaction(codeIndex - 1);
                    case BYTE_LATCH, BYTE_LATCH_6 -> byteCompaction(code, codeIndex);
                    case NUMERIC_LATCH -> numericCompaction(codeIndex);
                    case ECI_CHARSET, ECI_GENERAL, ECI_USER -> processEci(codeIndex, code);
                    case MACRO_BEGIN -> macroBlock(codeIndex);
                    case MACRO_OPTIONAL, MACRO_TERMINATOR -> throw new Format();
                    case READER_INIT -> {
                        if (codeIndex != 2) {
                            throw new Format();
                        }
                        yield codeIndex;
                    }
                    case 920 -> {
                        // GS1 composite linkage is legal only as the first codeword after the length.
                        if (codeIndex != 2) {
                            throw new Format();
                        }
                        yield codeIndex;
                    }
                    case LINKAGE_OTHER -> throw new Format();
                    default -> {
                        if (code >= TEXT_LATCH) {
                            throw new Format();
                        }
                        yield textCompaction(codeIndex - 1);
                    }
                };
            }
            return text.finish();
        }

        private int textCompaction(int codeIndex) {
            int remaining = codewords[0] - codeIndex;
            if (remaining < 0) {
                throw new Format();
            }
            int[] data = new int[remaining * 2];
            int index = 0;
            boolean end = false;
            while (codeIndex < codewords[0] && !end) {
                int code = codewords[codeIndex++];
                if (code < TEXT_LATCH) {
                    data[index] = code / 30;
                    data[index + 1] = code % 30;
                    index += 2;
                } else if (code == BYTE_SHIFT) {
                    data[index++] = BYTE_SHIFT;
                    while (codeIndex < codewords[0] && isEci(codewords[codeIndex])) {
                        int marker = codewords[codeIndex++];
                        index = copyTextEci(data, index, marker);
                        if (codeIndex < codewords[0]) {
                            data[index++] = codewords[codeIndex++];
                            if (codeIndex < codewords[0] && marker == ECI_GENERAL) {
                                data[index++] = codewords[codeIndex++];
                            }
                        }
                    }
                    if (codeIndex < codewords[0]) {
                        data[index++] = codewords[codeIndex++];
                    }
                } else if (isEci(code)) {
                    index = copyTextEci(data, index, code);
                    if (codeIndex < codewords[0]) {
                        data[index++] = codewords[codeIndex++];
                        if (codeIndex < codewords[0] && code == ECI_GENERAL) {
                            data[index++] = codewords[codeIndex++];
                        }
                    }
                } else if (terminates(code)) {
                    codeIndex--;
                    end = true;
                } else {
                    throw new Format();
                }
            }
            decodeText(data, index);
            return codeIndex;
        }

        /** Stores the ECI opcode. The following parameter codewords are copied by the caller. */
        private int copyTextEci(int[] data, int index, int code) {
            data[index] = code;
            return index + 1;
        }

        private void decodeText(int[] data, int length) {
            SubMode mode = SubMode.ALPHA;
            SubMode prior = SubMode.ALPHA;
            int i = 0;
            while (i < length) {
                int value = data[i];
                if (isEci(value)) {
                    i = applyTextEci(data, i + 1, length, value);
                    continue;
                }
                if (value == BYTE_SHIFT) {
                    i++;
                    while (i < length && isEci(data[i])) {
                        i = applyTextEci(data, i + 1, length, data[i]);
                    }
                    if (i < length) {
                        out.push(data[i++]);
                    }
                    continue;
                }
                char ch = 0;
                switch (mode) {
                    case ALPHA, LOWER -> {
                        if (value < 26) {
                            ch = (char) ((mode == SubMode.ALPHA ? 'A' : 'a') + value);
                        } else if (value == 26) {
                            ch = ' ';
                        } else if (value == 27 && mode == SubMode.ALPHA) {
                            mode = SubMode.LOWER;
                        } else if (value == 27) {
                            prior = mode;
                            mode = SubMode.ALPHA_SHIFT;
                        } else if (value == 28) {
                            mode = SubMode.MIXED;
                        } else if (i + 1 < length && data[i + 1] != BYTE_SHIFT) {
                            prior = mode;
                            mode = SubMode.PUNCT_SHIFT;
                        }
                    }
                    case MIXED -> {
                        if (value < 25) {
                            ch = MIXED.charAt(value);
                        } else if (value == 25) {
                            mode = SubMode.PUNCT;
                        } else if (value == 26) {
                            ch = ' ';
                        } else if (value == 27) {
                            mode = SubMode.LOWER;
                        } else if (value == 28) {
                            mode = SubMode.ALPHA;
                        } else if (i + 1 < length && data[i + 1] != BYTE_SHIFT) {
                            prior = mode;
                            mode = SubMode.PUNCT_SHIFT;
                        }
                    }
                    case PUNCT -> {
                        if (value < 29) {
                            ch = PUNCT.charAt(value);
                        } else {
                            mode = SubMode.ALPHA;
                        }
                    }
                    case ALPHA_SHIFT -> {
                        mode = prior;
                        if (value < 26) {
                            ch = (char) ('A' + value);
                        } else if (value == 26) {
                            ch = ' ';
                        }
                    }
                    case PUNCT_SHIFT -> {
                        mode = prior;
                        if (value < 29) {
                            ch = PUNCT.charAt(value);
                        } else {
                            mode = SubMode.ALPHA;
                        }
                    }
                }
                if (ch != 0) {
                    out.push(ch);
                }
                i++;
            }
        }

        private int applyTextEci(int[] data, int codeIndex, int length, int code) {
            if (!isEci(code) || codeIndex >= length) {
                return codeIndex;
            }
            if (code == ECI_CHARSET) {
                out.eci(data[codeIndex++]);
            } else {
                int params = code == ECI_GENERAL ? 2 : 1;
                if (codeIndex + params > length) {
                    return codeIndex;
                }
                codeIndex += params;
            }
            return codeIndex;
        }

        private int byteCompaction(int mode, int codeIndex) {
            int[] trailing = new int[1];
            int batches = countByteBatches(mode, codeIndex, trailing);
            codeIndex = processByteEcis(codeIndex);
            for (int batch = 0; batch < batches; batch++) {
                long value = 0;
                for (int count = 0; count < 5; count++) {
                    if (codeIndex >= codewords[0] || codewords[codeIndex] >= TEXT_LATCH) {
                        throw new Format();
                    }
                    value = 900L * value + codewords[codeIndex++];
                }
                for (int shift = 5; shift >= 0; shift--) {
                    out.push((int) (value >> (8 * shift)));
                }
                codeIndex = processByteEcis(codeIndex);
            }
            for (int i = 0; i < trailing[0] && codeIndex < codewords[0]; i++) {
                out.push(codewords[codeIndex++]);
                codeIndex = processByteEcis(codeIndex);
            }
            return codeIndex;
        }

        private int countByteBatches(int mode, int codeIndex, int[] trailing) {
            int count = 0;
            trailing[0] = 0;
            while (codeIndex < codewords[0]) {
                int code = codewords[codeIndex++];
                if (code >= TEXT_LATCH) {
                    if (mode == BYTE_LATCH_6 && count % 5 != 0) {
                        throw new Format();
                    }
                    if (isEci(code)) {
                        codeIndex += code == ECI_GENERAL ? 2 : 1;
                        continue;
                    }
                    if (!terminates(code)) {
                        throw new Format();
                    }
                    break;
                }
                count++;
            }
            if (codeIndex > codewords[0]) {
                throw new Format();
            }
            if (count == 0) {
                return 0;
            }
            if (mode == BYTE_LATCH) {
                trailing[0] = count % 5;
                if (trailing[0] == 0) {
                    trailing[0] = 5;
                    count -= 5;
                }
            } else if (count % 5 != 0) {
                throw new Format();
            }
            return count / 5;
        }

        private int processByteEcis(int codeIndex) {
            while (codeIndex < codewords[0] && codewords[codeIndex] >= TEXT_LATCH && !terminates(codewords[codeIndex])) {
                int code = codewords[codeIndex++];
                if (!isEci(code)) {
                    throw new Format();
                }
                codeIndex = processEci(codeIndex, code);
            }
            return codeIndex;
        }

        private int numericCompaction(int codeIndex) {
            int count = 0;
            while (codeIndex < codewords[0]) {
                int code = codewords[codeIndex];
                if (code < TEXT_LATCH) {
                    count++;
                    codeIndex++;
                }
                if (count > 0 && (count == MAX_NUMERIC || codeIndex == codewords[0] || code >= TEXT_LATCH)) {
                    out.append(base900(codeIndex, count));
                    count = 0;
                }
                if (code >= TEXT_LATCH) {
                    if (isEci(code)) {
                        codeIndex = processEci(codeIndex + 1, code);
                    } else if (terminates(code)) {
                        break;
                    } else {
                        throw new Format();
                    }
                }
            }
            return codeIndex;
        }

        private int macroBlock(int codeIndex) {
            if (codeIndex + 2 > codewords[0]) {
                throw new Format();
            }
            parseLong(base900(codeIndex + 2, 2));
            codeIndex += 2;
            while (codeIndex < codewords[0]
                    && codewords[codeIndex] != MACRO_TERMINATOR
                    && codewords[codeIndex] != MACRO_OPTIONAL) {
                codeIndex++;
            }
            while (codeIndex < codewords[0]) {
                int code = codewords[codeIndex];
                if (code == MACRO_OPTIONAL) {
                    codeIndex++;
                    if (codeIndex >= codewords[0]) {
                        break;
                    }
                    int field = codewords[codeIndex];
                    Out scratch = new Out();
                    Out saved = out;
                    out = scratch;
                    try {
                        codeIndex = switch (field) {
                            case 0, 3, 4 -> textCompaction(codeIndex + 1);
                            case 1, 2, 5, 6 -> numericField(codeIndex + 1);
                            default -> throw new Format();
                        };
                    } finally {
                        out = saved;
                    }
                } else if (code == MACRO_TERMINATOR) {
                    codeIndex++;
                } else {
                    throw new Format();
                }
            }
            return codeIndex;
        }

        /** Numeric macro metadata is checked and then dropped. It is not part of the symbol text. */
        private int numericField(int codeIndex) {
            int next = numericCompaction(codeIndex);
            String digits = out.finish();
            if (digits == null) {
                throw new Format();
            }
            parseLong(digits);
            return next;
        }

        private int processEci(int codeIndex, int code) {
            if (!isEci(code) || codeIndex >= codewords[0]) {
                return codeIndex;
            }
            if (code == ECI_CHARSET) {
                out.eci(codewords[codeIndex]);
                return codeIndex + 1;
            }
            int params = code == ECI_GENERAL ? 2 : 1;
            if (codeIndex + params > codewords[0]) {
                return codeIndex;
            }
            return codeIndex + params;
        }

        private String base900(int end, int count) {
            if (count <= 0 || count > 16 || end < count) {
                throw new Format();
            }
            BigInteger value = BigInteger.ZERO;
            BigInteger nineHundred = BigInteger.valueOf(900);
            for (int i = 0; i < count; i++) {
                int code = codewords[end - count + i];
                if (code < 0 || code >= TEXT_LATCH) {
                    throw new Format();
                }
                value = value.multiply(nineHundred).add(BigInteger.valueOf(code));
            }
            String decoded = value.toString();
            if (decoded.isEmpty() || decoded.charAt(0) != '1') {
                throw new Format();
            }
            return decoded.substring(1);
        }
    }

    private static void parseLong(String digits) {
        if (digits.isEmpty()) {
            throw new Format();
        }
        try {
            Long.parseLong(digits);
        } catch (NumberFormatException bad) {
            throw new Format();
        }
    }

    private static boolean isEci(int code) {
        return code >= ECI_USER && code <= ECI_CHARSET;
    }

    private static boolean terminates(int code) {
        return code == TEXT_LATCH || code == BYTE_LATCH || code == NUMERIC_LATCH || code == BYTE_LATCH_6
                || code == MACRO_BEGIN || code == MACRO_OPTIONAL || code == MACRO_TERMINATOR;
    }

    /**
     * A guessed character set without any ECI, ISO-8859-1 before the first one. The first ECI keeps bytes already written in Latin-1,
     * matching {@code Content::ForEachECIBlock} when {@code hasECI} is set.
     */
    private static final class Out {
        private final List<Integer> bytes = new ArrayList<>();
        private final List<Integer> marks = new ArrayList<>();
        private final List<Integer> sets = new ArrayList<>();
        private boolean hasEci;
        /** A charset assignment above 899 makes the whole text empty. The bytes stay. */
        private boolean unprocessable;

        void push(int value) {
            bytes.add(value & 0xFF);
        }

        byte[] raw() {
            byte[] raw = new byte[bytes.size()];
            for (int i = 0; i < raw.length; i++) {
                raw[i] = (byte) (int) bytes.get(i);
            }
            return raw;
        }

        void append(String value) {
            for (int i = 0; i < value.length(); i++) {
                push(value.charAt(i));
            }
        }

        void eci(int eci) {
            if (eci > 899) {
                unprocessable = true;
            }
            if (!hasEci) {
                marks.clear();
                sets.clear();
            }
            hasEci = true;
            marks.add(bytes.size());
            sets.add(eci);
        }

        String finish() {
            if (bytes.isEmpty()) {
                return null;
            }
            if (unprocessable) {
                return "";
            }
            byte[] raw = new byte[bytes.size()];
            for (int i = 0; i < raw.length; i++) {
                raw[i] = bytes.get(i).byteValue();
            }
            if (!hasEci) {
                return TextDecoder.decodeOrLatin1(raw, TextDecoder.guessUtf8OrLatin1(raw));
            }
            StringBuilder builder = new StringBuilder();
            if (marks.get(0) > 0) {
                builder.append(new String(raw, 0, marks.get(0), StandardCharsets.ISO_8859_1));
            }
            for (int i = 0; i < marks.size(); i++) {
                int start = marks.get(i);
                int end = i + 1 < marks.size() ? marks.get(i + 1) : raw.length;
                if (start != end) {
                    builder.append(TextDecoder.decodeOrLatin1(Arrays.copyOfRange(raw, start, end), sets.get(i)));
                }
            }
            return builder.isEmpty() ? null : builder.toString();
        }
    }
}
