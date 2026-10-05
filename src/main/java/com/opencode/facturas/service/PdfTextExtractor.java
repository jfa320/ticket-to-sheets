package com.opencode.facturas.service;

import com.opencode.facturas.model.OcrDetection;
import com.opencode.facturas.model.OcrResult;
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.apache.pdfbox.util.Matrix;

import java.awt.geom.Point2D;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/** Extracts only substantial, readable digital receipt pages, with real PDF boxes. */
final class PdfTextExtractor {

    private static final Pattern PRICE = Pattern.compile("(?<!\\p{L})\\d+(?:[.,]\\d{3})*[.,]\\d{2}(?!\\d)");

    Optional<OcrResult> extractPage(PDDocument document, int pageIndex) throws IOException {
        if (!document.getCurrentAccessPermission().canExtractContent()) {
            return Optional.empty();
        }
        PDPage page = document.getPage(pageIndex);
        ImageCoverage images = new ImageCoverage(page);
        images.processPage(page);
        if (images.hasSubstantialImage()) {
            return Optional.empty();
        }

        PositionedText stripper = new PositionedText();
        stripper.setSortByPosition(true);
        stripper.setShouldSeparateByBeads(false);
        stripper.setStartPage(pageIndex + 1);
        stripper.setEndPage(pageIndex + 1);
        stripper.setLineSeparator("\n");
        String text = stripper.getText(document).replace('\u00a0', ' ').trim();
        if (!isUseful(text) || stripper.detections.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new OcrResult(text, List.of(), stripper.detections, "pdf-text", null));
    }

    private boolean isUseful(String text) {
        List<String> lines = text.lines().map(String::trim).filter(line -> !line.isBlank()).toList();
        long characters = text.codePoints().filter(character -> !Character.isWhitespace(character)).count();
        long letters = text.codePoints().filter(Character::isLetter).count();
        long alphanumeric = text.codePoints().filter(Character::isLetterOrDigit).count();
        long invalid = text.codePoints().filter(character -> character == 0xfffd
                || Character.isISOControl(character) && !Character.isWhitespace(character)
                || Character.getType(character) == Character.PRIVATE_USE
                || Character.getType(character) == Character.UNASSIGNED).count();
        long priceRows = lines.stream().filter(line -> PRICE.matcher(line).find()
                && line.codePoints().anyMatch(Character::isLetter)).count();
        return lines.size() >= 3 && characters >= 60 && letters >= 25
                && alphanumeric >= characters * 0.65 && invalid == 0 && priceRows >= 2;
    }

    private static final class PositionedText extends PDFTextStripper {
        private final List<OcrDetection> detections = new ArrayList<>();

        @Override
        protected void writeString(String text, List<TextPosition> positions) throws IOException {
            super.writeString(text, positions);
            if (text.isBlank() || positions.isEmpty()) {
                return;
            }
            double left = Double.POSITIVE_INFINITY;
            double top = Double.POSITIVE_INFINITY;
            double right = Double.NEGATIVE_INFINITY;
            double bottom = Double.NEGATIVE_INFINITY;
            for (TextPosition position : positions) {
                left = Math.min(left, position.getXDirAdj());
                right = Math.max(right, position.getXDirAdj() + position.getWidthDirAdj());
                top = Math.min(top, position.getYDirAdj() - position.getHeightDir());
                bottom = Math.max(bottom, position.getYDirAdj());
            }
            if (Double.isFinite(left) && Double.isFinite(top) && Double.isFinite(right)
                    && Double.isFinite(bottom) && right > left && bottom > top) {
                detections.add(new OcrDetection(text.trim(), null, List.of(
                        List.of(left, top), List.of(right, top), List.of(right, bottom), List.of(left, bottom))));
            }
        }
    }

    /** Checks displayed image area, including images inside forms and inline images. */
    private static final class ImageCoverage extends PDFGraphicsStreamEngine {
        private final double pageArea;
        private double imageArea;
        private double largestImageArea;
        private Point2D point = new Point2D.Float();

        private ImageCoverage(PDPage page) {
            super(page);
            pageArea = (double) page.getCropBox().getWidth() * page.getCropBox().getHeight();
        }

        private boolean hasSubstantialImage() {
            return largestImageArea >= pageArea * 0.25 || imageArea >= pageArea * 0.40;
        }

        @Override
        public void drawImage(PDImage image) {
            Matrix matrix = getGraphicsState().getCurrentTransformationMatrix();
            double area = Math.abs((double) matrix.getScaleX() * matrix.getScaleY()
                    - (double) matrix.getShearX() * matrix.getShearY());
            imageArea += area;
            largestImageArea = Math.max(largestImageArea, area);
        }

        @Override
        public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) { point = p0; }
        @Override
        public void clip(int windingRule) { }
        @Override
        public void moveTo(float x, float y) { point = new Point2D.Float(x, y); }
        @Override
        public void lineTo(float x, float y) { point = new Point2D.Float(x, y); }
        @Override
        public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) {
            point = new Point2D.Float(x3, y3);
        }
        @Override
        public Point2D getCurrentPoint() { return point; }
        @Override
        public void closePath() { }
        @Override
        public void endPath() { }
        @Override
        public void strokePath() { }
        @Override
        public void fillPath(int windingRule) { }
        @Override
        public void fillAndStrokePath(int windingRule) { }
        @Override
        public void shadingFill(COSName shadingName) { }
    }
}
