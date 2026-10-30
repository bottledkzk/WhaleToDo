-- 全账号 MOCK 商品目录；金额仅用于模拟订单展示，不产生真实扣款
-- 保留已配置商品及既有订单快照，不覆盖运营配置
SET search_path TO whaletodo, public;
INSERT INTO products (product_id, title, price_minor, currency, vip_days, points, active)
VALUES
 ('vip_7d', 'VIP 7 天', 700, 'CNY', 7, 0, true),
 ('vip_30d', 'VIP 30 天', 1800, 'CNY', 30, 0, true),
 ('points_100', '100 积分', 600, 'CNY', 0, 100, true)
ON CONFLICT (product_id) DO NOTHING;
