package io.github.kamilkrzywanski.barcode.image;

import java.awt.image.BufferedImage;

/** Grayscale conversions between {@link BufferedImage} and {@link GrayU8}. */
public final class GrayImages {
    private GrayImages() {
    }

    /**
     * The gray the scan ladder works on: {@code (77 R + 150 G + 29 B + 128) >> 8} of what
     * {@link BufferedImage#getRGB} returns. Alpha is ignored.
     */
    public static GrayU8 from(BufferedImage image) {
        return convert(image, false).gray();
    }

    /**
     * Both grays of {@link #convert(BufferedImage, boolean)}, from one pass over the image.
     *
     * @param gray  {@link #from(BufferedImage)}
     * @param zxing the bytes ZXing's {@code BufferedImageLuminanceSource} holds for the same image,
     *              or {@code null} when not asked for
     */
    public record Converted(GrayU8 gray, GrayU8 zxing) {
    }

    /**
     * {@link #from(BufferedImage)}, and with {@code withZxing} also the luminance ZXing's
     * {@code BufferedImageLuminanceSource} would build: {@code (306 R + 601 G + 117 B + 512) >> 10},
     * a fully transparent pixel as white, and the raw samples of an 8-bit gray image.
     */
    public static Converted convert(BufferedImage image, boolean withZxing) {
        int width = image.getWidth();
        int height = image.getHeight();
        GrayU8 gray = new GrayU8(width, height);
        GrayU8 zxing = withZxing ? new GrayU8(width, height) : null;
        RgbRows rows = new RgbRows(image);
        boolean rawGray = withZxing && rows.isByteGray();
        int[] argb = new int[width];
        byte[] raw = rawGray ? new byte[width] : null;
        byte[] out = gray.data;
        byte[] zout = withZxing ? zxing.data : null;
        for (int y = 0, row = 0; y < height; y++, row += width) {
            rows.read(y, argb);
            for (int x = 0; x < width; x++) {
                out[row + x] = (byte) luma(argb[x]);
            }
            if (rawGray) {
                rows.readGray(y, raw);
                System.arraycopy(raw, 0, zout, row, width);
            } else if (withZxing) {
                for (int x = 0; x < width; x++) {
                    zout[row + x] = (byte) zxingLuma(argb[x]);
                }
            }
        }
        return new Converted(gray, zxing);
    }

    /** Stretches the 1st–99th percentile range to 0..255. Returns {@code gray} when contrast is already tiny. */
    public static GrayU8 stretch(GrayU8 gray) {
        int[] hist = new int[256];
        int total = gray.width * gray.height;
        for (int y = 0; y < gray.height; y++) {
            for (int x = 0; x < gray.width; x++) {
                hist[gray.get(x, y)]++;
            }
        }
        int lowCount = Math.max(1, total / 100);
        int lo = 0;
        int seen = 0;
        for (int i = 0; i < 256; i++) {
            seen += hist[i];
            if (seen >= lowCount) {
                lo = i;
                break;
            }
        }
        int hi = 255;
        seen = 0;
        for (int i = 255; i >= 0; i--) {
            seen += hist[i];
            if (seen >= lowCount) {
                hi = i;
                break;
            }
        }
        if (hi - lo < 8) {
            return gray;
        }
        GrayU8 out = new GrayU8(gray.width, gray.height);
        double scale = 255.0 / (hi - lo);
        for (int y = 0; y < gray.height; y++) {
            for (int x = 0; x < gray.width; x++) {
                int value = (int) Math.round((gray.get(x, y) - lo) * scale);
                out.set(x, y, clamp(value));
            }
        }
        return out;
    }

    /**
     * Removes a slow illumination field by subtracting a wide box blur and expanding the residual
     * around mid-gray. Helps glare and uneven lighting before a global binarizer sees the image.
     */
    public static GrayU8 flattenIllumination(GrayU8 gray) {
        int radius = Math.max(8, Math.min(gray.width, gray.height) / 16);
        GrayU8 blur = new GrayU8(gray.width, gray.height);
        BoxMean.mean(gray, blur, radius);
        GrayU8 out = new GrayU8(gray.width, gray.height);
        for (int y = 0; y < gray.height; y++) {
            for (int x = 0; x < gray.width; x++) {
                int residual = gray.get(x, y) - blur.get(x, y);
                out.set(x, y, clamp(128 + residual * 3));
            }
        }
        return out;
    }

