import importlib.util
import json
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("roster", HERE / "prepare-artist-roster.py")
roster = importlib.util.module_from_spec(spec)
spec.loader.exec_module(roster)
MOCK = Path(__file__).resolve().parents[3] / "dev/catalog/frontend-mock-catalog.json"
FESTIVAL = "ec00912b-763f-4f8f-8f57-4bdfc389ccbf"
REVISION = "11111111-1111-4111-8111-111111111111"


class PrepareArtistRosterTest(unittest.TestCase):
    def setUp(self):
        self.base = json.loads(MOCK.read_text(encoding="utf-8"))
        self.base["festivalId"] = FESTIVAL
        self.base["baselineRevisionId"] = REVISION
        self.draft = json.loads(roster.DRAFT.read_text(encoding="utf-8"))

    def build(self):
        return roster.candidate(self.base, self.draft, FESTIVAL, REVISION)

    def test_replaces_only_artist_roster_and_its_performances(self):
        result = self.build()
        self.assertEqual(REVISION, result["baselineRevisionId"])
        self.assertEqual(FESTIVAL, result["festivalId"])
        expected_ids = {
            "nct-wish", "nowimyoung", "kim-haon", "rescene",
            "heegyu", "ahof", "alphadrive1", "fromis-9",
        }
        self.assertEqual(expected_ids, {row["id"] for row in result["artists"]
                                        if row["category"] == "ARTIST"})
        self.assertFalse(any(row["id"].startswith("mock-artist") for row in result["artists"]))
        self.assertEqual(expected_ids, {row["artistId"] for row in result["artistLinks"]
                                        if row["artistId"] in expected_ids})
        self.assertEqual(expected_ids, {row["artistId"] for row in result["artistLinkTranslations"]
                                        if row["artistId"] in expected_ids})
        self.assertEqual([row for row in self.base["artists"] if row["category"] == "CONTEST"],
                         [row for row in result["artists"] if row["category"] == "CONTEST"])
        for key in self.base.keys() - {
            "artists", "artistTranslations", "artistLinks", "artistLinkTranslations",
            "artistSongs", "artistSongTranslations", "performances",
            "performanceTranslations", "performanceArtists",
        }:
            self.assertEqual(self.base[key], result[key], key)
        for key in roster.ARTIST_CHILDREN:
            original = [row for row in self.base[key] if row["artistId"].startswith("mock-contest")]
            current = [row for row in result[key] if row["artistId"].startswith("mock-contest")]
            self.assertEqual(original, current, key)
        contest_performances = {row["id"] for row in self.base["performances"]
                                if row["id"] == "mock-performance-1"}
        for key in ("performances", "performanceTranslations", "performanceArtists"):
            id_key = "id" if key == "performances" else "performanceId"
            self.assertEqual([row for row in self.base[key] if row[id_key] in contest_performances],
                             [row for row in result[key] if row[id_key] in contest_performances])
        self.assertFalse(any(row["id"] in {"mock-performance-2", "mock-performance-3",
                                           "mock-performance-4"} for row in result["performances"]))
        by_day = {}
        for performance in result["performances"]:
            if performance["id"].startswith("performance-"):
                by_day[performance["festivalDate"]] = by_day.get(performance["festivalDate"], 0) + 1
        self.assertEqual({"2026-09-29": 4, "2026-10-01": 4}, by_day)
        performance_dates = {row["id"]: row["festivalDate"] for row in result["performances"]}
        artist_links_by_day = {day: 0 for day in sorted(roster.DAYS)}
        for row in result["performanceArtists"]:
            if row["artistId"] in expected_ids:
                artist_links_by_day[performance_dates[row["performanceId"]]] += 1
        self.assertEqual({"2026-09-29": 4, "2026-09-30": 0, "2026-10-01": 4},
                         artist_links_by_day)

    def test_rejects_stale_baseline_and_unexpected_roster(self):
        self.base["baselineRevisionId"] = "22222222-2222-4222-8222-222222222222"
        with self.assertRaisesRegex(ValueError, "baselineRevisionId"):
            self.build()
        self.base["baselineRevisionId"] = REVISION
        self.base["artists"][0]["id"] = "different"
        with self.assertRaisesRegex(ValueError, "known three mock artists"):
            self.build()

    def test_rejects_mixed_performance_and_id_collision(self):
        self.base["performanceArtists"].append({
            "performanceId": "mock-performance-2", "artistId": "mock-contest-a",
            "displayOrder": 2,
        })
        with self.assertRaisesRegex(ValueError, "mixed ARTIST/CONTEST"):
            self.build()
        self.base["performanceArtists"].pop()
        self.base["artists"].append({
            "id": "nct-wish", "category": "CONTEST", "imageUrl": "https://example.invalid/x",
            "imageWidth": 1, "imageHeight": 1,
        })
        with self.assertRaisesRegex(ValueError, "real artist ID already exists"):
            self.build()


if __name__ == "__main__":
    unittest.main()
