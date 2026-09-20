package com.company.migrator.service;

import com.company.migrator.common.MigrationModels.Settings;
import com.company.migrator.common.MigrationModels.SettingsUpdate;
import com.company.migrator.common.MigrationModels.SettingsView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SettingService {
    private final JdbcTemplate jdbc;

    public SettingService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Settings get() {
        return jdbc.queryForObject("SELECT source_jdbc_url,source_username,source_password,target_base_url,target_token,target_operator FROM migration_setting WHERE id=1",
                (rs, n) -> new Settings(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6)));
    }

    public SettingsView view() {
        Settings s = get();
        return new SettingsView(s.sourceJdbcUrl(), s.sourceUsername(), has(s.sourcePassword()),
                s.targetBaseUrl(), has(s.targetToken()), s.targetOperator());
    }

    @Transactional
    public SettingsView update(SettingsUpdate u) {
        Settings old = get();
        String sourcePassword = has(u.sourcePassword()) ? u.sourcePassword() : old.sourcePassword();
        String targetToken = has(u.targetToken()) ? u.targetToken() : old.targetToken();
        jdbc.update("UPDATE migration_setting SET source_jdbc_url=?,source_username=?,source_password=?,target_base_url=?,target_token=?,target_operator=?,updated_at=CURRENT_TIMESTAMP WHERE id=1",
                trimOr(u.sourceJdbcUrl(), old.sourceJdbcUrl()),
                trimOr(u.sourceUsername(), old.sourceUsername()),
                sourcePassword,
                trimOr(u.targetBaseUrl(), old.targetBaseUrl()),
                targetToken,
                trimOr(u.targetOperator(), old.targetOperator()));
        return view();
    }

    private boolean has(String v) {
        return v != null && !v.isBlank() && !"********".equals(v.trim());
    }

    private String trimOr(String v, String fallback) {
        return v == null || v.isBlank() ? fallback : v.trim();
    }
}
