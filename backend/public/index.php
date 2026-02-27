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
