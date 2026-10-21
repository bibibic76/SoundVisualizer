"""Prospective scheduling experiment; no app behavior changes.

Advance two existing 250ms slots after an onset, then return to the original
schedule. This pays back the advance instead of increasing the steady cadence.
The longer recovery gap and early weak windows must be evaluated, not hidden.
"""
from dataclasses import dataclass
import math


class ChangeDetector:
    def __init__(self):
        self.previous_time = None
        self.last_active = -math.inf
        self.last_event = -math.inf
        self.power = 0.0

    def update(self, now_ms, peak, power):
        duration = 10.0 if self.previous_time is None else max(0.0, now_ms - self.previous_time)
        active = peak > 0.01
        resumed = active and now_ms - self.last_active >= 100.0
        stronger = active and power > max(0.0001, self.power * 4.0)
        trigger = (resumed or stronger) and now_ms - self.last_event >= 1000.0
        alpha = -math.expm1(-duration / 500.0)
        self.power += alpha * (power - self.power)
        if active:
            self.last_active = now_ms
        if trigger:
            self.last_event = now_ms
        self.previous_time = now_ms
        return trigger


@dataclass
class BorrowedSlots:
    delay_ms: int = 50
    interval_ms: int = 250
    followup: bool = True
    regular: float = 0.0
    early: float | None = None
    borrowed: bool = False
    last_finished: float = -math.inf
    advances: int = 0

    @property
    def deadline(self):
        return self.regular if self.early is None else self.early

    def on_change(self, now_ms):
        if self.borrowed or self.early is not None:
            return
        ready = max(now_ms + self.delay_ms, self.last_finished + 100.0)
        if ready < self.regular:
            self.early = ready
            self.borrowed = True
            self.advances += 1

    def complete(self, started_ms, duration_ms):
        self.last_finished = started_ms + duration_ms
        was_early = self.early is not None
        self.regular += self.interval_ms
        if was_early and self.borrowed and self.followup:
            self.early = max(started_ms + self.interval_ms, self.last_finished + 100.0)
            self.borrowed = False
        else:
            self.early = None
            self.borrowed = False
        # Never replay overdue slots in a burst on a slow device.
        earliest = self.last_finished + 100.0
        self.regular = max(self.regular, earliest)
        if self.early is not None and self.early >= self.regular:
            self.early = None


class SilenceGate:
    """AiSilenceGate semantics on the experiment's capture-chunk time axis."""
    def __init__(self):
        self.last_active = -math.inf
        self.silent_completions = 0

    def capture(self, now_ms, peak):
        if peak > 0.01:
            self.last_active = now_ms
            self.silent_completions = 0

    def is_open(self, now_ms):
        return self.last_active != -math.inf and (
            now_ms - self.last_active < 975 or self.silent_completions < 2
        )

    def complete(self, snapshot_ms):
        if self.last_active != -math.inf and snapshot_ms - self.last_active >= 975:
            self.silent_completions += 1


class RephasedSlots(BorrowedSlots):
    """Alternative: keep 250ms cadence after an early tick, without recovery debt."""
    def complete(self, started_ms, duration_ms):
        self.last_finished = started_ms + duration_ms
        self.regular = max(started_ms + self.interval_ms, self.last_finished + 100.0)
        self.early = None
        self.borrowed = False
