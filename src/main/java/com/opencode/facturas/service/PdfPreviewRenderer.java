package com.opencode.facturas.service;

import com.opencode.facturas.model.OcrPreview;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import javax.imageio.ImageIO;
import javax.imageio.IIOImage;
import javax.imageio.ImageWriteParam;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;

/** Vista acotada en memoria; las coordenadas siguen expresadas en puntos PDF. */
final class PdfPreviewRenderer {
    OcrPreview render(PDFRenderer renderer, PDPage page, int pageIndex) throws IOException {
        int width = Math.max(1, Math.round(page.getCropBox().getWidth()));
        int height = Math.max(1, Math.round(page.getCropBox().getHeight()));
        double area = Math.ceil(page.getCropBox().getWidth()) * Math.ceil(page.getCropBox().getHeight());
        float scale = (float) Math.min(2, Math.sqrt(2_000_000.0 / area));
        // PDFTextStripper expresa las cajas en el marco de lectura sin la rotación
        // de metadatos de la página. Renderizar ese mismo marco evita desalinearlas.
        int rotation = page.getRotation();
        BufferedImage image;
        try { page.setRotation(0); image = renderer.renderImage(pageIndex, scale, ImageType.RGB); }
        finally { page.setRotation(rotation); }
        try {
            for (;;) {
                byte[] jpeg = jpeg(image);
                if (jpeg.length <= 900_000) return new OcrPreview("data:image/jpeg;base64,"
                        + Base64.getEncoder().encodeToString(jpeg), width, height);
                BufferedImage smaller = new BufferedImage(Math.max(1, image.getWidth() * 3 / 4),
                        Math.max(1, image.getHeight() * 3 / 4), BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = smaller.createGraphics();
                try {
                    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                    graphics.drawImage(image, 0, 0, smaller.getWidth(), smaller.getHeight(), null);
                } finally { graphics.dispose(); }
                image.flush(); image = smaller;
            }
        } finally { image.flush(); }
    }

    private byte[] jpeg(BufferedImage image) throws IOException {
        var writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try (var bytes = new ByteArrayOutputStream(); var output = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(output);
            var parameters = writer.getDefaultWriteParam();
            parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT); parameters.setCompressionQuality(.82f);
            writer.write(null, new IIOImage(image, null, null), parameters); output.flush();
            return bytes.toByteArray();
        } finally { writer.dispose(); }
    }
}
