-- OneDay backend 0.1.0: generated from Alembic revision 0001_backend.
-- Empty databases only. Existing databases: run alembic upgrade head.
-- Historical mobile/cloud design: backend/docs/DB_SCHEMA_DESIGN_ARCHIVE.txt
BEGIN;

CREATE TABLE alembic_version (
    version_num VARCHAR(32) NOT NULL, 
    CONSTRAINT alembic_version_pkc PRIMARY KEY (version_num)
);

-- Running upgrade  -> 0001_backend

CREATE TABLE users (
    user_id VARCHAR(36) NOT NULL, 
    device_id VARCHAR(128) NOT NULL, 
    created_at FLOAT NOT NULL, 
    PRIMARY KEY (user_id), 
    UNIQUE (device_id)
);

CREATE TABLE devices (
    device_id VARCHAR(128) NOT NULL, 
    user_id VARCHAR(36) NOT NULL, 
    camera_info JSON NOT NULL, 
    clip_info JSON NOT NULL, 
    updated_at FLOAT NOT NULL, 
    PRIMARY KEY (device_id), 
    FOREIGN KEY(user_id) REFERENCES users (user_id)
);

CREATE INDEX ix_devices_user_id ON devices (user_id);

CREATE TABLE tasks (
    task_id VARCHAR(36) NOT NULL, 
    user_id VARCHAR(36) NOT NULL, 
    current_version INTEGER NOT NULL, 
    created_at FLOAT NOT NULL, 
    PRIMARY KEY (task_id), 
    FOREIGN KEY(user_id) REFERENCES users (user_id)
);

CREATE INDEX ix_tasks_user_id ON tasks (user_id);

CREATE TABLE task_versions (
    task_id VARCHAR(36) NOT NULL, 
    version INTEGER NOT NULL, 
    user_input TEXT NOT NULL, 
    parsed_definition JSON NOT NULL, 
    target_subjects JSON NOT NULL, 
    interaction_direction JSON NOT NULL, 
    created_at FLOAT NOT NULL, 
    PRIMARY KEY (task_id, version), 
    CHECK (version >= 1), 
    FOREIGN KEY(task_id) REFERENCES tasks (task_id)
);

CREATE TABLE sessions (
    session_id VARCHAR(36) NOT NULL, 
    user_id VARCHAR(36) NOT NULL, 
    device_id VARCHAR(128) NOT NULL, 
    task_id VARCHAR(36) NOT NULL, 
    task_version INTEGER NOT NULL, 
    status VARCHAR(20) NOT NULL, 
    camera_recording_id VARCHAR(128) NOT NULL, 
    sync_event JSON NOT NULL, 
    privacy_pet_only BOOLEAN NOT NULL, 
    video_file JSON, 
    created_at FLOAT NOT NULL, 
    stopped_at FLOAT, 
    PRIMARY KEY (session_id), 
    CHECK (status IN ('active', 'completed')), 
    FOREIGN KEY(device_id) REFERENCES devices (device_id), 
    FOREIGN KEY(task_id, task_version) REFERENCES task_versions (task_id, version), 
    FOREIGN KEY(user_id) REFERENCES users (user_id)
);

CREATE INDEX ix_sessions_user_id ON sessions (user_id);

CREATE TABLE candidates (
    candidate_id VARCHAR(36) NOT NULL, 
    event_id VARCHAR(128) NOT NULL, 
    user_id VARCHAR(36) NOT NULL, 
    session_id VARCHAR(36) NOT NULL, 
    task_id VARCHAR(36) NOT NULL, 
    task_version INTEGER NOT NULL, 
    payload_hash VARCHAR(64) NOT NULL, 
    trigger VARCHAR(80) NOT NULL, 
    preview_start_time FLOAT NOT NULL, 
    preview_end_time FLOAT NOT NULL, 
    file_start_time FLOAT, 
    file_end_time FLOAT, 
    video_file TEXT, 
    media_status VARCHAR(20) NOT NULL, 
    status VARCHAR(24) NOT NULL, 
    disposition VARCHAR(20) NOT NULL, 
    sampled_frames JSON NOT NULL, 
    event_metadata JSON NOT NULL, 
    review_result JSON, 
    revision INTEGER NOT NULL, 
    created_at FLOAT NOT NULL, 
    PRIMARY KEY (candidate_id), 
    CHECK (disposition IN ('undecided','kept','ignored')), 
    CHECK (media_status IN ('available','missing')), 
    CHECK (status IN ('pending_review','preliminary_match','confirmed','needs_review','rejected')), 
    CHECK (preview_end_time >= preview_start_time), 
    FOREIGN KEY(session_id) REFERENCES sessions (session_id), 
    FOREIGN KEY(task_id, task_version) REFERENCES task_versions (task_id, version), 
    FOREIGN KEY(user_id) REFERENCES users (user_id), 
    CONSTRAINT uq_candidate_event UNIQUE (user_id, event_id)
);

