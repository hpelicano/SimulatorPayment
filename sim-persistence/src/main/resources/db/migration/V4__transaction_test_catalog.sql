-- Separate operational auditing from ISO transaction tracking.
ALTER TABLE audit_log RENAME TO system_audit_log;

-- Preserve execution history while reserving the requested names for reusable messages.
ALTER TABLE regression_run RENAME TO regression_execution;
ALTER TABLE regression_result RENAME TO regression_execution_result;
ALTER TABLE stress_run RENAME TO stress_execution;
ALTER TABLE stress_sample RENAME TO stress_execution_sample;
ALTER TABLE regression_execution ALTER COLUMN suite_id VARCHAR(120);

CREATE TABLE dialect (
    id          VARCHAR(120) PRIMARY KEY,
    description VARCHAR(500),
    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);

INSERT INTO dialect(id, description) VALUES
    ('base24-pos', 'Transacciones POS'),
    ('base24-atm', 'Transacciones ATM'),
    ('base24-upay', 'Transacciones UPAY'),
    ('base24-clover', 'Transacciones Clover'),
    ('base24-chub', 'Transacciones CHUB'),
    ('base24-regresion', 'Pruebas de regresión'),
    ('base24-stress', 'Pruebas de estrés'),
    ('base24-audit_log', 'Auditoría de transacciones');

INSERT INTO dialect(id, description)
SELECT DISTINCT LOWER(dialect), 'Migrado desde test_case'
FROM test_case
WHERE dialect IS NOT NULL
  AND TRIM(dialect) <> ''
  AND NOT EXISTS (SELECT 1 FROM dialect d WHERE d.id = LOWER(test_case.dialect));

CREATE TABLE unit_test (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    suite_id        VARCHAR(120) NOT NULL REFERENCES dialect(id),
    name            VARCHAR(200) NOT NULL,
    request         CLOB NOT NULL,
    expected_rc     VARCHAR(4),
    validation_json CLOB,
    ignore_fields   VARCHAR(400),
    enabled         BOOLEAN DEFAULT TRUE NOT NULL,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE TABLE regresion (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    suite_id        VARCHAR(120) NOT NULL REFERENCES dialect(id),
    name            VARCHAR(200) NOT NULL,
    request         CLOB NOT NULL,
    expected_rc     VARCHAR(4),
    validation_json CLOB,
    ignore_fields   VARCHAR(400),
    enabled         BOOLEAN DEFAULT TRUE NOT NULL,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE TABLE stress_test (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    suite_id        VARCHAR(120) NOT NULL REFERENCES dialect(id),
    name            VARCHAR(200) NOT NULL,
    request         CLOB NOT NULL,
    expected_rc     VARCHAR(4),
    validation_json CLOB,
    ignore_fields   VARCHAR(400),
    enabled         BOOLEAN DEFAULT TRUE NOT NULL,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);

INSERT INTO regresion(suite_id, name, request, expected_rc, validation_json, ignore_fields, enabled, created_at, updated_at)
SELECT LOWER(dialect), name, request_json, expected_rc, validation_json, ignore_fields, enabled, created_at, updated_at
FROM test_case
WHERE dialect IS NOT NULL AND TRIM(dialect) <> '';

CREATE TABLE audit_log (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    request    CLOB,
    response   CLOB,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    status     VARCHAR(40) NOT NULL
);

DROP TABLE test_case;
DROP TABLE test_suite;

CREATE INDEX ix_unit_test_suite ON unit_test(suite_id);
CREATE INDEX ix_regresion_suite ON regresion(suite_id);
CREATE INDEX ix_stress_test_suite ON stress_test(suite_id);
CREATE INDEX ix_audit_log_created ON audit_log(created_at);
