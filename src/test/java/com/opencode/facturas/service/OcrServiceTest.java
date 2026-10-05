package com.opencode.facturas.service;

import com.opencode.facturas.model.OcrDetection;
import com.opencode.facturas.model.OcrLine;
import com.opencode.facturas.model.OcrResult;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OcrServiceTest {

    @Test
    void preservesFaintTextAndColorWhenDelegatingToClientAndMapper() throws Exception {
        OcrResultMapper mapper = mock(OcrResultMapper.class);
        OcrApiClient client = mock(OcrApiClient.class);
        PdfPageRenderer pdfRenderer = mock(PdfPageRenderer.class);
        OcrResult expected = new OcrResult("PRODUCTO 123,45", List.of(), List.of(), "enhanced", 42.5);

        when(client.recognize(any(BufferedImage.class))).thenReturn("respuesta-json");
        when(mapper.parse("respuesta-json")).thenReturn(expected);

        BufferedImage source = new BufferedImage(2, 3, BufferedImage.TYPE_INT_RGB);
        source.setRGB(0, 0, 0xC3C3C3);
        source.setRGB(1, 0, 0xE6E6E6);
        source.setRGB(0, 1, 0x336699);
        OcrResult result = new OcrService(mapper, client, pdfRenderer).extract(
                new MockMultipartFile("file", "ticket.PNG", "image/png", pngBytes(source))
        );

        assertThat(result).isSameAs(expected);
        ArgumentCaptor<BufferedImage> imageCaptor = ArgumentCaptor.forClass(BufferedImage.class);
        verify(client).recognize(imageCaptor.capture());
        assertThat(imageCaptor.getValue().getWidth()).isEqualTo(34);
        assertThat(imageCaptor.getValue().getHeight()).isEqualTo(35);
        assertThat(imageCaptor.getValue().getType()).isEqualTo(BufferedImage.TYPE_INT_RGB);
        assertThat(imageCaptor.getValue().getRGB(16, 16) & 0xFFFFFF).isEqualTo(0xC3C3C3);
        assertThat(imageCaptor.getValue().getRGB(17, 16) & 0xFFFFFF).isEqualTo(0xE6E6E6);
        assertThat(imageCaptor.getValue().getRGB(16, 17) & 0xFFFFFF).isEqualTo(0x336699);
        assertThat(imageCaptor.getValue().getRGB(0, 0) & 0xFFFFFF).isEqualTo(0xFFFFFF);
        verify(pdfRenderer, never()).forEachPage(any(), anyBoolean(), any());
    }

    @Test
    void rendersAndMergesEveryPdfPage() throws Exception {
        OcrResultMapper mapper = mock(OcrResultMapper.class);
        OcrApiClient client = mock(OcrApiClient.class);
        PdfPageRenderer pdfRenderer = mock(PdfPageRenderer.class);
        BufferedImage firstImage = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        BufferedImage secondImage = new BufferedImage(3, 3, BufferedImage.TYPE_INT_RGB);
        OcrLine line = new OcrLine("UNO", 1.0, 0.9, 0, 0, 1, 1);
        OcrDetection detection = new OcrDetection("DOS", 0.8, List.of(List.of(0.0, 0.0)));

        doAnswer(invocation -> {
            PdfPageRenderer.HybridPageProcessor processor = invocation.getArgument(2);
            processor.process(1, 2, null, firstImage);
            processor.process(2, 2, null, secondImage);
            return null;
        }).when(pdfRenderer).forEachPage(any(), anyBoolean(), any());
        when(client.recognize(any(BufferedImage.class))).thenReturn("pagina-1", "pagina-2");
        when(mapper.parse("pagina-1")).thenReturn(new OcrResult("UNO", List.of(line), List.of(), "original", 10.0));
        when(mapper.parse("pagina-2")).thenReturn(new OcrResult("DOS", List.of(), List.of(detection), "rotated", 30.0));

        OcrResult result = new OcrService(mapper, client, pdfRenderer).extract(
                new MockMultipartFile("file", "factura.pdf", "application/pdf", pdfWithPages(2))
        );

        assertThat(result.text()).isEqualTo("UNO\n\nDOS");
        assertThat(result.lines()).containsExactly(line);
        assertThat(result.detections()).containsExactly(detection);
        assertThat(result.pages()).extracting(OcrResult::text).containsExactly("UNO", "DOS");
        assertThat(result.variant()).isEqualTo("original;rotated");
        assertThat(result.score()).isEqualTo(20.0);
        verify(pdfRenderer).forEachPage(any(), anyBoolean(), any());
    }

    @Test
    void detectsPdfFromItsSignatureEvenWhenTheFilenameDoesNotEndInPdf() throws Exception {
        OcrResultMapper mapper = mock(OcrResultMapper.class);
        OcrApiClient client = mock(OcrApiClient.class);
        PdfPageRenderer pdfRenderer = mock(PdfPageRenderer.class);
        BufferedImage page = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        OcrResult expected = new OcrResult("PRODUCTO 123,45", List.of(), List.of(), "original", 42.5);
        doAnswer(invocation -> {
            PdfPageRenderer.HybridPageProcessor processor = invocation.getArgument(2);
            processor.process(1, 1, null, page);
            return null;
        }).when(pdfRenderer).forEachPage(any(), anyBoolean(), any());
        when(client.recognize(any(BufferedImage.class))).thenReturn("pagina-1");
        when(mapper.parse("pagina-1")).thenReturn(expected);

        OcrResult result = new OcrService(mapper, client, pdfRenderer).extract(
                new MockMultipartFile("file", "factura.bin", "application/octet-stream", pdfWithPages(1))
        );

        assertThat(result.text()).isEqualTo("PRODUCTO 123,45");
        verify(pdfRenderer).forEachPage(any(), anyBoolean(), any());
        verify(client).recognize(any(BufferedImage.class));
    }

    @Test
    void rejectsPdfExtensionWhenTheContentDoesNotHaveAPdfSignature() {
        OcrService service = new OcrService(mock(OcrResultMapper.class), mock(OcrApiClient.class), mock(PdfPageRenderer.class));

        assertThatThrownBy(() -> service.extract(
                new MockMultipartFile("file", "factura.pdf", "application/pdf", new byte[]{1, 2, 3})
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no contiene una cabecera PDF válida");
    }

    @Test
    void rejectsImageDimensionsBeforeDecodingWhenTheyExceedTheConfiguredLimit() throws Exception {
        OcrService service = new OcrService(mock(OcrResultMapper.class), mock(OcrApiClient.class),
                mock(PdfPageRenderer.class), 5, 100);

        assertThatThrownBy(() -> service.extract(
                new MockMultipartFile("file", "ticket.png", "image/png", pngBytes(2, 3))
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("La imagen supera las dimensiones admitidas");
    }

    @Test
    void rejectsUnsupportedFileContentWithUserInputError() {
        OcrService service = new OcrService(mock(OcrResultMapper.class), mock(OcrApiClient.class), mock(PdfPageRenderer.class));

        assertThatThrownBy(() -> service.extract(
                new MockMultipartFile("file", "factura.txt", "text/plain", "no es imagen".getBytes())
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Formato de imagen no soportado.");
    }

    @Test
    void rejectsSuccessfulOcrResponseWithoutUsefulText() throws Exception {
        OcrResultMapper mapper = mock(OcrResultMapper.class);
        OcrApiClient client = mock(OcrApiClient.class);
        PdfPageRenderer pdfRenderer = mock(PdfPageRenderer.class);
        when(client.recognize(any(BufferedImage.class))).thenReturn("respuesta-json");
        when(mapper.parse("respuesta-json")).thenReturn(new OcrResult("  ", List.of(), List.of(), null, null));

        OcrService service = new OcrService(mapper, client, pdfRenderer);

        assertThatThrownBy(() -> service.extract(
                new MockMultipartFile("file", "ticket.png", "image/png", pngBytes(1, 1))
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("PaddleOCR no detecto texto util en la factura.");
    }

    private byte[] pngBytes(int width, int height) throws Exception {
        return pngBytes(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB));
    }

    private byte[] pngBytes(BufferedImage image) throws Exception {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", output);
            return output.toByteArray();
        }
    }

    private byte[] pdfWithPages(int pageCount) throws Exception {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (int index = 0; index < pageCount; index++) {
                document.addPage(new PDPage(new PDRectangle(36, 72)));
            }
            document.save(output);
            return output.toByteArray();
        }
    }
}
