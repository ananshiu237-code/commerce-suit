# POS Backend (PHP)

## Run on Ubuntu (Nginx + PHP-FPM)

If your server root is already `/var/www/myapp/public`, copy this file:

```bash
sudo mkdir -p /var/www/myapp/public
sudo cp /home/dell3442/.openclaw/workspace/POS/backend/public/index.php /var/www/myapp/public/index.php
sudo chown www-data:www-data /var/www/myapp/public/index.php
sudo systemctl reload nginx
```

Test:

```bash
curl http://localhost/
```
