SET NAMES utf8mb4;
INSERT IGNORE INTO users (id, company_id, username, password_hash, full_name, role)
VALUES (1, 1, 'cashier1', 'demo_hash', 'Cashier One', 'cashier');

INSERT IGNORE INTO categories (id, company_id, code, name)
VALUES (1, 1, 'FOOD', 'Food');

INSERT INTO products (company_id, sku, qr_code, barcode, category_id, name, cost, price, is_active)
VALUES
(1, 'SKU-1001', 'QR1001', '4710001001', 1, 'Coke 330ml', 18, 30, 1),
(1, 'SKU-1002', 'QR1002', '4710001002', 1, 'Water 600ml', 8, 15, 1),
(1, 'SKU-1003', 'QR1003', '4710001003', 1, 'Potato Chips', 22, 35, 1)
ON DUPLICATE KEY UPDATE name=VALUES(name), price=VALUES(price), cost=VALUES(cost), is_active=1;

INSERT INTO store_inventory (company_id, store_id, product_id, qty_on_hand, qty_reserved, safety_stock)
SELECT 1, 1, p.id, 200, 0, 20
FROM products p
WHERE p.company_id = 1
ON DUPLICATE KEY UPDATE qty_on_hand=VALUES(qty_on_hand), safety_stock=VALUES(safety_stock);
