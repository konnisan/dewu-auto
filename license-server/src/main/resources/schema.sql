PRAGMA journal_mode = WAL;
PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS license_keys (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    card_key TEXT NOT NULL UNIQUE,
    status TEXT NOT NULL DEFAULT 'ACTIVE',
    bound_device_id TEXT,
    expires_at TEXT,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS license_sessions (
    session_token TEXT PRIMARY KEY,
    card_key TEXT NOT NULL,
    device_id TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_heartbeat_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY(card_key) REFERENCES license_keys(card_key) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS purchase_orders (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    order_no TEXT NOT NULL UNIQUE,
    client_token TEXT NOT NULL,
    device_id TEXT NOT NULL,
    plan_days INTEGER NOT NULL,
    amount_fen INTEGER NOT NULL,
    status TEXT NOT NULL DEFAULT 'CREATED',
    card_key TEXT,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    paid_at TEXT,
    expires_at TEXT NOT NULL,
    FOREIGN KEY(card_key) REFERENCES license_keys(card_key)
);

CREATE TABLE IF NOT EXISTS purchase_order_items (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    order_no TEXT NOT NULL,
    item_no INTEGER NOT NULL,
    card_key TEXT,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(order_no, item_no),
    UNIQUE(card_key),
    FOREIGN KEY(order_no) REFERENCES purchase_orders(order_no) ON DELETE CASCADE,
    FOREIGN KEY(card_key) REFERENCES license_keys(card_key)
);

CREATE INDEX IF NOT EXISTS idx_license_keys_status ON license_keys(status);
CREATE INDEX IF NOT EXISTS idx_license_sessions_card_device ON license_sessions(card_key, device_id);
CREATE INDEX IF NOT EXISTS idx_license_sessions_heartbeat ON license_sessions(last_heartbeat_at);
CREATE INDEX IF NOT EXISTS idx_purchase_orders_status ON purchase_orders(status);
CREATE INDEX IF NOT EXISTS idx_purchase_orders_device ON purchase_orders(device_id);
CREATE INDEX IF NOT EXISTS idx_purchase_order_items_order ON purchase_order_items(order_no, item_no);
