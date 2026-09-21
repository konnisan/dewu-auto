package com.konnisan.dewulicense.license;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
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
    private final Path adminPasswordFile;

    public AdminController(
        LicenseRepository repository,
        @Value("${license.admin-password-file:admin-password.txt}") String adminPasswordFile
    ) {
        this.repository = repository;
        this.adminPasswordFile = Path.of(
            adminPasswordFile == null || adminPasswordFile.isBlank()
                ? "admin-password.txt"
                : adminPasswordFile.trim()
        ).toAbsolutePath().normalize();
    }

    @GetMapping("/licenses")
    public List<Map<String, Object>> list(
        @RequestHeader(value = "X-Admin-Password", required = false) String password
    ) {
        requireAdminPassword(password);
        return repository.listLicenses();
    }

    @PostMapping("/licenses")
    @Transactional
    public ResponseEntity<Map<String, Object>> create(
        @RequestHeader(value = "X-Admin-Password", required = false) String password,
        @RequestBody(required = false) CreateLicenseRequest request
    ) {
        requireAdminPassword(password);

        int days = request == null || request.days() == null ? 30 : request.days();
        if (days < 0 || days > 3650) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "days 必须在 0~3650 之间，0 表示永久");
        }

        int quantity = request == null || request.quantity() == null ? 1 : request.quantity();
        if (quantity < 1 || quantity > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "quantity 必须在 1~100 之间");
        }

        String remark = normalizeRemark(request == null ? null : request.remark());

        String requestedKey = request == null ? "" : normalizeCardKey(request.cardKey());
        if (quantity > 1 && !requestedKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "批量生成时不能指定自定义卡密");
        }
        String expiresAt = days == 0
            ? null
            : LocalDateTime.now(ZoneOffset.UTC).plusDays(days).format(SQLITE_TIME);

        List<String> cardKeys = new ArrayList<>(quantity);
        for (int index = 0; index < quantity; index++) {
            String cardKey = requestedKey;
            boolean created = false;
            for (int attempt = 0; attempt < 8 && !created; attempt++) {
                if (cardKey.isBlank()) cardKey = generateCardKey();
                created = repository.createLicense(cardKey, expiresAt, remark);
                if (!created && !requestedKey.isBlank()) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "卡密已存在");
                }
                if (!created) cardKey = "";
            }

            if (!created) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "生成卡密失败，请重试");
            }
            cardKeys.add(cardKey);
        }

        if (remark != null) {
            repository.saveQuickRemark(remark);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("cardKey", cardKeys.get(0));
        body.put("cardKeys", cardKeys);
        body.put("quantity", cardKeys.size());
        body.put("status", "ACTIVE");
        body.put("days", days);
        body.put("remark", remark);
        body.put("expiresAt", expiresAt);
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @GetMapping("/remarks")
    public List<String> remarks(
        @RequestHeader(value = "X-Admin-Password", required = false) String password
    ) {
        requireAdminPassword(password);
        return repository.listRecentRemarks();
    }

    @PostMapping("/remarks")
    public Map<String, Object> createRemark(
        @RequestHeader(value = "X-Admin-Password", required = false) String password,
        @RequestBody RemarkRequest request
    ) {
        requireAdminPassword(password);
        String remark = normalizeRemark(request == null ? null : request.remark());
        if (remark == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "备注不能为空");
        }
        repository.saveQuickRemark(remark);
        return Map.of("ok", true, "remark", remark);
    }

    @DeleteMapping("/remarks")
    public Map<String, Object> deleteRemark(
        @RequestHeader(value = "X-Admin-Password", required = false) String password,
        @RequestParam("remark") String rawRemark
    ) {
        requireAdminPassword(password);
        String remark = normalizeRemark(rawRemark);
        if (remark == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "备注不能为空");
        }
        repository.deleteQuickRemark(remark);
        return Map.of("ok", true, "remark", remark);
    }

    @PatchMapping("/licenses/{cardKey}/status")
    public Map<String, Object> updateStatus(
        @RequestHeader(value = "X-Admin-Password", required = false) String password,
        @PathVariable String cardKey,
        @RequestBody StatusRequest request
    ) {
        requireAdminPassword(password);
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
        @RequestHeader(value = "X-Admin-Password", required = false) String password,
        @PathVariable String cardKey
    ) {
        requireAdminPassword(password);
        boolean updated = repository.unbindDevice(normalizeCardKey(cardKey));
        if (!updated) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "卡密不存在");
        return Map.of("ok", true, "message", "设备已解绑，会话已清理");
    }

    private void requireAdminPassword(String providedPassword) {
        String expectedPassword;
        try {
            expectedPassword = Files.readString(adminPasswordFile, StandardCharsets.UTF_8);
            if (expectedPassword.startsWith("\uFEFF")) {
                expectedPassword = expectedPassword.substring(1);
            }
            expectedPassword = expectedPassword.trim();
        } catch (IOException ex) {
            throw new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "管理密码文件不存在或无法读取: " + adminPasswordFile
            );
        }
        if (expectedPassword.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "管理密码文件内容为空");
        }
        String provided = providedPassword == null ? "" : providedPassword.trim();
        boolean matches = MessageDigest.isEqual(
            expectedPassword.getBytes(StandardCharsets.UTF_8),
            provided.getBytes(StandardCharsets.UTF_8)
        );
        if (!matches) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "密码错误");
    }

    private String normalizeCardKey(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeRemark(String value) {
        String remark = value == null ? "" : value.trim();
        if (remark.length() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "备注不能超过 100 个字符");
        }
        return remark.isBlank() ? null : remark;
    }

    private String generateCardKey() {
        byte[] bytes = new byte[8];
        RANDOM.nextBytes(bytes);
        String hex = HexFormat.of().formatHex(bytes).toUpperCase(Locale.ROOT);
        return "DEWU-" + hex.substring(0, 4) + "-" + hex.substring(4, 8)
            + "-" + hex.substring(8, 12) + "-" + hex.substring(12, 16);
    }

    public record CreateLicenseRequest(Integer days, Integer quantity, String remark, String cardKey) {}
    public record RemarkRequest(String remark) {}
    public record StatusRequest(String status) {}
}
