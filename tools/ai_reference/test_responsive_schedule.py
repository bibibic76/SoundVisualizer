import unittest

from responsive_schedule import BorrowedSlots, ChangeDetector, ProductionSlots, RuntimeProfile, SilenceGate


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

    def test_production_regular_tick_rephases_after_late_wakeup(self):
        scheduler = ProductionSlots(regular=250)
        scheduler.complete(290, 20)
        self.assertEqual(540, scheduler.regular)

    def test_production_estimate_boundary_and_late_early_wakeup(self):
        scheduler = ProductionSlots(regular=2000, last_finished=1775, last_duration=25)
        scheduler.on_change(1775)
        self.assertEqual(1875, scheduler.early)
        self.assertTrue(scheduler.can_start_early(1875))
        self.assertFalse(scheduler.can_start_early(1876))
        scheduler = ProductionSlots(regular=2000, last_finished=1776, last_duration=26)
        scheduler.on_change(1776)
        self.assertIsNone(scheduler.early)

    def test_runtime_profile_is_wall_time_based_except_explicit_adversary(self):
        profile = RuntimeProfile("variable")
        self.assertEqual(profile.duration(510, False), profile.duration(510, True))
        self.assertEqual(180, profile.duration(510, False))
        self.assertEqual(200, RuntimeProfile("early-spike").duration(1870, True))


if __name__ == "__main__":
    unittest.main()
