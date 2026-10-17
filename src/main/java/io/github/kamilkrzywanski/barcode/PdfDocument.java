/*
 * Copyright 2026 Kamil Krzywański
 */
// SPDX-License-Identifier: Apache-2.0
package io.github.kamilkrzywanski.barcode;

import java.awt.geom.PathIterator;
import java.awt.geom.Rectangle2D;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.DeflaterOutputStream;

/**
 * A one-page PDF of a {@link Drawing}: the page the size of the drawing, the bars as rectangles and
 * the text as filled outlines, so it needs no font. PDF 1.4 with a compressed content stream.
 */
final class PdfDocument {
    private static final double POINTS_PER_MILLIMETRE = 72 / 25.4;

    private PdfDocument() {
    }

    static byte[] write(Drawing drawing, double moduleMillimetres, String title) {
        double scale = moduleMillimetres * POINTS_PER_MILLIMETRE;
        double pageWidth = drawing.width * scale;
        double pageHeight = drawing.height * scale;
        StringBuilder content = new StringBuilder();
        content.append("1 g 0 0 ").append(number(pageWidth)).append(' ').append(number(pageHeight)).append(" re f\n");
        // modules from here on, y down from the top left corner as in the drawing
        String exact = String.format(java.util.Locale.ROOT, "%.6f", scale);
        content.append(exact).append(" 0 0 -").append(exact).append(" 0 ").append(number(pageHeight)).append(" cm\n0 g\n");
        for (Rectangle2D.Double dark : drawing.dark) {
            content.append(number(dark.x)).append(' ').append(number(dark.y)).append(' ')
                    .append(number(dark.width)).append(' ').append(number(dark.height)).append(" re\n");
        }
        if (!drawing.dark.isEmpty()) {
            content.append("f\n");
        }
        if (drawing.hasText()) {
            PathIterator path = drawing.text.getPathIterator(null);
            double[] at = new double[6];
            double lastX = 0;
            double lastY = 0;
            for (; !path.isDone(); path.next()) {
                switch (path.currentSegment(at)) {
                    case PathIterator.SEG_MOVETO -> {
                        content.append(number(at[0])).append(' ').append(number(at[1])).append(" m\n");
                        lastX = at[0];
                        lastY = at[1];
                    }
                    case PathIterator.SEG_LINETO -> {
                        content.append(number(at[0])).append(' ').append(number(at[1])).append(" l\n");
                        lastX = at[0];
                        lastY = at[1];
                    }
                    case PathIterator.SEG_QUADTO -> {
                        // PDF has cubic curves only: the control points two thirds of the way to the quadratic one
                        content.append(number(lastX + 2 * (at[0] - lastX) / 3)).append(' ')
                                .append(number(lastY + 2 * (at[1] - lastY) / 3)).append(' ')
                                .append(number(at[2] + 2 * (at[0] - at[2]) / 3)).append(' ')
                                .append(number(at[3] + 2 * (at[1] - at[3]) / 3)).append(' ')
                                .append(number(at[2])).append(' ').append(number(at[3])).append(" c\n");
                        lastX = at[2];
                        lastY = at[3];
                    }
                    case PathIterator.SEG_CUBICTO -> {
                        content.append(number(at[0])).append(' ').append(number(at[1])).append(' ')
                                .append(number(at[2])).append(' ').append(number(at[3])).append(' ')
                                .append(number(at[4])).append(' ').append(number(at[5])).append(" c\n");
                        lastX = at[4];
                        lastY = at[5];
                    }
                    default -> content.append("h\n");
                }
            }
            content.append(drawing.text.getWindingRule() == PathIterator.WIND_EVEN_ODD ? "f*\n" : "f\n");
        }
        byte[] stream = deflate(content.toString().getBytes(StandardCharsets.US_ASCII));

        ByteArrayOutputStream out = new ByteArrayOutputStream(stream.length + 1024);
        List<Integer> offsets = new ArrayList<>();
        ascii(out, "%PDF-1.4\n%âãÏÓ\n");
        object(out, offsets, "<< /Type /Catalog /Pages 2 0 R >>");
        object(out, offsets, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>");
        object(out, offsets, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " + number(pageWidth) + " " + number(pageHeight)
                + "] /Resources << >> /Contents 4 0 R >>");
        offsets.add(out.size());
        ascii(out, "4 0 obj\n<< /Length " + stream.length + " /Filter /FlateDecode >>\nstream\n");
        out.writeBytes(stream);
        ascii(out, "\nendstream\nendobj\n");
        object(out, offsets, "<< /Producer (barcode-pro) /Title " + textString(title) + " >>");
        int xref = out.size();
        StringBuilder table = new StringBuilder("xref\n0 " + (offsets.size() + 1) + "\n0000000000 65535 f \n");
        for (int offset : offsets) {
            table.append(String.format(java.util.Locale.ROOT, "%010d 00000 n \n", offset));
        }
        table.append("trailer\n<< /Size ").append(offsets.size() + 1).append(" /Root 1 0 R /Info 5 0 R >>\nstartxref\n")
                .append(xref).append("\n%%EOF\n");
        ascii(out, table.toString());
        return out.toByteArray();
    }

    private static void object(ByteArrayOutputStream out, List<Integer> offsets, String dictionary) {
        offsets.add(out.size());
        ascii(out, offsets.size() + " 0 obj\n" + dictionary + "\nendobj\n");
    }

    /** ISO-8859-1, so that the binary marker of the header is four bytes above 127. */
    private static void ascii(ByteArrayOutputStream out, String text) {
        out.writeBytes(text.getBytes(StandardCharsets.ISO_8859_1));
    }

    /** A text string in UTF-16 with its byte order mark, written in hexadecimal: any text, no escapes. */
    private static String textString(String text) {
        StringBuilder hex = new StringBuilder("<FEFF");
        for (byte b : text.getBytes(StandardCharsets.UTF_16BE)) {
            hex.append(String.format(java.util.Locale.ROOT, "%02X", b & 0xFF));
        }
        return hex.append('>').toString();
    }

    private static byte[] deflate(byte[] data) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(data.length / 4 + 64);
        try (DeflaterOutputStream deflater = new DeflaterOutputStream(bytes)) {
            deflater.write(data);
        } catch (java.io.IOException impossible) {
            throw new IllegalStateException(impossible);
        }
        return bytes.toByteArray();
    }

    private static String number(double value) {
        return Drawing.number(value);
    }
}
