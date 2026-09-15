package com.konnisan.dewulicense.license;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
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

    public void ensureOrderItems(String orderNo, int quantity) {
        for (int itemNo = 1; itemNo <= quantity; itemNo++) {
            jdbc.update(
                """
                INSERT OR IGNORE INTO purchase_order_items(order_no, item_no, card_key, created_at)
                VALUES (?, ?, NULL, CURRENT_TIMESTAMP)
                """,
                orderNo,
                itemNo
            );
        }
    }

    public boolean assignCardToItem(String orderNo, int itemNo, String cardKey) {
        return jdbc.update(
            """
            UPDATE purchase_order_items
               SET card_key = ?
             WHERE order_no = ?
               AND item_no = ?
               AND card_key IS NULL
            """,
            cardKey,
            orderNo,
            itemNo
        ) == 1;
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

    public List<OrderRow> findByPhone(String phone) {
        return jdbc.query(
            baseSelect() + " WHERE o.device_id = ? ORDER BY o.created_at DESC, o.id DESC",
            (rs, rowNum) -> mapRow(rs),
            phone
        );
    }

    public List<OrderCardRow> findCards(String orderNo) {
        List<OrderCardRow> cards = jdbc.query(
            """
            SELECT i.item_no,
                   i.card_key,
                   k.expires_at AS license_expires_at
              FROM purchase_order_items i
              JOIN license_keys k ON k.card_key = i.card_key
             WHERE i.order_no = ?
               AND i.card_key IS NOT NULL
             ORDER BY i.item_no ASC
            """,
            (rs, rowNum) -> new OrderCardRow(
                rs.getInt("item_no"),
                rs.getString("card_key"),
                rs.getString("license_expires_at")
            ),
            orderNo
        );
        if (!cards.isEmpty()) return cards;

        List<OrderCardRow> legacy = jdbc.query(
            """
            SELECT 1 AS item_no,
                   o.card_key,
                   k.expires_at AS license_expires_at
              FROM purchase_orders o
              JOIN license_keys k ON k.card_key = o.card_key
             WHERE o.order_no = ?
               AND o.card_key IS NOT NULL
             LIMIT 1
            """,
            (rs, rowNum) -> new OrderCardRow(
                rs.getInt("item_no"),
                rs.getString("card_key"),
                rs.getString("license_expires_at")
            ),
            orderNo
        );
        return legacy.isEmpty() ? new ArrayList<>() : legacy;
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

    public boolean markPaid(String orderNo, String clientToken, String firstCardKey) {
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
            firstCardKey,
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
                   CASE
                       WHEN EXISTS(SELECT 1 FROM purchase_order_items i WHERE i.order_no = o.order_no)
                       THEN (SELECT COUNT(*) FROM purchase_order_items i WHERE i.order_no = o.order_no)
                       ELSE 1
                   END AS quantity
            FROM purchase_orders o
            """;
    }

    private OrderRow mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new OrderRow(
            rs.getString("order_no"),
            rs.getString("client_token"),
            rs.getString("device_id"),
            rs.getInt("plan_days"),
            rs.getInt("amount_fen"),
            rs.getInt("quantity"),
            rs.getString("status"),
            rs.getString("card_key"),
            rs.getString("created_at"),
            rs.getString("paid_at"),
            rs.getString("expires_at")
        );
    }

    public record OrderRow(
        String orderNo,
        String clientToken,
        String phone,
        int planDays,
        int amountFen,
        int quantity,
        String status,
        String cardKey,
        String createdAt,
        String paidAt,
        String orderExpiresAt
    ) {}

    public record OrderCardRow(
        int itemNo,
        String cardKey,
        String licenseExpiresAt
    ) {}
}
