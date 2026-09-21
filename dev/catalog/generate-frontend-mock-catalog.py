"""Generates frontend-mock-catalog.json, a fictional catalog for frontend development.

Run from the repository root: python dev/catalog/generate-frontend-mock-catalog.py
Every name starts with "[목]" and every link points to example.invalid, except
the student council's official channels confirmed in HOME-008 and the deployed
test stamp page used as the common QR value.
"""
import json
from pathlib import Path

OUT = Path(__file__).with_name("frontend-mock-catalog.json")
DATES = ["2026-09-29", "2026-09-30", "2026-10-01"]


def image(key, width=800, height=600):
    return f"https://placehold.co/{width}x{height}/png?text={key}", width, height


def link(path):
    return f"https://example.invalid/mock/{path}"


# (id, category, name, location, operator, hours, description, experience, contact, events, menu)
SPACES = [
    ("mock-pub-moonlight", "PUB", "달빛포차", "주점 구역 P-01", "목 컴퓨터학부 학생회", "18:00 ~ 익일 00:00",
     "목 주점 설명입니다. 실제 운영 정보가 아닙니다.", None, "moonlight",
     [], [("닭꼬치", 4000), ("김치전", 8000), ("하이볼", 5500)]),
    ("mock-pub-sunset", "PUB", "노을상회", "주점 구역 P-02", "목 경영학부 학생회", "18:00 ~ 익일 00:00",
     None, None, None, [], [("떡볶이", 6000), ("어묵탕", 9000)]),
    ("mock-pub-wave", "PUB", "파도주점", "주점 구역 P-03", None, None, None, None, None,
     [], [("모둠튀김", 12000)]),
    ("mock-pub-starlight", "PUB", "별빛식당", "주점 구역 P-04", "목 디자인대학 학생회", "17:00 ~ 23:00",
     "메뉴가 많은 주점 예시입니다.", None, "starlight",
     [], [("치즈볼", 5000), ("감자튀김", 6000), ("순대볶음", 13000), ("콜라", 2000), ("사이다", 2000), ("레모네이드", 4500)]),
    ("mock-pub-youth", "PUB", "청춘포차", "주점 구역 P-05", "목 공학대학 학생회", "18:00 ~ 익일 00:00",
     None, None, None, [], [("제육볶음", 12000)]),
    ("mock-booth-constellation", "BOOTH", "별자리 연구소", "부스 구역 B-01", "목 천문 동아리", "12:00 ~ 18:00",
     "나만의 별자리 카드를 만드는 체험 부스입니다.", "카드를 골라 스티커로 꾸미면 완성됩니다.", "constellation",
     ["별자리 퀴즈", "스탬프 카드 꾸미기"], []),
    ("mock-booth-rhythm", "BOOTH", "리듬 아케이드", "부스 구역 B-02", "목 음악 동아리", "13:00 ~ 19:00",
     None, "리듬 게임 한 판에 기념품을 드립니다.", None, ["최고 점수 이벤트"], []),
    ("mock-booth-likelion", "BOOTH", "멋쟁이사자처럼 부스", "부스 구역 B-03", "목 멋쟁이사자처럼", "12:00 ~ 20:00",
     "스탬프 투어를 시작하고 상품을 받는 부스입니다.", "QR을 스캔해 스탬프 투어를 시작합니다.", "likelion",
     ["스탬프 투어 상품 수령"], []),
    ("mock-booth-green", "BOOTH", "초록 공방", "부스 구역 B-04", None, None, None, None, None, [], []),
    ("mock-booth-radio", "BOOTH", "캠퍼스 라디오", "부스 구역 B-05", "목 방송국", "11:00 ~ 17:00",
     "사연을 남기면 현장에서 읽어 드립니다.", "사연 카드를 작성해 함에 넣어 주세요.", "radio", [], []),
    ("mock-market-film", "FLEA_MARKET", "필름마켓", "플리마켓 구역 M-01", "목 사진 동아리", "12:00 ~ 18:00",
     "필름 사진 엽서를 판매합니다.", None, "film", [], []),
    ("mock-market-record", "FLEA_MARKET", "레코드마켓", "플리마켓 구역 M-02", "목 개인 판매자", "13:00 ~ 18:00",
     None, None, None, [], []),
    ("mock-market-vintage", "FLEA_MARKET", "빈티지서랍", "플리마켓 구역 M-03", None, None, None, None, None, [], []),
    ("mock-market-books", "FLEA_MARKET", "작은책방", "플리마켓 구역 M-04", "목 독서 동아리", "12:00 ~ 17:00",
     "중고 책과 굿즈를 나눕니다.", None, None, [], []),
    ("mock-truck-fire", "FOOD_TRUCK", "불맛트럭", "푸드트럭 구역 F-01", "목 푸드트럭 1호", "11:00 ~ 22:00",
     "직화 메뉴를 판매하는 푸드트럭입니다.", None, None, [], [("불닭 타코", 6000), ("치즈 핫도그", 4500)]),
    ("mock-truck-sweet", "FOOD_TRUCK", "달콤트럭", "푸드트럭 구역 F-02", "목 푸드트럭 2호", "12:00 ~ 22:00",
     None, None, "sweet", [], [("크로플", 5000), ("츄러스", 4000), ("딸기 스무디", 4500)]),
    ("mock-truck-coffee", "FOOD_TRUCK", "커피트럭", "푸드트럭 구역 F-03", None, None, None, None, None,
     [], [("아메리카노", 3000)]),
    ("mock-council-booth", "STUDENT_COUNCIL_BOOTH", "총학생회 부스", "부스 구역 S-01", "목 총학생회", "11:00 ~ 21:00",
     "축제 안내와 분실물 접수를 받습니다.", "안내 책자와 굿즈 수령 확인을 도와드립니다.", "council",
     ["축제 퀴즈", "분실물 접수"], []),
    ("mock-promo-drink", "PROMOTION_BOOTH", "음료 프로모션 부스", "부스 구역 R-01", "목 협찬사 A", "13:00 ~ 20:00",
     "새 음료를 시음하는 부스입니다.", "시음 후 설문에 답하면 경품 추첨에 참여합니다.", None, ["경품 추첨"], []),
    ("mock-promo-app", "PROMOTION_BOOTH", "앱 프로모션 부스", "부스 구역 R-02", "목 협찬사 B", None,
     None, None, None, [], []),
]

