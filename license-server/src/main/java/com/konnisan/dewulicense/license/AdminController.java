package com.konnisan.dewulicense.license;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/admin/api")
public class AdminController {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DateTimeFormatter SQLITE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final LicenseRepository repository;
    private final String adminToken;

    public AdminController(
        LicenseRepository repository,
        @Value("${license.admin-token:}") String adminToken
    ) {
        this.repository = repository;
        this.adminToken = adminToken == null ? "" : adminToken.trim();
    }

    @GetMapping("/licenses")
    public List<Map<String, Object>> list(
        @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        requireAdmin(authorization);
        return repository.listLicenses();
    }

    @PostMapping("/licenses")
    public ResponseEntity<Map<String, Object>> create(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @RequestBody(required = false) CreateLicenseRequest request
    ) {
        requireAdmin(authorization);

        int days = request == null || request.days() == null ? 30 : request.days();
        if (days < 0 || days > 3650) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "days 必须在 0~3650 之间，0 表示永久");
        }

        String requestedKey = request == null ? "" : normalizeCardKey(request.cardKey());
        String expiresAt = days == 0
            ? null
            : LocalDateTime.now(ZoneOffset.UTC).plusDays(days).format(SQLITE_TIME);

        String cardKey = requestedKey;
        boolean created = false;
        for (int attempt = 0; attempt < 8 && !created; attempt++) {
            if (cardKey.isBlank()) cardKey = generateCardKey();
            created = repository.createLicense(cardKey, expiresAt);
            if (!created && !requestedKey.isBlank()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "卡密已存在");
            }
            if (!created) cardKey = "";
        }

        if (!created) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "生成卡密失败，请重试");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("cardKey", cardKey);
        body.put("status", "ACTIVE");
        body.put("days", days);
        body.put("expiresAt", expiresAt);
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @PatchMapping("/licenses/{cardKey}/status")
    public Map<String, Object> updateStatus(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @PathVariable String cardKey,
        @RequestBody StatusRequest request
    ) {
        requireAdmin(authorization);
        String status = request == null || request.status() == null
            ? ""
            : request.status().trim().toUpperCase(Locale.ROOT);
        if (!status.equals("ACTIVE") && !status.equals("DISABLED")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status 仅支持 ACTIVE / DISABLED");
        }
        boolean updated = repository.updateStatus(normalizeCardKey(cardKey), status);
        if (!updated) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "卡密不存在");
        return Map.of("ok", true, "status", status);
    }

    @PostMapping("/licenses/{cardKey}/unbind")
    public Map<String, Object> unbind(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @PathVariable String cardKey
    ) {
        requireAdmin(authorization);
        boolean updated = repository.unbindDevice(normalizeCardKey(cardKey));
        if (!updated) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "卡密不存在");
        return Map.of("ok", true, "message", "设备已解绑，会话已清理");
    }

    private void requireAdmin(String authorization) {
        if (adminToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "LICENSE_ADMIN_TOKEN 未配置");
        }
        String provided = authorization == null ? "" : authorization.trim();
        if (provided.regionMatches(true, 0, "Bearer ", 0, 7)) {
            provided = provided.substring(7).trim();
        } else {
            provided = "";
        }
        boolean matches = MessageDigest.isEqual(
            adminToken.getBytes(StandardCharsets.UTF_8),
            provided.getBytes(StandardCharsets.UTF_8)
        );
        if (!matches) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "管理员 Token 错误");
    }

    private String normalizeCardKey(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String generateCardKey() {
        byte[] bytes = new byte[8];
        RANDOM.nextBytes(bytes);
        String hex = HexFormat.of().formatHex(bytes).toUpperCase(Locale.ROOT);
        return "DEWU-" + hex.substring(0, 4) + "-" + hex.substring(4, 8)
            + "-" + hex.substring(8, 12) + "-" + hex.substring(12, 16);
    }

    public record CreateLicenseRequest(Integer days, String cardKey) {}
    public record StatusRequest(String status) {}
}
