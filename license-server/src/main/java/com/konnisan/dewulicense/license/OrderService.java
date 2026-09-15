package com.konnisan.dewulicense.license;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

@Service
public class OrderService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DateTimeFormatter SQLITE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter ORDER_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final int MAX_QUANTITY = 50;
    private static final Map<Integer, Integer> PLAN_PRICES = Map.of(
        1, 100,
        7, 300,
        30, 900,
        90, 1990,
        365, 4990,
        0, 9990
    );

    private final OrderRepository orders;
    private final LicenseRepository licenses;

    public OrderService(OrderRepository orders, LicenseRepository licenses) {
        this.orders = orders;
        this.licenses = licenses;
    }

    public CreatedOrder create(String phone, Integer requestedPlanDays) {
        return create(phone, requestedPlanDays, 1);
    }

    @Transactional
    public CreatedOrder create(String phone, Integer requestedPlanDays, Integer requestedQuantity) {
        String normalizedPhone = normalizePhone(phone);
        requirePhone(normalizedPhone);

        int planDays = requestedPlanDays == null ? 30 : requestedPlanDays;
        Integer unitAmountFen = PLAN_PRICES.get(planDays);
        if (unitAmountFen == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的授权套餐");
        }

        int quantity = requestedQuantity == null ? 1 : requestedQuantity;
        if (quantity < 1 || quantity > MAX_QUANTITY) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "购买数量必须在 1~" + MAX_QUANTITY + " 之间");
        }

        int amountFen;
        try {
            amountFen = Math.multiplyExact(unitAmountFen, quantity);
        } catch (ArithmeticException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "购买数量超出范围");
        }

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        String orderExpiresAt = now.plusMinutes(20).format(SQLITE_TIME);
        String clientToken = generateClientToken();

        for (int attempt = 0; attempt < 8; attempt++) {
            String orderNo = generateOrderNo(now);
            if (orders.createOrder(orderNo, clientToken, normalizedPhone, planDays, amountFen, orderExpiresAt)) {
                orders.ensureOrderItems(orderNo, quantity);
                return new CreatedOrder(
                    orderNo,
                    clientToken,
                    planDays,
                    quantity,
                    unitAmountFen,
                    amountFen,
                    "CREATED",
                    orderExpiresAt
                );
            }
        }
        throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "创建订单失败，请重试");
    }

    public OrderRepository.OrderRow status(String orderNo, String clientToken) {
        requireCredentials(orderNo, clientToken);
        OrderRepository.OrderRow row = orders.find(orderNo.trim(), clientToken.trim());
        if (row == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "订单不存在或凭证无效");
        }
        if ("CREATED".equals(row.status())) {
            orders.markExpiredIfNeeded(row.orderNo());
            row = orders.find(row.orderNo(), row.clientToken());
        }
        return row;
    }

    public List<OrderRepository.OrderRow> queryByPhone(String phone) {
        String normalizedPhone = normalizePhone(phone);
        requirePhone(normalizedPhone);
        return orders.findByPhone(normalizedPhone);
    }

    public List<OrderRepository.OrderCardRow> cardsForOrder(String orderNo) {
        return orders.findCards(orderNo);
    }

    @Transactional
    public OrderRepository.OrderRow mockPay(String orderNo, String clientToken) {
        OrderRepository.OrderRow row = status(orderNo, clientToken);
        if ("PAID".equals(row.status())) return row;
        if (!"CREATED".equals(row.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前订单状态不可支付：" + row.status());
        }

        int quantity = Math.max(1, row.quantity());
        orders.ensureOrderItems(row.orderNo(), quantity);
        String firstCardKey = null;

        for (int itemNo = 1; itemNo <= quantity; itemNo++) {
            String cardKey = createLicenseForPlan(row.planDays());
            if (!orders.assignCardToItem(row.orderNo(), itemNo, cardKey)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "订单卡密生成状态已变化，请刷新");
            }
            if (firstCardKey == null) firstCardKey = cardKey;
        }

        if (!orders.markPaid(row.orderNo(), row.clientToken(), firstCardKey)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "订单状态已变化，请刷新");
        }
        return orders.find(row.orderNo(), row.clientToken());
    }

    private String createLicenseForPlan(int planDays) {
        String licenseExpiresAt = planDays == 0
            ? null
            : LocalDateTime.now(ZoneOffset.UTC).plusDays(planDays).format(SQLITE_TIME);

        for (int attempt = 0; attempt < 8; attempt++) {
            String cardKey = generateCardKey();
            if (licenses.createLicense(cardKey, licenseExpiresAt)) return cardKey;
        }
        throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "生成卡密失败，请重试");
    }

    private String normalizePhone(String value) {
        return value == null ? "" : value.trim();
    }

    private void requirePhone(String phone) {
        if (!phone.matches("^1\\d{10}$")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请输入正确的 11 位手机号");
        }
    }

    private void requireCredentials(String orderNo, String clientToken) {
        if (orderNo == null || orderNo.isBlank() || clientToken == null || clientToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "订单号和 clientToken 不能为空");
        }
    }

    private String generateClientToken() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String generateOrderNo(LocalDateTime now) {
        return "DW" + now.format(ORDER_TIME) + String.format("%06d", RANDOM.nextInt(1_000_000));
    }

    private String generateCardKey() {
        byte[] bytes = new byte[8];
        RANDOM.nextBytes(bytes);
        String hex = HexFormat.of().formatHex(bytes).toUpperCase();
        return "DEWU-" + hex.substring(0, 4) + "-" + hex.substring(4, 8)
            + "-" + hex.substring(8, 12) + "-" + hex.substring(12, 16);
    }

    public record CreatedOrder(
        String orderNo,
        String clientToken,
        int planDays,
        int quantity,
        int unitAmountFen,
        int amountFen,
        String status,
        String expiresAt
    ) {}
}
