package com.konnisan.dewulicense.license;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class LicenseController {
    private final LicenseService service;
    private final LicenseRepository repository;

    public LicenseController(LicenseService service, LicenseRepository repository) {
        this.service = service;
        this.repository = repository;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("service", "dewu-license-server");
        body.putAll(repository.healthSnapshot());
        return body;
    }

    @PostMapping("/verify")
    public ResponseEntity<Map<String, Object>> verify(@RequestBody VerifyRequest request) {
        LicenseService.VerifyResult result = service.verify(request.cardKey(), request.deviceId());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", result.ok());
        body.put("message", result.message());
        if (result.sessionToken() != null) body.put("sessionToken", result.sessionToken());
        return ResponseEntity.ok(body);
    }

    @PostMapping("/heartbeat")
    public ResponseEntity<Map<String, Object>> heartbeat(@RequestBody HeartbeatRequest request) {
        LicenseService.BasicResult result = service.heartbeat(request.sessionToken(), request.deviceId());
        return ResponseEntity.ok(Map.of(
            "ok", result.ok(),
            "message", result.message()
        ));
    }

    public record VerifyRequest(String cardKey, String deviceId) {}
    public record HeartbeatRequest(String sessionToken, String deviceId) {}
}