    /**
     * 3×3 median. The QR trace uses this only after local average missed, so salt-and-pepper
     * can be retried without changing finder thresholds. Edge pixels repeat the border.
     * Interior pixels skip the clamp. The nine samples stay in the same order as a
     * dy/dx scan, and the median is the middle value after the same insertion sort.
     */
    public static GrayU8 median3(GrayU8 gray) {
        GrayU8 out = new GrayU8(gray.width, gray.height);
        byte[] src = gray.data;
        int base = gray.startIndex;
        int stride = gray.stride;
        int width = gray.width;
        int height = gray.height;
        byte[] dst = out.data;
        int[] window = new int[9];
        for (int y = 0; y < height; y++) {
            int top = y == 0 ? base : base + (y - 1) * stride;
            int mid = base + y * stride;
            int bot = y == height - 1 ? mid : mid + stride;
            int dest = out.startIndex + y * out.stride;
            if (y == 0 || y == height - 1 || width < 3) {
                for (int x = 0; x < width; x++) {
                    dst[dest + x] = (byte) medianClamped(src, top, mid, bot, width, x, window);
                }
                continue;
            }
            dst[dest] = (byte) medianClamped(src, top, mid, bot, width, 0, window);
            for (int x = 1; x < width - 1; x++) {
                window[0] = src[top + x - 1] & 255;
                window[1] = src[top + x] & 255;
                window[2] = src[top + x + 1] & 255;
                window[3] = src[mid + x - 1] & 255;
                window[4] = src[mid + x] & 255;
                window[5] = src[mid + x + 1] & 255;
                window[6] = src[bot + x - 1] & 255;
                window[7] = src[bot + x] & 255;
                window[8] = src[bot + x + 1] & 255;
                dst[dest + x] = (byte) median9(window);
            }
            dst[dest + width - 1] = (byte) medianClamped(src, top, mid, bot, width, width - 1, window);
        }
        return out;
    }

    private static int medianClamped(byte[] src, int top, int mid, int bot, int width, int x, int[] window) {
        int x0 = x == 0 ? 0 : x - 1;
        int x2 = x == width - 1 ? x : x + 1;
        window[0] = src[top + x0] & 255;
        window[1] = src[top + x] & 255;
        window[2] = src[top + x2] & 255;
        window[3] = src[mid + x0] & 255;
        window[4] = src[mid + x] & 255;
        window[5] = src[mid + x2] & 255;
        window[6] = src[bot + x0] & 255;
        window[7] = src[bot + x] & 255;
        window[8] = src[bot + x2] & 255;
        return median9(window);
    }

    /** Middle of nine after insertion sort. {@code window} is overwritten. */
    private static int median9(int[] window) {
        for (int i = 1; i < 9; i++) {
            int value = window[i];
            int j = i - 1;
            while (j >= 0 && window[j] > value) {
                window[j + 1] = window[j];
                j--;
            }
            window[j + 1] = value;
        }
        return window[4];
    }

    public static GrayU8 invert(GrayU8 gray) {
        GrayU8 out = new GrayU8(gray.width, gray.height);
        for (int y = 0; y < gray.height; y++) {
            for (int x = 0; x < gray.width; x++) {
                out.set(x, y, 255 - gray.get(x, y));
            }
        }
        return out;
    }

    /**
     * Horizontal unsharp mask. A box blur of {@code radius} is subtracted, scaled by {@code amount}.
     * Vertical edges stay put, so a deskewed barcode keeps its bar heights while thin bars separate.
     * Flat rows are unchanged. Values clamp to 0..255.
     */
    public static GrayU8 unsharpHorizontal(GrayU8 gray, int radius, double amount) {
        if (radius < 1) {
            throw new IllegalArgumentException("radius must be at least 1");
        }
        GrayU8 out = new GrayU8(gray.width, gray.height);
        int width = gray.width;
        int[] row = new int[width];
        long[] prefix = new long[width + 1];
        for (int y = 0; y < gray.height; y++) {
            for (int x = 0; x < width; x++) {
                row[x] = gray.get(x, y);
                prefix[x + 1] = prefix[x] + row[x];
            }
            for (int x = 0; x < width; x++) {
                int left = Math.max(0, x - radius);
                int right = Math.min(width - 1, x + radius);
                int blur = (int) ((prefix[right + 1] - prefix[left]) / (right - left + 1));
                int value = (int) Math.round(row[x] + amount * (row[x] - blur));
                out.set(x, y, clamp(value));
            }
        }
        return out;
    }

    /** Binary images use 0 and 1, with 1 as the dark foreground. ZXing wants 0 dark and 255 light. */
    public static GrayU8 binaryToLuma(GrayU8 binary) {
        GrayU8 out = new GrayU8(binary.width, binary.height);
        for (int y = 0; y < binary.height; y++) {
            for (int x = 0; x < binary.width; x++) {
                out.set(x, y, binary.get(x, y) == 0 ? 255 : 0);
            }
        }
        return out;
    }

    static int clamp(int value) {
        if (value < 0) {
            return 0;
        }
        return Math.min(value, 255);
    }

    private static int zxingLuma(int argb) {
        if ((argb & 0xFF000000) == 0) {
            return 0xFF;
        }
        return (306 * ((argb >> 16) & 0xFF) + 601 * ((argb >> 8) & 0xFF) + 117 * (argb & 0xFF) + 0x200) >> 10;
    }

    private static int luma(int rgb) {
        int r = (rgb >>> 16) & 0xFF;
        int g = (rgb >>> 8) & 0xFF;
        int b = rgb & 0xFF;
        return (r * 77 + g * 150 + b * 29 + 128) >> 8;
    }
}
