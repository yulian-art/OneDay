"""Relational storage. Times are Unix seconds; JSON is native on PostgreSQL."""

import time
import uuid

from sqlalchemy import (
    JSON,
    Boolean,
    CheckConstraint,
    Float,
    ForeignKey,
    ForeignKeyConstraint,
    Integer,
    String,
    Text,
    UniqueConstraint,
)
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column


def uid():
    return str(uuid.uuid4())


class Base(DeclarativeBase):
    pass


class User(Base):
    __tablename__ = "users"
    user_id: Mapped[str] = mapped_column(String(36), primary_key=True, default=uid)
    device_id: Mapped[str] = mapped_column(String(128), unique=True)
    created_at: Mapped[float] = mapped_column(Float, default=time.time)


class Device(Base):
    __tablename__ = "devices"
    device_id: Mapped[str] = mapped_column(String(128), primary_key=True)
    user_id: Mapped[str] = mapped_column(ForeignKey("users.user_id"), index=True)
    camera_info: Mapped[dict] = mapped_column(JSON)
    clip_info: Mapped[dict] = mapped_column(JSON)
    updated_at: Mapped[float] = mapped_column(Float, default=time.time)


class Task(Base):
    __tablename__ = "tasks"
    task_id: Mapped[str] = mapped_column(String(36), primary_key=True, default=uid)
    user_id: Mapped[str] = mapped_column(ForeignKey("users.user_id"), index=True)
    current_version: Mapped[int] = mapped_column(Integer, default=1)
    created_at: Mapped[float] = mapped_column(Float, default=time.time)


class TaskVersion(Base):
    __tablename__ = "task_versions"
    task_id: Mapped[str] = mapped_column(ForeignKey("tasks.task_id"), primary_key=True)
    version: Mapped[int] = mapped_column(Integer, primary_key=True)
    user_input: Mapped[str] = mapped_column(Text)
    parsed_definition: Mapped[dict] = mapped_column(JSON)
    target_subjects: Mapped[dict] = mapped_column(JSON, default=dict)
    interaction_direction: Mapped[dict] = mapped_column(JSON, default=dict)
    created_at: Mapped[float] = mapped_column(Float, default=time.time)
    __table_args__ = (CheckConstraint("version >= 1"),)


class RecordingSession(Base):
    __tablename__ = "sessions"
    session_id: Mapped[str] = mapped_column(String(36), primary_key=True, default=uid)
    user_id: Mapped[str] = mapped_column(ForeignKey("users.user_id"), index=True)
    device_id: Mapped[str] = mapped_column(ForeignKey("devices.device_id"))
    task_id: Mapped[str] = mapped_column(String(36))
    task_version: Mapped[int] = mapped_column(Integer)
    status: Mapped[str] = mapped_column(String(20), default="active")
    camera_recording_id: Mapped[str] = mapped_column(String(128))
    sync_event: Mapped[dict] = mapped_column(JSON)
    privacy_pet_only: Mapped[bool] = mapped_column(Boolean, default=True)
    video_file: Mapped[dict | None] = mapped_column(JSON, nullable=True)
    created_at: Mapped[float] = mapped_column(Float, default=time.time)
    stopped_at: Mapped[float | None] = mapped_column(Float, nullable=True)
    __table_args__ = (
        ForeignKeyConstraint(["task_id", "task_version"], ["task_versions.task_id", "task_versions.version"]),
        CheckConstraint("status IN ('active', 'completed')"),
    )


class Candidate(Base):
    __tablename__ = "candidates"
    candidate_id: Mapped[str] = mapped_column(String(36), primary_key=True, default=uid)
    event_id: Mapped[str] = mapped_column(String(128))
    user_id: Mapped[str] = mapped_column(ForeignKey("users.user_id"), index=True)
    session_id: Mapped[str] = mapped_column(ForeignKey("sessions.session_id"), index=True)
    task_id: Mapped[str] = mapped_column(String(36))
    task_version: Mapped[int] = mapped_column(Integer)
    payload_hash: Mapped[str] = mapped_column(String(64))
    trigger: Mapped[str] = mapped_column(String(80))
    preview_start_time: Mapped[float] = mapped_column(Float)
    preview_end_time: Mapped[float] = mapped_column(Float)
    file_start_time: Mapped[float | None] = mapped_column(Float)
    file_end_time: Mapped[float | None] = mapped_column(Float)
    video_file: Mapped[str | None] = mapped_column(Text)
    media_status: Mapped[str] = mapped_column(String(20))
    status: Mapped[str] = mapped_column(String(24), default="pending_review", index=True)
    disposition: Mapped[str] = mapped_column(String(20), default="undecided")
    sampled_frames: Mapped[list] = mapped_column(JSON)
    event_metadata: Mapped[dict] = mapped_column(JSON, default=dict)
    review_result: Mapped[dict | None] = mapped_column(JSON)
    revision: Mapped[int] = mapped_column(Integer, default=0)
    created_at: Mapped[float] = mapped_column(Float, default=time.time)
    __table_args__ = (
        UniqueConstraint("user_id", "event_id", name="uq_candidate_event"),
        ForeignKeyConstraint(["task_id", "task_version"], ["task_versions.task_id", "task_versions.version"]),
        CheckConstraint("preview_end_time >= preview_start_time"),
        CheckConstraint(
            "status IN ('pending_review','preliminary_match','confirmed','needs_review','rejected')"
        ),
        CheckConstraint("disposition IN ('undecided','kept','ignored')"),
        CheckConstraint("media_status IN ('available','missing')"),
    )


