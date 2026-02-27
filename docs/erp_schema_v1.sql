SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS companies (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  code VARCHAR(32) NOT NULL UNIQUE,
  name VARCHAR(128) NOT NULL,
  is_active TINYINT(1) NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS regions (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  company_id BIGINT NOT NULL,
  code VARCHAR(32) NOT NULL,
  name VARCHAR(128) NOT NULL,
  is_active TINYINT(1) NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_regions (company_id, code),
  CONSTRAINT fk_regions_company FOREIGN KEY (company_id) REFERENCES companies(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS stores (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  company_id BIGINT NOT NULL,
  region_id BIGINT NULL,
  store_code VARCHAR(32) NOT NULL,
  store_name VARCHAR(128) NOT NULL,
  store_type VARCHAR(32) DEFAULT 'retail',
  timezone VARCHAR(64) DEFAULT 'Asia/Taipei',
  address VARCHAR(255),
  is_active TINYINT(1) NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_store_code (company_id, store_code),
  KEY idx_store_region (region_id),
  CONSTRAINT fk_stores_company FOREIGN KEY (company_id) REFERENCES companies(id),
  CONSTRAINT fk_stores_region FOREIGN KEY (region_id) REFERENCES regions(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS users (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  company_id BIGINT NOT NULL,
  username VARCHAR(64) NOT NULL,
  password_hash VARCHAR(255) NOT NULL,
  full_name VARCHAR(128),
  role VARCHAR(32) NOT NULL DEFAULT 'cashier',
  is_active TINYINT(1) NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_users (company_id, username),
  CONSTRAINT fk_users_company FOREIGN KEY (company_id) REFERENCES companies(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS employees (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  company_id BIGINT NOT NULL,
  employee_no VARCHAR(32) NOT NULL,
  full_name VARCHAR(128) NOT NULL,
  store_id BIGINT NULL,
  dept VARCHAR(64),
  title VARCHAR(64),
  hire_date DATE,
  status VARCHAR(32) DEFAULT 'active',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_emp_no (company_id, employee_no),
  KEY idx_emp_store (store_id),
  CONSTRAINT fk_emp_company FOREIGN KEY (company_id) REFERENCES companies(id),
  CONSTRAINT fk_emp_store FOREIGN KEY (store_id) REFERENCES stores(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS categories (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  company_id BIGINT NOT NULL,
  code VARCHAR(32) NOT NULL,
  name VARCHAR(128) NOT NULL,
  parent_id BIGINT NULL,
  is_active TINYINT(1) NOT NULL DEFAULT 1,
  UNIQUE KEY uk_categories (company_id, code),
  CONSTRAINT fk_cat_company FOREIGN KEY (company_id) REFERENCES companies(id),
  CONSTRAINT fk_cat_parent FOREIGN KEY (parent_id) REFERENCES categories(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS products (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  company_id BIGINT NOT NULL,
  sku VARCHAR(64) NOT NULL,
  qr_code VARCHAR(128),
  barcode VARCHAR(128),
  category_id BIGINT NULL,
  name VARCHAR(255) NOT NULL,
  uom VARCHAR(16) DEFAULT 'EA',
  cost DECIMAL(12,2) NOT NULL DEFAULT 0,
  price DECIMAL(12,2) NOT NULL DEFAULT 0,
  is_active TINYINT(1) NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_products_sku (company_id, sku),
  KEY idx_products_qr (company_id, qr_code),
  KEY idx_products_barcode (company_id, barcode),
  CONSTRAINT fk_products_company FOREIGN KEY (company_id) REFERENCES companies(id),
  CONSTRAINT fk_products_category FOREIGN KEY (category_id) REFERENCES categories(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS store_inventory (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  company_id BIGINT NOT NULL,
  store_id BIGINT NOT NULL,
  product_id BIGINT NOT NULL,
  qty_on_hand DECIMAL(14,3) NOT NULL DEFAULT 0,
  qty_reserved DECIMAL(14,3) NOT NULL DEFAULT 0,
  safety_stock DECIMAL(14,3) NOT NULL DEFAULT 0,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_store_inventory (store_id, product_id),
  KEY idx_inv_company_store (company_id, store_id),
  CONSTRAINT fk_inv_company FOREIGN KEY (company_id) REFERENCES companies(id),
  CONSTRAINT fk_inv_store FOREIGN KEY (store_id) REFERENCES stores(id),
  CONSTRAINT fk_inv_product FOREIGN KEY (product_id) REFERENCES products(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS payment_methods (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  company_id BIGINT NOT NULL,
  code VARCHAR(32) NOT NULL,
  name VARCHAR(64) NOT NULL,
  provider VARCHAR(64),
  fee_rate DECIMAL(8,4) DEFAULT 0,
  is_active TINYINT(1) NOT NULL DEFAULT 1,
  UNIQUE KEY uk_pay_method (company_id, code),
  CONSTRAINT fk_pay_company FOREIGN KEY (company_id) REFERENCES companies(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS pos_devices (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  company_id BIGINT NOT NULL,
  store_id BIGINT NOT NULL,
  device_code VARCHAR(64) NOT NULL,
  device_type VARCHAR(32) NOT NULL,
  model VARCHAR(64),
  status VARCHAR(32) DEFAULT 'active',
  last_seen_at DATETIME NULL,
  UNIQUE KEY uk_device (store_id, device_code),
  CONSTRAINT fk_device_company FOREIGN KEY (company_id) REFERENCES companies(id),
  CONSTRAINT fk_device_store FOREIGN KEY (store_id) REFERENCES stores(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS orders (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  company_id BIGINT NOT NULL,
  store_id BIGINT NOT NULL,
  order_no VARCHAR(64) NOT NULL,
  order_source VARCHAR(32) DEFAULT 'pos',
  customer_id BIGINT NULL,
  cashier_user_id BIGINT NULL,
  employee_id BIGINT NULL,
  subtotal_amount DECIMAL(14,2) NOT NULL DEFAULT 0,
  discount_amount DECIMAL(14,2) NOT NULL DEFAULT 0,
  tax_amount DECIMAL(14,2) NOT NULL DEFAULT 0,
  total_amount DECIMAL(14,2) NOT NULL DEFAULT 0,
  paid_amount DECIMAL(14,2) NOT NULL DEFAULT 0,
  change_amount DECIMAL(14,2) NOT NULL DEFAULT 0,
  status VARCHAR(32) NOT NULL DEFAULT 'created',
  business_date DATE NOT NULL,
  order_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_order_no (store_id, order_no),
  KEY idx_orders_store_date (store_id, business_date),
  CONSTRAINT fk_orders_company FOREIGN KEY (company_id) REFERENCES companies(id),
  CONSTRAINT fk_orders_store FOREIGN KEY (store_id) REFERENCES stores(id),
  CONSTRAINT fk_orders_user FOREIGN KEY (cashier_user_id) REFERENCES users(id),
  CONSTRAINT fk_orders_emp FOREIGN KEY (employee_id) REFERENCES employees(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS order_items (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  company_id BIGINT NOT NULL,
  order_id BIGINT NOT NULL,
  line_no INT NOT NULL,
  product_id BIGINT NOT NULL,
  qty DECIMAL(14,3) NOT NULL,
  unit_cost DECIMAL(12,2) NOT NULL DEFAULT 0,
  unit_price DECIMAL(12,2) NOT NULL DEFAULT 0,
  discount_amount DECIMAL(12,2) NOT NULL DEFAULT 0,
  tax_amount DECIMAL(12,2) NOT NULL DEFAULT 0,
  line_total DECIMAL(14,2) NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_order_line (order_id, line_no),
  KEY idx_item_product (product_id),
  CONSTRAINT fk_items_company FOREIGN KEY (company_id) REFERENCES companies(id),
  CONSTRAINT fk_items_order FOREIGN KEY (order_id) REFERENCES orders(id),
  CONSTRAINT fk_items_product FOREIGN KEY (product_id) REFERENCES products(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS payments (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  company_id BIGINT NOT NULL,
  store_id BIGINT NOT NULL,
  order_id BIGINT NOT NULL,
  payment_method_id BIGINT NOT NULL,
  amount DECIMAL(14,2) NOT NULL,
  currency VARCHAR(8) DEFAULT 'TWD',
  provider_txn_id VARCHAR(128),
  provider_status VARCHAR(32),
  paid_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_pay_order (order_id),
  KEY idx_pay_provider_txn (provider_txn_id),
  CONSTRAINT fk_payment_company FOREIGN KEY (company_id) REFERENCES companies(id),
  CONSTRAINT fk_payment_store FOREIGN KEY (store_id) REFERENCES stores(id),
  CONSTRAINT fk_payment_order FOREIGN KEY (order_id) REFERENCES orders(id),
  CONSTRAINT fk_payment_method FOREIGN KEY (payment_method_id) REFERENCES payment_methods(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS inventory_txns (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  company_id BIGINT NOT NULL,
  store_id BIGINT NOT NULL,
  product_id BIGINT NOT NULL,
  txn_type VARCHAR(32) NOT NULL,
  ref_type VARCHAR(32),
  ref_id BIGINT,
  qty_change DECIMAL(14,3) NOT NULL,
  qty_after DECIMAL(14,3) NULL,
  reason VARCHAR(128),
  created_by BIGINT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_itx_store_date (store_id, created_at),
  CONSTRAINT fk_itx_company FOREIGN KEY (company_id) REFERENCES companies(id),
  CONSTRAINT fk_itx_store FOREIGN KEY (store_id) REFERENCES stores(id),
  CONSTRAINT fk_itx_product FOREIGN KEY (product_id) REFERENCES products(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS inventory_checks (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  company_id BIGINT NOT NULL,
  store_id BIGINT NOT NULL,
  check_no VARCHAR(64) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'draft',
  checked_by BIGINT NULL,
  checked_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_check_no (store_id, check_no),
  CONSTRAINT fk_chk_company FOREIGN KEY (company_id) REFERENCES companies(id),
  CONSTRAINT fk_chk_store FOREIGN KEY (store_id) REFERENCES stores(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS inventory_check_items (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  check_id BIGINT NOT NULL,
  product_id BIGINT NOT NULL,
  system_qty DECIMAL(14,3) NOT NULL,
  counted_qty DECIMAL(14,3) NOT NULL,
  diff_qty DECIMAL(14,3) NOT NULL,
  UNIQUE KEY uk_check_item (check_id, product_id),
  CONSTRAINT fk_chk_item_check FOREIGN KEY (check_id) REFERENCES inventory_checks(id),
  CONSTRAINT fk_chk_item_product FOREIGN KEY (product_id) REFERENCES products(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS suppliers (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  company_id BIGINT NOT NULL,
  supplier_code VARCHAR(32) NOT NULL,
  supplier_name VARCHAR(128) NOT NULL,
  contact_name VARCHAR(64),
  phone VARCHAR(32),
  email VARCHAR(128),
  is_active TINYINT(1) NOT NULL DEFAULT 1,
  UNIQUE KEY uk_supplier (company_id, supplier_code),
  CONSTRAINT fk_supplier_company FOREIGN KEY (company_id) REFERENCES companies(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS purchase_orders (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  company_id BIGINT NOT NULL,
  store_id BIGINT NOT NULL,
  supplier_id BIGINT NOT NULL,
  po_no VARCHAR(64) NOT NULL,
  status VARCHAR(32) DEFAULT 'draft',
  order_date DATE NOT NULL,
  expected_date DATE NULL,
  total_amount DECIMAL(14,2) NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_po_no (store_id, po_no),
  CONSTRAINT fk_po_company FOREIGN KEY (company_id) REFERENCES companies(id),
  CONSTRAINT fk_po_store FOREIGN KEY (store_id) REFERENCES stores(id),
  CONSTRAINT fk_po_supplier FOREIGN KEY (supplier_id) REFERENCES suppliers(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS gl_accounts (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  company_id BIGINT NOT NULL,
  account_code VARCHAR(32) NOT NULL,
  account_name VARCHAR(128) NOT NULL,
  account_type VARCHAR(32) NOT NULL,
  is_active TINYINT(1) NOT NULL DEFAULT 1,
  UNIQUE KEY uk_gl_account (company_id, account_code),
  CONSTRAINT fk_gl_company FOREIGN KEY (company_id) REFERENCES companies(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS gl_journal_entries (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  company_id BIGINT NOT NULL,
  store_id BIGINT NULL,
  je_no VARCHAR(64) NOT NULL,
  je_date DATE NOT NULL,
  period_yyyymm CHAR(6) NOT NULL,
  source_type VARCHAR(32),
  source_id BIGINT,
  status VARCHAR(32) DEFAULT 'draft',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_je_no (company_id, je_no),
  KEY idx_je_period (company_id, period_yyyymm),
  CONSTRAINT fk_je_company FOREIGN KEY (company_id) REFERENCES companies(id),
  CONSTRAINT fk_je_store FOREIGN KEY (store_id) REFERENCES stores(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS gl_journal_lines (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  je_id BIGINT NOT NULL,
  line_no INT NOT NULL,
  account_id BIGINT NOT NULL,
  debit DECIMAL(14,2) NOT NULL DEFAULT 0,
  credit DECIMAL(14,2) NOT NULL DEFAULT 0,
  memo VARCHAR(255),
  UNIQUE KEY uk_je_line (je_id, line_no),
  CONSTRAINT fk_jl_je FOREIGN KEY (je_id) REFERENCES gl_journal_entries(id),
  CONSTRAINT fk_jl_account FOREIGN KEY (account_id) REFERENCES gl_accounts(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS branch_sync_logs (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  company_id BIGINT NOT NULL,
  source_store_id BIGINT NOT NULL,
  sync_type VARCHAR(32) NOT NULL,
  idempotency_key VARCHAR(128) NOT NULL,
  payload_json JSON,
  sync_status VARCHAR(32) NOT NULL DEFAULT 'received',
  synced_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_sync_idempotency (source_store_id, idempotency_key),
  KEY idx_sync_status (sync_status, created_at),
  CONSTRAINT fk_sync_company FOREIGN KEY (company_id) REFERENCES companies(id),
  CONSTRAINT fk_sync_store FOREIGN KEY (source_store_id) REFERENCES stores(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS audit_logs (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  company_id BIGINT NOT NULL,
  store_id BIGINT NULL,
  actor_type VARCHAR(32),
  actor_id BIGINT NULL,
  action VARCHAR(64) NOT NULL,
  entity_type VARCHAR(64),
  entity_id BIGINT,
  detail_json JSON,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_audit_company_date (company_id, created_at),
  CONSTRAINT fk_audit_company FOREIGN KEY (company_id) REFERENCES companies(id),
  CONSTRAINT fk_audit_store FOREIGN KEY (store_id) REFERENCES stores(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT IGNORE INTO companies (id, code, name) VALUES (1, 'HQ', 'Headquarter');
INSERT IGNORE INTO regions (id, company_id, code, name) VALUES (1, 1, 'NORTH', 'North Region');
INSERT IGNORE INTO stores (id, company_id, region_id, store_code, store_name) VALUES (1, 1, 1, 'S001', 'Demo Store 1');
INSERT IGNORE INTO payment_methods (company_id, code, name, provider) VALUES
(1, 'CASH', 'Cash', 'LOCAL'),
(1, 'CARD', 'Credit Card', 'CARD_TERMINAL'),
(1, 'LINEPAY', 'LINE Pay', 'LINE_PAY'),
(1, 'APPLEPAY', 'Apple Pay', 'APPLE_PAY'),
(1, 'WECHAT', 'WeChat Pay', 'WECHAT_PAY'),
(1, 'ALIPAY', 'Alipay', 'ALIPAY');
