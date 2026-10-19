package io.github.kamilkrzywanski.barleader.decode;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.datamatrix.DataMatrixWriter;
import com.google.zxing.qrcode.QRCodeWriter;
import io.github.kamilkrzywanski.barleader.BarcodeHit;
import io.github.kamilkrzywanski.barleader.BarcodeReader;
import io.github.kamilkrzywanski.barleader.ReadOptions;
import io.github.kamilkrzywanski.barleader.Symbology;
import io.github.kamilkrzywanski.barleader.image.PixelMap;
import io.github.kamilkrzywanski.barleader.image.Samples;
import io.github.kamilkrzywanski.barleader.image.ZxingLuminance;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Matrix symbologies stay on the attempt that produced the black matrix, ahead of a linear hit. */
class MatrixScanPathTest {
    @Test
    void readsARenderedQrOnTheScanPath() throws Exception {
        assertMatrix(
                new QRCodeWriter().encode("QR-PRO-HELLO", BarcodeFormat.QR_CODE, 240, 240),
                Symbology.QR_CODE,
                "QR-PRO-HELLO"
        );
    }

    @Test
    void readsAnInvertedQrOnTheSameAttempt() throws Exception {
        BitMatrix modules = new QRCodeWriter().encode("QR-INVERT", BarcodeFormat.QR_CODE, 240, 240);
        BufferedImage image = invert(MatrixToImageWriter.toBufferedImage(modules));
        EnumSet<Symbology> requested = EnumSet.of(Symbology.QR_CODE);
        List<BarcodeHit> hits = BitmapDecoder.read(
                ZxingLuminance.of(image),
                PixelMap.IDENTITY,
                "original-hybrid",
                0,
                0.92,
                requested,
                false,
                false
        );
        assertEquals(1, hits.size());
        assertEquals("QR-INVERT", hits.get(0).text());
        assertEquals("original-hybrid", hits.get(0).attempt());
        assertEquals(1, requested.size());
    }

    @Test
    void readsARenderedDataMatrixOnTheScanPath() throws Exception {
        assertMatrix(
                new DataMatrixWriter().encode("DM-PRO-123", BarcodeFormat.DATA_MATRIX, 200, 200),
                Symbology.DATA_MATRIX,
                "DM-PRO-123"
        );
    }

    @Test
    void qrBesideCode128StaysQrWhenFindAllIsOff() {
        Samples.Sample pair = Samples.qrAndCode128();
        List<BarcodeHit> hits = new BarcodeReader().read(pair.image(), ReadOptions.fast());
        assertEquals(List.of("QR-AND"), hits.stream().map(BarcodeHit::text).toList());
        assertEquals(Symbology.QR_CODE, hits.get(0).symbology());
        assertTrue(hits.get(0).attempt().startsWith("original"), hits.get(0).attempt());
    }

    private static void assertMatrix(BitMatrix modules, Symbology symbology, String text) throws Exception {
        EnumSet<Symbology> requested = EnumSet.of(symbology);
        List<BarcodeHit> hits = BitmapDecoder.read(
                ZxingLuminance.of(MatrixToImageWriter.toBufferedImage(modules)),
                PixelMap.IDENTITY,
                "original-hybrid",
                0,
                0.92,
                requested,
                false,
                false
        );
        assertEquals(1, hits.size(), hits.toString());
        BarcodeHit hit = hits.get(0);
        assertEquals(text, hit.text());
        assertEquals(symbology, hit.symbology());
        assertEquals("original-hybrid", hit.attempt());
        assertEquals(4, hit.corners().size());
        assertEquals(EnumSet.of(symbology), requested);
    }

    private static BufferedImage invert(BufferedImage source) {
        BufferedImage out = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                int rgb = source.getRGB(x, y);
                int red = 255 - ((rgb >> 16) & 0xFF);
                int green = 255 - ((rgb >> 8) & 0xFF);
                int blue = 255 - (rgb & 0xFF);
                out.setRGB(x, y, (0xFF << 24) | (red << 16) | (green << 8) | blue);
            }
        }
        return out;
    }
}
