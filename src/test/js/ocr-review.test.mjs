import test from 'node:test';
import assert from 'node:assert/strict';
import {normalizeReview, cropCoordinates, sourceSelection} from '../../main/resources/static/ocr-review.mjs';

test('unknown confidence is doubtful, zero is not lost, native PDF text stays distinct', () => {
    const detections = [{text: 'CERO', confidence: 0}, {text: 'DUDOSO', confidence: null}, {text: 'CLARO', confidence: .95}];
    const [ocr, native] = normalizeReview([{detections}, {source: 'pdf-text', detections}]);
    assert.deepEqual(ocr.detections.map(item => item.doubtful), [true, true, false]);
    assert.equal(ocr.detections[0].confidence, 0);
    assert.ok(native.detections.every(item => !item.doubtful));
});

test('a price conflict is visible even when OCR reports high confidence', () => {
    const [page] = normalizeReview([{detections: [{text: '2800,00', confidence: .946, reviewRequired: true}]}]);
    assert.equal(page.detections[0].doubtful, true);
    assert.equal(page.detections[0].confidence, .946);
    assert.equal(page.detections[0].reviewRequired, true);
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

test('row source uses original indices after invalid detections and selects the exact page and union', () => {
    const pages = normalizeReview([{pageNumber: 1}, {pageNumber: 2, width: 500, height: 500,
        imageDataUrl: 'data:image/jpeg;base64,AAAA', detections: [null,
            {text: 'Leche', confidence: .9, box: [[10,20],[100,20],[100,40],[10,40]]},
            {text: '1200,00', confidence: .7, box: [[200,20],[300,20],[300,40],[200,40]]}]}]);
    assert.deepEqual(pages[1].detections.map(item => item.id), [1,2]);
    const selection = sourceSelection(pages, [{pageNumber: 2, detectionIndices: [1,2]}]);
    assert.equal(selection.pageIndex, 1);
    assert.deepEqual(selection.item.bounds, {left: 10, top: 20, right: 300, bottom: 40});
    assert.equal(selection.item.confidence, .7);
    assert.equal(sourceSelection(pages, [{pageNumber: 2, detectionIndices: [0,1]}]), null);
    assert.equal(sourceSelection(pages, [{pageNumber: 3, detectionIndices: [1]}]), null);
});
