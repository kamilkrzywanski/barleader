package io.github.kamilkrzywanski.barleader.image;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class ColourChannelsTest {
    @Test
    void aGrayImageHasNoChannelsToRead() {
        BufferedImage gray = new BufferedImage(40, 20, BufferedImage.TYPE_BYTE_GRAY);
        assertNull(GrayImages.channels(gray));
        BufferedImage rgbGray = new BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 20; y++) {
            for (int x = 0; x < 40; x++) {
                rgbGray.setRGB(x, y, x % 2 == 0 ? 0x202020 : 0xE0E0E0);
            }
        }
        assertNull(GrayImages.channels(rgbGray));
    }

    @Test
    void blackOnRedTurnsBlackOnWhiteInTheBrightestChannel() {
        BufferedImage image = new BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 20; y++) {
            for (int x = 0; x < 40; x++) {
                image.setRGB(x, y, x % 4 == 0 ? 0x101010 : 0xC81E1E);
            }
        }
        GrayU8[] planes = GrayImages.channels(image);
        assertNotNull(planes);
        assertEquals(0xC8, planes[0].get(1, 5));
        assertEquals(0x10, planes[0].get(0, 5));
        assertEquals(0x1E, planes[1].get(1, 5));
    }
}