class Job(Base):
    __tablename__ = "jobs"
    job_id: Mapped[str] = mapped_column(String(36), primary_key=True, default=uid)
    candidate_id: Mapped[str] = mapped_column(ForeignKey("candidates.candidate_id"), index=True)
    candidate_revision: Mapped[int] = mapped_column(Integer)
    status: Mapped[str] = mapped_column(String(20), default="queued", index=True)
    attempts: Mapped[int] = mapped_column(Integer, default=0)
    available_at: Mapped[float] = mapped_column(Float, default=time.time)
    lease_until: Mapped[float | None] = mapped_column(Float)
    lease_token: Mapped[str | None] = mapped_column(String(36))
    last_error: Mapped[str | None] = mapped_column(String(80))
    created_at: Mapped[float] = mapped_column(Float, default=time.time)
    __table_args__ = (
        UniqueConstraint("candidate_id", "candidate_revision"),
        CheckConstraint("status IN ('queued','running','completed','failed','cancelled')"),
    )


class Review(Base):
    __tablename__ = "review_results"
    review_id: Mapped[str] = mapped_column(String(36), primary_key=True, default=uid)
    candidate_id: Mapped[str] = mapped_column(ForeignKey("candidates.candidate_id"), index=True)
    job_id: Mapped[str] = mapped_column(ForeignKey("jobs.job_id"), unique=True)
    model_name: Mapped[str] = mapped_column(String(128))
    result: Mapped[dict] = mapped_column(JSON)
    created_at: Mapped[float] = mapped_column(Float, default=time.time)


class Feedback(Base):
    __tablename__ = "user_feedback"
    feedback_id: Mapped[str] = mapped_column(String(36), primary_key=True, default=uid)
    user_id: Mapped[str] = mapped_column(ForeignKey("users.user_id"), index=True)
    candidate_id: Mapped[str] = mapped_column(ForeignKey("candidates.candidate_id"), index=True)
    request_id: Mapped[str] = mapped_column(String(128))
    payload_hash: Mapped[str] = mapped_column(String(64))
    payload: Mapped[dict] = mapped_column(JSON)
    response: Mapped[dict] = mapped_column(JSON)
    created_at: Mapped[float] = mapped_column(Float, default=time.time)
    __table_args__ = (UniqueConstraint("user_id", "request_id"),)


class Example(Base):
    __tablename__ = "task_examples"
    example_id: Mapped[str] = mapped_column(String(36), primary_key=True, default=uid)
    task_id: Mapped[str] = mapped_column(String(36))
    task_version: Mapped[int] = mapped_column(Integer)
    candidate_id: Mapped[str] = mapped_column(ForeignKey("candidates.candidate_id"))
    example_type: Mapped[str] = mapped_column(String(16))
    user_annotation: Mapped[str] = mapped_column(Text)
    frames: Mapped[list] = mapped_column(JSON)
    created_at: Mapped[float] = mapped_column(Float, default=time.time)
    __table_args__ = (
        ForeignKeyConstraint(["task_id", "task_version"], ["task_versions.task_id", "task_versions.version"]),
        UniqueConstraint("candidate_id", "task_version"),
        CheckConstraint("example_type IN ('positive','negative')"),
    )


class Audit(Base):
    __tablename__ = "system_logs"
    log_id: Mapped[str] = mapped_column(String(36), primary_key=True, default=uid)
    user_id: Mapped[str] = mapped_column(ForeignKey("users.user_id"), index=True)
    candidate_id: Mapped[str | None] = mapped_column(ForeignKey("candidates.candidate_id"))
    event_type: Mapped[str] = mapped_column(String(80))
    details: Mapped[dict] = mapped_column(JSON)
    created_at: Mapped[float] = mapped_column(Float, default=time.time)
