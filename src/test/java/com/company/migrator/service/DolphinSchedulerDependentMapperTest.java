package com.company.migrator.service;

import com.company.migrator.source.DolphinScheduler319Reader.EdgeRow;
import com.company.migrator.source.DolphinScheduler319Reader.TaskRow;
import com.company.migrator.source.DolphinScheduler319Reader.WorkflowRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DolphinSchedulerDependentMapperTest {
    private final DolphinSchedulerDependentMapper mapper = new DolphinSchedulerDependentMapper(new ObjectMapper());

    @Test
    void mapsSingleRootWholeWorkflowAndDependency() {
        WorkflowRow workflow = workflow();
        TaskRow dependent = dependent(10, "AND", "AND", 7001, 0, "day", "today");
        TaskRow sql = task(20, "SQL");
        var plan = mapper.analyze(workflow, List.of(dependent, sql), List.of(edge(10, 20)));

        assertTrue(plan.supported());
        assertTrue(plan.hasDependency());
        assertEquals(10L, plan.dependentTaskCode());
        assertEquals(List.of(new DolphinSchedulerDependentMapper.Rule(7001, 0)), plan.rules());
    }

    @Test
    void mapsPreviousBusinessDayToMinusOne() {
        var plan = mapper.analyze(workflow(),
                List.of(dependent(10, "AND", "AND", 7001, 0, "day", "last1Days"), task(20, "SQL")),
                List.of(edge(10, 20)));
        assertTrue(plan.supported());
        assertEquals(-1, plan.rules().getFirst().businessDateOffsetDays());
    }

    @Test
    void rejectsSpecificUpstreamTaskDependency() {
        var plan = mapper.analyze(workflow(),
                List.of(dependent(10, "AND", "AND", 7001, 9001, "day", "today"), task(20, "SQL")),
                List.of(edge(10, 20)));
        assertFalse(plan.supported());
        assertTrue(plan.message().contains("具体上游 Task"));
    }

    @Test
    void rejectsOrSemantics() {
        var plan = mapper.analyze(workflow(),
                List.of(dependent(10, "AND", "OR", 7001, 0, "day", "today"), task(20, "SQL")),
                List.of(edge(10, 20)));
        assertFalse(plan.supported());
        assertTrue(plan.message().contains("OR"));
    }

    @Test
    void rejectsDependentThatIsNotTheOnlyRootGate() {
        var plan = mapper.analyze(workflow(),
                List.of(dependent(10, "AND", "AND", 7001, 0, "day", "today"), task(20, "SQL"), task(30, "SQL")),
                List.of(edge(10, 20)));
        assertFalse(plan.supported());
        assertTrue(plan.message().contains("其他独立根节点"));
    }

    private WorkflowRow workflow() {
        return new WorkflowRow(900, 1, "downstream", null, 800, 1, null, null);
    }

    private TaskRow task(long code, String type) {
        return new TaskRow(900, 1, code, 1, "task-" + code, type, "{}", null, 800, 0, 0, "default", null);
    }

    private TaskRow dependent(long code, String outerRelation, String groupRelation, long definitionCode,
                              long depTaskCode, String cycle, String dateValue) {
        String json = "{\"dependence\":{\"relation\":\"" + outerRelation + "\",\"dependTaskList\":[{\"relation\":\"" + groupRelation +
                "\",\"dependItemList\":[{\"projectCode\":1,\"definitionCode\":" + definitionCode +
                ",\"depTaskCode\":" + depTaskCode + ",\"cycle\":\"" + cycle + "\",\"dateValue\":\"" + dateValue + "\"}]}]}}";
        return new TaskRow(900, 1, code, 1, "dependent-" + code, "DEPENDENT", json, null, 800, 0, 0, "default", null);
    }

    private EdgeRow edge(long pre, long post) {
        return new EdgeRow(900, 1, pre, 1, post, 1, "NONE", "{}");
    }
}
