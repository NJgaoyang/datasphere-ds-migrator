package com.company.migrator.service;

import com.company.migrator.common.MigrationModels.Settings;
import com.company.migrator.common.SqlDependencyModels.*;
import com.company.migrator.source.DolphinScheduler319Reader;
import com.company.migrator.source.DolphinScheduler319Reader.Snapshot;
import com.company.migrator.source.DolphinScheduler319Reader.TaskRow;
import com.company.migrator.source.DolphinScheduler319Reader.WorkflowRow;
import com.company.migrator.target.DataSphereClient;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class SqlDependencyService {
    private final JdbcTemplate jdbc;
    private final SettingService settings;
    private final DolphinScheduler319Reader source;
    private final DataSphereClient target;
    private final SqlTableLineageParser parser;

    public SqlDependencyService(JdbcTemplate jdbc, SettingService settings, DolphinScheduler319Reader source,
                                DataSphereClient target, SqlTableLineageParser parser) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.source = source;
        this.target = target;
        this.parser = parser;
    }

    public SqlDependencyScanResult scan() {
        try {
            Settings s = settings.get();
            Snapshot snapshot = source.read(s);
            Map<Long, WorkflowRow> workflows = snapshot.workflows().stream()
                    .collect(Collectors.toMap(WorkflowRow::code, Function.identity(), (a, b) -> a, LinkedHashMap::new));
            Map<Long, JsonNode> fileCache = new HashMap<>();
            Map<Long, JsonNode> workflowCache = new HashMap<>();
            List<TaskContext> contexts = new ArrayList<>();

            for (TaskRow task : snapshot.tasks()) {
                if (!"SQL".equalsIgnoreCase(task.taskType())) continue;
                Long fileId = mappedId("TASK", String.valueOf(task.code()), task.version(), "DEV_FILE");
                Long workflowId = mappedId("WORKFLOW", String.valueOf(task.workflowCode()), task.workflowVersion(), "WORKFLOW");
                WorkflowRow sourceWorkflow = workflows.get(task.workflowCode());
                if (fileId == null || workflowId == null || sourceWorkflow == null) continue;
                JsonNode file = fileCache.computeIfAbsent(fileId, id -> target.file(s, id));
                if (!"SQL".equalsIgnoreCase(file.path("fileType").asText(""))) continue;
                JsonNode targetWorkflow = workflowCache.computeIfAbsent(workflowId, id -> target.workflow(s, id));
                SqlTableLineageParser.Lineage lineage = parser.parse(file.path("content").asText(""));
                contexts.add(new TaskContext(task, sourceWorkflow.name(), fileId, workflowId, targetWorkflow, lineage));
            }

            contexts = contexts.stream().collect(Collectors.toMap(
                    c -> c.task().workflowCode() + ":" + c.task().code() + ":" + c.task().version(),
                    Function.identity(), (a, b) -> a, LinkedHashMap::new)).values().stream().toList();

            Map<String, List<TaskContext>> exactProducers = new LinkedHashMap<>();
            Map<String, List<TaskContext>> leafProducers = new LinkedHashMap<>();
            for (TaskContext ctx : contexts) {
                for (String table : ctx.lineage().outputTables()) {
                    exactProducers.computeIfAbsent(table, ignored -> new ArrayList<>()).add(ctx);
                    leafProducers.computeIfAbsent(parser.leaf(table), ignored -> new ArrayList<>()).add(ctx);
                }
            }

            List<SqlDependencyRelation> relations = new ArrayList<>();
            LinkedHashSet<String> externalTables = new LinkedHashSet<>();
            for (TaskContext consumer : contexts) {
                for (String input : consumer.lineage().inputTables()) {
                    List<TaskContext> candidates = producerCandidates(input, exactProducers, leafProducers).stream()
                            .filter(p -> p.fileId() != consumer.fileId())
                            .collect(Collectors.toMap(p -> p.fileId() + ":" + p.workflowId(), Function.identity(), (a, b) -> a,
                                    LinkedHashMap::new)).values().stream().toList();
                    if (candidates.isEmpty()) {
                        externalTables.add(input);
                        continue;
                    }
                    if (candidates.size() > 1) {
                        TaskContext first = candidates.getFirst();
                        String names = candidates.stream().map(c -> c.task().name() + "@" + c.workflowName()).distinct().collect(Collectors.joining("、"));
                        relations.add(relation(input, first, consumer, "CONFLICT", "同一输入表匹配到多个生产任务：" + names));
                        continue;
                    }
                    TaskContext producer = candidates.getFirst();
                    String scope = producer.workflowId() == consumer.workflowId() ? "SAME_WORKFLOW" : "CROSS_WORKFLOW";
                    String status = "READY";
                    String message = "可应用";
                    if ("SAME_WORKFLOW".equals(scope)) {
                        if ("PUBLISHED".equalsIgnoreCase(consumer.workflowJson().path("status").asText(""))) {
                            status = "BLOCKED";
                            message = "目标工作流已发布；请先转为可编辑状态后重新分析，避免静默修改生产版本";
                        } else if (edgeExists(consumer.workflowJson(), producer.fileId(), consumer.fileId())) {
                            status = "EXISTING";
                            message = "前置关系已存在";
                        }
                    }
                    relations.add(relation(input, producer, consumer, status, message));
                }
            }

            relations = markCrossWorkflowCycles(relations);
            List<SqlTaskLineage> taskRows = contexts.stream().map(c -> new SqlTaskLineage(
                    c.task().code(), c.task().version(), c.task().name(), c.task().workflowCode(), c.workflowName(),
                    c.fileId(), c.workflowId(), c.lineage().inputTables(), c.lineage().outputTables())).toList();
            int ready = (int) relations.stream().filter(r -> "READY".equals(r.status())).count();
            int existing = (int) relations.stream().filter(r -> "EXISTING".equals(r.status())).count();
            int conflict = (int) relations.stream().filter(r -> "CONFLICT".equals(r.status()) || "BLOCKED".equals(r.status())).count();
            int same = (int) relations.stream().filter(r -> "SAME_WORKFLOW".equals(r.scope())).count();
            int cross = (int) relations.stream().filter(r -> "CROSS_WORKFLOW".equals(r.scope())).count();
            return new SqlDependencyScanResult(taskRows.size(), relations.size(), ready, existing, conflict, same, cross,
                    taskRows, List.copyOf(relations), List.copyOf(externalTables));
        } catch (Exception ex) {
            throw new IllegalStateException("SQL 依赖分析失败：" + rootMessage(ex), ex);
        }
    }

    public SqlDependencyApplyResult apply(SqlDependencyApplyRequest request) {
        SqlDependencyApplyRequest selected = request == null ? new SqlDependencyApplyRequest(true, true) : request;
        if (!selected.sameWorkflowValue() && !selected.crossWorkflowValue()) {
            throw new IllegalArgumentException("请至少选择同工作流依赖或跨工作流依赖");
        }
        Settings s = settings.get();
        SqlDependencyScanResult scan = scan();
        List<SqlDependencyApplyFailure> failures = new ArrayList<>();
        int appliedSame = 0;
        int appliedCross = 0;
        int skipped = 0;

        if (selected.sameWorkflowValue()) {
            Map<Long, List<SqlDependencyRelation>> byWorkflow = scan.relations().stream()
                    .filter(r -> "READY".equals(r.status()) && "SAME_WORKFLOW".equals(r.scope()))
                    .collect(Collectors.groupingBy(SqlDependencyRelation::consumerTargetWorkflowId, LinkedHashMap::new, Collectors.toList()));
            for (Map.Entry<Long, List<SqlDependencyRelation>> entry : byWorkflow.entrySet()) {
                try {
                    JsonNode workflow = target.workflow(s, entry.getKey());
                    if ("PUBLISHED".equalsIgnoreCase(workflow.path("status").asText(""))) {
                        throw new IllegalStateException("工作流已发布，拒绝静默修改生产版本");
                    }
                    Map<String, Object> payload = workflowPayloadWithEdges(workflow, entry.getValue());
                    target.updateWorkflow(s, entry.getKey(), payload);
                    target.validateWorkflow(s, entry.getKey());
                    appliedSame += entry.getValue().size();
                } catch (Exception ex) {
                    String name = entry.getValue().isEmpty() ? "Workflow #" + entry.getKey() : entry.getValue().getFirst().consumerWorkflowName();
                    failures.add(new SqlDependencyApplyFailure("SAME_WORKFLOW", name, rootMessage(ex)));
                }
            }
        } else {
            skipped += (int) scan.relations().stream().filter(r -> "READY".equals(r.status()) && "SAME_WORKFLOW".equals(r.scope())).count();
        }

        if (selected.crossWorkflowValue()) {
            Map<String, SqlDependencyRelation> uniquePairs = scan.relations().stream()
                    .filter(r -> "READY".equals(r.status()) && "CROSS_WORKFLOW".equals(r.scope()))
                    .collect(Collectors.toMap(r -> r.producerTargetWorkflowId() + "->" + r.consumerTargetWorkflowId(),
                            Function.identity(), (a, b) -> a, LinkedHashMap::new));
            for (SqlDependencyRelation relation : uniquePairs.values()) {
                try {
                    String upstreamCode = target.workflowCode(s, relation.producerTargetWorkflowId());
                    String downstreamCode = target.workflowCode(s, relation.consumerTargetWorkflowId());
                    target.saveWorkflowDependency(s, downstreamCode, upstreamCode, false, 0, 3600);
                    appliedCross++;
                } catch (Exception ex) {
                    failures.add(new SqlDependencyApplyFailure("CROSS_WORKFLOW",
                            relation.consumerWorkflowName() + " <- " + relation.producerWorkflowName(), rootMessage(ex)));
                }
            }
        } else {
            skipped += (int) scan.relations().stream().filter(r -> "READY".equals(r.status()) && "CROSS_WORKFLOW".equals(r.scope())).count();
        }

        skipped += (int) scan.relations().stream().filter(r -> !"READY".equals(r.status())).count();
        String message = failures.isEmpty()
                ? "SQL 前置依赖应用完成：同工作流 " + appliedSame + " 条，跨工作流 " + appliedCross + " 条"
                : "SQL 前置依赖部分应用完成，有 " + failures.size() + " 个对象失败";
        return new SqlDependencyApplyResult(failures.isEmpty(), appliedSame, appliedCross, skipped,
                failures.size(), List.copyOf(failures), message);
    }

    private List<TaskContext> producerCandidates(String input, Map<String, List<TaskContext>> exact,
                                                 Map<String, List<TaskContext>> byLeaf) {
        List<TaskContext> exactMatches = exact.getOrDefault(input, List.of());
        if (!exactMatches.isEmpty()) return exactMatches;
        if (input.contains(".")) return List.of();
        return byLeaf.getOrDefault(parser.leaf(input), List.of());
    }

    private SqlDependencyRelation relation(String table, TaskContext producer, TaskContext consumer,
                                             String status, String message) {
        String scope = producer.workflowId() == consumer.workflowId() ? "SAME_WORKFLOW" : "CROSS_WORKFLOW";
        String key = producer.fileId() + "->" + consumer.fileId() + "@" + table;
        return new SqlDependencyRelation(key, table, scope, status, message,
                producer.task().code(), producer.task().name(), producer.fileId(), producer.task().workflowCode(),
                producer.workflowName(), producer.workflowId(), consumer.task().code(), consumer.task().name(),
                consumer.fileId(), consumer.task().workflowCode(), consumer.workflowName(), consumer.workflowId());
    }

    private boolean edgeExists(JsonNode workflow, long producerFileId, long consumerFileId) {
        Map<Long, Long> nodeByFile = new HashMap<>();
        for (JsonNode node : workflow.path("nodes")) {
            if (!node.path("devFileId").isMissingNode() && !node.path("devFileId").isNull()) {
                nodeByFile.put(node.path("devFileId").asLong(), node.path("id").asLong());
            }
        }
        Long sourceNode = nodeByFile.get(producerFileId);
        Long targetNode = nodeByFile.get(consumerFileId);
        if (sourceNode == null || targetNode == null) return false;
        for (JsonNode edge : workflow.path("edges")) {
            if (edge.path("sourceNodeId").asLong(Long.MIN_VALUE) == sourceNode
                    && edge.path("targetNodeId").asLong(Long.MIN_VALUE) == targetNode) return true;
        }
        return false;
    }

    private Map<String, Object> workflowPayloadWithEdges(JsonNode workflow, List<SqlDependencyRelation> relations) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        Map<Long, String> nodeCodeByFile = new HashMap<>();
        for (JsonNode node : workflow.path("nodes")) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", node.path("name").asText(""));
            row.put("nodeType", node.path("nodeType").asText("SQL"));
            if (!node.path("devFileId").isMissingNode() && !node.path("devFileId").isNull()) {
                long fileId = node.path("devFileId").asLong();
                row.put("devFileId", fileId);
                nodeCodeByFile.put(fileId, node.path("nodeCode").asText(""));
            } else row.put("devFileId", null);
            row.put("configJson", node.path("configJson").asText("{}"));
            row.put("x", node.path("x").asInt(0));
            row.put("y", node.path("y").asInt(0));
            row.put("nodeCode", node.path("nodeCode").asText(""));
            nodes.add(row);
        }

        List<Map<String, Object>> edges = new ArrayList<>();
        LinkedHashSet<String> edgeKeys = new LinkedHashSet<>();
        for (JsonNode edge : workflow.path("edges")) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("sourceNodeId", nullableLong(edge.get("sourceNodeId")));
            row.put("targetNodeId", nullableLong(edge.get("targetNodeId")));
            row.put("sourceNodeCode", textOrNull(edge.get("sourceNodeCode")));
            row.put("targetNodeCode", textOrNull(edge.get("targetNodeCode")));
            row.put("branchType", edge.path("branchType").asText("NORMAL"));
            edges.add(row);
            edgeKeys.add(edgeIdentity(row));
        }
        for (SqlDependencyRelation relation : relations) {
            String sourceCode = nodeCodeByFile.get(relation.producerFileId());
            String targetCode = nodeCodeByFile.get(relation.consumerFileId());
            if (sourceCode == null || sourceCode.isBlank() || targetCode == null || targetCode.isBlank()) {
                throw new IllegalStateException("工作流节点缺少 nodeCode，无法安全写入前置依赖：" + relation.producerTaskName() + " -> " + relation.consumerTaskName());
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("sourceNodeId", null);
            row.put("targetNodeId", null);
            row.put("sourceNodeCode", sourceCode);
            row.put("targetNodeCode", targetCode);
            row.put("branchType", "NORMAL");
            if (edgeKeys.add(edgeIdentity(row))) edges.add(row);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", workflow.path("name").asText(""));
        payload.put("description", workflow.path("description").asText(""));
        payload.put("nodes", nodes);
        payload.put("edges", edges);
        return payload;
    }

    private String edgeIdentity(Map<String, Object> edge) {
        Object sourceCode = edge.get("sourceNodeCode");
        Object targetCode = edge.get("targetNodeCode");
        if (sourceCode != null && !String.valueOf(sourceCode).isBlank() && targetCode != null && !String.valueOf(targetCode).isBlank()) {
            return sourceCode + "->" + targetCode;
        }
        return edge.get("sourceNodeId") + "->" + edge.get("targetNodeId");
    }

    private List<SqlDependencyRelation> markCrossWorkflowCycles(List<SqlDependencyRelation> sourceRelations) {
        Map<Long, Set<Long>> graph = new LinkedHashMap<>();
        for (SqlDependencyRelation relation : sourceRelations) {
            if ("READY".equals(relation.status()) && "CROSS_WORKFLOW".equals(relation.scope())) {
                graph.computeIfAbsent(relation.producerTargetWorkflowId(), ignored -> new LinkedHashSet<>())
                        .add(relation.consumerTargetWorkflowId());
            }
        }
        List<SqlDependencyRelation> result = new ArrayList<>();
        for (SqlDependencyRelation relation : sourceRelations) {
            if ("READY".equals(relation.status()) && "CROSS_WORKFLOW".equals(relation.scope())
                    && reachable(graph, relation.consumerTargetWorkflowId(), relation.producerTargetWorkflowId(), new HashSet<>())) {
                result.add(withStatus(relation, "CONFLICT", "SQL 血缘推导出的跨工作流依赖形成环，已禁止自动应用"));
            } else result.add(relation);
        }
        return result;
    }

    private boolean reachable(Map<Long, Set<Long>> graph, long from, long targetId, Set<Long> visited) {
        if (from == targetId) return true;
        if (!visited.add(from)) return false;
        for (Long next : graph.getOrDefault(from, Set.of())) if (reachable(graph, next, targetId, visited)) return true;
        return false;
    }

    private SqlDependencyRelation withStatus(SqlDependencyRelation r, String status, String message) {
        return new SqlDependencyRelation(r.key(), r.tableName(), r.scope(), status, message,
                r.producerTaskCode(), r.producerTaskName(), r.producerFileId(), r.producerWorkflowCode(),
                r.producerWorkflowName(), r.producerTargetWorkflowId(), r.consumerTaskCode(), r.consumerTaskName(),
                r.consumerFileId(), r.consumerWorkflowCode(), r.consumerWorkflowName(), r.consumerTargetWorkflowId());
    }

    private Long mappedId(String sourceType, String sourceCode, int sourceVersion, String targetType) {
        List<Long> rows = jdbc.query("SELECT target_id FROM migration_object_map WHERE source_type=? AND source_code=? AND source_version=? AND target_type=?",
                (rs, n) -> Long.parseLong(rs.getString(1)), sourceType, sourceCode, sourceVersion, targetType);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private Long nullableLong(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode() ? null : node.asLong();
    }

    private String textOrNull(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode() ? null : node.asText();
    }

    private String rootMessage(Throwable ex) {
        Throwable cursor = ex;
        while (cursor.getCause() != null) cursor = cursor.getCause();
        return cursor.getMessage() == null ? cursor.getClass().getSimpleName() : cursor.getMessage();
    }

    private record TaskContext(TaskRow task, String workflowName, long fileId, long workflowId,
                               JsonNode workflowJson, SqlTableLineageParser.Lineage lineage) { }
}
