package org.krzywanski.barcode.image;

import java.awt.image.BufferedImage;

/** Grayscale conversions between {@link BufferedImage} and {@link GrayU8}. */
public final class GrayImages {
    private GrayImages() {
    }

    public static GrayU8 from(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        GrayU8 out = new GrayU8(width, height);
        int[] rgb = image.getRGB(0, 0, width, height, new int[width * height], 0, width);
        if (packed(out)) {
            byte[] dest = out.data;
            for (int i = 0; i < rgb.length; i++) {
                dest[i] = (byte) luma(rgb[i]);
            }
            return out;
        }
        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                out.set(x, y, luma(rgb[row + x]));
            }
        }
        return out;
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
     */
    public static GrayU8 median3(GrayU8 gray) {
        GrayU8 out = new GrayU8(gray.width, gray.height);
        int[] window = new int[9];
        for (int y = 0; y < gray.height; y++) {
            for (int x = 0; x < gray.width; x++) {
                int n = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    int yy = y + dy;
                    if (yy < 0) {
                        yy = 0;
                    } else if (yy >= gray.height) {
                        yy = gray.height - 1;
                    }
                    for (int dx = -1; dx <= 1; dx++) {
                        int xx = x + dx;
                        if (xx < 0) {
                            xx = 0;
                        } else if (xx >= gray.width) {
                            xx = gray.width - 1;
                        }
                        window[n++] = gray.get(xx, yy);
                    }
                }
                for (int i = 1; i < window.length; i++) {
                    int value = window[i];
                    int j = i - 1;
                    while (j >= 0 && window[j] > value) {
                        window[j + 1] = window[j];
                        j--;
                    }
                    window[j + 1] = value;
                }
                out.set(x, y, window[4]);
            }
        }
        return out;
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

    private static int luma(int rgb) {
        int r = (rgb >>> 16) & 0xFF;
        int g = (rgb >>> 8) & 0xFF;
        int b = rgb & 0xFF;
        return (r * 77 + g * 150 + b * 29 + 128) >> 8;
    }

    private static boolean packed(GrayU8 image) {
        return image.stride == image.width && image.startIndex == 0;
    }
}
