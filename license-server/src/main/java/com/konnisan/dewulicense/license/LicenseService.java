package com.konnisan.dewulicense.license;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.HexFormat;

@Service
public class LicenseService {
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final LicenseRepository repository;

    public LicenseService(LicenseRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public VerifyResult verify(String rawCardKey, String rawDeviceId) {
        String cardKey = normalize(rawCardKey);
        String deviceId = normalize(rawDeviceId);

        if (cardKey.isBlank()) return VerifyResult.failure("请输入卡密");
        if (deviceId.isBlank()) return VerifyResult.failure("设备标识为空");

        LicenseRepository.LicenseRow license = repository.findLicense(cardKey);
        if (license == null) return VerifyResult.failure("卡密不存在");
        if (!"ACTIVE".equalsIgnoreCase(license.status())) return VerifyResult.failure("卡密已禁用");
        if (!license.notExpired()) return VerifyResult.failure("卡密已过期");

        if (!repository.bindDeviceIfNeeded(cardKey, deviceId)) {
            return VerifyResult.failure("该卡密已绑定其他设备");
        }

        String sessionToken = newSessionToken();
        repository.replaceSession(sessionToken, cardKey, deviceId);
        return VerifyResult.success(sessionToken);
    }

    @Transactional
    public BasicResult heartbeat(String rawSessionToken, String rawDeviceId) {
        String sessionToken = normalize(rawSessionToken);
        String deviceId = normalize(rawDeviceId);

        if (sessionToken.isBlank()) return BasicResult.failure("卡密会话不存在");
        if (deviceId.isBlank()) return BasicResult.failure("设备标识为空");

        LicenseRepository.SessionRow session = repository.findSession(sessionToken);
        if (session == null) return BasicResult.failure("卡密会话已失效");

        if (!deviceId.equals(session.deviceId())) {
            repository.deleteSession(sessionToken);
            return BasicResult.failure("设备不匹配");
        }
        if (!"ACTIVE".equalsIgnoreCase(session.status())) {
            repository.deleteSession(sessionToken);
            return BasicResult.failure("卡密已禁用");
        }
        if (!session.notExpired()) {
            repository.deleteSession(sessionToken);
            return BasicResult.failure("卡密已过期");
        }
        if (session.boundDeviceId() == null || !deviceId.equals(session.boundDeviceId())) {
            repository.deleteSession(sessionToken);
            return BasicResult.failure("卡密设备绑定已变更");
        }

        repository.touchHeartbeat(sessionToken);
        return BasicResult.success();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private String newSessionToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    public record VerifyResult(boolean ok, String message, String sessionToken) {
        static VerifyResult success(String token) {
            return new VerifyResult(true, "验证成功", token);
        }

        static VerifyResult failure(String message) {
            return new VerifyResult(false, message, null);
        }
    }

    public record BasicResult(boolean ok, String message) {
        static BasicResult success() {
            return new BasicResult(true, "ok");
        }

        static BasicResult failure(String message) {
            return new BasicResult(false, message);
        }
    }
}
