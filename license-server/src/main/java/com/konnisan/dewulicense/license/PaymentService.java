package com.konnisan.dewulicense.license;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;

@Service
public class PaymentService {
    private final OrderService orders;
    private final String paymentMode;

    public PaymentService(
        OrderService orders,
        @Value("${license.payment.mode:mock}") String paymentMode
    ) {
        this.orders = orders;
        this.paymentMode = paymentMode == null ? "mock" : paymentMode.trim().toLowerCase(Locale.ROOT);
    }

    public PaymentSession create(String orderNo, String clientToken, String rawMethod) {
        OrderRepository.OrderRow order = orders.status(orderNo, clientToken);
        if (!"CREATED".equals(order.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前订单状态不可支付：" + order.status());
        }

        String method = normalizeMethod(rawMethod);
        if ("mock".equals(paymentMode)) {
            return new PaymentSession(
                "MOCK",
                method,
                order.orderNo(),
                order.amountFen(),
                null,
                null,
                "测试支付通道已创建"
            );
        }

        throw new ResponseStatusException(
            HttpStatus.SERVICE_UNAVAILABLE,
            method.equals("ALIPAY")
                ? "支付宝真实支付尚未配置，请先配置支付宝商户应用参数"
                : "微信真实支付尚未配置，请先配置微信支付商户参数"
        );
    }

    private String normalizeMethod(String rawMethod) {
        String method = rawMethod == null ? "" : rawMethod.trim().toUpperCase(Locale.ROOT);
        if (!method.equals("ALIPAY") && !method.equals("WECHAT")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "支付方式仅支持 alipay / wechat");
        }
        return method;
    }

    public record PaymentSession(
        String mode,
        String method,
        String orderNo,
        int amountFen,
        String qrCodeUrl,
        String redirectUrl,
        String message
    ) {}
}
