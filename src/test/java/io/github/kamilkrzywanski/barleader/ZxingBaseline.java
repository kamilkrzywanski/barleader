package io.github.kamilkrzywanski.barleader;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.Result;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;

import java.awt.image.BufferedImage;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The usual one-shot ZXing call: HybridBinarizer and TRY_HARDER, nothing else.
 * This is the phase-0 baseline and is intentionally not improved.
 */
final class ZxingBaseline {
    private ZxingBaseline() {
    }

    static List<String> read(BufferedImage image) {
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        try {
            Result result = new MultiFormatReader().decode(
                    new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(image))),
                    hints
            );
            if (result.getText() == null || result.getText().isBlank()) {
                return List.of();
            }
            return List.of(result.getText());
        } catch (NotFoundException exception) {
            return List.of();
        }
    }
}
