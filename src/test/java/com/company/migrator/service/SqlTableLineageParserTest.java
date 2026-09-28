package com.company.migrator.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlTableLineageParserTest {
    private final SqlTableLineageParser parser = new SqlTableLineageParser();

    @Test
    void preservesQualifiedTablesAndExtractsInsertOverwrite() {
        var lineage = parser.parse("""
                INSERT OVERWRITE TABLE ads.ads_order
                SELECT a.id
                FROM dws.dws_order a
                JOIN dim.dim_shop b ON a.shop_id=b.id
                """);

        assertEquals(2, lineage.inputTables().size());
        assertTrue(lineage.inputTables().contains("dws.dws_order"));
        assertTrue(lineage.inputTables().contains("dim.dim_shop"));
        assertEquals(java.util.Set.of("ads.ads_order"), lineage.outputTables());
    }

    @Test
    void ignoresCteAliasesCommentsAndStringLiterals() {
        var lineage = parser.parse("""
                -- FROM fake.comment_table
                WITH recent AS (
                  SELECT * FROM dwd.dwd_order WHERE note = 'FROM fake.string_table'
                )
                INSERT INTO dws.dws_order
                SELECT * FROM recent
                JOIN dim.dim_shop s ON recent.shop_id=s.id
                """);

        assertTrue(lineage.inputTables().contains("dwd.dwd_order"));
        assertTrue(lineage.inputTables().contains("dim.dim_shop"));
        assertFalse(lineage.inputTables().contains("recent"));
        assertFalse(lineage.inputTables().contains("fake.comment_table"));
        assertFalse(lineage.inputTables().contains("fake.string_table"));
        assertEquals(java.util.Set.of("dws.dws_order"), lineage.outputTables());
    }

    @Test
    void removesSelfReadTargetFromInputs() {
        var lineage = parser.parse("INSERT INTO dws.order_day SELECT * FROM dws.order_day JOIN ods.order_inc i ON 1=1");
        assertEquals(java.util.Set.of("ods.order_inc"), lineage.inputTables());
    }
}
