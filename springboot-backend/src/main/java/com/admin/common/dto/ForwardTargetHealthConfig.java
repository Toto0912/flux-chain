package com.admin.common.dto;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import lombok.Data;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Data
public class ForwardTargetHealthConfig {
    public static final String FIXED_ECHO = "echo_fixed";
    public static final String GOOGLE_STUN = "stun";
    private static final int MAX_ADDRESS_LENGTH = 320;

    private Boolean enabled;
    private Boolean tcp;
    private String udpMode;
    private Map<String, String> udpTargets;
    private Integer intervalSeconds;
    private Integer timeoutSeconds;
    private Integer switchThresholdMs;

    public static ForwardTargetHealthConfig read(String stored) {
        if (stored == null || stored.isBlank()) return null;
        ForwardTargetHealthConfig settings = JSON.parseObject(stored, ForwardTargetHealthConfig.class);
        if (settings != null && GOOGLE_STUN.equals(settings.udpMode)) settings.udpTargets = Map.of();
        return settings;
    }

    public static ForwardTargetHealthConfig validate(ForwardTargetHealthConfig settings, String destinations, String strategy) {
        if (settings == null || !Boolean.TRUE.equals(settings.enabled)) {
            if ("latency".equals(strategy)) {
                throw new IllegalArgumentException("最低延迟策略需要启用目标健康检查");
            }
            return null;
        }

        List<String> targets = targetList(destinations);
        if (targets.size() < 2 || targets.stream().distinct().count() != targets.size()) {
            throw new IllegalArgumentException("目标健康检查需要至少两个不同的转发目标");
        }
        targets.forEach(ForwardTargetHealthConfig::validateAddress);
        if (strategy != null && !strategy.isBlank() &&
                !List.of("fifo", "round", "rand", "hash", "latency").contains(strategy)) {
            throw new IllegalArgumentException("目标健康检查不支持当前负载策略");
        }

        ForwardTargetHealthConfig normalized = new ForwardTargetHealthConfig();
        normalized.enabled = true;
        normalized.tcp = !Boolean.FALSE.equals(settings.tcp);
        normalized.udpMode = Objects.toString(settings.udpMode, "off");
        if (!List.of("off", "echo", GOOGLE_STUN, FIXED_ECHO).contains(normalized.udpMode)) {
            throw new IllegalArgumentException("UDP 探测方式无效");
        }
        if (!normalized.tcp && "off".equals(normalized.udpMode)) {
            throw new IllegalArgumentException("请至少启用一种探测方式");
        }
        normalized.intervalSeconds = inRange(settings.intervalSeconds, 300, 5, 3600, "探测间隔（秒）");
        normalized.timeoutSeconds = inRange(settings.timeoutSeconds, 3, 1, 5, "单次探测超时（秒）");
        normalized.switchThresholdMs = "latency".equals(strategy)
                ? inRange(settings.switchThresholdMs, 1000, 1, normalized.timeoutSeconds * 1000, "切换阈值（毫秒）")
                : 1000;
        normalized.udpTargets = new LinkedHashMap<>();
        if ("echo".equals(normalized.udpMode) && settings.udpTargets != null) {
            for (String target : targets) {
                String probeAddress = settings.udpTargets.get(target);
                if (probeAddress != null && !probeAddress.isBlank()) {
                    probeAddress = probeAddress.trim();
                    validateAddress(probeAddress);
                    normalized.udpTargets.put(target, probeAddress);
                }
            }
        }
        return normalized;
    }

    public JSONObject nodeSettings(List<String> targets) {
        JSONObject settings = new JSONObject();
        settings.put("tcp", tcp);
        settings.put("udpMode", udpMode);
        settings.put("intervalSeconds", intervalSeconds);
        settings.put("timeoutSeconds", timeoutSeconds);
        settings.put("switchThresholdMs", switchThresholdMs);
        if ("echo".equals(udpMode) && udpTargets != null && !udpTargets.isEmpty()) {
            settings.put("udpTargets", targets.stream().map(target -> udpTargets.getOrDefault(target, "")).toList());
        }
        return settings;
    }

    public static List<String> targetList(String destinations) {
        return Arrays.stream(destinations.split(",", -1)).map(String::trim).toList();
    }

    /** Worst-case concurrent probes in thousandths of a slot, with a minimum charge per running rule. */
    public long probeLoad(String destinations) {
        List<String> targets = targetList(destinations);
        long checks = Boolean.FALSE.equals(tcp) ? 0 : targets.size();
        checks += switch (Objects.toString(udpMode, "off")) {
            case GOOGLE_STUN -> 1;
            case FIXED_ECHO -> targets.stream().map(target -> target.substring(0, Math.max(0, target.lastIndexOf(':'))))
                    .distinct().count();
            case "echo" -> targets.size();
            default -> 0;
        };
        int interval = Math.max(1, Objects.requireNonNullElse(intervalSeconds, 300));
        int timeout = Math.max(1, Objects.requireNonNullElse(timeoutSeconds, 3));
        return Math.max(125L, (checks * timeout * 1000 + interval - 1) / interval);
    }

    private static int inRange(Integer value, int fallback, int minimum, int maximum, String label) {
        int result = value == null ? fallback : value;
        if (result < minimum || result > maximum) {
            throw new IllegalArgumentException(label + "必须在 " + minimum + " 到 " + maximum + " 之间");
        }
        return result;
    }

    private static void validateAddress(String address) {
        if (address.length() > MAX_ADDRESS_LENGTH) {
            throw new IllegalArgumentException("单个探测地址不能超过 " + MAX_ADDRESS_LENGTH + " 个字符");
        }
        int separator = address.lastIndexOf(':');
        if (separator > 0 && address.chars().noneMatch(Character::isWhitespace)) {
            String host = address.substring(0, separator);
            if (!host.contains(":") || (host.startsWith("[") && host.endsWith("]"))) {
                try {
                    int port = Integer.parseInt(address.substring(separator + 1));
                    if (port >= 1 && port <= 65535) return;
                } catch (NumberFormatException ignored) {
                    // Report a uniform error for malformed hosts and ports.
                }
            }
        }
        throw new IllegalArgumentException("目标探测地址无效：" + address);
    }
}
