package com.konnisan.dewulicense.license;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/payments")
public class PaymentController {
    private final PaymentService payments;

    public PaymentController(PaymentService payments) {
        this.payments = payments;
    }

    @PostMapping("/create")
    public PaymentService.PaymentSession create(@RequestBody(required = false) CreatePaymentRequest request) {
        String orderNo = request == null ? null : request.orderNo();
        String clientToken = request == null ? null : request.clientToken();
        String method = request == null ? null : request.method();
        return payments.create(orderNo, clientToken, method);
    }

    public record CreatePaymentRequest(String orderNo, String clientToken, String method) {}
}
