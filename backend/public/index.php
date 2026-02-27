<?php
header('Content-Type: application/json; charset=utf-8');
echo json_encode([
  "ok" => true,
  "message" => "Hello FROM PHP API",
  "time" => date('c')
], JSON_UNESCAPED_UNICODE);
