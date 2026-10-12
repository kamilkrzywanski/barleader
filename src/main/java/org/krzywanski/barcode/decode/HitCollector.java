package org.krzywanski.barcode.decode;

import org.krzywanski.barcode.BarcodeHit;
import org.krzywanski.barcode.ImagePoint;

import java.util.ArrayList;
import java.util.List;

/** Drops empty payloads and keeps one hit when the same payload sits on the same spot. */
public final class HitCollector {
    private static final double MERGE_DISTANCE_SQ = 80.0 * 80.0;

    private final List<BarcodeHit> hits = new ArrayList<>();

    public boolean add(BarcodeHit hit) {
        if (hit.text().isBlank()) {
            return false;
        }
        for (int i = 0; i < hits.size(); i++) {
            BarcodeHit existing = hits.get(i);
            if (existing.symbology() == hit.symbology()
                    && existing.text().equals(hit.text())
                    && close(existing, hit)) {
                if (hit.confidence() > existing.confidence()) {
                    hits.set(i, hit);
                }
                return false;
            }
        }
        hits.add(hit);
        return true;
    }

    public boolean isEmpty() {
        return hits.isEmpty();
    }

    public List<BarcodeHit> list() {
        return List.copyOf(hits);
    }

    private static boolean close(BarcodeHit left, BarcodeHit right) {
        ImagePoint a = center(left);
        ImagePoint b = center(right);
        if (a == null || b == null) {
            return true;
        }
        double dx = a.x() - b.x();
        double dy = a.y() - b.y();
        return dx * dx + dy * dy <= MERGE_DISTANCE_SQ;
    }

    private static ImagePoint center(BarcodeHit hit) {
        if (hit.corners().isEmpty()) {
            return null;
        }
        double x = 0.0;
        double y = 0.0;
        for (ImagePoint corner : hit.corners()) {
            x += corner.x();
            y += corner.y();
        }
        return new ImagePoint(x / hit.corners().size(), y / hit.corners().size());
    }
}
