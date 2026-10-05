package com.opencode.facturas.service;

import com.opencode.facturas.model.OcrPreview;
import com.opencode.facturas.model.OcrResult;
import com.opencode.facturas.model.OcrReviewPage;

import java.util.ArrayList;
import java.util.List;

public final class OcrReviewMapper {
    private OcrReviewMapper() {
    }

    public static List<OcrReviewPage> pages(OcrResult result) {
        List<OcrResult> sources = result.pages().isEmpty() ? List.of(result) : result.pages();
        List<OcrReviewPage> review = new ArrayList<>();
        for (int index = 0; index < sources.size(); index++) {
            OcrResult page = sources.get(index);
            OcrPreview preview = page.preview();
            review.add(new OcrReviewPage(index + 1, "pdf-text".equals(page.variant()) ? "pdf-text" : "ocr",
                    preview == null ? null : preview.imageDataUrl(),
                    preview == null ? 0 : preview.width(), preview == null ? 0 : preview.height(),
                    page.detections()));
        }
        return List.copyOf(review);
    }
}
