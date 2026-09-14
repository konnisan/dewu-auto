# Dewu License Server

轻量卡密服务，供 `dewu-auto` Android 客户端调用。

## 功能

- `POST /verify`：校验卡密、状态、有效期，并在首次使用时绑定设备。
- `POST /heartbeat`：校验会话、设备绑定、卡密状态和有效期。
- `GET /health`：健康检查。
- SQLite 存储卡密和会话。
- 服务默认仅监听 `127.0.0.1:8080`，由 Nginx 对公网反向代理。

## 服务器目录

推荐部署目录：

```text
/data/dewu-license/
├── app.jar
├── app.pid
├── data/
│   └── license.db
└── logs/
    └── app.log
```

## 构建

Ubuntu：

```bash
sudo apt update
sudo apt install -y maven sqlite3 openssl
cd /path/to/dewu-auto/license-server
mvn clean package -DskipTests
```

生成：

```text
target/dewu-license-server.jar
```

## 部署

```bash
sudo mkdir -p /data/dewu-license/data /data/dewu-license/logs
sudo cp target/dewu-license-server.jar /data/dewu-license/app.jar
sudo cp deploy/restart.sh /data/dewu-license/restart.sh
sudo cp deploy/create-license.sh /data/dewu-license/create-license.sh
sudo cp deploy/list-licenses.sh /data/dewu-license/list-licenses.sh
sudo chmod +x /data/dewu-license/*.sh
cd /data/dewu-license
./restart.sh
```

检查：

```bash
curl http://127.0.0.1:8080/health
```

预期类似：

```json
{"ok":true,"service":"dewu-license-server","licenses":0,"sessions":0}
```

## 创建卡密

30 天卡：

```bash
/data/dewu-license/create-license.sh 30
```

7 天卡：

```bash
/data/dewu-license/create-license.sh 7
```

永久卡：

```bash
/data/dewu-license/create-license.sh 0
```

自定义卡密：

```bash
/data/dewu-license/create-license.sh 30 DEWU-TEST-0001
```

查看卡密：

```bash
/data/dewu-license/list-licenses.sh
```

## 手动管理

禁用：

```bash
sqlite3 /data/dewu-license/data/license.db \
  "UPDATE license_keys SET status='DISABLED', updated_at=CURRENT_TIMESTAMP WHERE card_key='DEWU-TEST-0001';"
```

重新启用：

```bash
sqlite3 /data/dewu-license/data/license.db \
  "UPDATE license_keys SET status='ACTIVE', updated_at=CURRENT_TIMESTAMP WHERE card_key='DEWU-TEST-0001';"
```

解绑设备：

```bash
sqlite3 /data/dewu-license/data/license.db \
  "UPDATE license_keys SET bound_device_id=NULL, updated_at=CURRENT_TIMESTAMP WHERE card_key='DEWU-TEST-0001'; DELETE FROM license_sessions WHERE card_key='DEWU-TEST-0001';"
```

## API

### 验证

```bash
curl -X POST http://127.0.0.1:8080/verify \
  -H 'Content-Type: application/json' \
  -d '{"cardKey":"DEWU-TEST-0001","deviceId":"test-device-001"}'
```

成功：

```json
{"ok":true,"message":"验证成功","sessionToken":"..."}
```

### 心跳

```bash
curl -X POST http://127.0.0.1:8080/heartbeat \
  -H 'Content-Type: application/json' \
  -d '{"sessionToken":"上一步返回的token","deviceId":"test-device-001"}'
```

成功：

```json
{"ok":true,"message":"ok"}
```

## Nginx

示例文件：`deploy/nginx-dewu-license.conf`。

安装后：

```bash
sudo cp deploy/nginx-dewu-license.conf /etc/nginx/conf.d/dewu-license.conf
sudo nginx -t
sudo systemctl reload nginx
```

当前测试公网路径：

```text
http://101.37.18.75/api/license/health
http://101.37.18.75/api/license/verify
http://101.37.18.75/api/license/heartbeat
```

正式客户端建议使用域名 + HTTPS，而不是长期使用纯 HTTP 公网 IP。
