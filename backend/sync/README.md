# Branch Sync v1

## Files
- `schema-v1.json`: sync payload schema v1
- `sample-upload-v1.json`: sample payload
- `upload-demo.sh`: one-command upload test

## Test
```bash
cd /home/dell3442/.openclaw/workspace/POS/backend/sync
./upload-demo.sh http://localhost/api sample-upload-v1.json
```

## Check result
```sql
SELECT source_store_id, sync_type, idempotency_key, sync_status, created_at
FROM branch_sync_logs
ORDER BY id DESC
LIMIT 10;
```
