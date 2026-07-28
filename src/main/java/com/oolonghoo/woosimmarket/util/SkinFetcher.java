package com.oolonghoo.woosimmarket.util;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.oolonghoo.woosimmarket.npc.SimNpc;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 皮肤获取器 —— 调用 Ashcon API 按 playerName 获取皮肤数据。
 *
 * <p>API 端点：{@code https://api.ashcon.app/mojang/v2/user/<name>}<br>
 * 响应格式（关键字段）：
 * <pre>{@code
 * {
 *   "uuid": "...",
 *   "username": "Notch",
 *   "textures": {
 *     "raw": {
 *       "value": "ewog...",      // base64 编码的皮肤 JSON
 *       "signature": "sig..."    // Yggdrasil 签名
 *     }
 *   }
 * }
 * }</pre></p>
 *
 * <p>本类仅做同步 HTTP 调用 + JSON 解析，异步调度由调用方
 * （{@code NpcSkinCache.preloadAsync} 通过 {@link TaskUtil#runAsync}）负责。</p>
 *
 * <p>失败策略：任何异常（网络错误、超时、HTTP 非 2xx、JSON 解析错误、
 * 字段缺失）均返回 {@code null}，由调用方走 Steve 兜底（NpcPacketSender
 * 对 null 皮肤创建无 textures 属性的 GameProfile，客户端显示默认 Steve）。</p>
 *
 * @author oolongho
 */
public final class SkinFetcher {

    /** Ashcon API 基础 URL。 */
    private static final String ASHCON_API_BASE = "https://api.ashcon.app/mojang/v2/user/";

    /** 复用的 HttpClient 实例（线程安全，支持连接池）。 */
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private SkinFetcher() {
    }

    /**
     * 同步获取指定玩家的皮肤数据（阻塞调用，应在异步线程执行）。
     *
     * @param playerName     玩家名（非空）
     * @param timeoutSeconds HTTP 请求超时（秒）
     * @return 皮肤数据，或 {@code null} 表示获取失败（走 Steve 兜底）
     */
    public static SimNpc.SkinData fetch(String playerName, int timeoutSeconds) {
        if (playerName == null || playerName.isBlank()) {
            return null;
        }

        try {
            String encodedName = URLEncoder.encode(playerName, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ASHCON_API_BASE + encodedName))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .GET()
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(
                    request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (response.statusCode() != 200) {
                return null;
            }

            return parseTextures(response.body());
        } catch (Exception e) {
            // 所有异常（网络、超时、解析等）均走 null 兜底
            return null;
        }
    }

    /**
     * 从 Ashcon API 响应体中解析 textures.raw.value + signature。
     *
     * @param jsonBody HTTP 响应体
     * @return 皮肤数据，或 {@code null}（字段缺失或格式异常）
     */
    private static SimNpc.SkinData parseTextures(String jsonBody) {
        try {
            JsonObject root = JsonParser.parseString(jsonBody).getAsJsonObject();
            JsonObject textures = root.getAsJsonObject("textures");
            if (textures == null) {
                return null;
            }
            JsonObject raw = textures.getAsJsonObject("raw");
            if (raw == null) {
                return null;
            }
            String value = raw.get("value") != null ? raw.get("value").getAsString() : null;
            String signature = raw.get("signature") != null ? raw.get("signature").getAsString() : null;

            if (value == null || value.isEmpty()) {
                return null;
            }
            return new SimNpc.SkinData(value, signature);
        } catch (Exception e) {
            return null;
        }
    }
}
