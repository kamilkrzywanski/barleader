package io.github.kamilkrzywanski.barleader;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.oned.EAN13Writer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A 36° EAN pasted on clutter. Full-frame ±15/±30 still see the surrounding dashes,
 * so the read has to come from the deskewed band. Nothing here is added to {@code Samples.all()}.
 */
class LinearBandTest {
    private static final String GTIN = "5901234123457";

    private final BarcodeReader reader = new BarcodeReader();

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void tiltedEanOnClutterIsReadFromTheBand() throws Exception {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.MARGIN, 2);
        // Width 180 at 36° with a 28 px plate is the cell the angle ladder misses.
        // 220 px at the same angle is already rotate--30.0.
        BufferedImage symbol = MatrixToImageWriter.toBufferedImage(
                new EAN13Writer().encode(GTIN, BarcodeFormat.EAN_13, 180, 60, hints));
        BufferedImage canvas = cluttered(symbol, 36.0);
        List<BarcodeHit> hits = reader.read(canvas, ReadOptions.aggressive());
        assertEquals(1, hits.size(), String.valueOf(hits));
        BarcodeHit hit = hits.get(0);
        assertEquals(GTIN, hit.text());
        assertEquals(Symbology.EAN_13, hit.symbology());
        assertTrue(hit.attempt().startsWith("linear-band"), hit.attempt());
        assertTrue(hit.orientationDegrees() >= 0, "orientation " + hit.orientationDegrees());
        assertTrue(hit.corners().size() >= 2, hit.toString());
        double centerX = canvas.getWidth() / 2.0;
        double centerY = canvas.getHeight() / 2.0;
        for (ImagePoint corner : hit.corners()) {
            double dx = corner.x() - centerX;
            double dy = corner.y() - centerY;
            assertTrue(dx * dx + dy * dy < 340.0 * 340.0, corner.toString());
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void blankAndNoiseStayEmpty() {
        assertTrue(reader.read(filled(640, 480, 0xFFFFFF), ReadOptions.aggressive()).isEmpty());
        BufferedImage noise = filled(200, 200, 0xD0D0D0);
        Random random = new Random(1);
        for (int y = 0; y < noise.getHeight(); y++) {
            for (int x = 0; x < noise.getWidth(); x++) {
                if (random.nextDouble() < 0.08) {
                    int value = random.nextBoolean() ? 0 : 255;
                    noise.setRGB(x, y, (value << 16) | (value << 8) | value);
                }
            }
        }
        assertTrue(reader.read(noise, ReadOptions.aggressive()).isEmpty());
    }

    private static BufferedImage cluttered(BufferedImage symbol, double degrees) {
        int width = 900;
        int height = 680;
        int quiet = 28;
        BufferedImage canvas = filled(width, height, 0x303030);
        Graphics2D graphics = canvas.createGraphics();
        graphics.setColor(Color.WHITE);
        for (int y = 6; y < height; y += 11) {
            graphics.fillRect(0, y, width, 3);
        }
        AffineTransform transform = new AffineTransform();
        transform.translate(width / 2.0, height / 2.0);
        transform.rotate(Math.toRadians(degrees));
        graphics.setTransform(transform);
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.setColor(Color.WHITE);
        graphics.fillRect(
                -symbol.getWidth() / 2 - quiet,
                -symbol.getHeight() / 2 - quiet,
                symbol.getWidth() + quiet * 2,
                symbol.getHeight() + quiet * 2
        );
        graphics.drawImage(symbol, -symbol.getWidth() / 2, -symbol.getHeight() / 2, null);
        graphics.dispose();
        return canvas;
    }

    private static BufferedImage filled(int width, int height, int rgb) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(new Color(rgb));
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        return image;
    }
}
