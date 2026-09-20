package com.company.migrator.service;

import com.company.migrator.common.MigrationModels.Settings;
import com.company.migrator.common.MigrationModels.SettingsUpdate;
import com.company.migrator.common.MigrationModels.SettingsView;
import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SettingService {
    private final JdbcTemplate jdbc;

    public SettingService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @PostConstruct
    public void ensureTargetCredentialColumns() {
        addColumnIfMissing("target_username", "VARCHAR(255)");
        addColumnIfMissing("target_password", "VARCHAR(1000)");
        jdbc.update("UPDATE migration_setting SET target_username=COALESCE(NULLIF(target_username,''),NULLIF(target_operator,''),'admin') WHERE id=1");
    }

    private void addColumnIfMissing(String name, String definition) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='migration_setting' AND COLUMN_NAME=?", Integer.class, name);
        if (count != null && count == 0) jdbc.execute("ALTER TABLE migration_setting ADD COLUMN " + name + " " + definition);
    }

    public Settings get() {
        return jdbc.queryForObject("SELECT source_jdbc_url,source_username,source_password,target_base_url,target_username,target_password FROM migration_setting WHERE id=1",
                (rs, n) -> new Settings(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6)));
    }

    public SettingsView view() {
        Settings s = get();
        return new SettingsView(s.sourceJdbcUrl(), s.sourceUsername(), has(s.sourcePassword()),
                s.targetBaseUrl(), s.targetUsername(), has(s.targetPassword()));
    }

    @Transactional
    public SettingsView update(SettingsUpdate u) {
        Settings old = get();
        String sourcePassword = has(u.sourcePassword()) ? u.sourcePassword() : old.sourcePassword();
        String targetPassword = has(u.targetPassword()) ? u.targetPassword() : old.targetPassword();
        jdbc.update("UPDATE migration_setting SET source_jdbc_url=?,source_username=?,source_password=?,target_base_url=?,target_username=?,target_password=?,updated_at=CURRENT_TIMESTAMP WHERE id=1",
                trimOr(u.sourceJdbcUrl(), old.sourceJdbcUrl()), trimOr(u.sourceUsername(), old.sourceUsername()), sourcePassword,
                trimOr(u.targetBaseUrl(), old.targetBaseUrl()), trimOr(u.targetUsername(), old.targetUsername()), targetPassword);
        return view();
    }

    private boolean has(String v) { return v != null && !v.isBlank() && !"********".equals(v.trim()); }
    private String trimOr(String v, String fallback) { return v == null || v.isBlank() ? fallback : v.trim(); }
}
