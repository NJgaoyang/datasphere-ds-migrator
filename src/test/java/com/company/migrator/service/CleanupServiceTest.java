package com.company.migrator.service;

import com.company.migrator.common.MigrationModels.CleanupRequest;
import com.company.migrator.common.MigrationModels.Settings;
import com.company.migrator.target.DataSphereClient;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CleanupServiceTest {
    @Test
    void deletesWorkflowBeforeDevelopmentFileAndClearsSuccessfulMappings() {
        JdbcTemplate jdbc = jdbc("cleanup_order");
        jdbc.update("INSERT INTO migration_object_map(source_type,source_code,source_version,target_type,target_id,target_name) VALUES('WORKFLOW','100',1,'WORKFLOW','11','wf_a')");
        jdbc.update("INSERT INTO migration_object_map(source_type,source_code,source_version,target_type,target_id,target_name) VALUES('TASK','200',1,'DEV_FILE','21','sql_a')");
        SettingService settings = mock(SettingService.class);
        when(settings.get()).thenReturn(targetSettings());
        DataSphereClient target = mock(DataSphereClient.class);
        when(target.workflowExists(targetSettings(), 11L)).thenReturn(true);
        when(target.fileExists(targetSettings(), 21L)).thenReturn(true);
        when(target.fileLifecycleStatus(targetSettings(), 21L)).thenReturn("OFFLINE");
        CleanupService service = new CleanupService(jdbc, settings, target);

        var preview = service.preview(new CleanupRequest(true, true));
        assertEquals(1, preview.workflowCount());
        assertEquals(1, preview.developmentTaskCount());
        assertEquals(2, preview.totalSelectedObjects());

        var result = service.cleanup(new CleanupRequest(true, true));
        assertTrue(result.success());
        assertEquals(1, result.deletedWorkflows());
        assertEquals(1, result.deletedDevelopmentTasks());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM migration_object_map", Integer.class));

        var ordered = inOrder(target);
        ordered.verify(target).workflowExists(targetSettings(), 11L);
        ordered.verify(target).deleteWorkflow(targetSettings(), 11L);
        ordered.verify(target).fileExists(targetSettings(), 21L);
        ordered.verify(target).fileLifecycleStatus(targetSettings(), 21L);
        ordered.verify(target).deleteFile(targetSettings(), 21L);
    }

    @Test
    void keepsMappingWhenDevelopmentTaskIsStillOnline() {
        JdbcTemplate jdbc = jdbc("cleanup_online");
        jdbc.update("INSERT INTO migration_object_map(source_type,source_code,source_version,target_type,target_id,target_name) VALUES('TASK','200',1,'DEV_FILE','21','sql_a')");
        SettingService settings = mock(SettingService.class);
        when(settings.get()).thenReturn(targetSettings());
        DataSphereClient target = mock(DataSphereClient.class);
        when(target.fileExists(targetSettings(), 21L)).thenReturn(true);
        when(target.fileLifecycleStatus(targetSettings(), 21L)).thenReturn("ONLINE");
        CleanupService service = new CleanupService(jdbc, settings, target);

        var result = service.cleanup(new CleanupRequest(true, false));
        assertFalse(result.success());
        assertEquals(1, result.failureCount());
        assertTrue(result.failures().getFirst().message().contains("ONLINE"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM migration_object_map", Integer.class));
    }

    private JdbcTemplate jdbc(String db) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource("jdbc:h2:mem:" + db + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE migration_object_map (source_type VARCHAR(40),source_code VARCHAR(128),source_version INT,target_type VARCHAR(40),target_id VARCHAR(128),target_name VARCHAR(255))");
        return jdbc;
    }

    private Settings targetSettings() {
        return new Settings("jdbc:mysql://source", "source", "secret", "http://datasphere", "admin", "password");
    }
}
