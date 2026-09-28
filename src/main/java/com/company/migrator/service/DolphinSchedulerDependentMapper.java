package com.company.migrator.service;

import com.company.migrator.source.DolphinScheduler319Reader.EdgeRow;
import com.company.migrator.source.DolphinScheduler319Reader.TaskRow;
import com.company.migrator.source.DolphinScheduler319Reader.WorkflowRow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Converts the safe subset of DolphinScheduler DEPENDENT gates to Native Scheduler workflow dependencies.
 *
 * <p>A DEPENDENT task has no executable body. It may only be removed from the DAG when it is the single
 * root gate of the workflow; otherwise converting it to a workflow-level dependency would change execution
 * semantics. Native Scheduler currently models an AND set of whole-workflow, business-date dependencies,
 * so task-level dependencies, OR groups and non-daily date expressions are intentionally rejected.</p>
 */
final class DolphinSchedulerDependentMapper {
    private static final String DEPENDENT = "DEPENDENT";
    private final ObjectMapper mapper;

    DolphinSchedulerDependentMapper(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    Plan analyze(WorkflowRow workflow, List<TaskRow> allTasks, List<EdgeRow> allEdges) {
        List<TaskRow> tasks = allTasks.stream()
                .filter(t -> t.workflowCode() == workflow.code() && t.workflowVersion() == workflow.version())
                .toList();
        if (tasks.isEmpty()) return Plan.unsupported("工作流没有可解析的任务");

        List<TaskRow> dependentTasks = tasks.stream().filter(this::isDependent).toList();
        if (dependentTasks.isEmpty()) return Plan.none();
        if (dependentTasks.size() != 1) {
            return Plan.unsupported("同一工作流包含多个 DEPENDENT 节点，无法无损转换为单个工作流级依赖门禁");
        }
        TaskRow gate = dependentTasks.getFirst();

        Map<Long, Integer> indegree = new LinkedHashMap<>();
        tasks.forEach(t -> indegree.put(t.code(), 0));
        allEdges.stream()
                .filter(e -> e.workflowCode() == workflow.code() && e.workflowVersion() == workflow.version())
                .filter(e -> e.preTaskCode() != 0 && indegree.containsKey(e.postTaskCode()))
                .forEach(e -> indegree.compute(e.postTaskCode(), (k, v) -> v == null ? 1 : v + 1));
        if (indegree.getOrDefault(gate.code(), 0) != 0) {
            return Plan.unsupported("DEPENDENT 不是工作流根节点；提升为工作流级依赖会改变原 DAG 执行顺序");
        }
        List<Long> otherRoots = tasks.stream()
                .filter(t -> t.code() != gate.code())
                .filter(t -> indegree.getOrDefault(t.code(), 0) == 0)
                .map(TaskRow::code)
                .toList();
        if (!otherRoots.isEmpty()) {
            return Plan.unsupported("DEPENDENT 不是唯一根门禁，工作流还存在其他独立根节点：" + otherRoots);
        }

        try {
            JsonNode root = mapper.readTree(gate.taskParams() == null || gate.taskParams().isBlank() ? "{}" : gate.taskParams());
            JsonNode dependence = root.path("dependence");
            if (!dependence.isObject() && root.path("dependTaskList").isArray()) dependence = root;
            if (!dependence.isObject()) return Plan.unsupported("DEPENDENT 缺少 dependence 配置");
            if (!"AND".equalsIgnoreCase(dependence.path("relation").asText("AND"))) {
                return Plan.unsupported("DEPENDENT 顶层 OR 暂不能无损转换；Native Scheduler 当前工作流依赖为 AND 语义");
            }
            JsonNode groups = dependence.path("dependTaskList");
            if (!groups.isArray() || groups.isEmpty()) return Plan.unsupported("DEPENDENT 没有依赖项");

            LinkedHashMap<Long, Integer> offsets = new LinkedHashMap<>();
            for (JsonNode group : groups) {
                if (!"AND".equalsIgnoreCase(group.path("relation").asText("AND"))) {
                    return Plan.unsupported("DEPENDENT 包含 OR 分组，暂不能无损转换为 Native Scheduler AND 依赖");
                }
                JsonNode items = group.path("dependItemList");
                if (!items.isArray() || items.isEmpty()) return Plan.unsupported("DEPENDENT 分组没有依赖项");
                for (JsonNode item : items) {
                    long definitionCode = item.path("definitionCode").asLong(0);
                    long depTaskCode = item.path("depTaskCode").asLong(0);
                    if (definitionCode <= 0) return Plan.unsupported("DEPENDENT 缺少上游 workflow definitionCode");
                    if (depTaskCode != 0) {
                        return Plan.unsupported("DEPENDENT 依赖具体上游 Task（" + depTaskCode + "），Native Scheduler 工作流级依赖不能等价表达");
                    }
                    String cycle = item.path("cycle").asText("day").trim().toLowerCase(Locale.ROOT);
                    if (!cycle.isBlank() && !"day".equals(cycle)) {
                        return Plan.unsupported("DEPENDENT 周期 " + cycle + " 暂不能转换为按天业务日期依赖");
                    }
                    String dateValue = item.path("dateValue").asText("today").trim();
                    int offset;
                    if (dateValue.isBlank() || "today".equalsIgnoreCase(dateValue)) offset = 0;
                    else if ("last1Days".equalsIgnoreCase(dateValue)) offset = -1;
                    else return Plan.unsupported("DEPENDENT 日期表达式 " + dateValue + " 暂不能无损转换");
                    Integer existing = offsets.putIfAbsent(definitionCode, offset);
                    if (existing != null && existing != offset) {
                        return Plan.unsupported("同一上游工作流配置了多个业务日期偏移，目标依赖模型无法无损表达");
                    }
                }
            }
            List<Rule> rules = offsets.entrySet().stream().map(e -> new Rule(e.getKey(), e.getValue())).toList();
            return rules.isEmpty() ? Plan.unsupported("DEPENDENT 没有有效依赖规则")
                    : new Plan(true, "可迁为 Native Scheduler 工作流依赖", gate.code(), rules);
        } catch (Exception ex) {
            return Plan.unsupported("DEPENDENT 参数解析失败：" + ex.getMessage());
        }
    }

    boolean isDependent(TaskRow task) {
        return task != null && DEPENDENT.equalsIgnoreCase(task.taskType() == null ? "" : task.taskType().trim());
    }

    record Rule(long upstreamWorkflowCode, int businessDateOffsetDays) { }

    record Plan(boolean supported, String message, Long dependentTaskCode, List<Rule> rules) {
        static Plan none() { return new Plan(true, "无跨工作流 DEPENDENT", null, List.of()); }
        static Plan unsupported(String message) { return new Plan(false, message, null, List.of()); }
        boolean hasDependency() { return dependentTaskCode != null; }
    }
}
