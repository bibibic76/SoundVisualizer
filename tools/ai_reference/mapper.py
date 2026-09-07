"""YAMNet 521-class keyword mapping — no algorithm changes."""

from __future__ import annotations


def translate_to_korean(display_name: str) -> str:
    if not display_name:
        return "알 수 없음"
    s = display_name.lower()

    if "gunshot" in s or "gunfire" in s:
        return "총소리"
    if "machine gun" in s:
        return "기관총"
    if "explosion" in s or "폭발" in s:
        return "폭발음"
    if "artillery" in s or "fusillade" in s or "cap gun" in s:
        return "총소리"

    if "footstep" in s or "footsteps" in s:
        return "발소리"
    if "siren" in s or "civil defense" in s:
        return "사이렌"
    if "horn" in s:
        return "경적"
    if "car" in s or "truck" in s or "bus" in s or "motorcycle" in s:
        return "자동차"
    if "helicopter" in s:
        return "헬리콥터"
    if "engine" in s or "idling" in s or "accelerating" in s:
        return "엔진소리"
    if "alarm" in s or "smoke detector" in s:
        return "사이렌"
    if "police car" in s or "ambulance" in s or "fire engine" in s or "fire truck" in s:
        return "사이렌"

    if (
        "speech" in s
        or "conversation" in s
        or "narration" in s
        or "speaking" in s
        or "babbling" in s
    ):
        return "사람 목소리"
    if (
        "shout" in s
        or "screaming" in s
        or "yell" in s
        or "laughter" in s
        or "crying" in s
        or "sobbing" in s
    ):
        return "사람 소리"
    if "music" in s:
        return "음악"
    if "wind" in s or "rustling leaves" in s:
        return "바람 소리"
    if "rain" in s or "water" in s or "ocean" in s or "waves" in s:
        return "비/물 소리"
    if (
        "animal" in s
        or "dog" in s
        or "cat" in s
        or "bird" in s
        or "bark" in s
        or "meow" in s
    ):
        return "동물 소리"
    if "door" in s or "knock" in s or "slam" in s:
        return "문 소리"

    return display_name


def is_generic_sound_effect_label(display_name: str) -> bool:
    if not display_name:
        return False
    return "sound effect" in display_name.lower()


def map_display_name_to_coarse(display_name: str) -> str:
    if not display_name:
        return "ambient"

    s = display_name.lower()

    if _matches_danger(s):
        return "danger"
    if _matches_speech(s):
        return "speech"
    if _matches_ambient(s):
        return "ambient"

    return "ambient"


def _matches_temporary_gunshot_proxy_danger(s: str) -> bool:
    if "plop" in s:
        return True
    if "gargling" in s:
        return True
    if s == "rain" or "raindrop" in s or "rain on surface" in s:
        return True
    if "waterfall" in s:
        return True
    return False


def _matches_danger(s: str) -> bool:
    if _matches_temporary_gunshot_proxy_danger(s):
        return True

    if "footstep" in s or "footsteps" in s:
        return True
    if (
        "gunshot" in s
        or "gunfire" in s
        or "machine gun" in s
        or "artillery" in s
        or "fusillade" in s
        or "cap gun" in s
    ):
        return True
    if "explosion" in s or "fireworks" in s or "firecracker" in s:
        return True
    if "civil defense siren" in s:
        return True
    if "police car" in s and "siren" in s:
        return True
    if "ambulance" in s and "siren" in s:
        return True
    if "fire engine" in s or "fire truck" in s:
        return True
    if "siren" in s and "telephone" not in s:
        return True
    if "smoke detector" in s or "fire alarm" in s:
        return True
    if s == "alarm" or "car alarm" in s:
        return True
    return False


def _matches_speech(s: str) -> bool:
    if "speech" in s or "conversation" in s or "narration" in s:
        return True
    if "speaking" in s or "babbling" in s:
        return True
    if "shout" in s or "whisper" in s or "screaming" in s:
        return True
    if "laughter" in s or "crying" in s or "sobbing" in s:
        return True
    if "singing" in s or "choir" in s or "rapping" in s:
        return True
    if "crowd" in s or "chatter" in s or "hubbub" in s:
        return True
    if "children playing" in s:
        return True
    return False


def _matches_ambient(s: str) -> bool:
    if "wind" in s or "rustling leaves" in s:
        return True
    if "traffic" in s:
        return True
    if "music" in s or s.endswith(" music"):
        return True
    if "environmental noise" in s or "field recording" in s:
        return True
    if "ocean" in s or "waves" in s or "rain" in s or "thunder" in s:
        return True
    if "white noise" in s or "pink noise" in s or "static" in s:
        return True
    return False
