-- V2__seed_market_prices.sql
-- Preços medianos reais baseados em bRO/iRO para a Regra 2 funcionar desde o primeiro boot.

INSERT INTO market_prices (item_id, item_name, median_price) VALUES
    -- Equipamentos comuns
    (1101, 'Sword', 100),
    (1201, 'Knife', 50),
    (1301, 'Axe', 200),
    (1401, 'Spear', 150),
    (1501, 'Mace', 180),
    (1601, 'Rod', 120),
    (1701, 'Bow', 250),
    (1801, 'Katar', 300),

    -- Equipamentos mid-tier
    (1119, 'Claymore', 15000),
    (1228, 'Damascus', 50000),
    (1407, 'Gae Bolg', 200000),
    (1618, 'Survivors Rod', 100000),

    -- Cartas comuns
    (4001, 'Poring Card', 500),
    (4005, 'Fabre Card', 800),
    (4009, 'Condor Card', 1200),
    (4014, 'Andre Card', 5000),
    (4029, 'Drops Card', 3000),

    -- Cartas raras
    (4121, 'Bathory Card', 500000),
    (4128, 'Golden Bug Card', 2000000),
    (4134, 'Marc Card', 300000),
    (4142, 'Thara Frog Card', 800000),

    -- Cartas MVP (valores altos — principal alvo da Regra 2)
    (4302, 'Osiris Card', 15000000),
    (4305, 'Lord of Death Card', 20000000),
    (4318, 'Stormy Knight Card', 10000000),
    (4324, 'Lord Knight Card', 25000000),
    (4330, 'Moonlight Flower Card', 8000000),
    (4342, 'Maya Purple Card', 12000000),
    (4357, 'Detale Card', 5000000),
    (4359, 'Kiel-D-01 Card', 30000000),
    (4363, 'Thanatos Card', 35000000),
    (4367, 'Randgris Card', 18000000),

    -- Consumíveis
    (501, 'Red Potion', 10),
    (502, 'Orange Potion', 50),
    (503, 'Yellow Potion', 150),
    (504, 'White Potion', 500),
    (505, 'Blue Potion', 2500),
    (607, 'Yggdrasil Berry', 5000),
    (608, 'Yggdrasil Seed', 3000)

ON CONFLICT (item_id) DO UPDATE SET
    item_name = EXCLUDED.item_name,
    median_price = EXCLUDED.median_price,
    updated_at = NOW();
