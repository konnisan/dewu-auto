package com.konnisan.dewulicense.license;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/orders")
public class OrderController {
    private final OrderService service;

    public OrderController(OrderService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody(required = false) CreateOrderRequest request) {
        String phone = request == null ? null : request.phone();
        Integer planDays = request == null ? null : request.planDays();
        OrderService.CreatedOrder order = service.create(phone, planDays);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("orderNo", order.orderNo());
        body.put("clientToken", order.clientToken());
        body.put("status", order.status());
        body.put("planDays", order.planDays());
        body.put("amountFen", order.amountFen());
        body.put("expiresAt", order.expiresAt());
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @GetMapping("/query")
    public Map<String, Object> queryByPhone(@RequestParam("phone") String phone) {
        List<Map<String, Object>> rows = service.queryPaidByPhone(phone).stream()
            .map(this::toPublicQueryBody)
            .toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("count", rows.size());
        body.put("orders", rows);
        return body;
    }

    @GetMapping("/{orderNo}")
    public Map<String, Object> status(
        @PathVariable String orderNo,
        @RequestParam("token") String clientToken
    ) {
        return toBody(service.status(orderNo, clientToken));
    }

    @PostMapping("/{orderNo}/mock-pay")
    public Map<String, Object> mockPay(
        @PathVariable String orderNo,
        @RequestBody MockPayRequest request
    ) {
        String token = request == null ? null : request.clientToken();
        return toBody(service.mockPay(orderNo, token));
    }

    private Map<String, Object> toBody(OrderRepository.OrderRow row) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("orderNo", row.orderNo());
        body.put("status", row.status());
        body.put("planDays", row.planDays());
        body.put("amountFen", row.amountFen());
        body.put("createdAt", row.createdAt());
        body.put("paidAt", row.paidAt());
        body.put("orderExpiresAt", row.orderExpiresAt());
        if ("PAID".equals(row.status()) && row.cardKey() != null) {
            body.put("cardKey", row.cardKey());
            body.put("licenseExpiresAt", row.licenseExpiresAt());
        }
        return body;
    }

    private Map<String, Object> toPublicQueryBody(OrderRepository.OrderRow row) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orderNo", row.orderNo());
        body.put("cardKey", row.cardKey());
        body.put("planDays", row.planDays());
        body.put("paidAt", row.paidAt());
        body.put("licenseExpiresAt", row.licenseExpiresAt());
        return body;
    }

    public record CreateOrderRequest(String phone, Integer planDays) {}
    public record MockPayRequest(String clientToken) {}
}
