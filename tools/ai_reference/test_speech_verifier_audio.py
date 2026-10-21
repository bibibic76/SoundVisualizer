import os
from pathlib import Path
import unittest
import numpy as np

from evaluate_speech_verifier import MODEL_SHA, Silero, audio_blocks, digest
from preprocess import resample_mono_float_to_16k_custom


class SpeechVerifierAudioTest(unittest.TestCase):
    def test_chunked_resampling_matches_reference_at_multiple_rates(self):
        for rate in (8000, 16000, 22050, 44100, 48000):
            samples = np.random.default_rng(414).normal(0, .1, rate // 3).astype(np.float32)
            blocks = list(audio_blocks(samples, rate))
            actual = np.concatenate([audio for _, audio in blocks])
            expected = resample_mono_float_to_16k_custom(samples, rate, len(actual))
            np.testing.assert_array_equal(actual, expected)

    def test_no_future_sample_changes_already_available_blocks(self):
        for rate in (8000, 16000, 22050, 44100, 48000):
            audio = np.random.default_rng(414).normal(0, .1, rate).astype(np.float32)
            boundary = 512 * 4
            modified = audio.copy()
            modified[boundary:] *= -10.
            for (block, a), (_, b) in zip(audio_blocks(audio, rate), audio_blocks(modified, rate)):
                if block.available <= boundary * 1000. / rate:
                    np.testing.assert_array_equal(a, b)

    def test_complete_blocks_only_and_capture_delay_is_included(self):
        self.assertEqual(list(audio_blocks(np.zeros(511, np.float32), 16000)), [])
        block, _ = next(audio_blocks(np.zeros(5000, np.float32), 44100))
        self.assertGreaterEqual(block.available, block.end)
        self.assertAlmostEqual(block.available * 44100 / 1000 % 512, 0.)


@unittest.skipUnless(os.environ.get("SOVIS_VERIFIER_MODEL"), "Pinned ONNX model not provided")
class SpeechVerifierModelTest(unittest.TestCase):
    def test_real_onnx_state_reset_and_input_contract(self):
        path = Path(os.environ["SOVIS_VERIFIER_MODEL"])
        self.assertEqual(digest(path), MODEL_SHA)
        model = Silero(path)
        data = np.random.default_rng(414).normal(0, .1, 512).astype(np.float32)
        first = model.score(data)
        self.assertTrue(0 <= first <= 1)
        self.assertEqual(model.state.shape, (2, 1, 128))
        self.assertEqual(model.context.shape, (1, 64))
        self.assertTrue(np.any(model.state != 0))
        for _ in range(5):
            model.score(data)
        model.reset()
        self.assertEqual(model.score(data), first)
        with self.assertRaises(ValueError):
            model.score(data[:511])


if __name__ == "__main__":
    unittest.main()
