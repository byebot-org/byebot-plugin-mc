package dev.ronaldzav.byebot.antibot.verification;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Renders a URL as a QR code using Unicode full-block characters (█).
 * Each QR module becomes one BLACK or WHITE colored █ character.
 * Returns one {@link Component} per row so callers can join them with newlines.
 */
public final class QrCodeRenderer {

    private static final String BLOCK = "█";

    private QrCodeRenderer() {}

    /**
     * Encodes {@code url} as a QR code and returns one {@link Component} per module row.
     *
     * @throws WriterException if ZXing cannot encode the URL
     */
    public static List<Component> render(String url) throws WriterException {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.MARGIN, 1);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.L);

        BitMatrix matrix = new QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 0, 0, hints);
        int width  = matrix.getWidth();
        int height = matrix.getHeight();

        List<Component> lines = new ArrayList<>(height);
        for (int y = 0; y < height; y++) {
            TextComponent.Builder line = Component.text();
            for (int x = 0; x < width; x++) {
                line.append(Component.text(BLOCK)
                        .color(matrix.get(x, y) ? NamedTextColor.BLACK : NamedTextColor.WHITE));
            }
            lines.add(line.build());
        }
        return lines;
    }
}
