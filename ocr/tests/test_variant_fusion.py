import copy
import os
import sys
import unittest
from unittest.mock import patch

from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.dirname(__file__)))
from variant_fusion import fuse_detections
from image_variants import build_variants


def box(text, left=20, top=20, right=220, bottom=40, confidence=0.6):
    return dict(text=text, left=left, top=top, right=right, bottom=bottom, width=right-left,
                height=bottom-top, box=[[left, top], [right, top], [right, bottom], [left, bottom]],
                confidence=confidence, score=confidence)


def alternative(text, fingerprint, frame='crop:0', scale=1, **kwargs):
    return dict(metadata=dict(frame=frame, size=(300*scale, 200*scale), fingerprint=fingerprint),
                detections=[box(text, 20*scale, 20*scale, 220*scale, 40*scale, **kwargs)])


class VariantFusionTest(unittest.TestCase):
    def setUp(self):
        self.meta = dict(frame='crop:0', size=(300, 200), fingerprint='base')

    def test_corrects_one_row_without_duplicating_repeated_products_or_mutating_input(self):
        original = [box('LECH3 1200,08'), box('LECHE 1200,00', top=60, bottom=80, confidence=.98)]
        before = copy.deepcopy(original)
        result, summary = fuse_detections(original, self.meta, [alternative('LECHE 1200,00', 'a', confidence=.96),
                                                               alternative('LECHE 1200,00', 'b', confidence=.94)])
        self.assertEqual(['LECHE 1200,00', 'LECHE 1200,00'], [item['text'] for item in result])
        self.assertEqual(before, original)
        self.assertEqual([item['box'] for item in original], [item['box'] for item in result])
        self.assertEqual(.94, result[0]['confidence'])
        self.assertEqual(1, summary['acceptedRegions'])

    def test_maps_scaled_alternatives_in_the_same_explicit_frame(self):
        result, _ = fuse_detections([box('LECH3 1200,00')], self.meta,
                                   [alternative('LECHE 1200,00', str(scale), scale=scale, confidence=.95) for scale in (2, 3)])
        self.assertEqual('LECHE 1200,00', result[0]['text'])

    def test_equal_sizes_do_not_make_different_crops_or_rotations_compatible(self):
        original = [box('LECH3 1200,00')]
        for frame in ('full:0', 'crop:90', 'geometry:0'):
            result, summary = fuse_detections(original, self.meta,
                [alternative('LECHE 1200,00', key, frame=frame, confidence=.98) for key in ('a', 'b')])
            self.assertEqual(original, result)
            self.assertEqual(0, summary['acceptedRegions'])

    def test_duplicate_images_or_single_support_do_not_form_consensus(self):
        original = [box('LECH3 1200,00')]
        for fingerprints in (('a',), ('a', 'a'), ('base', 'a')):
            result, _ = fuse_detections(original, self.meta,
                [alternative('LECHE 1200,00', key, confidence=.98) for key in fingerprints])
            self.assertEqual(original, result)

    def test_contradictory_prices_keep_original_even_with_a_majority(self):
        original = [box('LECHE 1200,08')]
        result, summary = fuse_detections(original, self.meta,
            [alternative(text, str(i), confidence=.98) for i, text in enumerate(('LECHE 1200,00', 'LECHE 1200,00', 'LECHE 1700,00'))])
        self.assertEqual(original, result)
        self.assertEqual(1, summary['conflictingRegions'])

    def test_fragments_lost_signs_and_extra_amounts_cannot_replace_complete_rows(self):
        for original_text, wrong in (('LECHE 1200,00', '1200,00'), ('-500,00', '500,00'), ('1200,00', '1200,00 500,00')):
            original = [box(original_text)]
            result, _ = fuse_detections(original, self.meta,
                [alternative(wrong, key, confidence=.98) for key in ('a', 'b')])
            self.assertEqual(original, result)

    def test_split_boxes_can_recover_a_complete_row_without_changing_its_geometry(self):
        alternatives = []
        for key in ('a', 'b'):
            item = alternative('unused', key, confidence=.95)
            item['detections'] = [box('LECHE', right=125, confidence=.95), box('1200,00', left=140, confidence=.96)]
            alternatives.append(item)
        original = [box('LECH3 1200,00')]
        result, _ = fuse_detections(original, self.meta, alternatives)
        self.assertEqual('LECHE 1200,00', result[0]['text'])
        self.assertEqual(original[0]['box'], result[0]['box'])

    def test_partial_coverage_overlapping_alternatives_and_strong_originals_are_preserved(self):
        for original_confidence, pieces in ((.95, [box('LECHE 1200,00', confidence=.99)]),
                (.6, [box('LECHE 1200,00', left=160, confidence=.99)]),
                (.6, [box('LECHE 1200,00', confidence=.99), box('OTRO 1200,00', confidence=.98)])):
            original = [box('LECH3 1200,00', confidence=original_confidence)]
            candidates = [dict(metadata=dict(self.meta, fingerprint=key), detections=pieces) for key in ('a', 'b')]
            result, _ = fuse_detections(original, self.meta, candidates)
            self.assertEqual(original, result)

    def test_generator_labels_shared_frames_explicitly_before_resizing(self):
        with patch('image_variants.remove_physical_lines', return_value=None):
            candidates = {name: metadata for name, image, metadata in build_variants(
                Image.new('RGB', (80, 120), 'white'), document_corrections=False, with_metadata=True)}
        self.assertEqual(candidates['cropped-original']['frame'], candidates['enhanced-original']['frame'])
        self.assertNotEqual(candidates['original-original']['frame'], candidates['cropped-original']['frame'])
        self.assertNotEqual(candidates['cropped-original']['frame'], candidates['cropped-rot180']['frame'])
        self.assertEqual((160, 240), candidates['enhanced-original']['size'])


if __name__ == '__main__':
    unittest.main()
