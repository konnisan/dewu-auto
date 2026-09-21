package com.konnisan.dewulicense.license;

import jakarta.annotation.PostConstruct;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

@Repository
public class LicenseRepository {
    private final JdbcTemplate jdbc;

    public LicenseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void migrateAdminMetadata() {
        boolean hasRemark = jdbc.queryForList("PRAGMA table_info(license_keys)").stream()
            .anyMatch(column -> "remark".equalsIgnoreCase(String.valueOf(column.get("name"))));
        if (!hasRemark) {
            jdbc.execute("ALTER TABLE license_keys ADD COLUMN remark TEXT");
        }
        jdbc.execute(
            """
            CREATE TABLE IF NOT EXISTS app_migrations (
                name TEXT PRIMARY KEY,
                applied_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """
        );
        jdbc.execute(
            """
            CREATE TABLE IF NOT EXISTS quick_remarks (
                remark TEXT PRIMARY KEY,
                created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """
        );
        Integer seeded = jdbc.queryForObject(
            "SELECT COUNT(*) FROM app_migrations WHERE name = ?",
            Integer.class,
            "seed_quick_remarks_v1"
        );
        if (seeded == null || seeded == 0) {
            jdbc.update(
                """
                INSERT OR IGNORE INTO quick_remarks(remark, created_at, updated_at)
                SELECT trim(remark), MIN(created_at), MAX(created_at)
                FROM license_keys
                WHERE remark IS NOT NULL AND trim(remark) <> ''
                GROUP BY trim(remark)
                """
            );
            jdbc.update(
                "INSERT OR IGNORE INTO app_migrations(name) VALUES (?)",
                "seed_quick_remarks_v1"
            );
        }
    }

