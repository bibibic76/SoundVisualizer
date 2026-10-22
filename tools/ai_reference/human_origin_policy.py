"""#421 approved human-origin Speech policy, shared by diagnostic datasets."""
from mapper import map_display_name_to_coarse

POLICY_ID = "human-origin-v1"
CATEGORY_LABELS = {
    "sneezing": "Sneeze", "coughing": "Cough", "breathing": "Breathing",
    "laughing": "Laughter", "crying_baby": "Baby cry, infant cry",
    "korean_read": "Speech", "read_speech": "Speech", "short_digit": "Speech",
    "singing": "Singing", "instrumental_music": "Music",
}


def expected_for(row):
    category = row.get("stratum", row.get("category"))
    if category in CATEGORY_LABELS:
        return map_display_name_to_coarse(CATEGORY_LABELS[category])
    return row.get("expected", row.get("expected_hint", "uncertain"))
