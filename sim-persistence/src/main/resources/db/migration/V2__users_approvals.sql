-- Users with roles and maker-checker approvals for remote Base24 changes.
CREATE TABLE app_user (
    username       VARCHAR(60) PRIMARY KEY,
    display_name   VARCHAR(120),
    role           VARCHAR(20) NOT NULL,
    password_hash  VARCHAR(200) NOT NULL,
    salt           VARCHAR(64) NOT NULL,
    enabled        BOOLEAN DEFAULT TRUE NOT NULL,
    created_at     TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    last_login     TIMESTAMP
);

CREATE TABLE approval_request (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    type            VARCHAR(40) NOT NULL,
    target          VARCHAR(200),
    environment     VARCHAR(40),
    payload_json    CLOB,
    command_preview CLOB,
    requested_by    VARCHAR(60) NOT NULL,
    requested_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    status          VARCHAR(20) NOT NULL,
    decided_by      VARCHAR(60),
    decided_at      TIMESTAMP,
    comment         VARCHAR(500),
    result          CLOB
);

CREATE INDEX idx_approval_status ON approval_request(status);
