package com.konnisan.dewulicense.license;

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
