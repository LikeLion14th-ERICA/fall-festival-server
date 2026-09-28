"""Build an ARTIST-only candidate from a published catalog export. Never publishes."""
import argparse
import json
from datetime import datetime, time
from pathlib import Path
from uuid import UUID

HERE = Path(__file__).resolve().parent
DRAFT = HERE / "catalog-draft.json"
MOCK_IDS = {"mock-artist-wish", "mock-artist-dawn", "mock-artist-echo"}
REAL_IDS = {
    "nct-wish", "nowimyoung", "kim-haon", "rescene",
    "heegyu", "ahof", "alphadrive1", "fromis-9",
}
DAYS = {"2026-09-29", "2026-09-30", "2026-10-01"}
ARTIST_CHILDREN = (
    "artistTranslations", "artistLinks", "artistLinkTranslations",
    "artistSongs", "artistSongTranslations",
)
PERFORMANCE_CHILDREN = ("performanceTranslations", "performanceArtists")


def require(condition, message):
    if not condition:
        raise ValueError(message)


def rows(manifest, key):
    value = manifest.get(key)
    require(isinstance(value, list) and all(isinstance(row, dict) for row in value),
            f"{key} must be an array of objects")
    return value


def identifiers(items, key, label):
    values = [row.get(key) for row in items]
    require(all(isinstance(value, str) and value for value in values),
            f"{label}.{key} is missing")
    require(len(values) == len(set(values)), f"{label}.{key} has duplicates")
    return set(values)


def validate_references(manifest, section, field, ids):
    for row in rows(manifest, section):
        require(isinstance(row.get(field), str) and row[field] in ids,
                f"{section}.{field} references an unknown row")


def performance_ids(manifest, artist_categories, allow_unlinked=False):
    performances = rows(manifest, "performances")
    ids = identifiers(performances, "id", "performances")
    by_performance = {id_: set() for id_ in ids}
    for row in rows(manifest, "performanceArtists"):
        performance = row.get("performanceId")
        artist = row.get("artistId")
        require(performance in by_performance, "performanceArtists references unknown performance")
        require(artist in artist_categories, "performanceArtists references unknown artist")
        by_performance[performance].add(artist_categories[artist])
    require(allow_unlinked or all(categories for categories in by_performance.values()),
            "unlinked performance cannot be classified safely")
    require(all(len(categories) <= 1 for categories in by_performance.values()),
            "mixed ARTIST/CONTEST performance cannot be replaced safely")
    return {id_ for id_, categories in by_performance.items() if categories == {"ARTIST"}}