    public LicenseRow findLicense(String cardKey) {
        List<LicenseRow> rows = jdbc.query(
            """
            SELECT card_key,
                   status,
                   bound_device_id,
                   expires_at,
                   CASE WHEN expires_at IS NULL OR datetime(expires_at) > CURRENT_TIMESTAMP THEN 1 ELSE 0 END AS not_expired
            FROM license_keys
            WHERE card_key = ?
            LIMIT 1
            """,
            (rs, rowNum) -> new LicenseRow(
                rs.getString("card_key"),
                rs.getString("status"),
                rs.getString("bound_device_id"),
                rs.getString("expires_at"),
                rs.getInt("not_expired") == 1
            ),
            cardKey
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    public boolean bindDeviceIfNeeded(String cardKey, String deviceId) {
        LicenseRow row = findLicense(cardKey);
        if (row == null) return false;
        if (row.boundDeviceId() != null && !row.boundDeviceId().isBlank()) {
            return row.boundDeviceId().equals(deviceId);
        }

        int updated = jdbc.update(
            """
            UPDATE license_keys
            SET bound_device_id = ?, updated_at = CURRENT_TIMESTAMP
            WHERE card_key = ?
              AND (bound_device_id IS NULL OR bound_device_id = '')
            """,
            deviceId,
            cardKey
        );

        if (updated == 1) return true;
        LicenseRow latest = findLicense(cardKey);
        return latest != null && deviceId.equals(latest.boundDeviceId());
    }

    public void replaceSession(String sessionToken, String cardKey, String deviceId) {
        jdbc.update("DELETE FROM license_sessions WHERE card_key = ? AND device_id = ?", cardKey, deviceId);
        jdbc.update(
            """
            INSERT INTO license_sessions(session_token, card_key, device_id, created_at, last_heartbeat_at)
            VALUES (?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            sessionToken,
            cardKey,
            deviceId
        );
    }

    public SessionRow findSession(String sessionToken) {
        List<SessionRow> rows = jdbc.query(
            """
            SELECT s.session_token,
                   s.card_key,
                   s.device_id,
                   k.status,
                   k.bound_device_id,
                   k.expires_at,
                   CASE WHEN k.expires_at IS NULL OR datetime(k.expires_at) > CURRENT_TIMESTAMP THEN 1 ELSE 0 END AS not_expired
            FROM license_sessions s
            JOIN license_keys k ON k.card_key = s.card_key
            WHERE s.session_token = ?
            LIMIT 1
            """,
            (rs, rowNum) -> new SessionRow(
                rs.getString("session_token"),
                rs.getString("card_key"),
                rs.getString("device_id"),
                rs.getString("status"),
                rs.getString("bound_device_id"),
                rs.getString("expires_at"),
                rs.getInt("not_expired") == 1
            ),
            sessionToken
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void touchHeartbeat(String sessionToken) {
        jdbc.update(
            "UPDATE license_sessions SET last_heartbeat_at = CURRENT_TIMESTAMP WHERE session_token = ?",
            sessionToken
        );
    }

    public void deleteSession(String sessionToken) {
        jdbc.update("DELETE FROM license_sessions WHERE session_token = ?", sessionToken);
    }

    public List<Map<String, Object>> listLicenses() {
        return jdbc.queryForList(
            """
            SELECT k.card_key AS cardKey,
                   k.status AS status,
                   k.remark AS remark,
                   k.bound_device_id AS boundDeviceId,
                   k.expires_at AS expiresAt,
                   k.created_at AS createdAt,
                   k.updated_at AS updatedAt,
                   CASE
                       WHEN k.expires_at IS NULL THEN 0
                       ELSE CAST(ROUND(julianday(k.expires_at) - julianday(k.created_at)) AS INTEGER)
                   END AS days,
                   (SELECT MAX(s.last_heartbeat_at)
                      FROM license_sessions s
                     WHERE s.card_key = k.card_key) AS lastHeartbeatAt,
                   (SELECT COUNT(*)
                      FROM license_sessions s
                     WHERE s.card_key = k.card_key) AS sessionCount
            FROM license_keys k
            ORDER BY datetime(k.created_at) DESC, k.id DESC
            """
        );
    }

    public boolean createLicense(String cardKey, String expiresAt) {
        return createLicense(cardKey, expiresAt, null);
    }

    public boolean createLicense(String cardKey, String expiresAt, String remark) {
        try {
            return jdbc.update(
                """
                INSERT INTO license_keys(card_key, status, remark, bound_device_id, expires_at, created_at, updated_at)
                VALUES (?, 'ACTIVE', ?, NULL, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                cardKey,
                remark,
                expiresAt
            ) == 1;
        } catch (DataAccessException ex) {
            return false;
        }
    }

    public List<String> listRecentRemarks() {
        return jdbc.queryForList(
            """
            SELECT remark
            FROM quick_remarks
            ORDER BY datetime(updated_at) DESC, rowid DESC
            LIMIT 30
            """,
            String.class
        );
    }

    public void saveQuickRemark(String remark) {
        jdbc.update(
            """
            INSERT INTO quick_remarks(remark, created_at, updated_at)
            VALUES (?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            ON CONFLICT(remark) DO UPDATE SET updated_at = CURRENT_TIMESTAMP
            """,
            remark
        );
    }

    public boolean deleteQuickRemark(String remark) {
        return jdbc.update("DELETE FROM quick_remarks WHERE remark = ?", remark) == 1;
    }

    public boolean updateStatus(String cardKey, String status) {
        int updated = jdbc.update(
            "UPDATE license_keys SET status = ?, updated_at = CURRENT_TIMESTAMP WHERE card_key = ?",
            status,
            cardKey
        );
        if (updated == 1 && "DISABLED".equals(status)) {
            jdbc.update("DELETE FROM license_sessions WHERE card_key = ?", cardKey);
        }
        return updated == 1;
    }

    public boolean unbindDevice(String cardKey) {
        int updated = jdbc.update(
            "UPDATE license_keys SET bound_device_id = NULL, updated_at = CURRENT_TIMESTAMP WHERE card_key = ?",
            cardKey
        );
        if (updated == 1) {
            jdbc.update("DELETE FROM license_sessions WHERE card_key = ?", cardKey);
        }
        return updated == 1;
    }

    public Map<String, Object> healthSnapshot() {
        Integer licenseCount = jdbc.queryForObject("SELECT COUNT(*) FROM license_keys", Integer.class);
        Integer sessionCount = jdbc.queryForObject("SELECT COUNT(*) FROM license_sessions", Integer.class);
        return Map.of(
            "licenses", licenseCount == null ? 0 : licenseCount,
            "sessions", sessionCount == null ? 0 : sessionCount
        );
    }

    public record LicenseRow(
        String cardKey,
        String status,
        String boundDeviceId,
        String expiresAt,
        boolean notExpired
    ) {}

    public record SessionRow(
        String sessionToken,
        String cardKey,
        String deviceId,
        String status,
        String boundDeviceId,
        String expiresAt,
        boolean notExpired
    ) {}
}
