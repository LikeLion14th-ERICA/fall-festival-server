-- Single-row content table backing GET /api/v2/ticket-guide (TicketGuide
-- schema, api-v2/contract-source.mjs). No purchase/receipt state lives
-- server-side: the app only shows transfer info and computes a time-based
-- status. festival_start_date/festival_end_date stay null until 총학생회
-- confirms the actual festival dates (docs/wiki/product/ticket.md, "확인
-- 필요") — until then GET /api/v2/ticket-guide legitimately returns
-- status=UNCONFIGURED.
CREATE TABLE ticket_guide (
    id SMALLINT PRIMARY KEY,
    unit_price_amount INTEGER NULL,
    account_bank_name TEXT NULL,
    account_number TEXT NULL,
    account_holder TEXT NULL,
    transfer_link_label TEXT NULL,
    transfer_link_url TEXT NULL,
    map_id TEXT NULL,
    place_id TEXT NULL,
    pin_id TEXT NULL,
    map_version TEXT NULL,
    instructions TEXT[] NOT NULL DEFAULT '{}',
    festival_start_date DATE NULL,
    festival_end_date DATE NULL,
    daily_transfer_open_time TIME NULL,
    daily_transfer_close_time TIME NULL,
    daily_pickup_open_time TIME NULL,
    daily_pickup_close_time TIME NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ticket_guide_singleton CHECK (id = 1)
);

-- Placeholder values are the only figures docs/wiki/product/ticket.md gives
-- ("1인 환경부담금 15,000원", "00:00~21:00" 송금, "13:00~21:00" 현장 수령 —
-- all explicitly marked as temporary). Account, map target and festival
-- dates stay null because no confirmed source exists for them yet.
INSERT INTO ticket_guide (
    id, unit_price_amount, instructions,
    daily_transfer_open_time, daily_transfer_close_time,
    daily_pickup_open_time, daily_pickup_close_time,
    updated_at
) VALUES (
    1,
    15000,
    ARRAY[
        '당일 구매한 티켓은 당일에만 사용할 수 있으며, 다른 날짜로 이월되지 않습니다. 구매 후 사용하지 않은 티켓은 환불이 어려울 수 있으니, 방문 일정을 확인한 뒤 송금해 주세요.',
        '현장 외부인 티켓존에서 총학생회 담당자에게 송금 완료 화면을 보여주면 확인 후 입장 팔찌를 지급합니다.'
    ]::TEXT[],
    TIME '00:00',
    TIME '21:00',
    TIME '13:00',
    TIME '21:00',
    now()
);
