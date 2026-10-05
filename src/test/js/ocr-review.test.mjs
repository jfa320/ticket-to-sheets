import test from 'node:test';
import assert from 'node:assert/strict';
import {normalizeReview, cropCoordinates} from '../../main/resources/static/ocr-review.mjs';

test('unknown confidence is doubtful, zero is not lost, native PDF text stays distinct', () => {
    const detections = [{text: 'CERO', confidence: 0}, {text: 'DUDOSO', confidence: null}, {text: 'CLARO', confidence: .95}];
    const [ocr, native] = normalizeReview([{detections}, {source: 'pdf-text', detections}]);
    assert.deepEqual(ocr.detections.map(item => item.doubtful), [true, true, false]);
    assert.equal(ocr.detections[0].confidence, 0);
    assert.ok(native.detections.every(item => !item.doubtful));
});

test('rejects external preview URLs and malformed boxes without losing OCR text', () => {
    const [page] = normalizeReview([{width: 200, height: 300, imageDataUrl: 'https://example.org/image.jpg',
        detections: [{text: '<img src=x>', box: [[0, 0], [10, 0], [NaN, 20], [0, 20]]}]}]);
    assert.equal(page.imageDataUrl, null);
    assert.equal(page.detections[0].box, null);
    assert.equal(page.detections[0].text, '<img src=x>');
    assert.deepEqual(normalizeReview(null), []);
});

test('crop maps OCR coordinates onto the actual resized JPEG pixels on both axes', () => {
    const crop = cropCoordinates({left: 100, top: 200, right: 300, bottom: 240}, {width: 1000, height: 2000}, 500, 800);
    assert.deepEqual(crop, {x: 44, y: 75.2, width: 112, height: 25.6});
});

test('crop padding stays inside the image and unavailable dimensions have no crop', () => {
    assert.deepEqual(cropCoordinates({left: 0, top: 0, right: 100, bottom: 50}, {width: 100, height: 50}, 100, 50),
        {x: 0, y: 0, width: 100, height: 50});
    assert.equal(cropCoordinates({}, {width: 0, height: 0}, 0, 0), null);
});
