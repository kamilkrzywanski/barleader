package org.krzywanski.barcode.image;

/**
 * Separable box mean. Borders divide by the number of samples that exist, not by the full kernel.
 * The integer path rounds each pass with {@code (sum + count / 2) / count}, matching a horizontal
 * pass followed by a vertical pass. The float path skips that rounding so Sauvola can use it.
 */
public final class BoxMean {
    private BoxMean() {
    }

    public static void mean(GrayU8 input, GrayU8 output, int radius) {
        if (radius <= 0) {
            throw new IllegalArgumentException("radius must be > 0");
        }
        if (output.width != input.width || output.height != input.height) {
            throw new IllegalArgumentException("output shape");
        }
        GrayU8 horizontal = new GrayU8(input.width, input.height);
        int length = radius * 2 + 1;
        horizontal(input, horizontal, radius, length);
        vertical(horizontal, output, radius, length);
    }

    /** Float box mean, same window, no intermediate rounding. */
    public static float[] meanFloat(GrayU8 input, int radius) {
        int width = input.width;
        int height = input.height;
        float[] src = new float[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                src[y * width + x] = input.get(x, y);
            }
        }
        return meanFloat(src, width, height, radius);
    }

    /** Float box mean of values that do not fit in a byte, such as squared samples. */
    public static float[] meanFloat(float[] src, int width, int height, int radius) {
        if (radius <= 0) {
            throw new IllegalArgumentException("radius must be > 0");
        }
        if (src.length < width * height) {
            throw new IllegalArgumentException("buffer");
        }
        int length = radius * 2 + 1;
        float[] horizontal = new float[width * height];
        horizontal(src, horizontal, width, height, radius, length);
        float[] vertical = new float[width * height];
        vertical(horizontal, vertical, width, height, radius, length);
        return vertical;
    }

    private static void horizontal(GrayU8 input, GrayU8 output, int offset, int length) {
        if (length > input.width) {
            direct(input, output, offset, true);
            return;
        }
        horizontalBorder(input, output, offset, length);
        horizontalInner(input, output, offset, length);
    }

    private static void vertical(GrayU8 input, GrayU8 output, int offset, int length) {
        if (length > input.height) {
            direct(input, output, offset, false);
            return;
        }
        verticalBorder(input, output, offset, length);
        verticalInner(input, output, offset, length);
    }

    private static void horizontalBorder(GrayU8 input, GrayU8 output, int offset, int length) {
        byte[] dataSrc = input.data;
        byte[] dataDst = output.data;
        int offsetR = length - offset - 1;
        int width = input.width;
        for (int y = 0; y < input.height; y++) {
            int indexDest = output.startIndex + y * output.stride;
            int j = input.startIndex + y * input.stride;
            int total = 0;
            int count = length - offset;
            int jEnd = j + count;
            if (offset > 0) {
                for (int indexSrc = j; indexSrc < jEnd; indexSrc++) {
                    total += dataSrc[indexSrc] & 0xFF;
                }
                dataDst[indexDest++] = (byte) ((total + count / 2) / count);
            }
            while (++count < length) {
                total += dataSrc[jEnd++] & 0xFF;
                dataDst[indexDest++] = (byte) ((total + count / 2) / count);
            }
            jEnd = j + width;
            count = offset + offsetR;
            j += width - count;
            indexDest += width - count;
            total = 0;
            if (offsetR > 0) {
                for (int indexSrc = j; indexSrc < jEnd; indexSrc++) {
                    total += dataSrc[indexSrc] & 0xFF;
                }
                dataDst[indexDest++] = (byte) ((total + count / 2) / count);
            }
            while (--count > offset) {
                total -= dataSrc[j++] & 0xFF;
                dataDst[indexDest++] = (byte) ((total + count / 2) / count);
            }
        }
    }

    private static void horizontalInner(GrayU8 input, GrayU8 output, int offset, int length) {
        int divisor = length;
        int halfDivisor = divisor / 2;
        for (int y = 0; y < input.height; y++) {
            int indexIn = input.startIndex + input.stride * y;
            int indexOut = output.startIndex + output.stride * y + offset;
            int total = 0;
            int indexEnd = indexIn + length;
            for (; indexIn < indexEnd; indexIn++) {
                total += input.data[indexIn] & 0xFF;
            }
            output.data[indexOut++] = (byte) ((total + halfDivisor) / divisor);
            indexEnd = indexIn + input.width - length;
            for (; indexIn < indexEnd; indexIn++) {
                total -= input.data[indexIn - length] & 0xFF;
                total += input.data[indexIn] & 0xFF;
                output.data[indexOut++] = (byte) ((total + halfDivisor) / divisor);
            }
        }
    }

    private static void verticalBorder(GrayU8 input, GrayU8 output, int offset, int length) {
        byte[] dataSrc = input.data;
        byte[] dataDst = output.data;
        int width = input.width;
        int height = input.height;
        int[] totals = new int[width];
        for (int count = length - offset; count < length; count++) {
            int indexInRow = input.startIndex;
            if (count == length - offset) {
                int indexIn = indexInRow;
                for (int x = 0; x < width; x++) {
                    totals[x] = dataSrc[indexIn++] & 0xFF;
                }
                for (int y = 1; y < count; y++) {
                    indexIn = indexInRow + y * input.stride;
                    for (int x = 0; x < width; x++) {
                        totals[x] += dataSrc[indexIn++] & 0xFF;
                    }
                }
            } else {
                int indexIn0 = indexInRow + (count - 1) * input.stride;
                for (int x = 0; x < width; x++) {
                    totals[x] += dataSrc[indexIn0 + x] & 0xFF;
                }
            }
            int indexOut = output.startIndex + (count - (length - offset)) * output.stride;
            for (int x = 0; x < width; x++) {
                dataDst[indexOut + x] = (byte) ((totals[x] + count / 2) / count);
            }
        }
        for (int yStart = height - length + 1; yStart < height - offset; yStart++) {
            int indexInRow = input.startIndex;
            if (yStart == height - length + 1) {
                int indexIn = indexInRow + yStart * input.stride;
                for (int x = 0; x < width; x++) {
                    totals[x] = dataSrc[indexIn++] & 0xFF;
                }
                for (int y = yStart + 1; y < height; y++) {
                    indexIn = indexInRow + y * input.stride;
                    for (int x = 0; x < width; x++) {
                        totals[x] += dataSrc[indexIn++] & 0xFF;
                    }
                }
            } else {
                int indexIn0 = indexInRow + (yStart - 1) * input.stride;
                for (int x = 0; x < width; x++) {
                    totals[x] -= dataSrc[indexIn0 + x] & 0xFF;
                }
            }
            int count = height - yStart;
            int indexOut = output.startIndex + (yStart + offset) * output.stride;
            for (int x = 0; x < width; x++) {
                dataDst[indexOut + x] = (byte) ((totals[x] + count / 2) / count);
            }
        }
    }

    private static void verticalInner(GrayU8 input, GrayU8 output, int offset, int length) {
        int divisor = length;
        int halfDivisor = divisor / 2;
        int regionStepY = length * input.stride;
        int[] totals = new int[input.width];
        int indexIn = input.startIndex;
        for (int x = 0; x < input.width; x++) {
            totals[x] = input.data[indexIn++] & 0xFF;
        }
        for (int y = 1; y < length; y++) {
            indexIn = input.startIndex + y * input.stride;
            for (int x = 0; x < input.width; x++) {
                totals[x] += input.data[indexIn++] & 0xFF;
            }
        }
        int indexOut = output.startIndex + output.stride * offset;
        for (int x = 0; x < input.width; x++) {
            output.data[indexOut++] = (byte) ((totals[x] + halfDivisor) / divisor);
        }
        for (int y = 0; y < input.height - length; y++) {
            indexOut = output.startIndex + output.stride * (offset + y + 1);
            indexIn = input.startIndex + y * input.stride;
            for (int x = 0; x < input.width; x++) {
                int total = totals[x] - (input.data[indexIn] & 0xFF);
                total += input.data[indexIn + regionStepY] & 0xFF;
                output.data[indexOut++] = (byte) ((total + halfDivisor) / divisor);
                totals[x] = total;
                indexIn++;
            }
        }
    }

    /** Images smaller than the kernel. Each pixel averages the samples that exist on this axis. */
    private static void direct(GrayU8 input, GrayU8 output, int radius, boolean horizontal) {
        for (int y = 0; y < input.height; y++) {
            for (int x = 0; x < input.width; x++) {
                int total = 0;
                int count = 0;
                if (horizontal) {
                    int x0 = Math.max(0, x - radius);
                    int x1 = Math.min(input.width - 1, x + radius);
                    for (int xx = x0; xx <= x1; xx++) {
                        total += input.get(xx, y);
                        count++;
                    }
                } else {
                    int y0 = Math.max(0, y - radius);
                    int y1 = Math.min(input.height - 1, y + radius);
                    for (int yy = y0; yy <= y1; yy++) {
                        total += input.get(x, yy);
                        count++;
                    }
                }
                output.set(x, y, (total + count / 2) / count);
            }
        }
    }

    private static void horizontal(float[] input, float[] output, int width, int height, int offset, int length) {
        if (length > width) {
            direct(input, output, width, height, offset, true);
            return;
        }
        int offsetR = length - offset - 1;
        for (int y = 0; y < height; y++) {
            int row = y * width;
            int indexDest = row;
            int totalCount = length - offset;
            float total = 0.0f;
            if (offset > 0) {
                for (int i = 0; i < totalCount; i++) {
                    total += input[row + i];
                }
                output[indexDest++] = total / totalCount;
            }
            int count = totalCount;
            int end = totalCount;
            while (++count < length) {
                total += input[row + end];
                end++;
                output[indexDest++] = total / count;
            }
            int rightCount = offset + offsetR;
            int right = width - rightCount;
            indexDest = row + width - offsetR;
            total = 0.0f;
            count = rightCount;
            if (offsetR > 0) {
                for (int i = right; i < width; i++) {
                    total += input[row + i];
                }
                output[indexDest++] = total / count;
            }
            int drop = right;
            while (--count > offset) {
                total -= input[row + drop];
                drop++;
                output[indexDest++] = total / count;
            }
            int indexIn = row;
            int indexOut = row + offset;
            total = 0.0f;
            for (int i = 0; i < length; i++) {
                total += input[indexIn++];
            }
            output[indexOut++] = total / length;
            int innerEnd = row + width - offsetR;
            while (indexOut < innerEnd) {
                total -= input[indexIn - length];
                total += input[indexIn];
                indexIn++;
                output[indexOut++] = total / length;
            }
        }
    }

    private static void vertical(float[] input, float[] output, int width, int height, int offset, int length) {
        if (length > height) {
            direct(input, output, width, height, offset, false);
            return;
        }
        float[] totals = new float[width];
        for (int count = length - offset; count < length; count++) {
            if (count == length - offset) {
                System.arraycopy(input, 0, totals, 0, width);
                for (int y = 1; y < count; y++) {
                    int row = y * width;
                    for (int x = 0; x < width; x++) {
                        totals[x] += input[row + x];
                    }
                }
            } else {
                int row = (count - 1) * width;
                for (int x = 0; x < width; x++) {
                    totals[x] += input[row + x];
                }
            }
            int outRow = (count - (length - offset)) * width;
            for (int x = 0; x < width; x++) {
                output[outRow + x] = totals[x] / count;
            }
        }
        for (int yStart = height - length + 1; yStart < height - offset; yStart++) {
            if (yStart == height - length + 1) {
                int row = yStart * width;
                System.arraycopy(input, row, totals, 0, width);
                for (int y = yStart + 1; y < height; y++) {
                    int src = y * width;
                    for (int x = 0; x < width; x++) {
                        totals[x] += input[src + x];
                    }
                }
            } else {
                int row = (yStart - 1) * width;
                for (int x = 0; x < width; x++) {
                    totals[x] -= input[row + x];
                }
            }
            int count = height - yStart;
            int outRow = (yStart + offset) * width;
            for (int x = 0; x < width; x++) {
                output[outRow + x] = totals[x] / count;
            }
        }
        float divisor = length;
        int step = length * width;
        for (int x = 0; x < width; x++) {
            totals[x] = input[x];
        }
        for (int y = 1; y < length; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                totals[x] += input[row + x];
            }
        }
        int out = offset * width;
        for (int x = 0; x < width; x++) {
            output[out + x] = totals[x] / divisor;
        }
        for (int y = 0; y < height - length; y++) {
            int src = y * width;
            out = (offset + y + 1) * width;
            for (int x = 0; x < width; x++) {
                float total = totals[x] - input[src + x];
                total += input[src + x + step];
                output[out + x] = total / divisor;
                totals[x] = total;
            }
        }
    }

    private static void direct(float[] input, float[] output, int width, int height, int radius, boolean horizontal) {
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                float total = 0.0f;
                int count = 0;
                if (horizontal) {
                    int x0 = Math.max(0, x - radius);
                    int x1 = Math.min(width - 1, x + radius);
                    for (int xx = x0; xx <= x1; xx++) {
                        total += input[y * width + xx];
                        count++;
                    }
                } else {
                    int y0 = Math.max(0, y - radius);
                    int y1 = Math.min(height - 1, y + radius);
                    for (int yy = y0; yy <= y1; yy++) {
                        total += input[yy * width + x];
                        count++;
                    }
                }
                output[y * width + x] = total / count;
            }
        }
    }
}
