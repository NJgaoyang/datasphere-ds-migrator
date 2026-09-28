package com.company.migrator.service;

import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class SqlTableLineageParser {
    private static final String IDENTIFIER = "[`\"A-Za-z0-9_$.-]+";
    private static final Pattern SOURCE_TABLE = Pattern.compile("(?i)\\b(?:FROM|JOIN)\\s+(" + IDENTIFIER + ")");
    private static final Pattern TARGET_TABLE = Pattern.compile(
            "(?i)\\b(?:INSERT\\s+(?:OVERWRITE\\s+(?:TABLE\\s+)?)?INTO|INSERT\\s+OVERWRITE\\s+(?:TABLE\\s+)?|REPLACE\\s+INTO|MERGE\\s+INTO|CREATE\\s+TABLE(?:\\s+IF\\s+NOT\\s+EXISTS)?)\\s+(" + IDENTIFIER + ")");
    private static final Pattern CTE = Pattern.compile("(?i)(?:\\bWITH|,)\\s*([`\"A-Za-z_][`\"A-Za-z0-9_$]*)\\s+AS\\s*\\(");

    public Lineage parse(String sql) {
        String cleaned = stripCommentsAndStrings(sql);
        Set<String> ctes = collect(cleaned, CTE, false);
        Set<String> inputs = collect(cleaned, SOURCE_TABLE, true);
        Set<String> outputs = collect(cleaned, TARGET_TABLE, true);
        inputs.removeIf(table -> ctes.contains(leaf(table)));
        inputs.removeAll(outputs);
        return new Lineage(Set.copyOf(inputs), Set.copyOf(outputs), Set.copyOf(ctes));
    }

    private Set<String> collect(String sql, Pattern pattern, boolean tableName) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        Matcher matcher = pattern.matcher(sql == null ? "" : sql);
        while (matcher.find()) {
            String value = tableName ? normalizeTable(matcher.group(1)) : normalizeIdentifier(matcher.group(1));
            if (!value.isBlank() && !value.contains("${")) result.add(value);
        }
        return result;
    }

    String normalizeTable(String raw) {
        if (raw == null) return "";
        String value = raw.replace("`", "").replace("\"", "").trim().toLowerCase(Locale.ROOT);
        while (value.endsWith(";") || value.endsWith(",")) value = value.substring(0, value.length() - 1);
        return value;
    }

    String leaf(String table) {
        String normalized = normalizeTable(table);
        int dot = normalized.lastIndexOf('.');
        return dot >= 0 ? normalized.substring(dot + 1) : normalized;
    }

    private String normalizeIdentifier(String raw) {
        return raw == null ? "" : raw.replace("`", "").replace("\"", "").trim().toLowerCase(Locale.ROOT);
    }

    private String stripCommentsAndStrings(String sql) {
        if (sql == null || sql.isBlank()) return "";
        String noBlock = sql.replaceAll("(?s)/\\*.*?\\*/", " ");
        String noLine = noBlock.replaceAll("(?m)--.*$", " ");
        return noLine.replaceAll("(?s)'(?:''|\\\\.|[^'])*'", "''");
    }

    public record Lineage(Set<String> inputTables, Set<String> outputTables, Set<String> cteNames) { }
}
