package io.github.kamilkrzywanski.barcode.locate;

import io.github.kamilkrzywanski.barcode.ImagePoint;
import io.github.kamilkrzywanski.barcode.image.GrayU8;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * External boundaries of 8-connected foreground (pixel value 1). The point count is the number of
 * foreground pixels that touch a background pixel or the image edge. Callers that used to ask a
 * contour tracer for at least 80 points use that same cutoff.
 */
final class BlobContours {
    private BlobContours() {
    }

    static List<ImagePoint[]> external(GrayU8 binary, int minPoints) {
        int width = binary.width;
        int height = binary.height;
        boolean[] seen = new boolean[width * height];
        int[] qx = new int[width * height];
        int[] qy = new int[width * height];
        List<ImagePoint[]> blobs = new ArrayList<>();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int start = y * width + x;
                if (seen[start] || binary.get(x, y) != 1) {
                    continue;
                }
                int head = 0;
                int tail = 0;
                qx[tail] = x;
                qy[tail] = y;
                tail++;
                seen[start] = true;
                List<ImagePoint> edge = new ArrayList<>();
                while (head < tail) {
                    int cx = qx[head];
                    int cy = qy[head];
                    head++;
                    boolean boundary = false;
                    for (int dy = -1; dy <= 1; dy++) {
                        int ny = cy + dy;
                        for (int dx = -1; dx <= 1; dx++) {
                            if (dx == 0 && dy == 0) {
                                continue;
                            }
                            int nx = cx + dx;
                            if (nx < 0 || ny < 0 || nx >= width || ny >= height || binary.get(nx, ny) != 1) {
                                boundary = true;
                                continue;
                            }
                            int index = ny * width + nx;
                            if (!seen[index]) {
                                seen[index] = true;
                                qx[tail] = nx;
                                qy[tail] = ny;
                                tail++;
                            }
                        }
                    }
                    if (boundary) {
                        edge.add(new ImagePoint(cx, cy));
                    }
                }
                if (edge.size() >= minPoints) {
                    blobs.add(edge.toArray(ImagePoint[]::new));
                }
            }
        }
        blobs.sort(Comparator.comparingInt((ImagePoint[] points) -> points.length).reversed());
        return blobs;
    }
}
