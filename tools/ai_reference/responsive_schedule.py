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


@dataclass(frozen=True)
class RuntimeProfile:
    """Declared synthetic costs, NOT a distribution measured on Android.

    Wall-time buckets avoid feeding baseline/candidate different cost sequences
    merely because one makes an extra call. early-spike is deliberately
    adversarial: only speculative calls unexpectedly take 200ms.
    """
    name: str = "fixed"
    fixed_ms: float = 25.0

    def duration(self, started_ms, speculative):
        if self.name == "early-spike":
            return 200.0 if speculative else 20.0
        if self.name in ("variable", "mixed"):
            return (15.0, 20.0, 180.0, 20.0, 15.0, 350.0, 20.0, 20.0)[int(started_ms // 250) % 8]
        return self.fixed_ms

    def lateness(self, deadline_ms, speculative):
        if self.name in ("wake-jitter", "mixed"):
            return 80.0 if speculative else (0.0, 40.0, 0.0, 90.0)[int(deadline_ms // 250) % 4]
        return 0.0


@dataclass
class ProductionSlots(BorrowedSlots):
    """Mirror start(): each regular start rephases its next deadline.

    A rejected early tick restores the OLD regular deadline only if the actual
    completion +100ms still fits. Unexpected cost can therefore delay fallback.
    This model exposes that risk instead of assuming the 25ms estimate holds.
    """
    profile: RuntimeProfile = RuntimeProfile()
    last_duration: float = 0.0

    @property
    def deadline(self):
        planned = self.regular if self.early is None else self.early
        return planned + self.profile.lateness(planned, self.early is not None)

    def on_change(self, now_ms):
        early = now_ms + 100.0
        if (self.early is None and self.last_duration <= 25.0 and
                early + self.last_duration + 100.0 <= self.regular):
            self.early = early
            self.borrowed = True
            self.advances += 1

    def can_start_early(self, now_ms):
        return now_ms + self.last_duration + 100.0 <= self.regular

    def complete(self, started_ms, duration_ms):
        self.last_finished = started_ms + duration_ms
        self.last_duration = duration_ms
        if self.early is not None:
            self.regular = max(self.regular + self.interval_ms, self.last_finished + 100.0)
        else:
            self.regular = max(started_ms + self.interval_ms, self.last_finished + 100.0)
        self.early = None
        self.borrowed = False
