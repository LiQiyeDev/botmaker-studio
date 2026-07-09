package com.botmaker.studio.ui.util;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import javafx.scene.image.Image;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;

import java.util.Map;

/**
 * Renders text (a URL) as a QR code {@link Image} for the Remote Pilot dialog — a phone scans it to open the
 * pilot over HTTPS or to download the APK. Encodes ZXing's {@link BitMatrix} straight into a JavaFX
 * {@link WritableImage} (no AWT / zxing-javase), so it stays dependency-light and works headless.
 */
public final class QrCodes {

    private QrCodes() {}

    /**
     * A {@code size}×{@code size} black-on-white QR image for {@code text}, or {@code null} if encoding fails
     * (caller should degrade to showing the URL text only).
     */
    public static Image qr(String text, int size) {
        try {
            BitMatrix matrix = new QRCodeWriter().encode(
                    text, BarcodeFormat.QR_CODE, size, size,
                    // MARGIN 4 = the spec-mandated 4-module quiet zone. A tighter margin (we used 1) makes
                    // phone-camera finder-pattern detection unreliable, especially against a busy/dark border.
                    Map.of(EncodeHintType.MARGIN, 4,
                            EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M));
            int w = matrix.getWidth();
            int h = matrix.getHeight();
            WritableImage img = new WritableImage(w, h);
            PixelWriter px = img.getPixelWriter();
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    px.setArgb(x, y, matrix.get(x, y) ? 0xFF000000 : 0xFFFFFFFF);
                }
            }
            return img;
        } catch (Exception e) {
            return null;
        }
    }
}
