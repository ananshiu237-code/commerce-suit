<?php
require_once __DIR__ . '/../lib/db.php';

header('Content-Type: application/json; charset=utf-8');

function out($data, int $code = 200): void {
  http_response_code($code);
  echo json_encode($data, JSON_UNESCAPED_UNICODE);
  exit;
}

function body(): array {
  $raw = file_get_contents('php://input');
  if (!$raw) return [];
  $j = json_decode($raw, true);
  return is_array($j) ? $j : [];
}

function path(): string {
  $u = parse_url($_SERVER['REQUEST_URI'] ?? '/', PHP_URL_PATH);
  return rtrim($u, '/') ?: '/';
}

$method = $_SERVER['REQUEST_METHOD'] ?? 'GET';
$p = path();

try {
  if ($method === 'GET' && $p === '/api/health') {
    out(['ok' => true, 'service' => 'POS API', 'time' => date('c')]);
  }

  if ($method === 'GET' && $p === '/api/products') {
    $companyId = (int)($_GET['company_id'] ?? 1);
    $storeId = (int)($_GET['store_id'] ?? 1);
    $pdo = db();
    $sql = "SELECT p.id, p.sku, p.qr_code, p.barcode, p.name, p.price, IFNULL(i.qty_on_hand,0) qty_on_hand
            FROM products p
            LEFT JOIN store_inventory i ON i.product_id = p.id AND i.store_id = :store_id
            WHERE p.company_id = :company_id AND p.is_active = 1
            ORDER BY p.id DESC LIMIT 200";
    $st = $pdo->prepare($sql);
    $st->execute(['company_id' => $companyId, 'store_id' => $storeId]);
    out(['ok' => true, 'data' => $st->fetchAll()]);
  }

  if ($method === 'GET' && preg_match('#^/api/products/by-qr/(.+)$#', $p, $m)) {
    $code = urldecode($m[1]);
    $companyId = (int)($_GET['company_id'] ?? 1);
    $storeId = (int)($_GET['store_id'] ?? 1);
    $pdo = db();
    $sql = "SELECT p.id, p.sku, p.qr_code, p.barcode, p.name, p.price, IFNULL(i.qty_on_hand,0) qty_on_hand
            FROM products p
            LEFT JOIN store_inventory i ON i.product_id = p.id AND i.store_id = :store_id
            WHERE p.company_id = :company_id AND (p.qr_code = :code OR p.barcode = :code) AND p.is_active = 1
            LIMIT 1";
    $st = $pdo->prepare($sql);
    $st->execute(['company_id' => $companyId, 'store_id' => $storeId, 'code' => $code]);
    $row = $st->fetch();
    if (!$row) out(['ok' => false, 'error' => 'PRODUCT_NOT_FOUND'], 404);
    out(['ok' => true, 'data' => $row]);
  }


  if ($method === 'POST' && $p === '/api/inventory/check') {
    $b = body();
    $companyId = (int)($b['company_id'] ?? 1);
    $storeId = (int)($b['store_id'] ?? 1);
    $checkedBy = (int)($b['checked_by'] ?? 1);
    $items = $b['items'] ?? [];

    if (!is_array($items) || count($items) === 0) {
      out(['ok' => false, 'error' => 'ITEMS_REQUIRED'], 422);
    }

    $pdo = db();
    $pdo->beginTransaction();

    $checkNo = 'CHK' . date('YmdHis') . rand(100, 999);
    $insCheck = $pdo->prepare("INSERT INTO inventory_checks
      (company_id, store_id, check_no, status, checked_by, checked_at)
      VALUES (:company_id,:store_id,:check_no,'done',:checked_by,NOW())");
    $insCheck->execute([
      'company_id' => $companyId,
      'store_id' => $storeId,
      'check_no' => $checkNo,
      'checked_by' => $checkedBy,
    ]);
    $checkId = (int)$pdo->lastInsertId();

    $getInv = $pdo->prepare("SELECT qty_on_hand FROM store_inventory WHERE store_id=:store_id AND product_id=:product_id LIMIT 1");
    $insCheckItem = $pdo->prepare("INSERT INTO inventory_check_items
      (check_id, product_id, system_qty, counted_qty, diff_qty)
      VALUES (:check_id,:product_id,:system_qty,:counted_qty,:diff_qty)");
    $upInv = $pdo->prepare("UPDATE store_inventory SET qty_on_hand=:qty_on_hand WHERE store_id=:store_id AND product_id=:product_id");
    $insInvTxn = $pdo->prepare("INSERT INTO inventory_txns
      (company_id, store_id, product_id, txn_type, ref_type, ref_id, qty_change, qty_after, reason, created_by)
      VALUES (:company_id,:store_id,:product_id,'stocktake','inventory_check',:ref_id,:qty_change,:qty_after,'Inventory check adjust',:created_by)");

    foreach ($items as $it) {
      $pid = (int)($it['product_id'] ?? 0);
      $counted = (float)($it['counted_qty'] ?? 0);
      if ($pid <= 0 || $counted < 0) throw new Exception('INVALID_ITEM');

      $getInv->execute(['store_id' => $storeId, 'product_id' => $pid]);
      $row = $getInv->fetch();
      if (!$row) throw new Exception('INVENTORY_NOT_FOUND:' . $pid);

      $system = (float)$row['qty_on_hand'];
      $diff = $counted - $system;

      $insCheckItem->execute([
        'check_id' => $checkId,
        'product_id' => $pid,
        'system_qty' => $system,
        'counted_qty' => $counted,
        'diff_qty' => $diff,
      ]);

      $upInv->execute(['qty_on_hand' => $counted, 'store_id' => $storeId, 'product_id' => $pid]);

      if (abs($diff) > 0.0001) {
        $insInvTxn->execute([
          'company_id' => $companyId,
          'store_id' => $storeId,
          'product_id' => $pid,
          'ref_id' => $checkId,
          'qty_change' => $diff,
          'qty_after' => $counted,
          'created_by' => $checkedBy,
        ]);
      }
    }

    $audit = $pdo->prepare("INSERT INTO audit_logs (company_id, store_id, actor_type, actor_id, action, entity_type, entity_id, detail_json)
      VALUES (:company_id,:store_id,'user',:actor_id,'inventory_check','inventory_checks',:entity_id,:detail_json)");
    $audit->execute([
      'company_id' => $companyId,
      'store_id' => $storeId,
      'actor_id' => $checkedBy,
      'entity_id' => $checkId,
      'detail_json' => json_encode(['check_no' => $checkNo, 'item_count' => count($items)], JSON_UNESCAPED_UNICODE),
    ]);

    $pdo->commit();
    out(['ok' => true, 'data' => ['check_id' => $checkId, 'check_no' => $checkNo]]);
  }


  if ($method === 'GET' && $p === '/api/reports/daily-sales') {
    $companyId = (int)($_GET['company_id'] ?? 1);
    $storeId = (int)($_GET['store_id'] ?? 0);
    $from = $_GET['from'] ?? date('Y-m-d');
    $to = $_GET['to'] ?? date('Y-m-d');

    $pdo = db();
    $sql = "SELECT o.business_date, o.store_id, s.store_name,
                   COUNT(*) order_count,
                   SUM(o.total_amount) total_sales,
                   SUM(o.paid_amount) total_paid
            FROM orders o
            JOIN stores s ON s.id = o.store_id
            WHERE o.company_id=:company_id
              AND o.business_date BETWEEN :dfrom AND :dto
              AND o.status='paid'";
    $params = ['company_id'=>$companyId,'dfrom'=>$from,'dto'=>$to];
    if ($storeId > 0) {
      $sql .= " AND o.store_id = :store_id";
      $params['store_id'] = $storeId;
    }
    $sql .= " GROUP BY o.business_date, o.store_id, s.store_name ORDER BY o.business_date DESC, o.store_id";
    $st = $pdo->prepare($sql);
    $st->execute($params);
    out(['ok'=>true,'data'=>$st->fetchAll()]);
  }

  if ($method === 'GET' && $p === '/api/reports/payment-mix') {
    $companyId = (int)($_GET['company_id'] ?? 1);
    $storeId = (int)($_GET['store_id'] ?? 0);
    $from = $_GET['from'] ?? date('Y-m-d');
    $to = $_GET['to'] ?? date('Y-m-d');

    $pdo = db();
    $sql = "SELECT pm.code method_code, pm.name method_name,
                   COUNT(p.id) txn_count,
                   SUM(p.amount) total_amount
            FROM payments p
            JOIN payment_methods pm ON pm.id = p.payment_method_id
            JOIN orders o ON o.id = p.order_id
            WHERE p.company_id=:company_id
              AND o.business_date BETWEEN :dfrom AND :dto";
    $params = ['company_id'=>$companyId,'dfrom'=>$from,'dto'=>$to];
    if ($storeId > 0) {
      $sql .= " AND o.store_id = :store_id";
      $params['store_id'] = $storeId;
    }
    $sql .= " GROUP BY pm.code, pm.name ORDER BY total_amount DESC";
    $st = $pdo->prepare($sql);
    $st->execute($params);
    out(['ok'=>true,'data'=>$st->fetchAll()]);
  }

  if ($method === 'GET' && $p === '/api/sync/status') {
    $companyId = (int)($_GET['company_id'] ?? 1);
    $pdo = db();

    $summarySql = "SELECT sync_status, COUNT(*) cnt
                   FROM branch_sync_logs
                   WHERE company_id=:company_id
                   GROUP BY sync_status";
    $st1 = $pdo->prepare($summarySql);
    $st1->execute(['company_id' => $companyId]);
    $summary = $st1->fetchAll();

    $storeSql = "SELECT l.source_store_id, s.store_name,
                        COUNT(*) upload_count,
                        MAX(l.created_at) last_upload_at,
                        MAX(l.synced_at) last_synced_at
                 FROM branch_sync_logs l
                 LEFT JOIN stores s ON s.id = l.source_store_id
                 WHERE l.company_id=:company_id
                 GROUP BY l.source_store_id, s.store_name
                 ORDER BY last_upload_at DESC";
    $st2 = $pdo->prepare($storeSql);
    $st2->execute(['company_id' => $companyId]);
    $byStore = $st2->fetchAll();

    out(['ok' => true, 'data' => ['summary' => $summary, 'by_store' => $byStore]]);
  }

  if ($method === 'GET' && $p === '/api/dashboard/hq-summary') {
    $companyId = (int)($_GET['company_id'] ?? 1);
    $businessDate = $_GET['business_date'] ?? date('Y-m-d');
    $pdo = db();

    $salesSql = "SELECT COUNT(*) order_count, IFNULL(SUM(total_amount),0) total_sales
                 FROM orders
                 WHERE company_id=:company_id AND business_date=:business_date AND status='paid'";
    $stSales = $pdo->prepare($salesSql);
    $stSales->execute(['company_id' => $companyId, 'business_date' => $businessDate]);
    $sales = $stSales->fetch() ?: ['order_count' => 0, 'total_sales' => 0];

    $storeSql = "SELECT COUNT(*) store_count FROM stores WHERE company_id=:company_id AND is_active=1";
    $stStore = $pdo->prepare($storeSql);
    $stStore->execute(['company_id' => $companyId]);
    $stores = $stStore->fetch();

    $lowStockSql = "SELECT COUNT(*) low_stock_count
                    FROM store_inventory i
                    JOIN products p ON p.id = i.product_id
                    WHERE i.company_id=:company_id AND i.qty_on_hand <= i.safety_stock AND p.is_active=1";
    $stLow = $pdo->prepare($lowStockSql);
    $stLow->execute(['company_id' => $companyId]);
    $low = $stLow->fetch();

    $syncSql = "SELECT sync_status, COUNT(*) cnt
                FROM branch_sync_logs
                WHERE company_id=:company_id
                GROUP BY sync_status";
    $stSync = $pdo->prepare($syncSql);
    $stSync->execute(['company_id' => $companyId]);
    $syncSummary = $stSync->fetchAll();

    out(['ok' => true, 'data' => [
      'business_date' => $businessDate,
      'order_count' => (int)$sales['order_count'],
      'total_sales' => (float)$sales['total_sales'],
      'active_store_count' => (int)($stores['store_count'] ?? 0),
      'low_stock_count' => (int)($low['low_stock_count'] ?? 0),
      'sync_summary' => $syncSummary,
    ]]);
  }

  if ($method === 'GET' && $p === '/api/reports/store-ranking') {
    $companyId = (int)($_GET['company_id'] ?? 1);
    $from = $_GET['from'] ?? date('Y-m-d');
    $to = $_GET['to'] ?? date('Y-m-d');
    $pdo = db();

    $sql = "SELECT o.store_id, s.store_name,
                   COUNT(*) order_count,
                   IFNULL(SUM(o.total_amount),0) total_sales,
                   IFNULL(AVG(o.total_amount),0) avg_ticket
            FROM orders o
            JOIN stores s ON s.id = o.store_id
            WHERE o.company_id=:company_id
              AND o.business_date BETWEEN :dfrom AND :dto
              AND o.status='paid'
            GROUP BY o.store_id, s.store_name
            ORDER BY total_sales DESC";
    $st = $pdo->prepare($sql);
    $st->execute(['company_id' => $companyId, 'dfrom' => $from, 'dto' => $to]);
    out(['ok' => true, 'data' => $st->fetchAll()]);
  }

  if ($method === 'POST' && $p === '/api/sync/upload') {
    $b = body();
    $companyId = (int)($b['company_id'] ?? 1);
    $sourceStoreId = (int)($b['source_store_id'] ?? 0);
    $syncType = $b['sync_type'] ?? 'order_batch';
    $idempotencyKey = trim((string)($b['idempotency_key'] ?? ''));
    $payload = $b['payload'] ?? [];

    if ($sourceStoreId <= 0 || $idempotencyKey === '') {
      out(['ok'=>false,'error'=>'source_store_id_and_idempotency_key_required'],422);
    }

    $pdo = db();
    $sql = "INSERT INTO branch_sync_logs
      (company_id, source_store_id, sync_type, idempotency_key, payload_json, sync_status, synced_at)
      VALUES (:company_id,:source_store_id,:sync_type,:idempotency_key,:payload_json,'received',NOW())
      ON DUPLICATE KEY UPDATE sync_status='duplicate', synced_at=NOW()";
    $st = $pdo->prepare($sql);
    $st->execute([
      'company_id'=>$companyId,
      'source_store_id'=>$sourceStoreId,
      'sync_type'=>$syncType,
      'idempotency_key'=>$idempotencyKey,
      'payload_json'=>json_encode($payload, JSON_UNESCAPED_UNICODE),
    ]);

    out(['ok'=>true,'data'=>['source_store_id'=>$sourceStoreId,'idempotency_key'=>$idempotencyKey]]);
  }

  if ($method === 'POST' && $p === '/api/orders') {
    $b = body();
    $companyId = (int)($b['company_id'] ?? 1);
    $storeId = (int)($b['store_id'] ?? 1);
    $cashierUserId = (int)($b['cashier_user_id'] ?? 0) ?: null;
    $items = $b['items'] ?? [];
    $payment = $b['payment'] ?? [];
    $methodCode = $payment['method_code'] ?? 'CASH';

    if (!is_array($items) || count($items) === 0) {
      out(['ok' => false, 'error' => 'ITEMS_REQUIRED'], 422);
    }

    $pdo = db();
    $pdo->beginTransaction();

    $orderNo = 'SO' . date('YmdHis') . rand(100, 999);
    $subtotal = 0.0;
    $lineNo = 1;

    $insOrder = $pdo->prepare("INSERT INTO orders
      (company_id, store_id, order_no, cashier_user_id, subtotal_amount, total_amount, paid_amount, business_date, status)
      VALUES (:company_id,:store_id,:order_no,:cashier_user_id,0,0,0,CURDATE(),'created')");
    $insOrder->execute([
      'company_id' => $companyId,
      'store_id' => $storeId,
      'order_no' => $orderNo,
      'cashier_user_id' => $cashierUserId,
    ]);
    $orderId = (int)$pdo->lastInsertId();

    $getProd = $pdo->prepare("SELECT id, price, cost, name FROM products WHERE id=:id AND company_id=:company_id AND is_active=1 LIMIT 1");
    $insItem = $pdo->prepare("INSERT INTO order_items
      (company_id, order_id, line_no, product_id, qty, unit_cost, unit_price, line_total)
      VALUES (:company_id,:order_id,:line_no,:product_id,:qty,:unit_cost,:unit_price,:line_total)");

    $upInv = $pdo->prepare("UPDATE store_inventory
      SET qty_on_hand = qty_on_hand - :qty
      WHERE store_id = :store_id AND product_id = :product_id AND qty_on_hand >= :qty");
    $insInvTxn = $pdo->prepare("INSERT INTO inventory_txns
      (company_id, store_id, product_id, txn_type, ref_type, ref_id, qty_change, reason, created_by)
      VALUES (:company_id,:store_id,:product_id,'sale','order',:ref_id,:qty_change,'POS sale',:created_by)");

    foreach ($items as $it) {
      $pid = (int)($it['product_id'] ?? 0);
      $qty = (float)($it['qty'] ?? 0);
      if ($pid <= 0 || $qty <= 0) {
        throw new Exception('INVALID_ITEM');
      }

      $getProd->execute(['id' => $pid, 'company_id' => $companyId]);
      $prod = $getProd->fetch();
      if (!$prod) throw new Exception('PRODUCT_NOT_FOUND:' . $pid);

      $lineTotal = (float)$prod['price'] * $qty;
      $subtotal += $lineTotal;

      $insItem->execute([
        'company_id' => $companyId,
        'order_id' => $orderId,
        'line_no' => $lineNo++,
        'product_id' => $pid,
        'qty' => $qty,
        'unit_cost' => (float)$prod['cost'],
        'unit_price' => (float)$prod['price'],
        'line_total' => $lineTotal,
      ]);

      $upInv->execute(['qty' => $qty, 'store_id' => $storeId, 'product_id' => $pid]);
      if ($upInv->rowCount() === 0) {
        throw new Exception('INSUFFICIENT_STOCK:' . $pid);
      }

      $insInvTxn->execute([
        'company_id' => $companyId,
        'store_id' => $storeId,
        'product_id' => $pid,
        'ref_id' => $orderId,
        'qty_change' => -$qty,
        'created_by' => $cashierUserId,
      ]);
    }

    $total = $subtotal;
    $paid = (float)($payment['amount'] ?? $total);

    $updOrder = $pdo->prepare("UPDATE orders SET subtotal_amount=:subtotal,total_amount=:total,paid_amount=:paid,status='paid' WHERE id=:id");
    $updOrder->execute(['subtotal' => $subtotal, 'total' => $total, 'paid' => $paid, 'id' => $orderId]);

    $payMethodIdStmt = $pdo->prepare("SELECT id FROM payment_methods WHERE company_id=:company_id AND code=:code LIMIT 1");
    $payMethodIdStmt->execute(['company_id' => $companyId, 'code' => $methodCode]);
    $pm = $payMethodIdStmt->fetch();
    if (!$pm) throw new Exception('PAYMENT_METHOD_NOT_FOUND');

    $insPayment = $pdo->prepare("INSERT INTO payments
      (company_id, store_id, order_id, payment_method_id, amount, provider_status, paid_at)
      VALUES (:company_id,:store_id,:order_id,:payment_method_id,:amount,'paid',NOW())");
    $insPayment->execute([
      'company_id' => $companyId,
      'store_id' => $storeId,
      'order_id' => $orderId,
      'payment_method_id' => (int)$pm['id'],
      'amount' => $paid,
    ]);

    $audit = $pdo->prepare("INSERT INTO audit_logs (company_id, store_id, actor_type, actor_id, action, entity_type, entity_id, detail_json)
      VALUES (:company_id,:store_id,'user',:actor_id,'create_order','order',:entity_id,:detail_json)");
    $audit->execute([
      'company_id' => $companyId,
      'store_id' => $storeId,
      'actor_id' => $cashierUserId,
      'entity_id' => $orderId,
      'detail_json' => json_encode(['order_no' => $orderNo, 'total' => $total], JSON_UNESCAPED_UNICODE),
    ]);

    $pdo->commit();
    out(['ok' => true, 'data' => ['order_id' => $orderId, 'order_no' => $orderNo, 'total' => $total]]);
  }

  out(['ok' => false, 'error' => 'NOT_FOUND', 'path' => $p], 404);
} catch (Throwable $e) {
  if (isset($pdo) && $pdo instanceof PDO && $pdo->inTransaction()) {
    $pdo->rollBack();
  }
  out(['ok' => false, 'error' => $e->getMessage()], 500);
}
