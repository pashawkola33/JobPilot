package com.jobpilot.common;

import com.jobpilot.config.BuildInfoProperties;
import com.jobpilot.config.JobPilotProperties;
import com.jobpilot.config.MaintenanceProperties;
import com.jobpilot.resume.config.DocumentProperties;
import com.jobpilot.resume.storage.DocumentArtifactStorage;
import org.flywaydb.core.Flyway;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {
    private final JdbcTemplate jdbcTemplate;
    private final Flyway flyway;
    private final JobPilotProperties properties;
    private final DocumentProperties documents;
    private final MaintenanceProperties maintenance;
    private final BuildInfoProperties build;
    private final DocumentArtifactStorage storage;

    @Autowired
    public HealthController(JdbcTemplate jdbcTemplate, Flyway flyway,
                            JobPilotProperties properties, DocumentProperties documents,
                            MaintenanceProperties maintenance, BuildInfoProperties build,
                            DocumentArtifactStorage storage) {
        this.jdbcTemplate = jdbcTemplate;
        this.flyway = flyway;
        this.properties = properties;
        this.documents = documents;
        this.maintenance = maintenance;
        this.build = build;
        this.storage = storage;
    }

    public HealthController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.flyway = null;
        this.properties = null;
        this.documents = null;
        this.maintenance = null;
        this.build = null;
        this.storage = null;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        boolean databaseReady;
        try {
            jdbcTemplate.queryForObject("select 1", Integer.class);
            databaseReady = true;
        } catch (DataAccessException databaseFailure) {
            databaseReady = false;
        }
        boolean schemaReady = databaseReady && schemaReady();
        boolean storageReady = documents == null || !documents.enabled()
                || storage != null && storage.isReady();
        boolean ready = databaseReady && schemaReady && storageReady;

        Map<String, Object> components = new LinkedHashMap<>();
        components.put("database", databaseReady ? "READY" : "NOT_READY");
        components.put("schema", schemaReady ? "READY" : "NOT_READY");
        components.put("telegram", enabled(properties == null
                ? false : properties.telegram().commandsEnabled()));
        components.put("llm", enabled(properties != null && properties.llm().enabled()));
        components.put("documents", enabled(documents != null && documents.enabled()));
        components.put("artifactStorage", storageReady ? "READY" : "NOT_READY");
        components.put("maintenance", enabled(maintenance != null && maintenance.enabled()));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", ready ? "UP" : "DOWN");
        body.put("application", "JobPilot");
        body.put("version", build == null ? "unknown" : build.version());
        body.put("commit", build == null ? "unknown" : build.commit());
        body.put("components", components);
        return ResponseEntity.status(ready ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .body(body);
    }

    private boolean schemaReady() {
        if (flyway == null) return true;
        try {
            return flyway.info().current() != null
                    && flyway.validateWithResult().validationSuccessful;
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private String enabled(boolean value) {
        return value ? "ENABLED" : "DISABLED";
    }
}