AREA_OF = {"PUB": "map-mock-pub", "FOOD_TRUCK": "map-mock-pub", "FLEA_MARKET": "map-mock-market"}
MAPS = [
    ("map-mock-overview", "OVERVIEW", "전체 지도", "overview-v1"),
    ("map-mock-pub", "AREA", "주점·푸드트럭 구역", "pub-v1"),
    ("map-mock-booth", "AREA", "부스 구역", "booth-v1"),
    ("map-mock-market", "AREA", "플리마켓 구역", "market-v1"),
]
FACILITIES = [
    ("mock-restroom-main", "화장실", "RESTROOM", "restroom", "학생회관 1층", "상시"),
    ("mock-photo-booth", "포토부스", "PHOTO_BOOTH", "photo-booth", "본관 앞 광장", "12:00 ~ 22:00"),
    ("mock-smoking-area", "흡연구역", "SMOKING_AREA", "smoking", "체육관 뒤편", None),
    ("mock-trash-bin", "쓰레기통", "TRASH_BIN", "trash", "주점 구역 입구", None),
]
FILTER_LABELS = {"RESTROOM": "화장실", "PHOTO_BOOTH": "포토부스", "SMOKING_AREA": "흡연구역", "TRASH_BIN": "쓰레기통"}


def build():
    m = {key: [] for key in [
        "festivalDays", "spaces", "spaceTranslations", "spaceSortOrders", "spaceEvents", "spaceMenuItems",
        "places", "placeTranslations", "maps", "mapTranslations", "mapAssets", "mapAreas", "mapPins",
        "mapPinTranslations", "mapPinFilterGroupTranslations", "spaceMapTargets", "artists",
        "artistTranslations", "artistLinks", "artistLinkTranslations", "artistSongs", "artistSongTranslations",
        "performances", "performanceTranslations", "performanceArtists"]}
    for date in DATES:
        # Crowding needs each day to open and close on the same date, so close at 23:00.
        m["festivalDays"].append({"festivalDate": date, "opensAt": f"{date}T11:00:00+09:00",
                                  "closesAt": f"{date}T23:00:00+09:00"})

    version = {map_id: v for map_id, _, _, v in MAPS}
    for rank, (map_id, kind, name, v) in enumerate(MAPS, 1):
        m["maps"].append({"id": map_id, "kind": kind, "sortRank": rank, "currentVersion": v})
        m["mapTranslations"].append({"mapId": map_id, "locale": "ko", "name": f"[목] {name}"})
        url, w, h = image(map_id, 1600, 1000)
        m["mapAssets"].append({"mapId": map_id, "version": v, "imageUrl": url, "imageAlt": f"[목] {name} 이미지",
                               "imageWidth": w, "imageHeight": h})
    for index, (map_id, kind, name, v) in enumerate(MAPS[1:]):
        area = f"area-{map_id.removeprefix('map-')}"
        m["mapAreas"].append({"id": area, "targetMapId": map_id})
        pin(m, "map-mock-overview", version, f"pin-{area}", "area", None, 0.2 + index * 0.3, 0.3,
            None, area, f"[목] {name}")

    counters = {}
    for sid, category, name, location, operator, hours, description, experience, contact, events, menu in SPACES:
        url, w, h = image(sid)
        m["spaces"].append({"id": sid, "category": category, "imageUrl": url, "imageWidth": w, "imageHeight": h})
        m["spaceTranslations"].append({
            "spaceId": sid, "locale": "ko", "name": f"[목] {name}", "imageAlt": f"[목] {name} 대표 이미지",
            "locationText": location, "operatorText": operator, "hoursText": hours, "descriptionText": description,
            "experienceText": experience, "contactLabel": "문의 SNS" if contact else None,
            "contactUrl": link(f"contact/{contact}") if contact else None})
        for order, content in enumerate(events, 1):
            m["spaceEvents"].append({"spaceId": sid, "locale": "ko", "sortOrder": order, "content": content})
        for order, (item, price) in enumerate(menu, 1):
            m["spaceMenuItems"].append({"spaceId": sid, "locale": "ko", "sortOrder": order, "name": item,
                                        "priceAmount": price})
        place = f"place-{sid}"
        m["places"].append({"id": place, "kind": "SPACE", "spaceId": sid})
        m["placeTranslations"].append({"placeId": place, "locale": "ko", "name": f"[목] {name}",
                                       "locationText": location, "hoursText": hours,
                                       "descriptionText": description, "usageText": None})
        map_id = AREA_OF.get(category, "map-mock-booth")
        n = counters.get(map_id, 0)
        counters[map_id] = n + 1
        pin_id = f"pin-{sid}"
        pin(m, map_id, version, pin_id, category.lower().replace("_", "-"), None,
            0.15 + (n % 4) * 0.23, 0.2 + (n // 4) * 0.25, place, None, f"[목] {name}")
        m["spaceMapTargets"].append({"spaceId": sid, "mapId": map_id, "mapVersion": version[map_id],
                                     "pinId": pin_id, "placeId": place})
    for rank, sid in enumerate(sorted((s[0] for s in SPACES), key=lambda s: next(x[2] for x in SPACES if x[0] == s)), 1):
        m["spaceSortOrders"].append({"locale": "ko", "spaceId": sid, "sortRank": rank})

    for index, (pid, name, group, category, location, hours) in enumerate(FACILITIES):
        m["places"].append({"id": pid, "kind": "FACILITY", "spaceId": None})
        m["placeTranslations"].append({"placeId": pid, "locale": "ko", "name": f"[목] {name}",
                                       "locationText": location, "hoursText": hours, "descriptionText": None,
                                       "usageText": None})
        pin(m, "map-mock-overview", version, f"pin-{pid}", category, group, 0.1 + index * 0.2, 0.8, pid, None,
            f"[목] {name}")
        m["mapPinFilterGroupTranslations"].append({"filterGroup": group, "locale": "ko",
                                                   "label": FILTER_LABELS[group]})
    m["places"].append({"id": "mock-ticket-booth", "kind": "LANDMARK", "spaceId": None})
    m["placeTranslations"].append({"placeId": "mock-ticket-booth", "locale": "ko", "name": "[목] 외부인 티켓존",
                                   "locationText": "정문 옆 티켓 부스", "hoursText": "12:00 ~ 21:00",
                                   "descriptionText": "외부인 티켓을 구매하고 팔찌를 받는 곳입니다.",
                                   "usageText": "송금 완료 화면을 스태프에게 보여 주세요."})
    pin(m, "map-mock-overview", version, "pin-mock-ticket-booth", "ticket", None, 0.85, 0.55,
        "mock-ticket-booth", None, "[목] 외부인 티켓존")

    artists = [("mock-artist-wish", "ARTIST", "위시 밴드"), ("mock-artist-dawn", "ARTIST", "새벽 사중주"),
               ("mock-artist-echo", "ARTIST", "에코"), ("mock-contest-a", "CONTEST", "가요제 참가팀 A"),
               ("mock-contest-b", "CONTEST", "가요제 참가팀 B")]
    for aid, category, name in artists:
        url, w, h = image(aid, 800, 800)
        m["artists"].append({"id": aid, "category": category, "imageUrl": url, "imageWidth": w, "imageHeight": h})
        m["artistTranslations"].append({"artistId": aid, "locale": "ko", "name": f"[목] {name}",
                                        "imageAlt": f"[목] {name} 프로필 사진",
                                        "introduction": f"[목] {name} 소개입니다. 실제 출연진이 아닙니다."})
        if category == "ARTIST":
            m["artistLinks"].append({"artistId": aid, "sortOrder": 1, "url": link(f"artist/{aid}")})
            m["artistLinkTranslations"].append({"artistId": aid, "sortOrder": 1, "locale": "ko", "label": "공식 SNS"})
            for order in (1, 2):
                m["artistSongs"].append({"artistId": aid, "sortOrder": order, "url": link(f"song/{aid}-{order}")})
                m["artistSongTranslations"].append({"artistId": aid, "sortOrder": order, "locale": "ko",
                                                    "title": f"[목] 대표곡 {order}"})
    schedule = [("2026-09-29", "18:00", "18:40", "[목] 가요제 본선", ["mock-contest-a", "mock-contest-b"]),
                ("2026-09-29", "20:00", "20:50", "[목] 위시 밴드 공연", ["mock-artist-wish"]),
                ("2026-09-30", "19:00", "19:40", "[목] 새벽 사중주 공연", ["mock-artist-dawn"]),
                ("2026-10-01", "20:30", "21:30", "[목] 에코 × 위시 밴드 합동 무대", ["mock-artist-echo", "mock-artist-wish"])]
    for index, (date, start, end, title, members) in enumerate(schedule, 1):
        pid = f"mock-performance-{index}"
        m["performances"].append({"id": pid, "festivalDate": date, "startsAt": f"{date}T{start}:00+09:00",
                                  "endsAt": f"{date}T{end}:00+09:00"})
        m["performanceTranslations"].append({"performanceId": pid, "locale": "ko", "title": title,
                                             "description": "실제 공연 일정이 아닙니다."})
        for order, aid in enumerate(members, 1):
            m["performanceArtists"].append({"performanceId": pid, "artistId": aid, "displayOrder": order})

    m["timetableConfig"] = {"axisStartTime": "17:00:00", "axisEndTime": "23:00:00"}
    items = ["주류 반입", "유리병", "화기류", "대형 삼각대"]
    m["prohibitedItems"] = [{"id": f"mock-prohibited-{i}", "sortOrder": i} for i in range(1, len(items) + 1)]
    m["prohibitedItemTranslations"] = [{"itemId": f"mock-prohibited-{i}", "locale": "ko", "label": label}
                                       for i, label in enumerate(items, 1)]
    m["prohibitedMessages"] = [{"locale": "ko", "message": "[목] 안전을 위해 아래 물품은 반입할 수 없어요."}]
    m["ticketGuide"] = {
        "unitPriceAmount": 10000, "mapId": "map-mock-overview", "placeId": "mock-ticket-booth",
        "pinId": "pin-mock-ticket-booth", "mapVersion": version["map-mock-overview"],
        "instructions": ["[목] 외부인 티켓존 방문", "[목] 버튼을 눌러 티켓 금액 송금",
                         "[목] 송금 완료 화면을 스태프에게 제시", "[목] 팔찌를 받고 입장"],
        "festivalStartDate": DATES[0], "festivalEndDate": DATES[-1],
        "dailyTransferOpenTime": "12:00:00", "dailyTransferCloseTime": "21:00:00",
        "dailyPickupOpenTime": "12:00:00", "dailyPickupCloseTime": "21:30:00"}
    m["stampGuide"] = {
        "title": "[목] 스탬프 투어", "dates": DATES,
        "instructions": ["[목] 멋사 부스에서 QR을 스캔해 시작", "[목] 다른 부스 체험 후 QR 스캔",
                         "[목] 4개를 모으면 멋사 부스에서 상품 수령"],
        "rewardName": "[목] 기념 음료", "rewardLocationText": "[목] 멋쟁이사자처럼 부스",
        "rewardHoursText": "12:00 ~ 20:00", "rewardNotice": "[목] 준비 수량이 소진되면 지급이 끝나요.",
        "qrValue": "https://festival.likelionerica.com/stamps"}
    m["festivalLinks"] = [
        {"id": "mock-notices", "kind": "UNIVERSITY_NOTICES", "url": link("notices"), "iconKey": None, "sortOrder": 1},
        {"id": "mock-faq", "kind": "FAQ", "url": link("faq"), "iconKey": None, "sortOrder": 1},
        {"id": "mock-welcome", "kind": "WELCOME_DAY", "url": link("welcome"), "iconKey": None, "sortOrder": 1},
        {"id": "instagram", "kind": "OFFICIAL_CHANNEL", "url": "https://www.instagram.com/hanyang_erica_stu/",
         "iconKey": "instagram", "sortOrder": 1},
        {"id": "youtube", "kind": "OFFICIAL_CHANNEL", "url": "https://www.youtube.com/@hanyang_erica_stu",
         "iconKey": "youtube", "sortOrder": 2},
        {"id": "student-council", "kind": "OFFICIAL_CHANNEL", "url": "https://ericastu.hanyang.ac.kr/ko/act",
         "iconKey": "website", "sortOrder": 3}]
    m["festivalLinkTranslations"] = [
        {"linkId": "mock-notices", "locale": "ko", "label": "[목] 학교 공지사항"},
        {"linkId": "mock-faq", "locale": "ko", "label": "[목] FAQ"},
        {"linkId": "mock-welcome", "locale": "ko", "label": "[목] 에리카 웰컴 데이"},
        {"linkId": "instagram", "locale": "ko", "label": "Instagram"},
        {"linkId": "youtube", "locale": "ko", "label": "YouTube"},
        {"linkId": "student-council", "locale": "ko", "label": "총학생회 홈페이지"}]
    return m


def pin(m, map_id, version, pin_id, category, group, x, y, place, area, label):
    m["mapPins"].append({"mapId": map_id, "mapVersion": version[map_id], "id": pin_id, "category": category,
                         "filterGroup": group, "x": round(x, 3), "y": round(y, 3), "placeId": place, "areaId": area})
    m["mapPinTranslations"].append({"mapId": map_id, "mapVersion": version[map_id], "pinId": pin_id,
                                    "locale": "ko", "label": label})


if __name__ == "__main__":
    OUT.write_text(json.dumps(build(), ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"wrote {OUT}")
