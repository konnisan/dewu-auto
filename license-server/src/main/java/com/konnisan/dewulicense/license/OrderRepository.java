package com.konnisan.dewulicense.license;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class OrderRepository {
    private final JdbcTemplate jdbc;

    public OrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean createOrder(
        String orderNo,
        String clientToken,
        String phone,
        int planDays,
        int amountFen,
        String expiresAt
    ) {
        try {
            return jdbc.update(
                """
                INSERT INTO purchase_orders(
                    order_no,
                    client_token,
                    device_id,
                    plan_days,
                    amount_fen,
                    status,
                    card_key,
                    created_at,
                    paid_at,
                    expires_at
                )
                VALUES (?, ?, ?, ?, ?, 'CREATED', NULL, CURRENT_TIMESTAMP, NULL, ?)
                """,
                orderNo,
                clientToken,
                phone,
                planDays,
                amountFen,
                expiresAt
            ) == 1;
        } catch (DataAccessException ex) {
            return false;
        }
    }

    public OrderRow find(String orderNo, String clientToken) {
        List<OrderRow> rows = jdbc.query(
            baseSelect() + " WHERE o.order_no = ? AND o.client_token = ? LIMIT 1",
            (rs, rowNum) -> mapRow(rs),
            orderNo,
            clientToken
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<OrderRow> findPaidByPhone(String phone) {
        return jdbc.query(
            baseSelect() + " WHERE o.device_id = ? AND o.status = 'PAID' ORDER BY o.paid_at DESC, o.id DESC",
            (rs, rowNum) -> mapRow(rs),
            phone
        );
    }

    public boolean markExpiredIfNeeded(String orderNo) {
        return jdbc.update(
            """
            UPDATE purchase_orders
               SET status = 'EXPIRED'
             WHERE order_no = ?
               AND status = 'CREATED'
               AND datetime(expires_at) <= CURRENT_TIMESTAMP
            """,
            orderNo
        ) == 1;
    }

    public boolean markPaid(String orderNo, String clientToken, String cardKey) {
        return jdbc.update(
            """
            UPDATE purchase_orders
               SET status = 'PAID',
                   card_key = ?,
                   paid_at = CURRENT_TIMESTAMP
             WHERE order_no = ?
               AND client_token = ?
               AND status = 'CREATED'
            """,
            cardKey,
            orderNo,
            clientToken
        ) == 1;
    }

    private String baseSelect() {
        return """
            SELECT o.id,
                   o.order_no,
                   o.client_token,
                   o.device_id,
                   o.plan_days,
                   o.amount_fen,
                   o.status,
                   o.card_key,
                   o.created_at,
                   o.paid_at,
                   o.expires_at,
                   k.expires_at AS license_expires_at
            FROM purchase_orders o
            LEFT JOIN license_keys k ON k.card_key = o.card_key
            """;
    }

    private OrderRow mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new OrderRow(
            rs.getString("order_no"),
            rs.getString("client_token"),
            rs.getString("device_id"),
            rs.getInt("plan_days"),
            rs.getInt("amount_fen"),
            rs.getString("status"),
            rs.getString("card_key"),
            rs.getString("created_at"),
            rs.getString("paid_at"),
            rs.getString("expires_at"),
            rs.getString("license_expires_at")
        );
    }

    public record OrderRow(
        String orderNo,
        String clientToken,
        String phone,
        int planDays,
        int amountFen,
        String status,
        String cardKey,
        String createdAt,
        String paidAt,
        String orderExpiresAt,
        String licenseExpiresAt
    ) {}
}
