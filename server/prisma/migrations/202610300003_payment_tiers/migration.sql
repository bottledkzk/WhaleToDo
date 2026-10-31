SET search_path TO whaletodo;
INSERT INTO products (product_id, title, price_minor, currency, vip_days, points, active)
VALUES
 ('points_20', '20 积分', 180, 'CNY', 0, 20, true),
 ('points_500', '500 积分', 2500, 'CNY', 0, 500, true),
 ('vip_90d', 'VIP 90 天', 4800, 'CNY', 90, 0, true)
ON CONFLICT (product_id) DO NOTHING;
