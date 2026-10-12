-- Simulator core schema

CREATE TABLE test_suite (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    name         VARCHAR(120) NOT NULL UNIQUE,
    description  VARCHAR(1000),
    created_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE TABLE test_case (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    suite_id           BIGINT REFERENCES test_suite(id) ON DELETE CASCADE,
    name               VARCHAR(200) NOT NULL,
    dialect            VARCHAR(60)  NOT NULL,
    request_json       CLOB         NOT NULL,
    expected_rc        VARCHAR(4),
    validation_json    CLOB,
    ignore_fields      VARCHAR(400),
    tags               VARCHAR(400),
    source             VARCHAR(40)  DEFAULT 'MANUAL' NOT NULL,
    enabled            BOOLEAN      DEFAULT TRUE NOT NULL,
    created_at         TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at         TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL
);
CREATE INDEX ix_test_case_suite ON test_case(suite_id);

CREATE TABLE transaction_log (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    source          VARCHAR(20)  NOT NULL,
    environment     VARCHAR(40),
    dialect         VARCHAR(60),
    mti             VARCHAR(4),
    processing_code VARCHAR(6),
    stan            VARCHAR(12),
    rrn             VARCHAR(12),
    terminal_id     VARCHAR(16),
    pan_masked      VARCHAR(24),
    amount          VARCHAR(12),
    response_code   VARCHAR(4),
    outcome         VARCHAR(10)  NOT NULL,
    latency_micros  BIGINT,
    request_json    CLOB,
    response_json   CLOB,
    request_hex     CLOB,
    response_hex    CLOB,
    error_message   VARCHAR(1000),
    created_at      TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL
);
CREATE INDEX ix_txlog_created ON transaction_log(created_at);
CREATE INDEX ix_txlog_rc ON transaction_log(response_code);

CREATE TABLE regression_run (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    suite_id     BIGINT,
    suite_name   VARCHAR(120),
    environment  VARCHAR(40),
    started_at   TIMESTAMP NOT NULL,
    finished_at  TIMESTAMP,
    total        INT DEFAULT 0 NOT NULL,
    passed       INT DEFAULT 0 NOT NULL,
    failed       INT DEFAULT 0 NOT NULL,
    status       VARCHAR(20) NOT NULL
);

CREATE TABLE regression_result (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id          BIGINT NOT NULL REFERENCES regression_run(id) ON DELETE CASCADE,
    test_case_id    BIGINT,
    test_case_name  VARCHAR(200),
    passed          BOOLEAN NOT NULL,
    expected_rc     VARCHAR(4),
    actual_rc       VARCHAR(4),
    latency_micros  BIGINT,
    issues_json     CLOB,
    response_json   CLOB
);
CREATE INDEX ix_regres_run ON regression_result(run_id);

CREATE TABLE stress_run (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    name           VARCHAR(120),
    environment    VARCHAR(40),
    profile_json   CLOB,
    started_at     TIMESTAMP NOT NULL,
    finished_at    TIMESTAMP,
    sent           BIGINT DEFAULT 0,
    approved       BIGINT DEFAULT 0,
    declined       BIGINT DEFAULT 0,
    timeouts       BIGINT DEFAULT 0,
    errors         BIGINT DEFAULT 0,
    achieved_tps   DOUBLE,
    p50_ms         DOUBLE,
    p95_ms         DOUBLE,
    p99_ms         DOUBLE,
    max_ms         DOUBLE,
    status         VARCHAR(20)
);

CREATE TABLE stress_sample (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id       BIGINT NOT NULL REFERENCES stress_run(id) ON DELETE CASCADE,
    second_index INT NOT NULL,
    target_tps   DOUBLE,
    sent         INT,
    completed    INT,
    timeouts     INT,
    errors       INT,
    p99_ms       DOUBLE
);

CREATE TABLE base24_snapshot (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    query_name   VARCHAR(120) NOT NULL,
    environment  VARCHAR(40),
    params_json  CLOB,
    rows_count   INT,
    result_json  CLOB,
    created_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE TABLE audit_log (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    username     VARCHAR(60),
    action       VARCHAR(60) NOT NULL,
    target       VARCHAR(200),
    detail       CLOB,
    success      BOOLEAN NOT NULL,
    created_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);
