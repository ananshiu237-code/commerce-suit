# POS API (Phase 1)

Base URL: `http://<host>/api`

## Endpoints
- `GET /api/health`
- `GET /api/products?company_id=1&store_id=1`
- `GET /api/products/by-qr/{code}?company_id=1&store_id=1`
- `POST /api/orders`

## Create order payload
```json
{
  "company_id": 1,
  "store_id": 1,
  "cashier_user_id": 1,
  "items": [
    { "product_id": 1, "qty": 2 },
    { "product_id": 2, "qty": 1 }
  ],
  "payment": {
    "method_code": "CASH",
    "amount": 75
  }
}
```

## Quick setup
```bash
mysql -umyapp_user -p'MyApp@123456' -D myapp < seed_demo_data.sql
```

## Copy to web root
```bash
sudo cp -r /home/dell3442/.openclaw/workspace/POS/backend/* /var/www/myapp/
sudo chown -R www-data:www-data /var/www/myapp
sudo systemctl reload nginx
```
