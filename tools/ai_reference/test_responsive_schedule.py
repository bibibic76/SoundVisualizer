import unittest

from responsive_schedule import BorrowedSlots, ChangeDetector, SilenceGate


class ResponsiveScheduleTest(unittest.TestCase):
    def test_borrows_two_slots_and_repays_without_increasing_slot_count(self):
        scheduler = BorrowedSlots()
        for now in (0, 250, 500, 750):
            self.assertEqual(now, scheduler.deadline)
            scheduler.complete(now, 25)
        scheduler.on_change(800)
        self.assertEqual(875, scheduler.deadline)
        scheduler.complete(875, 25)
        self.assertEqual(1125, scheduler.deadline)
        scheduler.complete(1125, 25)
        self.assertEqual(1500, scheduler.deadline)

    def test_repeated_changes_cannot_overwrite_pending_or_followup_slot(self):
        scheduler = BorrowedSlots(regular=1000)
        scheduler.on_change(800)
        scheduler.on_change(810)
        self.assertEqual(850, scheduler.deadline)
        scheduler.complete(850, 25)
        scheduler.on_change(1000)
        self.assertEqual(1100, scheduler.deadline)

    def test_slow_tick_keeps_rest_and_skips_expired_slots(self):
        scheduler = BorrowedSlots()
        scheduler.complete(0, 700)
        self.assertGreaterEqual(scheduler.deadline, 800)

    def test_detector_cooldown_and_continuous_sound(self):
        detector = ChangeDetector()
        events = [time for time in range(10, 2001, 10) if detector.update(time, 0.1, 0.01)]
        self.assertEqual([10], events)
        for time in range(2010, 2501, 10):
            detector.update(time, 0, 0)
        self.assertTrue(detector.update(2510, 0.1, 0.01))

    def test_silence_gate_needs_two_drained_completions(self):
        gate = SilenceGate()
        self.assertFalse(gate.is_open(0))
        gate.capture(100, 0.1)
        gate.complete(1000)
        self.assertTrue(gate.is_open(2000))
        gate.complete(1100)
        self.assertTrue(gate.is_open(2000))
        gate.complete(1350)
        self.assertFalse(gate.is_open(2000))


if __name__ == "__main__":
    unittest.main()
