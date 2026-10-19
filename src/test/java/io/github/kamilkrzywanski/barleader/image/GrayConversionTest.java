package io.github.kamilkrzywanski.barleader.image;

import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link GrayImages#convert} reads common rasters row by row instead of through {@code getRGB}.
 * Both of its grays must stay byte for byte what the per-pixel paths produced: the ladder gray
 * from {@code getRGB}, and the ZXing luminance from {@code BufferedImageLuminanceSource}.
 */
class GrayConversionTest {
    private static final int[] TYPES = {
            BufferedImage.TYPE_INT_RGB,
            BufferedImage.TYPE_INT_ARGB,
            BufferedImage.TYPE_INT_ARGB_PRE,
            BufferedImage.TYPE_INT_BGR,
            BufferedImage.TYPE_3BYTE_BGR,
            BufferedImage.TYPE_4BYTE_ABGR,
            BufferedImage.TYPE_4BYTE_ABGR_PRE,
            BufferedImage.TYPE_BYTE_GRAY,
            BufferedImage.TYPE_USHORT_GRAY,
            BufferedImage.TYPE_USHORT_565_RGB,
            BufferedImage.TYPE_USHORT_555_RGB,
            BufferedImage.TYPE_BYTE_INDEXED,
            BufferedImage.TYPE_BYTE_BINARY
    };

    @Test
    void matchesGetRgbAndZxingOnEveryImageType() {
        Random random = new Random(7);
        for (int type : TYPES) {
            BufferedImage image = noisy(type, 37, 23, random);
            assertSame(image, "type " + type);
            assertSame(image.getSubimage(5, 3, 21, 17), "sub-image of type " + type);
        }
    }

    @Test
    void transparentPixelsAreWhiteOnlyForZxing() {
        BufferedImage image = new BufferedImage(8, 4, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(1, 1, 0x00000000);
        image.setRGB(2, 1, 0x80102030);
        assertSame(image, "transparent ARGB");
    }

    @Test
    void nonSrgbColourSpaceFallsBackToGetRgb() {
        ComponentColorModel model = new ComponentColorModel(
                ColorSpace.getInstance(ColorSpace.CS_LINEAR_RGB), false, false, Transparency.OPAQUE, DataBuffer.TYPE_BYTE);
        WritableRaster raster = Raster.createInterleavedRaster(DataBuffer.TYPE_BYTE, 19, 11, 3, null);
        Random random = new Random(3);
        for (int y = 0; y < 11; y++) {
            for (int x = 0; x < 19; x++) {
                raster.setPixel(x, y, new int[]{random.nextInt(256), random.nextInt(256), random.nextInt(256)});
            }
        }
        assertSame(new BufferedImage(model, raster, false, null), "linear RGB");
    }

    @Test
    void withoutZxingOnlyTheLadderGrayIsBuilt() {
        BufferedImage image = noisy(BufferedImage.TYPE_3BYTE_BGR, 9, 9, new Random(1));
        GrayImages.Converted converted = GrayImages.convert(image, false);
        assertArrayEquals(perPixelGray(image), converted.gray().data);
        assertNull(converted.zxing());
    }

    private static void assertSame(BufferedImage image, String label) {
        GrayImages.Converted converted = GrayImages.convert(image, true);
        assertArrayEquals(perPixelGray(image), converted.gray().data, label + " gray");
        assertArrayEquals(new BufferedImageLuminanceSource(image).getMatrix(), converted.zxing().data, label + " zxing");
        assertArrayEquals(perPixelGray(image), GrayImages.from(image).data, label + " from");
    }

    /** The conversion before the row reader: getRGB per pixel, then 77/150/29 and alpha ignored. */
    private static byte[] perPixelGray(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        byte[] out = new byte[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = image.getRGB(x, y);
                int r = (rgb >>> 16) & 0xFF;
                int g = (rgb >>> 8) & 0xFF;
                int b = rgb & 0xFF;
                out[y * width + x] = (byte) ((r * 77 + g * 150 + b * 29 + 128) >> 8);
            }
        }
        return out;
    }

    private static BufferedImage noisy(int type, int width, int height, Random random) {
        BufferedImage image = new BufferedImage(width, height, type);
        Graphics2D graphics = image.createGraphics();
        try {
            for (int i = 0; i < 40; i++) {
                graphics.setColor(new Color(random.nextInt(256), random.nextInt(256), random.nextInt(256), random.nextInt(256)));
                graphics.fillRect(random.nextInt(width), random.nextInt(height), 1 + random.nextInt(width), 1 + random.nextInt(height));
            }
        } finally {
            graphics.dispose();
        }
        for (int i = 0; i < width * height / 3; i++) {
            image.setRGB(random.nextInt(width), random.nextInt(height), random.nextInt());
        }
        return image;
    }
}
