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
            return new TargetCheck(true, "DataForge 登录成功：" + username, role.isBlank() ? username : username + " / " + role);
        } catch (Exception ex) {
            return new TargetCheck(false, "DataForge 登录失败：" + rootMessage(ex), "");
        }
    }

    public long singleProjectId(Settings settings) {
        JsonNode data = data(get(settings, "/api/projects"));
        if (!data.isArray() || data.isEmpty()) throw new IllegalStateException("DataForge 没有可用开发项目");
        return data.get(0).path("id").asLong();
    }

    public long ensureTopFolder(Settings settings, long projectId, String name) {
        JsonNode folders = data(get(settings, "/api/folders?projectId=" + projectId));
        if (folders.isArray()) {
            for (JsonNode folder : folders) {
                JsonNode parentId = folder.get("parentId");
                boolean root = parentId == null || parentId.isNull() || parentId.asLong(0) == 0;
                if (root && name.equalsIgnoreCase(folder.path("name").asText())) return folder.path("id").asLong();
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

    public void updateFile(Settings settings, long fileId, Long folderId, String name, String content, String description) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("content", content == null ? "" : content);
        payload.put("name", name);
        payload.put("description", description == null ? "" : description);
        payload.put("folderId", folderId);
        payload.put("moveToRoot", folderId == null);
        data(put(settings, "/api/files/" + fileId, payload));
    }

    public JsonNode file(Settings settings, long fileId) {
        return data(get(settings, "/api/files/" + fileId));
    }

    public JsonNode fileExecutionConfig(Settings settings, long fileId) {
        return data(get(settings, "/api/development/files/" + fileId + "/execution-config"));
    }

    public void saveExecutionParams(Settings settings, long fileId, List<Map<String, String>> localParams) {
        JsonNode current = fileExecutionConfig(settings, fileId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("timezone", current.path("timezone").asText("Asia/Shanghai"));
        payload.put("dataSourceId", current.hasNonNull("dataSourceId") ? current.path("dataSourceId").asLong() : null);
        payload.put("databaseName", current.path("databaseName").asText(""));
        payload.put("bizDateParam", current.path("bizDateParam").asText("${system.biz.date}"));
        payload.put("localParams", localParams == null ? List.of() : localParams);
        data(put(settings, "/api/development/files/" + fileId + "/execution-config", payload));
    }

    public String fileType(Settings settings, long fileId) {
        return file(settings, fileId).path("fileType").asText("");
    }

    public String fileLifecycleStatus(Settings settings, long fileId) {
        JsonNode row = file(settings, fileId);
        String status = row.path("status").asText("").trim();
        return status.isBlank() ? row.path("lifecycleStatus").asText("") : status;
    }

    public JsonNode fileSchedule(Settings settings, long fileId) {
        return data(get(settings, "/api/files/" + fileId + "/schedule"));
    }

    public JsonNode onlineFile(Settings settings, long fileId) {
        if ("PUBLISHED".equalsIgnoreCase(fileLifecycleStatus(settings, fileId))) return file(settings, fileId);
        return data(post(settings, "/api/development/lifecycle/files/" + fileId + "/publish-online", Map.of()));
    }

    public JsonNode publishFile(Settings settings, long fileId) {
        if ("PUBLISHED".equalsIgnoreCase(fileLifecycleStatus(settings, fileId))) return file(settings, fileId);
        return data(post(settings, "/api/development/lifecycle/files/" + fileId + "/publish-online", Map.of()));
    }

    public void offlineFile(Settings settings, long fileId) {
        String status = fileLifecycleStatus(settings, fileId);
        if (!"PUBLISHED".equalsIgnoreCase(status) && !"ONLINE".equalsIgnoreCase(status)) return;
        data(post(settings, "/api/development/lifecycle/files/" + fileId + "/offline", Map.of()));
    }

    public void deleteFile(Settings settings, long fileId) {
        request(settings).delete().uri("/api/files/" + fileId).retrieve().toBodilessEntity();
    }

    public boolean recycledFileExists(Settings settings, long fileId) {
        try {
            long projectId = singleProjectId(settings);
            JsonNode rows = data(get(settings, "/api/files/recycle?projectId=" + projectId));
            if (!rows.isArray()) return false;
            for (JsonNode row : rows) if (row.path("id").asLong(0) == fileId) return true;
            return false;
        } catch (Exception ignored) {
            return false;
        }
    }

    public void permanentlyDeleteFile(Settings settings, long fileId) {
        request(settings).delete().uri("/api/files/" + fileId + "/permanent").retrieve().toBodilessEntity();
    }

    public long createWorkflow(Settings settings, Map<String, Object> payload) {
        return data(post(settings, "/api/workflows", payload)).path("id").asLong();
    }

    public void updateWorkflow(Settings settings, long workflowId, Map<String, Object> payload) {
        data(put(settings, "/api/workflows/" + workflowId, payload));
    }

    public JsonNode workflow(Settings settings, long workflowId) {
        return data(get(settings, "/api/workflows/" + workflowId));
    }

    public JsonNode workflowSchedule(Settings settings, long workflowId) {
        return data(get(settings, "/api/scheduler/workflows/" + workflowId + "/schedule"));
    }

    public JsonNode workflowPreflight(Settings settings, long workflowId) {
        return data(get(settings, "/api/scheduler/workflows/" + workflowId + "/preflight"));
    }

    public JsonNode publishWorkflow(Settings settings, long workflowId) {
        JsonNode workflow = workflow(settings, workflowId);
        if ("PUBLISHED".equalsIgnoreCase(workflow.path("status").asText(""))) return workflow;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("resourceType", "WORKFLOW");
        payload.put("resourceId", workflowId);
        payload.put("resourceName", workflow.path("name").asText("Workflow #" + workflowId));
        payload.put("requestedVersion", workflow.path("publishedVersion").asInt(0) + 1);
        payload.put("payload", Map.of("source", "dolphinscheduler-migrator", "action", "one-click-online"));
        JsonNode release = data(post(settings, "/api/release/requests", payload));
        String releaseStatus = release.path("status").asText("");
        if ("PENDING_APPROVAL".equalsIgnoreCase(releaseStatus)) {
            throw new IllegalStateException("DataForge 发布策略要求审批，已创建发布申请 #" + release.path("id").asLong() + "，审批后再执行一键上线");
        }
        JsonNode published = workflow(settings, workflowId);
        if (!"PUBLISHED".equalsIgnoreCase(published.path("status").asText(""))) {
            throw new IllegalStateException("Workflow 发布未完成，发布申请状态：" + releaseStatus);
        }
        return published;
    }

    public JsonNode onlineWorkflow(Settings settings, long workflowId) {
        return saveWorkflowScheduleEnabled(settings, workflowId, true);
    }

    public JsonNode offlineWorkflow(Settings settings, long workflowId) {
        return saveWorkflowScheduleEnabled(settings, workflowId, false);
    }

    public JsonNode runWorkflow(Settings settings, long workflowId) {
        return data(post(settings, "/api/workflows/" + workflowId + "/run", Map.of()));
    }

    public JsonNode workflowInstanceStatus(Settings settings, String instanceId) {
        return data(get(settings, "/api/scheduler/instances/" + instanceId));
    }

    private JsonNode saveWorkflowScheduleEnabled(Settings settings, long workflowId, boolean enabled) {
        JsonNode current = workflowSchedule(settings, workflowId);
        if (current.path("id").asLong(0) == 0) return current;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("cronExpression", current.path("cronExpression").asText(""));
        payload.put("enabled", enabled);
        payload.put("retryCount", current.path("retryCount").asInt(0));
        payload.put("retryIntervalMinutes", current.path("retryIntervalMinutes").asInt(1));
        return data(put(settings, "/api/scheduler/workflows/" + workflowId + "/schedule", payload));
    }

    public boolean fileExists(Settings settings, long fileId) {
        try {
            JsonNode row = file(settings, fileId);
            return row.path("id").asLong(0) == fileId;
        } catch (Exception ignored) {
            return false;
        }
    }

    public boolean workflowExists(Settings settings, long workflowId) {
        try {
            JsonNode row = workflow(settings, workflowId);
            return row.path("id").asLong(0) == workflowId;
        } catch (Exception ignored) {
            return false;
        }
    }

    public String workflowCode(Settings settings, long workflowId) {
        JsonNode row = workflow(settings, workflowId);
        String code = row.path("workflowCode").asText("").trim();
        if (code.isBlank()) throw new IllegalStateException("DataForge Workflow #" + workflowId + " 未返回 workflowCode");
        return code;
    }

    public String workflowStatus(Settings settings, long workflowId) {
        return workflow(settings, workflowId).path("status").asText("");
    }

    public void deleteWorkflow(Settings settings, long workflowId) {
        request(settings).delete().uri("/api/workflows/" + workflowId).retrieve().toBodilessEntity();
    }

    public void deleteWorkflowDependencies(Settings settings, String workflowCode) {
        if (workflowCode == null || workflowCode.isBlank()) return;
        JsonNode rows = data(get(settings, "/api/scheduler/dependencies"));
        if (!rows.isArray()) return;
        for (JsonNode row : rows) {
            String downstream = row.path("downstreamWorkflowCode").asText("");
            String upstream = row.path("upstreamWorkflowCode").asText("");
            if (!workflowCode.equals(downstream) && !workflowCode.equals(upstream)) continue;
            long dependencyId = row.path("id").asLong(0);
            if (dependencyId > 0) {
                request(settings).delete().uri("/api/scheduler/dependencies?id=" + dependencyId).retrieve().toBodilessEntity();
            }
        }
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

    public void saveWorkflowDependency(Settings settings, String downstreamWorkflowCode, String upstreamWorkflowCode,
                                       boolean enabled, int businessDateOffsetDays, int timeoutSeconds) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("downstreamWorkflowCode", downstreamWorkflowCode);
        payload.put("upstreamWorkflowCode", upstreamWorkflowCode);
        payload.put("enabled", enabled);
        payload.put("businessDateOffsetDays", businessDateOffsetDays);
        payload.put("timeoutSeconds", timeoutSeconds);
        data(post(settings, "/api/scheduler/dependencies", payload));
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
                .defaultHeader("X-Requested-With", "DataForge")
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
                .defaultHeader("X-Requested-With", "DataForge").build();
        Map<String, Object> payload = Map.of("username", settings.targetUsername().trim(), "password", settings.targetPassword());
        ResponseEntity<JsonNode> response = client.post().uri("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON).body(payload).retrieve().toEntity(JsonNode.class);
        data(response.getBody());
        String setCookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        if (setCookie == null || setCookie.isBlank()) throw new IllegalStateException("DataForge 登录成功但未返回 platform_session Cookie");
        String cookie = setCookie.split(";", 2)[0].trim();
        if (!cookie.startsWith("platform_session=")) throw new IllegalStateException("DataForge 未返回有效 platform_session Cookie");
        sessionCookies.put(key, cookie);
        return cookie;
    }

    private void requireCredentials(Settings settings) {
        if (settings.targetUsername() == null || settings.targetUsername().isBlank()) throw new IllegalStateException("请配置 DataForge 登录用户名");
        if (settings.targetPassword() == null || settings.targetPassword().isBlank()) throw new IllegalStateException("请配置 DataForge 登录密码");
    }

    private String sessionKey(Settings settings) { return baseUrl(settings) + "|" + settings.targetUsername().trim().toLowerCase(Locale.ROOT); }
    private String baseUrl(Settings settings) {
        String base = settings.targetBaseUrl() == null ? "" : settings.targetBaseUrl().trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        if (base.isBlank()) throw new IllegalStateException("请配置 DataForge Base URL");
        return base;
    }

    private JsonNode data(JsonNode root) {
        if (root == null) throw new IllegalStateException("DataForge 返回空响应");
        if (root.has("success") && !root.path("success").asBoolean()) throw new IllegalStateException(root.path("message").asText("DataForge 请求失败"));
        return root.has("data") ? root.path("data") : root;
    }

    private String normalizeFailureStrategy(String value) {
        if (value == null || value.isBlank()) return "END";
        String v = value.trim().toUpperCase(Locale.ROOT);
        return "CONTINUE".equals(v) || "0".equals(v) ? "CONTINUE" : "END";
    }

    private String rootMessage(Throwable ex) {
        Throwable cursor = ex;
        while (cursor.getCause() != null) cursor = cursor.getCause();
        return cursor.getMessage() == null ? cursor.getClass().getSimpleName() : cursor.getMessage();
    }

    public record TargetCheck(boolean success, String message, String detail) { }
}
