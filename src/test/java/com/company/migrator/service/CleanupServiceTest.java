package com.company.migrator.service;

import com.company.migrator.common.MigrationModels.CleanupRequest;
import com.company.migrator.common.MigrationModels.Settings;
import com.company.migrator.target.DataSphereClient;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CleanupServiceTest {
    @Test
    void deletesDependenciesAndWorkflowBeforePermanentlyDeletingDevelopmentFile() {
        JdbcTemplate jdbc = jdbc("cleanup_order");
        jdbc.update("INSERT INTO migration_object_map(source_type,source_code,source_version,target_type,target_id,target_name) VALUES('WORKFLOW','100',1,'WORKFLOW','11','wf_a')");
        jdbc.update("INSERT INTO migration_object_map(source_type,source_code,source_version,target_type,target_id,target_name) VALUES('TASK','200',1,'DEV_FILE','21','sql_a')");
        SettingService settings = mock(SettingService.class);
        when(settings.get()).thenReturn(targetSettings());
        DataSphereClient target = mock(DataSphereClient.class);
        when(target.workflowExists(targetSettings(), 11L)).thenReturn(true);
        when(target.workflowCode(targetSettings(), 11L)).thenReturn("WF_11");
        when(target.workflowStatus(targetSettings(), 11L)).thenReturn("PUBLISHED");
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
        ordered.verify(target).workflowCode(targetSettings(), 11L);
        ordered.verify(target).deleteWorkflowDependencies(targetSettings(), "WF_11");
        ordered.verify(target).workflowStatus(targetSettings(), 11L);
        ordered.verify(target).offlineWorkflow(targetSettings(), 11L);
        ordered.verify(target).deleteWorkflow(targetSettings(), 11L);
        ordered.verify(target).fileExists(targetSettings(), 21L);
        ordered.verify(target).fileLifecycleStatus(targetSettings(), 21L);
        ordered.verify(target).deleteFile(targetSettings(), 21L);
        ordered.verify(target).permanentlyDeleteFile(targetSettings(), 21L);
    }

    @Test
    void cleanupAutomaticallyOfflinesPublishedDevelopmentTaskBeforeDeletingIt() {
        JdbcTemplate jdbc = jdbc("cleanup_online");
        jdbc.update("INSERT INTO migration_object_map(source_type,source_code,source_version,target_type,target_id,target_name) VALUES('TASK','200',1,'DEV_FILE','21','sql_a')");
        SettingService settings = mock(SettingService.class);
        when(settings.get()).thenReturn(targetSettings());
        DataSphereClient target = mock(DataSphereClient.class);
        when(target.fileExists(targetSettings(), 21L)).thenReturn(true);
        when(target.fileLifecycleStatus(targetSettings(), 21L)).thenReturn("PUBLISHED");
        CleanupService service = new CleanupService(jdbc, settings, target);

        var result = service.cleanup(new CleanupRequest(true, false));
        assertTrue(result.success());
        assertEquals(1, result.deletedDevelopmentTasks());
        assertEquals(0, result.failureCount());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM migration_object_map", Integer.class));

        var ordered = inOrder(target);
        ordered.verify(target).fileExists(targetSettings(), 21L);
        ordered.verify(target).fileLifecycleStatus(targetSettings(), 21L);
        ordered.verify(target).offlineFile(targetSettings(), 21L);
        ordered.verify(target).deleteFile(targetSettings(), 21L);
        ordered.verify(target).permanentlyDeleteFile(targetSettings(), 21L);
    }

    @Test
    void cleanupPermanentlyDeletesAlreadyRecycledMappedDevelopmentTask() {
        JdbcTemplate jdbc = jdbc("cleanup_recycled");
        jdbc.update("INSERT INTO migration_object_map(source_type,source_code,source_version,target_type,target_id,target_name) VALUES('TASK','200',1,'DEV_FILE','21','sql_recycled')");
        SettingService settings = mock(SettingService.class);
        when(settings.get()).thenReturn(targetSettings());
        DataSphereClient target = mock(DataSphereClient.class);
        when(target.fileExists(targetSettings(), 21L)).thenReturn(false);
        when(target.recycledFileExists(targetSettings(), 21L)).thenReturn(true);
        CleanupService service = new CleanupService(jdbc, settings, target);

        var result = service.cleanup(new CleanupRequest(true, false));
        assertTrue(result.success());
        assertEquals(0, result.deletedDevelopmentTasks());
        assertEquals(1, result.deletedRecycledDevelopmentTasks());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM migration_object_map", Integer.class));

        var ordered = inOrder(target);
        ordered.verify(target).fileExists(targetSettings(), 21L);
        ordered.verify(target).recycledFileExists(targetSettings(), 21L);
        ordered.verify(target).permanentlyDeleteFile(targetSettings(), 21L);
    }

    @Test
    void oneClickOfflineOnlyOfflinesPublishedMappedDevelopmentTasksAndKeepsMappings() {
        JdbcTemplate jdbc = jdbc("offline_development");
        jdbc.update("INSERT INTO migration_object_map(source_type,source_code,source_version,target_type,target_id,target_name) VALUES('TASK','200',1,'DEV_FILE','21','sql_online')");
        jdbc.update("INSERT INTO migration_object_map(source_type,source_code,source_version,target_type,target_id,target_name) VALUES('TASK','201',1,'DEV_FILE','22','sql_offline')");
        SettingService settings = mock(SettingService.class);
        when(settings.get()).thenReturn(targetSettings());
        DataSphereClient target = mock(DataSphereClient.class);
        when(target.fileExists(targetSettings(), 21L)).thenReturn(true);
        when(target.fileExists(targetSettings(), 22L)).thenReturn(true);
        when(target.fileLifecycleStatus(targetSettings(), 21L)).thenReturn("PUBLISHED");
        when(target.fileLifecycleStatus(targetSettings(), 22L)).thenReturn("OFFLINE");
        CleanupService service = new CleanupService(jdbc, settings, target);

        var result = service.offlineDevelopment();
        assertTrue(result.success());
        assertEquals(1, result.offlinedDevelopmentTasks());
        assertEquals(1, result.skippedDevelopmentTasks());
        assertEquals(0, result.failureCount());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM migration_object_map", Integer.class));

        var ordered = inOrder(target);
        ordered.verify(target).fileExists(targetSettings(), 21L);
        ordered.verify(target).fileLifecycleStatus(targetSettings(), 21L);
        ordered.verify(target).offlineFile(targetSettings(), 21L);
        ordered.verify(target).fileExists(targetSettings(), 22L);
        ordered.verify(target).fileLifecycleStatus(targetSettings(), 22L);
    }

    private JdbcTemplate jdbc(String db) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource("jdbc:h2:mem:" + db + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE migration_object_map (source_type VARCHAR(40),source_code VARCHAR(128),source_version INT,target_type VARCHAR(40),target_id VARCHAR(128),target_name VARCHAR(255))");
        return jdbc;
    }

    private Settings targetSettings() {
        return new Settings("jdbc:mysql://source", "source", "secret", "http://dataforge", "admin", "password");
    }
}
