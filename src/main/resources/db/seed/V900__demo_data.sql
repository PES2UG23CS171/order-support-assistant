-- ---------------------------------------------------------------------------
-- Demo fixtures. This file lives in `classpath:db/seed`, which is only added to
-- spring.flyway.locations under the `local` and `test` profiles -- production
-- runs migrations from `classpath:db/migration` alone and never sees it.
--
-- Every order below exists to exercise one branch of the business rules, so the
-- same fixtures back the demo script and the integration suite.
-- ---------------------------------------------------------------------------

INSERT INTO customers (id, email, full_name, api_key_hash) VALUES
    ('11111111-1111-1111-1111-111111111111', 'ada@example.com',   'Ada Lovelace',
     '83514f5aded0713a24ca7de3f41e7c5a3518a1ea48253300b43dc22e9415547c'),  -- key: osa_demo_ada_key
    ('22222222-2222-2222-2222-222222222222', 'blake@example.com', 'Blake Rivera',
     '26bb16fb89f347591390ae7f3c27b629299187e76b6ec242d361d344283c317e');  -- key: osa_demo_blake_key

-- ORD-1001  PROCESSING, unshipped        -> cancellable
-- ORD-1002  SHIPPED, in transit          -> NOT cancellable, NOT yet returnable
-- ORD-1003  DELIVERED 5 days ago         -> inside the 30-day return window
-- ORD-1004  DELIVERED 45 days ago, furniture -> still inside the 60-day window
-- ORD-1007  DELIVERED 45 days ago, audio     -> outside the standard 30-day window
--           (1004 and 1007 are the same age on purpose: the only thing separating
--            them is category, which is what the extended window turns on)
-- ORD-1005  DELIVERED 3 days ago         -> contains a non-returnable gift card
-- ORD-1006  CANCELLED, refund in flight  -> refund status lookups
-- ORD-2001  Blake's order                -> cross-customer authorization checks
INSERT INTO orders (id, order_number, customer_id, status, total_amount, placed_at, cancelled_at,
                    shipping_city, shipping_country) VALUES
    ('a0000001-0000-0000-0000-000000000001', 'ORD-1001', '11111111-1111-1111-1111-111111111111',
     'PROCESSING', 249.00, now() - interval '1 day',   NULL, 'Austin',   'US'),
    ('a0000002-0000-0000-0000-000000000002', 'ORD-1002', '11111111-1111-1111-1111-111111111111',
     'SHIPPED',    129.50, now() - interval '4 days',  NULL, 'Austin',   'US'),
    ('a0000003-0000-0000-0000-000000000003', 'ORD-1003', '11111111-1111-1111-1111-111111111111',
     'DELIVERED',   89.99, now() - interval '12 days', NULL, 'Austin',   'US'),
    ('a0000004-0000-0000-0000-000000000004', 'ORD-1004', '11111111-1111-1111-1111-111111111111',
     'DELIVERED',  459.00, now() - interval '52 days', NULL, 'Austin',   'US'),
    ('a0000005-0000-0000-0000-000000000005', 'ORD-1005', '11111111-1111-1111-1111-111111111111',
     'DELIVERED',  100.00, now() - interval '9 days',  NULL, 'Austin',   'US'),
    ('a0000006-0000-0000-0000-000000000006', 'ORD-1006', '11111111-1111-1111-1111-111111111111',
     'CANCELLED',  310.75, now() - interval '20 days', now() - interval '18 days', 'Austin', 'US'),
    ('a0000007-0000-0000-0000-000000000007', 'ORD-1007', '11111111-1111-1111-1111-111111111111',
     'DELIVERED',  219.00, now() - interval '50 days', NULL, 'Austin',   'US'),
    ('b0000001-0000-0000-0000-000000000001', 'ORD-2001', '22222222-2222-2222-2222-222222222222',
     'PROCESSING', 75.00,  now() - interval '2 days',  NULL, 'Seattle',  'US');

