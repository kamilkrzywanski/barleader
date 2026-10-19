package io.github.kamilkrzywanski.barleader.image;

import io.github.kamilkrzywanski.barleader.image.GrayU8;
import org.junit.jupiter.api.Test;
import io.github.kamilkrzywanski.barleader.ImagePoint;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeometryTest {
    @Test
    void cropAddsTheOriginInThePixelMap() {
        GrayU8 source = white(20, 16);
        source.set(7, 9, 0);
        Transformed crop = Geometry.crop(source, 5, 4, 8, 6);
        assertEquals(8, crop.image().width);
        assertEquals(6, crop.image().height);
        assertEquals(0, crop.image().get(2, 5));
        ImagePoint back = crop.toSource().toSource(2, 5);
        assertEquals(7.0, back.x(), 0.0);
        assertEquals(9.0, back.y(), 0.0);
    }

    @Test
    void rotationFitsRejectsAnImagePastThePixelBudget() {
        assertTrue(Geometry.rotationFits(200, 80, -30));
        assertTrue(Geometry.rotationFits(1152, 864, 30));
        assertTrue(Geometry.rotationFits(1600, 1200, 15));
        assertTrue(Geometry.rotationFits(2976, 2232, 30));
        assertFalse(Geometry.rotationFits(4000, 4000, 30));
        assertFalse(Geometry.rotationFits(8000, 6000, 15));
        assertFalse(Geometry.withinPixelBudget(1152, 864, 2.0));
    }

    @Test
    void orthogonalRotationsMapTheMarkedPixelBack() {
        assertRoundTrip(90);
        assertRoundTrip(180);
        assertRoundTrip(270);
    }

    @Test
    void scaleMapsTheMarkedPixelBack() {
        GrayU8 source = white(30, 18);
        source.set(4, 6, 0);
        Transformed scaled = Geometry.scale(source, 2.0);
        assertEquals(60, scaled.image().width);
        assertEquals(36, scaled.image().height);
        int marks = 0;
        for (int y = 0; y < scaled.image().height; y++) {
            for (int x = 0; x < scaled.image().width; x++) {
                if (scaled.image().get(x, y) == 0) {
                    marks++;
                    ImagePoint back = scaled.toSource().toSource(x, y);
                    assertEquals(4.0, back.x(), 1.0);
                    assertEquals(6.0, back.y(), 1.0);
                }
            }
        }
        assertTrue(marks >= 1);
    }

    @Test
    void boxDownscaleRoundsABlockOfOnesUpFromTheHalfBias() {
        GrayU8 block = filled(3, 3, 1);
        GrayU8 layer = Geometry.boxDownscale(block, 3);
        assertEquals(1, layer.width);
        assertEquals(1, layer.height);
        // (3*3)/2 + 9*1 = 13, and 13/9 truncates to 1. A plain sum would truncate to 0.
        assertEquals(1, layer.get(0, 0));
    }

    @Test
    void boxDownscaleDropsTheLeftoverEdge() {
        GrayU8 source = filled(4, 3, 0);
        source.set(3, 0, 255);
        source.set(3, 1, 255);
        source.set(3, 2, 255);
        for (int y = 0; y < 3; y++) {
            for (int x = 0; x < 3; x++) {
                source.set(x, y, x + y * 3);
            }
        }
        GrayU8 layer = Geometry.boxDownscale(source, 3);
        assertEquals(1, layer.width);
        assertEquals(1, layer.height);
        // bias 4 + (0+1+2+3+4+5+6+7+8) = 40, 40/9 = 4. Column x=3 is outside the box.
        assertEquals(4, layer.get(0, 0));
    }

    @Test
    void boxPyramidReproducesConstantModulesAndScalesByTheWidthRatio() {
        int[] modules = {0, 255, 10, 20, 30, 40};
        GrayU8 source = filled(9, 6, 0);
        for (int index = 0; index < modules.length; index++) {
            int moduleX = index % 3;
            int moduleY = index / 3;
            for (int ty = 0; ty < 3; ty++) {
                for (int tx = 0; tx < 3; tx++) {
                    source.set(moduleX * 3 + tx, moduleY * 3 + ty, modules[index]);
                }
            }
        }
        List<Transformed> layers = Geometry.boxPyramid(source, 1, 3);
        assertEquals(1, layers.size());
        Transformed layer = layers.get(0);
        assertEquals(3, layer.image().width);
        assertEquals(2, layer.image().height);
        for (int index = 0; index < modules.length; index++) {
            assertEquals(modules[index], layer.image().get(index % 3, index / 3));
        }
        ImagePoint back = layer.toSource().toSource(2, 1);
        assertEquals(6.0, back.x(), 0.0);
        assertEquals(3.0, back.y(), 0.0);
    }

    @Test
    void boxPyramidUsesTheOriginalWidthRatioWhenSidesDoNotDivideEvenly() {
        GrayU8 source = filled(30, 27, 8);
        List<Transformed> layers = Geometry.boxPyramid(source, 4, 3);
        assertEquals(2, layers.size());
        assertEquals(10, layers.get(0).image().width);
        assertEquals(9, layers.get(0).image().height);
        assertEquals(3, layers.get(1).image().width);
        assertEquals(3, layers.get(1).image().height);
        ImagePoint first = layers.get(0).toSource().toSource(1, 1);
        assertEquals(3.0, first.x(), 0.0);
        assertEquals(3.0, first.y(), 0.0);
        // 30/3 = 10. A product of the two factors of 3 would be 9.
        ImagePoint second = layers.get(1).toSource().toSource(1, 1);
        assertEquals(10.0, second.x(), 0.0);
        assertEquals(10.0, second.y(), 0.0);
        assertEquals(8, layers.get(1).image().get(0, 0));
    }

    @Test
    void boxPyramidStopsAtTheLongerSideThreshold() {
        GrayU8 wide = filled(960, 6, 40);
        List<Transformed> layers = Geometry.boxPyramid(wide, 500, 3);
        assertEquals(1, layers.size());
        assertEquals(320, layers.get(0).image().width);
        assertEquals(2, layers.get(0).image().height);
        ImagePoint back = layers.get(0).toSource().toSource(10, 1);
        assertEquals(30.0, back.x(), 0.0);
        assertEquals(3.0, back.y(), 0.0);

        GrayU8 small = filled(180, 140, 40);
        assertTrue(Geometry.boxPyramid(small, 500, 3).isEmpty());
        assertTrue(Geometry.boxPyramid(wide, 0, 3).isEmpty());
    }

    @Test
    void homographyMapsATranslatedSquare() {
        ImagePoint[] from = square(0, 0, 10);
        ImagePoint[] to = square(5, 3, 10);
        Homography homography = Homography.mapping(from, to);
        assertNotNull(homography);
        assertClose(homography.apply(0, 0), 5, 3);
        assertClose(homography.apply(10, 0), 15, 3);
        assertClose(homography.apply(10, 10), 15, 13);
        assertClose(homography.apply(0, 10), 5, 13);
    }

    @Test
    void homographyMapsAPerspectiveQuad() {
        ImagePoint[] from = square(0, 0, 100);
        ImagePoint[] to = new ImagePoint[]{
                new ImagePoint(10, 30),
                new ImagePoint(90, 10),
                new ImagePoint(110, 120),
                new ImagePoint(0, 100)
        };
        Homography homography = Homography.mapping(from, to);
        assertNotNull(homography);
        for (int i = 0; i < 4; i++) {
            assertClose(homography.apply(from[i].x(), from[i].y()), to[i].x(), to[i].y());
        }
    }

    @Test
    void rotatePixelsMatchRotateIncludingAReusedBuffer() {
        GrayU8 source = white(48, 36);
        for (int y = 0; y < source.height; y++) {
            for (int x = 0; x < source.width; x++) {
                source.set(x, y, (x * 17 + y * 31) & 255);
            }
        }
        Geometry.rotatePixels(source, 40);
        for (int degrees : new int[]{0, 15, -15, 33, 90, 180}) {
            GrayU8 expected = Geometry.rotate(source, degrees).image();
            GrayU8 actual = Geometry.rotatePixels(source, degrees);
            assertEquals(expected.width, actual.width, degrees + " width");
            assertEquals(expected.height, actual.height, degrees + " height");
            for (int y = 0; y < expected.height; y++) {
                for (int x = 0; x < expected.width; x++) {
                    assertEquals(expected.get(x, y), actual.get(x, y), degrees + " @" + x + "," + y);
                }
            }
        }
    }

    @Test
    void smallAngleRotationMapsTheMarkedPixelBack() {
        GrayU8 source = white(48, 36);
        source.set(8, 5, 0);
        source.set(8, 6, 0);
        source.set(9, 5, 0);
        source.set(9, 6, 0);
        Transformed rotated = Geometry.rotate(source, 15);
        boolean found = false;
        for (int y = 0; y < rotated.image().height; y++) {
            for (int x = 0; x < rotated.image().width; x++) {
                if (rotated.image().get(x, y) < 40) {
                    ImagePoint back = rotated.toSource().toSource(x + 0.0, y + 0.0);
                    if (Math.abs(back.x() - 8.5) < 2.5 && Math.abs(back.y() - 5.5) < 2.5) {
                        found = true;
                    }
                }
            }
        }
        assertTrue(found, "rotated mark did not map back to the source pixel");
    }

    private static void assertRoundTrip(int degrees) {
        GrayU8 source = white(40, 25);
        source.set(3, 7, 0);
        Transformed rotated = Geometry.rotateOrthogonal(source, degrees);
        int marks = 0;
        for (int y = 0; y < rotated.image().height; y++) {
            for (int x = 0; x < rotated.image().width; x++) {
                if (rotated.image().get(x, y) == 0) {
                    marks++;
                    ImagePoint back = rotated.toSource().toSource(x, y);
                    assertEquals(3.0, back.x(), 1e-6, degrees + " x");
                    assertEquals(7.0, back.y(), 1e-6, degrees + " y");
                }
            }
        }
        assertEquals(1, marks, degrees + " mark count");
    }

    private static ImagePoint[] square(double x, double y, double side) {
        return new ImagePoint[]{
                new ImagePoint(x, y),
                new ImagePoint(x + side, y),
                new ImagePoint(x + side, y + side),
                new ImagePoint(x, y + side)
        };
    }

    private static void assertClose(ImagePoint point, double x, double y) {
        assertEquals(x, point.x(), 0.05);
        assertEquals(y, point.y(), 0.05);
    }

    private static GrayU8 white(int width, int height) {
        return filled(width, height, 255);
    }

    private static GrayU8 filled(int width, int height, int value) {
        GrayU8 image = new GrayU8(width, height);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.set(x, y, value);
            }
        }
        return image;
    }
}