CREATE INDEX ix_candidates_session_id ON candidates (session_id);

CREATE INDEX ix_candidates_status ON candidates (status);

CREATE INDEX ix_candidates_user_id ON candidates (user_id);

CREATE TABLE jobs (
    job_id VARCHAR(36) NOT NULL, 
    candidate_id VARCHAR(36) NOT NULL, 
    candidate_revision INTEGER NOT NULL, 
    status VARCHAR(20) NOT NULL, 
    attempts INTEGER NOT NULL, 
    available_at FLOAT NOT NULL, 
    lease_until FLOAT, 
    lease_token VARCHAR(36), 
    last_error VARCHAR(80), 
    created_at FLOAT NOT NULL, 
    PRIMARY KEY (job_id), 
    CHECK (status IN ('queued','running','completed','failed','cancelled')), 
    FOREIGN KEY(candidate_id) REFERENCES candidates (candidate_id), 
    UNIQUE (candidate_id, candidate_revision)
);

CREATE INDEX ix_jobs_candidate_id ON jobs (candidate_id);

CREATE INDEX ix_jobs_status ON jobs (status);

CREATE TABLE system_logs (
    log_id VARCHAR(36) NOT NULL, 
    user_id VARCHAR(36) NOT NULL, 
    candidate_id VARCHAR(36), 
    event_type VARCHAR(80) NOT NULL, 
    details JSON NOT NULL, 
    created_at FLOAT NOT NULL, 
    PRIMARY KEY (log_id), 
    FOREIGN KEY(candidate_id) REFERENCES candidates (candidate_id), 
    FOREIGN KEY(user_id) REFERENCES users (user_id)
);

CREATE INDEX ix_system_logs_user_id ON system_logs (user_id);

CREATE TABLE task_examples (
    example_id VARCHAR(36) NOT NULL, 
    task_id VARCHAR(36) NOT NULL, 
    task_version INTEGER NOT NULL, 
    candidate_id VARCHAR(36) NOT NULL, 
    example_type VARCHAR(16) NOT NULL, 
    user_annotation TEXT NOT NULL, 
    frames JSON NOT NULL, 
    created_at FLOAT NOT NULL, 
    PRIMARY KEY (example_id), 
    CHECK (example_type IN ('positive','negative')), 
    FOREIGN KEY(candidate_id) REFERENCES candidates (candidate_id), 
    FOREIGN KEY(task_id, task_version) REFERENCES task_versions (task_id, version), 
    UNIQUE (candidate_id, task_version)
);

CREATE TABLE user_feedback (
    feedback_id VARCHAR(36) NOT NULL, 
    user_id VARCHAR(36) NOT NULL, 
    candidate_id VARCHAR(36) NOT NULL, 
    request_id VARCHAR(128) NOT NULL, 
    payload_hash VARCHAR(64) NOT NULL, 
    payload JSON NOT NULL, 
    response JSON NOT NULL, 
    created_at FLOAT NOT NULL, 
    PRIMARY KEY (feedback_id), 
    FOREIGN KEY(candidate_id) REFERENCES candidates (candidate_id), 
    FOREIGN KEY(user_id) REFERENCES users (user_id), 
    UNIQUE (user_id, request_id)
);

CREATE INDEX ix_user_feedback_candidate_id ON user_feedback (candidate_id);

CREATE INDEX ix_user_feedback_user_id ON user_feedback (user_id);

CREATE TABLE review_results (
    review_id VARCHAR(36) NOT NULL, 
    candidate_id VARCHAR(36) NOT NULL, 
    job_id VARCHAR(36) NOT NULL, 
    model_name VARCHAR(128) NOT NULL, 
    result JSON NOT NULL, 
    created_at FLOAT NOT NULL, 
    PRIMARY KEY (review_id), 
    FOREIGN KEY(candidate_id) REFERENCES candidates (candidate_id), 
    FOREIGN KEY(job_id) REFERENCES jobs (job_id), 
    UNIQUE (job_id)
);

CREATE INDEX ix_review_results_candidate_id ON review_results (candidate_id);

INSERT INTO alembic_version (version_num) VALUES ('0001_backend') RETURNING alembic_version.version_num;

COMMIT;