def candidate(export, draft, expected_festival, expected_revision):
    require(isinstance(export, dict) and isinstance(draft, dict), "manifests must be objects")
    UUID(expected_festival)
    UUID(expected_revision)
    require(export.get("festivalId") == expected_festival, "festivalId differs from expected festival")
    require(export.get("baselineRevisionId") == expected_revision,
            "export baselineRevisionId differs from exported published revision")
    require({row.get("festivalDate") for row in rows(export, "festivalDays")} == DAYS
            and len(export["festivalDays"]) == 3, "published festival days differ from 2026 schedule")
    require({row.get("festivalDate") for row in rows(draft, "festivalDays")} == DAYS
            and len(draft["festivalDays"]) == 3, "draft festival days differ from 2026 schedule")

    old_artists = rows(export, "artists")
    old_ids = identifiers(old_artists, "id", "artists")
    categories = {row["id"]: row.get("category") for row in old_artists}
    require({id_ for id_, category in categories.items() if category == "ARTIST"} == MOCK_IDS,
            "published ARTIST roster is not the known three mock artists")
    require(all(category in {"ARTIST", "CONTEST"} for category in categories.values()),
            "unknown published artist category")
    require(not (old_ids & REAL_IDS), "real artist ID already exists in published catalog")
    for key in ARTIST_CHILDREN:
        validate_references(export, key, "artistId", old_ids)
    old_performances = performance_ids(export, categories)
    require(old_performances, "no mock ARTIST performances found")
    for key in PERFORMANCE_CHILDREN:
        validate_references(export, key, "performanceId", identifiers(rows(export, "performances"), "id", "performances"))

    draft_artists = rows(draft, "artists")
    draft_ids = identifiers(draft_artists, "id", "draft artists")
    new_artists = [row for row in draft_artists if row.get("category") == "ARTIST"]
    require({row["id"] for row in new_artists} == REAL_IDS and len(new_artists) == 8,
            "draft ARTIST roster is not the confirmed eight artists")
    for row in new_artists:
        require(isinstance(row.get("imageUrl"), str) and row["imageUrl"].startswith(
            "https://festival.likelionerica.com/artists/") and
            isinstance(row.get("imageWidth"), int) and row["imageWidth"] > 0 and
            isinstance(row.get("imageHeight"), int) and row["imageHeight"] > 0,
            "draft ARTIST image is incomplete")
    for key in ARTIST_CHILDREN:
        validate_references(draft, key, "artistId", draft_ids)
    new_performances = performance_ids(
        draft, {row["id"]: row.get("category") for row in draft_artists}, allow_unlinked=True
    )
    require(len(new_performances) == 8, "draft needs eight ARTIST performances")
    require(not (identifiers(rows(export, "performances"), "id", "performances") & new_performances),
            "real performance ID already exists in published catalog")
    require({row.get("artistId") for row in rows(draft, "performanceArtists")
             if row.get("performanceId") in new_performances} == REAL_IDS,
            "draft performance artists differ from confirmed roster")
    for key in PERFORMANCE_CHILDREN:
        validate_references(draft, key, "performanceId", identifiers(rows(draft, "performances"), "id", "draft performances"))

    axis = export.get("timetableConfig")
    require(isinstance(axis, dict), "published timetable axis is missing")
    start_axis = time.fromisoformat(axis["axisStartTime"])
    end_axis = time.fromisoformat(axis["axisEndTime"])
    require(start_axis < end_axis, "published timetable axis is invalid")
    for row in rows(draft, "performances"):
        if row.get("id") not in new_performances:
            continue
        start = datetime.fromisoformat(row["startsAt"])
        end = datetime.fromisoformat(row["endsAt"])
        require(row.get("festivalDate") in DAYS and
                start.date().isoformat() == row["festivalDate"] and
                end.date().isoformat() == row["festivalDate"] and
                start < end and start.utcoffset().total_seconds() == 32400 and
                end.utcoffset().total_seconds() == 32400 and
                start_axis <= start.time() and end.time() <= end_axis,
                f"ARTIST performance {row['id']} is outside festival day or timetable axis")

    result = export.copy()
    result["artists"] = [row for row in old_artists if row["id"] not in MOCK_IDS] + new_artists
    for key in ARTIST_CHILDREN:
        result[key] = [row for row in rows(export, key) if row["artistId"] not in MOCK_IDS]
        result[key] += [row for row in rows(draft, key) if row["artistId"] in REAL_IDS]
    result["performances"] = [row for row in rows(export, "performances")
                              if row["id"] not in old_performances]
    result["performances"] += [row for row in rows(draft, "performances")
                               if row["id"] in new_performances]
    for key in PERFORMANCE_CHILDREN:
        result[key] = [row for row in rows(export, key)
                       if row["performanceId"] not in old_performances]
        result[key] += [row for row in rows(draft, key)
                        if row["performanceId"] in new_performances]
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("published_export", type=Path)
    parser.add_argument("candidate_output", type=Path)
    parser.add_argument("--festival-id", required=True)
    parser.add_argument("--expected-revision", required=True,
                        help="UUID of the published revision selected for export")
    args = parser.parse_args()
    require(args.candidate_output.resolve() not in
            {args.published_export.resolve(), DRAFT.resolve()}, "output would overwrite an input")
    export = json.loads(args.published_export.read_text(encoding="utf-8"))
    draft = json.loads(DRAFT.read_text(encoding="utf-8"))
    result = candidate(export, draft, args.festival_id, args.expected_revision)
    with args.candidate_output.open("x", encoding="utf-8") as output:
        json.dump(result, output, ensure_ascii=False, indent=2)
        output.write("\n")
    print(f"Candidate written: {args.candidate_output}")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, KeyError, TypeError, OSError) as error:
        raise SystemExit(f"Refusing candidate: {error}")
