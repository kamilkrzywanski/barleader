/*
 * Copyright 2026 Kamil Krzywański
 */
// SPDX-License-Identifier: Apache-2.0
package io.github.kamilkrzywanski.barleader;

import io.github.kamilkrzywanski.barleader.image.GrayU8;
import io.github.kamilkrzywanski.barleader.image.PixelMap;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Locator for every patch of parallel bars, at any angle, from the structure tensor of 16-pixel
 * blocks: on bars the gradients are strong and all point one way. Neighbouring such blocks whose
 * directions agree form a region, sampled from the original as a strip with the bars vertical.
 * {@link LinearBand} ranks bands of edges at a few angles and on a parcel label settles on the
 * printed text; this keeps each region, as {@link io.github.kamilkrzywanski.barleader.decode.Code128Fit}
 * needs the symbol itself.
 */
final class BarStrips {
    private static final int BLOCK = 16;
    private static final double MIN_COHERENCE = 0.55;
    private static final double MAX_TURN = Math.toRadians(12);
    /** Pixels of quiet zone sampled on each end of a strip. */
    private static final int END = 20;
    private static final int MAX_WIDTH = 2400;
    private static final int MAX_HEIGHT = 1200;

    private BarStrips() {
    }

    /** Up to {@code limit} strips, the region with the most gradient energy first. */
    static List<LinearBand.Crop> find(GrayU8 gray, int limit) {
        int columns = gray.width / BLOCK;
        int rows = gray.height / BLOCK;
        if (columns < 3 || rows < 1) {
            return List.of();
        }
        int blocks = columns * rows;
        double[] energy = new double[blocks];
        double[] coherence = new double[blocks];
        double[] direction = new double[blocks];
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                double xx = 0;
                double yy = 0;
                double xy = 0;
                int y1 = Math.min(gray.height - 1, row * BLOCK + BLOCK);
                int x1 = Math.min(gray.width - 1, column * BLOCK + BLOCK);
                for (int y = Math.max(1, row * BLOCK); y < y1; y++) {
                    for (int x = Math.max(1, column * BLOCK); x < x1; x++) {
                        int gx = gray.get(x + 1, y) - gray.get(x - 1, y);
                        int gy = gray.get(x, y + 1) - gray.get(x, y - 1);
                        xx += gx * gx;
                        yy += gy * gy;
                        xy += gx * gy;
                    }
                }
                int i = row * columns + column;
                double total = xx + yy;
                energy[i] = total / (BLOCK * BLOCK);
                coherence[i] = total <= 0 ? 0 : Math.sqrt((xx - yy) * (xx - yy) + 4 * xy * xy) / total;
                direction[i] = 0.5 * Math.atan2(2 * xy, xx - yy);
            }
        }
        double[] sorted = energy.clone();
        Arrays.sort(sorted);
        double floor = Math.max(150, 4 * sorted[blocks / 2]);
        boolean[] barred = new boolean[blocks];
        for (int i = 0; i < blocks; i++) {
            barred[i] = energy[i] > floor && coherence[i] > MIN_COHERENCE;
        }
        boolean[] seen = new boolean[blocks];
        List<Region> regions = new ArrayList<>();
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int seed = 0; seed < blocks; seed++) {
            if (!barred[seed] || seen[seed]) {
                continue;
            }
            List<Integer> members = new ArrayList<>();
            seen[seed] = true;
            queue.add(seed);
            while (!queue.isEmpty()) {
                int at = queue.poll();
                members.add(at);
                int column = at % columns;
                int row = at / columns;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int x = column + dx;
                        int y = row + dy;
                        if (x < 0 || y < 0 || x >= columns || y >= rows) {
                            continue;
                        }
                        int next = y * columns + x;
                        if (!barred[next] || seen[next]) {
                            continue;
                        }
                        double turn = Math.abs(direction[next] - direction[at]);
                        if (Math.min(turn, Math.PI - turn) > MAX_TURN) {
                            continue;
                        }
                        seen[next] = true;
                        queue.add(next);
                    }
                }
            }
            if (members.size() >= 3) {
                regions.add(region(members, columns, energy, direction));
            }
        }
        regions.sort((a, b) -> Double.compare(b.weight, a.weight));
        List<LinearBand.Crop> strips = new ArrayList<>();
        for (Region region : regions) {
            if (strips.size() >= limit) {
                break;
            }
            LinearBand.Crop strip = strip(gray, region);
            if (strip != null) {
                strips.add(strip);
            }
        }
        return strips;
    }

    /** The energy-weighted direction across the bars, and the extent of the block centres along and across it. */
    private static Region region(List<Integer> members, int columns, double[] energy, double[] direction) {
        double cos = 0;
        double sin = 0;
        double weight = 0;
        for (int i : members) {
            cos += energy[i] * Math.cos(2 * direction[i]);
            sin += energy[i] * Math.sin(2 * direction[i]);
            weight += energy[i];
        }
        double angle = 0.5 * Math.atan2(sin, cos);
        double ux = Math.cos(angle);
        double uy = Math.sin(angle);
        double alongMin = Double.MAX_VALUE;
        double alongMax = -Double.MAX_VALUE;
        double acrossMin = Double.MAX_VALUE;
        double acrossMax = -Double.MAX_VALUE;
        for (int i : members) {
            double x = (i % columns) * BLOCK + BLOCK / 2.0;
            double y = (i / columns) * BLOCK + BLOCK / 2.0;
            double along = x * ux + y * uy;
            double across = -x * uy + y * ux;
            alongMin = Math.min(alongMin, along);
            alongMax = Math.max(alongMax, along);
            acrossMin = Math.min(acrossMin, across);
            acrossMax = Math.max(acrossMax, across);
        }
        return new Region(angle, alongMin, alongMax, acrossMin, acrossMax, weight);
    }

    private record Region(double angle, double alongMin, double alongMax, double acrossMin, double acrossMax, double weight) {
    }

    /** The region from the original, bars vertical, with {@link #END} pixels past each end. */
    private static LinearBand.Crop strip(GrayU8 gray, Region region) {
        if (region.alongMax - region.alongMin + BLOCK < 2.5 * BLOCK) {
            return null;
        }
        double alongMin = region.alongMin - BLOCK / 2.0 - END;
        double alongMax = region.alongMax + BLOCK / 2.0 + END;
        double acrossMin = region.acrossMin - BLOCK / 2.0;
        double acrossMax = region.acrossMax + BLOCK / 2.0;
        int width = (int) Math.round(alongMax - alongMin);
        int height = (int) Math.round(acrossMax - acrossMin);
        if (width > MAX_WIDTH || height > MAX_HEIGHT) {
            return null;
        }
        double ux = Math.cos(region.angle);
        double uy = Math.sin(region.angle);
        double originX = alongMin * ux - acrossMin * uy;
        double originY = alongMin * uy + acrossMin * ux;
        PixelMap toSource = (x, y) -> new ImagePoint(originX + ux * x - uy * y, originY + uy * x + ux * y);
        GrayU8 image = new GrayU8(width, height);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.set(x, y, LinearBand.bilinear(gray, originX + ux * x - uy * y, originY + uy * x + ux * y));
            }
        }
        int degrees = (int) Math.round(Math.toDegrees(region.angle));
        return new LinearBand.Crop(image, toSource, Math.floorMod(degrees, 360));
    }
}
