package bd.edu.uiu.unipay.vendor;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;

/**
 * Renders QR payloads as PNG images (ZXing) for display on the vendor POS
 * dashboard and printing on stall counters.
 */
@Component
public class QrImageService {

    public byte[] renderPng(String payload, int size) {
        try {
            QRCodeWriter writer = new QRCodeWriter();
            BitMatrix matrix = writer.encode(payload, BarcodeFormat.QR_CODE, size, size,
                    Map.of(EncodeHintType.ERROR_CORRECTION, "M",
                           EncodeHintType.MARGIN, 1));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, "PNG", out);
            return out.toByteArray();
        } catch (IOException | com.google.zxing.WriterException ex) {
            throw new IllegalStateException("Failed to render QR code", ex);
        }
    }
}
