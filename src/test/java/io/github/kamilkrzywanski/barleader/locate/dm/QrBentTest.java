package io.github.kamilkrzywanski.barleader.locate.dm;

import io.github.kamilkrzywanski.barleader.QrCode;
import io.github.kamilkrzywanski.barleader.image.GrayU8;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A QR code wrapped around a can and turned in its plane: across the can its modules shrink
 * toward the edges, and turned, that bends its rows and columns both, which no homography follows.
 * The grid bent onto the module edges reads it at every angle; a homography alone read none of
 * these when the bent grid was added.
 */
class QrBentTest {
    private static final String TEXT = "https://example.com/bent/around/a/can?id=1234567890";

    @Test
    void readsACodeWrappedAroundACanAtAnyAngle() {
        QrCode code = QrCode.encode(TEXT);
        for (double degrees : new double[]{15, 20, 30, 45, 60}) {
            List<QrDetector.Found> found = QrDetector.read(wrapped(code, 6, 145, Math.toRadians(degrees)));
            assertEquals(1, found.size(), degrees + "°");
            assertEquals(TEXT, found.get(0).text(), degrees + "°");
        }
    }

    /**
     * The code with modules of {@code module} pixels and four modules of quiet zone, turned by
     * {@code angle}, on a can of {@code radius} pixels whose axis is vertical, seen from far away,
     * each pixel the mean of nine samples.
     */
    private static GrayU8 wrapped(QrCode code, double module, double radius, double angle) {
        int n = code.size();
        int side = 520;
        GrayU8 out = new GrayU8(side, side);
        double half = (n + 8) * module / 2;
        double cos = Math.cos(angle);
        double sin = Math.sin(angle);
        for (int y = 0; y < side; y++) {
            for (int x = 0; x < side; x++) {
                int ink = 0;
                for (int sy = 0; sy < 3; sy++) {
                    for (int sx = 0; sx < 3; sx++) {
                        double px = x + (sx + 0.5) / 3 - side / 2.0;
                        double py = y + (sy + 0.5) / 3 - side / 2.0;
                        if (Math.abs(px) >= radius) {
                            continue;
                        }
                        // Distance along the surface of the can.
                        double u = radius * Math.asin(px / radius);
                        double s = cos * u + sin * py;
                        double t = -sin * u + cos * py;
                        int mx = (int) Math.floor((s + half) / module) - 4;
                        int my = (int) Math.floor((t + half) / module) - 4;
                        if (mx >= 0 && my >= 0 && mx < n && my < n && code.isDark(mx, my)) {
                            ink++;
                        }
                    }
                }
                out.set(x, y, (int) Math.round(230 - 200 * ink / 9.0));
            }
        }
        return out;
    }
}
