package io.github.kamilkrzywanski.barcode.image;

import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.DirectColorModel;
import java.awt.image.Raster;

/**
 * Reads a {@link BufferedImage} one row at a time as the ARGB ints {@link BufferedImage#getRGB}
 * returns. {@code getRGB} converts every pixel through the colour model. The common sRGB rasters
 * that ImageIO and Java 2D produce are read row by row from the raster here, which gives the same
 * ints for a fraction of the cost. Any other image falls back to {@code getRGB}.
 */
final class RgbRows {
    private enum Layout {
        INT_RGB, INT_ARGB, BYTE_RGB, BYTE_RGBA, BYTE_GRAY, GENERIC
    }

    private final BufferedImage image;
    private final Raster raster;
    private final int width;
    private final Layout layout;
    private final int[] grayToArgb;
    private int[] intRow;
    private byte[] byteRow;

    RgbRows(BufferedImage image) {
        this.image = image;
        this.raster = image.getRaster();
        this.width = image.getWidth();
        this.layout = layout(image);
        this.grayToArgb = layout == Layout.BYTE_GRAY ? grayTable(image.getColorModel()) : null;
    }

    /** {@code true} for an 8-bit gray image whose raw samples {@link #readGray} returns. */
    boolean isByteGray() {
        return layout == Layout.BYTE_GRAY;
    }

    /** Raw samples of row {@code y} of an 8-bit gray image, without the colour model's gamma. */
    void readGray(int y, byte[] out) {
        raster.getDataElements(0, y, width, 1, out);
    }

    /** Row {@code y} into {@code argb}, exactly as {@code getRGB(0, y, width, 1, argb, 0, width)}. */
    void read(int y, int[] argb) {
        switch (layout) {
            case INT_RGB -> {
                raster.getDataElements(0, y, width, 1, argb);
                for (int x = 0; x < width; x++) {
                    argb[x] |= 0xFF000000;
                }
            }
            case INT_ARGB -> raster.getDataElements(0, y, width, 1, argb);
            case BYTE_RGB -> {
                byte[] row = byteRow(3);
                raster.getDataElements(0, y, width, 1, row);
                for (int x = 0, i = 0; x < width; x++, i += 3) {
                    argb[x] = 0xFF000000 | (row[i] & 0xFF) << 16 | (row[i + 1] & 0xFF) << 8 | (row[i + 2] & 0xFF);
                }
            }
            case BYTE_RGBA -> {
                byte[] row = byteRow(4);
                raster.getDataElements(0, y, width, 1, row);
                for (int x = 0, i = 0; x < width; x++, i += 4) {
                    argb[x] = (row[i + 3] & 0xFF) << 24 | (row[i] & 0xFF) << 16 | (row[i + 1] & 0xFF) << 8 | (row[i + 2] & 0xFF);
                }
            }
            case BYTE_GRAY -> {
                byte[] row = byteRow(1);
                raster.getDataElements(0, y, width, 1, row);
                for (int x = 0; x < width; x++) {
                    argb[x] = grayToArgb[row[x] & 0xFF];
                }
            }
            case GENERIC -> image.getRGB(0, y, width, 1, argb, 0, width);
        }
    }

    private byte[] byteRow(int bands) {
        if (byteRow == null) {
            byteRow = new byte[width * bands];
        }
        return byteRow;
    }

    private static Layout layout(BufferedImage image) {
        ColorModel model = image.getColorModel();
        if (!model.getColorSpace().isCS_sRGB() && image.getType() != BufferedImage.TYPE_BYTE_GRAY) {
            return Layout.GENERIC;
        }
        if (model.isAlphaPremultiplied()) {
            return Layout.GENERIC;
        }
        return switch (image.getType()) {
            case BufferedImage.TYPE_INT_RGB -> standardDirect(model, false) ? Layout.INT_RGB : Layout.GENERIC;
            case BufferedImage.TYPE_INT_ARGB -> standardDirect(model, true) ? Layout.INT_ARGB : Layout.GENERIC;
            case BufferedImage.TYPE_3BYTE_BGR -> standardComponent(model, 3) ? Layout.BYTE_RGB : Layout.GENERIC;
            case BufferedImage.TYPE_4BYTE_ABGR -> standardComponent(model, 4) ? Layout.BYTE_RGBA : Layout.GENERIC;
            case BufferedImage.TYPE_BYTE_GRAY -> standardComponent(model, 1) ? Layout.BYTE_GRAY : Layout.GENERIC;
            default -> Layout.GENERIC;
        };
    }

    private static boolean standardDirect(ColorModel model, boolean alpha) {
        return model instanceof DirectColorModel direct
                && direct.getTransferType() == DataBuffer.TYPE_INT
                && direct.getRedMask() == 0x00FF0000
                && direct.getGreenMask() == 0x0000FF00
                && direct.getBlueMask() == 0x000000FF
                && direct.getAlphaMask() == (alpha ? 0xFF000000 : 0);
    }

    private static boolean standardComponent(ColorModel model, int components) {
        if (!(model instanceof ComponentColorModel) || model.getTransferType() != DataBuffer.TYPE_BYTE) {
            return false;
        }
        if (model.getNumComponents() != components) {
            return false;
        }
        for (int bits : model.getComponentSize()) {
            if (bits != 8) {
                return false;
            }
        }
        return true;
    }

    /** What {@code getRGB} returns for each raw gray sample. The gray colour space is linear, so this is not identity. */
    private static int[] grayTable(ColorModel model) {
        int[] table = new int[256];
        byte[] sample = new byte[1];
        for (int value = 0; value < 256; value++) {
            sample[0] = (byte) value;
            table[value] = model.getRGB(sample);
        }
        return table;
    }
}
