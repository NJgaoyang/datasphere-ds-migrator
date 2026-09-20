package com.company.migrator.target;

import com.company.migrator.common.MigrationModels.Settings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class DataSphereClient {
    private final ObjectMapper mapper;
    private final Map<String, String> sessionCookies = new ConcurrentHashMap<>();

    public DataSphereClient(ObjectMapper mapper) { this.mapper = mapper; }

    public TargetCheck test(Settings settings) {
        try {
            login(settings, true);
            JsonNode me = data(get(settings, "/api/auth/me"));
            String username = me.path("username").asText(settings.targetUsername());
            String role = me.path("roleCode").asText("");
            return new TargetCheck(true, "DataSphere 登录成功：" + username, role.isBlank() ? username : username + " / " + role);
        } catch (Exception ex) {
            return new TargetCheck(false, "DataSphere 登录失败：" + rootMessage(ex), "");
        }
    }

    public long singleProjectId(Settings settings) {
        JsonNode data = data(get(settings, "/api/projects"));
        if (!data.isArray() || data.isEmpty()) throw new IllegalStateException("DataSphere 没有可用开发项目");
        return data.get(0).path("id").asLong();
    }

    public long ensureTopFolder(Settings settings, long projectId, String name) {
        JsonNode folders = data(get(settings, "/api/folders?projectId=" + projectId));
        if (folders.isArray()) {
            for (JsonNode folder : folders) {
                if (folder.path("parentId").isNull() && name.equals(folder.path("name").asText())) return folder.path("id").asLong();
            }
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("projectId", projectId); payload.put("parentId", null); payload.put("name", name);
        return data(post(settings, "/api/folders", payload)).path("id").asLong();
    }

    public long createFile(Settings settings, long projectId, Long folderId, String name, String fileType, String content, String description) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("projectId", projectId); payload.put("folderId", folderId); payload.put("name", name);
        payload.put("fileType", fileType); payload.put("content", content == null ? "" : content);
        payload.put("description", description == null ? "" : description);
        return data(post(settings, "/api/files", payload)).path("id").asLong();
    }

    public long createWorkflow(Settings settings, Map<String, Object> payload) {
        return data(post(settings, "/api/workflows", payload)).path("id").asLong();
    }

    public void saveSchedule(Settings settings, long workflowId, String cron, String timezone, String failureStrategy, String workerGroup) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("cronExpression", cron);
        payload.put("timezone", timezone == null || timezone.isBlank() ? "Asia/Shanghai" : timezone);
        payload.put("enabled", false); payload.put("failureStrategy", normalizeFailureStrategy(failureStrategy));
        payload.put("parallelism", 1); payload.put("workerGroup", workerGroup == null || workerGroup.isBlank() ? "default" : workerGroup);
        payload.put("alertGroup", "");
        data(put(settings, "/api/scheduler/workflows/" + workflowId + "/schedule", payload));
    }

    public JsonNode validateWorkflow(Settings settings, long workflowId) {
        return data(post(settings, "/api/workflows/" + workflowId + "/validate", Map.of()));
    }

    private JsonNode get(Settings settings, String path) {
        return request(settings).get().uri(path).retrieve().body(JsonNode.class);
    }

    private JsonNode post(Settings settings, String path, Object body) {
        return request(settings).post().uri(path).contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(JsonNode.class);
    }

    private JsonNode put(Settings settings, String path, Object body) {
        return request(settings).put().uri(path).contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(JsonNode.class);
    }

    private RestClient request(Settings settings) {
        String cookie = sessionCookie(settings);
        return RestClient.builder().baseUrl(baseUrl(settings))
                .defaultHeader("X-Requested-With", "DataSphere")
                .defaultHeader(HttpHeaders.COOKIE, cookie).build();
    }

    private String sessionCookie(Settings settings) {
        String key = sessionKey(settings);
        String cookie = sessionCookies.get(key);
        return cookie == null || cookie.isBlank() ? login(settings, false) : cookie;
    }

    private String login(Settings settings, boolean force) {
        requireCredentials(settings);
        String key = sessionKey(settings);
        if (!force) {
            String existing = sessionCookies.get(key);
            if (existing != null && !existing.isBlank()) return existing;
        }
        RestClient client = RestClient.builder().baseUrl(baseUrl(settings))
                .defaultHeader("X-Requested-With", "DataSphere").build();
        Map<String, Object> payload = Map.of("username", settings.targetUsername().trim(), "password", settings.targetPassword());
        ResponseEntity<JsonNode> response = client.post().uri("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON).body(payload).retrieve().toEntity(JsonNode.class);
        data(response.getBody());
        String setCookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        if (setCookie == null || setCookie.isBlank()) throw new IllegalStateException("DataSphere 登录成功但未返回 platform_session Cookie");
        String cookie = setCookie.split(";", 2)[0].trim();
        if (!cookie.startsWith("platform_session=")) throw new IllegalStateException("DataSphere 未返回有效 platform_session Cookie");
        sessionCookies.put(key, cookie);
        return cookie;
    }

    private void requireCredentials(Settings settings) {
        if (settings.targetUsername() == null || settings.targetUsername().isBlank()) throw new IllegalStateException("请配置 DataSphere 登录用户名");
        if (settings.targetPassword() == null || settings.targetPassword().isBlank()) throw new IllegalStateException("请配置 DataSphere 登录密码");
    }

    private String sessionKey(Settings settings) { return baseUrl(settings) + "|" + settings.targetUsername().trim().toLowerCase(Locale.ROOT); }
    private String baseUrl(Settings settings) {
        String base = settings.targetBaseUrl() == null ? "" : settings.targetBaseUrl().trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        if (base.isBlank()) throw new IllegalStateException("请配置 DataSphere Base URL");
        return base;
    }

    private JsonNode data(JsonNode root) {
        if (root == null) throw new IllegalStateException("DataSphere 返回空响应");
        if (root.has("success") && !root.path("success").asBoolean()) throw new IllegalStateException(root.path("message").asText("DataSphere 请求失败"));
        return root.has("data") ? root.path("data") : root;
    }

    private String normalizeFailureStrategy(String value) {
        if (value == null || value.isBlank()) return "END";
        String v = value.trim().toUpperCase(Locale.ROOT);
        return "CONTINUE".equals(v) || "1".equals(v) ? "CONTINUE" : "END";
    }

    private String rootMessage(Throwable ex) {
        Throwable cursor = ex;
        while (cursor.getCause() != null) cursor = cursor.getCause();
        return cursor.getMessage() == null ? cursor.getClass().getSimpleName() : cursor.getMessage();
    }

    public record TargetCheck(boolean success, String message, String detail) { }
}