INSERT INTO order_items (id, order_id, sku, description, category, quantity, unit_price, returnable) VALUES
    ('c0000001-0000-0000-0000-000000000001', 'a0000001-0000-0000-0000-000000000001',
     'KB-ERGO-01',  'Ergonomic split keyboard',      'PERIPHERALS', 1, 249.00, true),
    ('c0000002-0000-0000-0000-000000000002', 'a0000002-0000-0000-0000-000000000002',
     'MON-ARM-02',  'Single monitor arm',            'PERIPHERALS', 1, 129.50, true),
    ('c0000003-0000-0000-0000-000000000003', 'a0000003-0000-0000-0000-000000000003',
     'HP-NC-03',    'Noise-cancelling headphones',   'AUDIO',       1,  89.99, true),
    ('c0000004-0000-0000-0000-000000000004', 'a0000004-0000-0000-0000-000000000004',
     'CHAIR-04',    'Mesh task chair',               'FURNITURE',   1, 459.00, true),
    ('c0000005-0000-0000-0000-000000000005', 'a0000005-0000-0000-0000-000000000005',
     'GIFTCARD-100','Digital gift card, $100',       'GIFT_CARD',   1, 100.00, false),
    ('c0000006-0000-0000-0000-000000000006', 'a0000006-0000-0000-0000-000000000006',
     'DESK-06',     'Standing desk, 60 inch',        'FURNITURE',   1, 310.75, true),
    ('c0000007-0000-0000-0000-000000000007', 'b0000001-0000-0000-0000-000000000001',
     'CBL-USB-07',  'USB-C cable, 2m, 3-pack',       'ACCESSORIES', 1,  75.00, true),
    ('c0000008-0000-0000-0000-000000000008', 'a0000007-0000-0000-0000-000000000007',
     'SPK-DESK-08', 'Desktop studio monitors, pair', 'AUDIO',       1, 219.00, true);

INSERT INTO shipments (id, order_id, carrier, tracking_number, status, shipped_at,
                       estimated_delivery, delivered_at, last_location) VALUES
    ('d0000002-0000-0000-0000-000000000002', 'a0000002-0000-0000-0000-000000000002',
     'UPS',   '1Z999AA10123456784', 'IN_TRANSIT', now() - interval '2 days',
     (current_date + 1), NULL, 'Dallas, TX'),
    ('d0000003-0000-0000-0000-000000000003', 'a0000003-0000-0000-0000-000000000003',
     'FEDEX', '7712 3456 7890',     'DELIVERED',  now() - interval '9 days',
     (current_date - 5), now() - interval '5 days', 'Austin, TX'),
    ('d0000004-0000-0000-0000-000000000004', 'a0000004-0000-0000-0000-000000000004',
     'FEDEX', '7712 3456 1111',     'DELIVERED',  now() - interval '49 days',
     (current_date - 45), now() - interval '45 days', 'Austin, TX'),
    ('d0000005-0000-0000-0000-000000000005', 'a0000005-0000-0000-0000-000000000005',
     'EMAIL', 'DIGITAL-DELIVERY',   'DELIVERED',  now() - interval '9 days',
     (current_date - 3), now() - interval '3 days', 'ada@example.com'),
    ('d0000007-0000-0000-0000-000000000007', 'a0000007-0000-0000-0000-000000000007',
     'UPS',   '1Z999AA10199999999', 'DELIVERED',  now() - interval '49 days',
     (current_date - 45), now() - interval '45 days', 'Austin, TX');

-- ORD-1006 was cancelled two days after it was placed; the refund is still moving.
INSERT INTO refunds (id, order_id, return_request_id, status, amount, method,
                     initiated_at, expected_settlement_date, external_reference) VALUES
    ('e0000006-0000-0000-0000-000000000006', 'a0000006-0000-0000-0000-000000000006', NULL,
     'PROCESSING', 310.75, 'ORIGINAL_PAYMENT_METHOD', now() - interval '18 days',
     (current_date + 2), 'rfnd_5f2a9c31');
