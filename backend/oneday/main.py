import hashlib
import json
import time

import jwt
from fastapi import Depends, FastAPI, HTTPException, Query, Request
from fastapi.responses import JSONResponse
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from sqlalchemy import func, select, text, update
from sqlalchemy.exc import IntegrityError, OperationalError

from .config import Settings
from .db import database
from .models import (
    Audit,
    Candidate,
    Device,
    Example,
    Feedback,
    Job,
    RecordingSession,
    Task,
    TaskVersion,
    User,
    uid,
)
from .parser import UnsupportedTask, parse_task
from .schemas import (
    CandidateInput,
    DeviceInput,
    DispositionInput,
    FeedbackInput,
    LoginInput,
    RetryInput,
    SessionInput,
    StopInput,
    TaskInput,
    TaskUpdate,
)


def fingerprint(value):
    return hashlib.sha256(
        json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()
    ).hexdigest()


def owned(db, model, key, user):
    obj = db.get(model, key)
    if obj is None or obj.user_id != user:
        raise HTTPException(404, "not_found")
    return obj


def task_data(db, task, version=None):
    v = db.get(TaskVersion, (task.task_id, version or task.current_version))
    if not v:
        raise HTTPException(404, "task_version_not_found")
    examples = db.scalars(
        select(Example)
        .where(Example.task_id == task.task_id, Example.task_version == v.version)
        .order_by(Example.created_at.desc())
        .limit(20)
    )
    return {
        "task_id": task.task_id,
        "version": v.version,
        "user_input": v.user_input,
        "parsed_definition": v.parsed_definition,
        "supported": True,
        "created_at": v.created_at,
        "examples": [
            {"example_id": x.example_id, "example_type": x.example_type, "user_annotation": x.user_annotation}
            for x in examples
        ],
    }


def candidate_data(c):
    ui_status = (
        "ignored"
        if c.disposition == "ignored"
        else "reviewed"
        if c.status == "confirmed"
        else "pending_confirmation"
        if c.status in ("needs_review", "rejected")
        else "candidate"
    )
    return {
        "candidate_id": c.candidate_id,
        "eventID": c.event_id,
        "session_id": c.session_id,
        "task_id": c.task_id,
        "task_version": c.task_version,
        "status": c.status,
        "ui_status": ui_status,
        "disposition": c.disposition,
        "revision": c.revision,
        "trigger": c.trigger,
        "preview_time_range": [c.preview_start_time, c.preview_end_time],
        "file_time_range": [c.file_start_time, c.file_end_time] if c.media_status == "available" else None,
        "video_file": c.video_file,
        "media_status": c.media_status,
        "media_message": "没有对应视频" if c.media_status == "missing" else None,
        "captured": c.media_status == "available",
        "recognized": c.status in ("preliminary_match", "confirmed"),
        "generated": False,
        "review_result": c.review_result,
        "created_at": c.created_at,
    }


def audit(db, user, candidate, event_type, **details):
    db.add(Audit(user_id=user, candidate_id=candidate, event_type=event_type, details=details))


def change_version(db, task, expected, user_input, definition, subjects=None, direction=None):
    old = db.get(TaskVersion, (task.task_id, expected))
    if not old:
        raise HTTPException(409, "task_version_conflict")
    result = db.execute(
        update(Task)
        .where(Task.task_id == task.task_id, Task.current_version == expected)
        .values(current_version=expected + 1)
    )
    if result.rowcount != 1:
        raise HTTPException(409, "task_version_conflict")
    db.add(
        TaskVersion(
            task_id=task.task_id,
            version=expected + 1,
            user_input=user_input,
            parsed_definition=definition,
            target_subjects=subjects if subjects is not None else old.target_subjects,
            interaction_direction=direction if direction is not None else old.interaction_direction,
        )
    )
    db.flush()
    return expected + 1


def claim_candidate(db, c, expected):
    changed = db.execute(
        update(Candidate)
        .where(Candidate.candidate_id == c.candidate_id, Candidate.revision == expected)
        .values(revision=expected + 1)
    )
    if changed.rowcount != 1:
        raise HTTPException(409, "candidate_revision_conflict")


def create_app(settings=None):
    config = settings or Settings()
    engine, factory = database(config.database_url)
    app = FastAPI(title="OneDay Backend", version="0.1.0")
    app.state.settings, app.state.engine, app.state.db = config, engine, factory
    bearer = HTTPBearer(auto_error=False)

    def db_session():
        with factory() as db:
            yield db

    def identity(credentials: HTTPAuthorizationCredentials | None = Depends(bearer), db=Depends(db_session)):
        try:
            if credentials is None:
                raise ValueError("missing token")
            payload = jwt.decode(
                credentials.credentials,
                config.jwt_secret.get_secret_value(),
                algorithms=["HS256"],
                audience="oneday",
                issuer="oneday",
                options={"require": ["exp", "iat", "sub", "aud", "iss"]},
            )
            user = db.get(User, payload["sub"])
            if not user:
                raise ValueError("unknown user")
            return user.user_id
        except (jwt.PyJWTError, ValueError, KeyError):
            raise HTTPException(401, "invalid_or_expired_token", headers={"WWW-Authenticate": "Bearer"})

    @app.exception_handler(UnsupportedTask)
    async def unsupported_handler(request, exc):
        return JSONResponse(
            status_code=422,
            content={
                "error": "unsupported_task",
                "supported": False,
                "message": str(exc),
                "suggested_alternatives": ["喝水", "接近停留", "玩具交接", "跳跃"],
            },
        )

    @app.exception_handler(IntegrityError)
    async def conflict_handler(request, exc):
        return JSONResponse(
            status_code=409, content={"detail": "concurrent_write_conflict_retry_same_request"}
        )

    @app.get("/health")
    def health(db=Depends(db_session)):
        try:
            revision = db.execute(text("SELECT version_num FROM alembic_version")).scalar()
        except OperationalError:
            raise HTTPException(503, "run_alembic_upgrade_head")
        if revision != "0001_backend":
            raise HTTPException(503, "database_revision_mismatch")
        return {"status": "ok", "schema_revision": revision, "vlm_provider": config.vlm_provider}

    @app.post("/api/v1/auth/login")
    def login(body: LoginInput, request: Request, db=Depends(db_session)):
        if not config.dev_login or config.environment != "development":
            raise HTTPException(403, "development_login_disabled")
        if request.client and request.client.host not in ("127.0.0.1", "::1", "localhost", "testclient"):
            raise HTTPException(403, "development_login_requires_loopback")
        user = db.scalar(select(User).where(User.device_id == body.device_id))
        if user is None:
            user = User(device_id=body.device_id)
            db.add(user)
            db.commit()
        now = int(time.time())
        token = jwt.encode(
            {
                "sub": user.user_id,
                "iat": now,
                "exp": now + config.token_ttl_seconds,
                "aud": "oneday",
                "iss": "oneday",
            },
            config.jwt_secret.get_secret_value(),
            algorithm="HS256",
        )
        return {
            "access_token": token,
            "token_type": "Bearer",
            "expires_in": config.token_ttl_seconds,
            "user_id": user.user_id,
            "development_only": True,
        }

    @app.post("/api/v1/devices/sync")
    def sync_device(body: DeviceInput, user=Depends(identity), db=Depends(db_session)):
        device = db.get(Device, body.device_id)
        if device and device.user_id != user:
            raise HTTPException(404, "not_found")
        if not device:
            device = Device(device_id=body.device_id, user_id=user)
            db.add(device)
        device.camera_info, device.clip_info, device.updated_at = (
            body.camera_info,
            body.clip_info,
            time.time(),
        )
        db.commit()
        return {
            "device_id": device.device_id,
            "server_time": device.updated_at,
            "camera_info": device.camera_info,
            "clip_info": device.clip_info,
            "time_offset_ms": None,
            "note": "单次服务器时间差不能证明相机时间已对齐",
        }

    @app.get("/api/v1/devices/{device_id}")
    def get_device(device_id: str, user=Depends(identity), db=Depends(db_session)):
        d = owned(db, Device, device_id, user)
        return {
            "device_id": d.device_id,
            "camera_info": d.camera_info,
            "clip_info": d.clip_info,
            "last_sync": d.updated_at,
        }

    @app.post("/api/v1/tasks", status_code=201)
    def create_task(body: TaskInput, user=Depends(identity), db=Depends(db_session)):
        definition = parse_task(body.user_input)
        task = Task(user_id=user)
        db.add(task)
        db.flush()
        db.add(
            TaskVersion(
                task_id=task.task_id,
                version=1,
                user_input=body.user_input,
                parsed_definition=definition,
                target_subjects=body.target_subjects,
                interaction_direction=body.interaction_direction,
            )
        )
        db.commit()
        return task_data(db, task)

    @app.get("/api/v1/tasks/{task_id}")
    def get_task(
        task_id: str, version: int | None = Query(None, ge=1), user=Depends(identity), db=Depends(db_session)
    ):
        return task_data(db, owned(db, Task, task_id, user), version)

    @app.put("/api/v1/tasks/{task_id}")
    def edit_task(task_id: str, body: TaskUpdate, user=Depends(identity), db=Depends(db_session)):
        task = owned(db, Task, task_id, user)
        change_version(
            db,
            task,
            body.expected_version,
            body.user_input,
            parse_task(body.user_input),
            body.target_subjects,
            body.interaction_direction,
        )
        db.commit()
        return task_data(db, task)

    @app.post("/api/v1/sessions", status_code=201)
    def create_session(body: SessionInput, user=Depends(identity), db=Depends(db_session)):
        owned(db, Task, body.task_id, user)
        device = owned(db, Device, body.device_id, user)
        if not db.get(TaskVersion, (body.task_id, body.task_version)):
            raise HTTPException(404, "task_version_not_found")
        if not body.sync_event.aligned or body.sync_event.estimated_error_ms > config.sync_max_error_ms:
            raise HTTPException(409, "sync_required_or_error_too_large")
        if device.camera_info.get("status") != "connected":
            raise HTTPException(409, "camera_not_connected")
        s = RecordingSession(user_id=user, **body.model_dump())
        db.add(s)
        db.commit()
        return {
            "session_id": s.session_id,
            "task_id": s.task_id,
            "task_version": s.task_version,
            "status": s.status,
            "created_at": s.created_at,
        }

    @app.get("/api/v1/sessions/{session_id}")
    def get_session(session_id: str, user=Depends(identity), db=Depends(db_session)):
        s = owned(db, RecordingSession, session_id, user)
        count = db.scalar(
            select(func.count()).select_from(Candidate).where(Candidate.session_id == session_id)
        )
        return {
            "session_id": s.session_id,
            "task_id": s.task_id,
            "task_version": s.task_version,
            "status": s.status,
            "sync_event": s.sync_event,
            "privacy_pet_only": s.privacy_pet_only,
            "video_file": s.video_file,
            "created_at": s.created_at,
            "stopped_at": s.stopped_at,
            "candidates_count": count,
        }

    @app.post("/api/v1/sessions/{session_id}/stop")
    def stop_session(session_id: str, body: StopInput, user=Depends(identity), db=Depends(db_session)):
        s = owned(db, RecordingSession, session_id, user)
        video = body.video_file.model_dump() if body.video_file else None
        if s.status == "completed":
            if s.stopped_at != body.stop_time or s.video_file != video:
                raise HTTPException(409, "session_already_stopped_differently")
        else:
            if body.stop_time < s.sync_event["user_time"]:
                raise HTTPException(422, "stop_precedes_start")
            s.status, s.stopped_at, s.video_file = "completed", body.stop_time, video
            db.commit()
        return {
            "session_id": s.session_id,
            "status": s.status,
            "stopped_at": s.stopped_at,
            "duration_seconds": s.stopped_at - s.sync_event["user_time"],
        }

    @app.post("/api/v1/sessions/{session_id}/candidates", status_code=201)
    def submit_candidate(
        session_id: str, body: CandidateInput, user=Depends(identity), db=Depends(db_session)
    ):
        s = owned(db, RecordingSession, session_id, user)
        digest = fingerprint({"session_id": session_id, **body.model_dump()})
        existing = db.scalar(
            select(Candidate).where(Candidate.user_id == user, Candidate.event_id == body.event_id)
        )
        if existing:
            if existing.payload_hash != digest:
                raise HTTPException(409, "eventID_payload_conflict")
            return candidate_data(existing)
        if s.privacy_pet_only and body.sampled_frames and not body.privacy_redacted:
            raise HTTPException(422, "redact_people_before_upload")
        start = body.preview_start_time - s.sync_event["preview_time"] + s.sync_event["file_time"]
        end = body.preview_end_time - s.sync_event["preview_time"] + s.sync_event["file_time"]
        available = body.camera_recording and bool(body.video_file) and start >= 0
        if s.status == "completed":
            available = (
                available
                and s.video_file is not None
                and body.video_file == s.video_file["path"]
                and start >= s.video_file["file_time_start"]
                and end <= s.video_file["file_time_end"]
            )
        c = Candidate(
            user_id=user,
            session_id=session_id,
            task_id=s.task_id,
            task_version=s.task_version,
            event_id=body.event_id,
            payload_hash=digest,
            trigger=body.trigger,
            preview_start_time=body.preview_start_time,
            preview_end_time=body.preview_end_time,
            file_start_time=start if available else None,
            file_end_time=end if available else None,
            video_file=body.video_file if available else None,
            media_status="available" if available else "missing",
            sampled_frames=[f.model_dump() for f in body.sampled_frames],
            event_metadata=body.metadata,
        )
        db.add(c)
        db.flush()
        db.add(Job(candidate_id=c.candidate_id, candidate_revision=c.revision))
        audit(db, user, c.candidate_id, "candidate_retained", media_status=c.media_status)
        db.commit()
        return candidate_data(c)

    @app.get("/api/v1/candidates/{candidate_id}")
    def get_candidate(candidate_id: str, user=Depends(identity), db=Depends(db_session)):
        return candidate_data(owned(db, Candidate, candidate_id, user))

    @app.get("/api/v1/sessions/{session_id}/candidates")
    def list_candidates(
        session_id: str,
        status: str | None = None,
        limit: int = Query(20, ge=1, le=100),
        offset: int = Query(0, ge=0),
        user=Depends(identity),
        db=Depends(db_session),
    ):
        owned(db, RecordingSession, session_id, user)
        q = select(Candidate).where(Candidate.session_id == session_id)
        if status:
            q = q.where(Candidate.status == status)
        total = db.scalar(select(func.count()).select_from(q.subquery()))
        items = db.scalars(
            q.order_by(Candidate.created_at, Candidate.candidate_id).offset(offset).limit(limit)
        )
        return {
            "session_id": session_id,
            "candidates": [candidate_data(c) for c in items],
            "total": total,
            "limit": limit,
            "offset": offset,
        }

    @app.post("/api/v1/candidates/{candidate_id}/disposition")
    def disposition(
        candidate_id: str, body: DispositionInput, user=Depends(identity), db=Depends(db_session)
    ):
        c = owned(db, Candidate, candidate_id, user)
        claim_candidate(db, c, body.expected_revision)
        c.disposition = "kept" if body.action == "keep" else "ignored"
        # New revision invalidates a running review. Keep pending work live for retained candidates.
        if c.disposition == "kept" and c.status == "pending_review":
            db.add(Job(candidate_id=c.candidate_id, candidate_revision=c.revision))
        audit(db, user, c.candidate_id, "user_" + c.disposition)
        db.commit()
        return candidate_data(c)

    @app.post("/api/v1/candidates/{candidate_id}/feedback")
    def feedback(candidate_id: str, body: FeedbackInput, user=Depends(identity), db=Depends(db_session)):
        c = owned(db, Candidate, candidate_id, user)
        digest = fingerprint({"candidate_id": candidate_id, **body.model_dump()})
        prior = db.scalar(
            select(Feedback).where(Feedback.user_id == user, Feedback.request_id == body.request_id)
        )
        if prior:
            if prior.payload_hash != digest:
                raise HTTPException(409, "feedback_request_id_conflict")
            return prior.response
        claim_candidate(db, c, c.revision)
        result = {
            "feedback_id": uid(),
            "candidate_id": candidate_id,
            "task_version_updated": False,
            "reevaluation_triggered": False,
            "affected_candidates": 0,
            "processed_at": time.time(),
        }
        if body.correction_type == "standard_error":
            task = owned(db, Task, c.task_id, user)
            corrected = body.corrected_task
            definition = parse_task(corrected.user_input)
            exclusions = set(definition["exclusion_conditions"])
            exclusions.update(corrected.added_exclusions)
            exclusions.difference_update(corrected.removed_exclusions)
            definition["exclusion_conditions"] = sorted(exclusions)
            version = change_version(db, task, corrected.expected_version, corrected.user_input, definition)
            c.task_version, c.status, c.review_result = version, "pending_review", None
            c.disposition = "undecided"
            db.add(Job(candidate_id=c.candidate_id, candidate_revision=c.revision))
            result.update(
                action_taken="updated_task_definition",
                task_version_updated=True,
                new_task_version=version,
                reevaluation_triggered=True,
                affected_candidates=1,
            )
        else:
            c.status = {"correct": "confirmed", "wrong": "rejected", "uncertain": "needs_review"}[
                body.user_verdict
            ]
            c.review_result = {"source": "human", "verdict": body.user_verdict, "reason": body.user_comment}
            example = db.scalar(
                select(Example).where(
                    Example.candidate_id == c.candidate_id, Example.task_version == c.task_version
                )
            )
            if body.user_verdict == "uncertain":
                if example:
                    db.delete(example)
                result["action_taken"] = "marked_needs_review"
            else:
                label = "positive" if body.user_verdict == "correct" else "negative"
                if example is None:
                    example = Example(
                        task_id=c.task_id, task_version=c.task_version, candidate_id=c.candidate_id
                    )
                    db.add(example)
                example.example_type, example.user_annotation = label, body.user_comment
                example.frames, example.created_at = c.sampled_frames, time.time()
                result["action_taken"] = "added_" + label + "_example"
        db.add(
            Feedback(
                feedback_id=result["feedback_id"],
                user_id=user,
                candidate_id=candidate_id,
                request_id=body.request_id,
                payload_hash=digest,
                payload=body.model_dump(),
                response=result,
            )
        )
        audit(db, user, c.candidate_id, "user_feedback", action=result["action_taken"])
        db.commit()
        return result

    @app.post("/api/v1/candidates/{candidate_id}/review", status_code=202)
    def retry_review(candidate_id: str, body: RetryInput, user=Depends(identity), db=Depends(db_session)):
        c = owned(db, Candidate, candidate_id, user)
        if c.disposition == "ignored" or c.status == "pending_review":
            raise HTTPException(409, "restore_candidate_or_wait_for_existing_review")
        claim_candidate(db, c, body.expected_revision)
        c.status, c.review_result = "pending_review", None
        j = Job(candidate_id=c.candidate_id, candidate_revision=c.revision)
        db.add(j)
        audit(db, user, c.candidate_id, "review_requested")
        db.commit()
        return {
            "job_id": j.job_id,
            "candidate_id": c.candidate_id,
            "status": j.status,
            "revision": c.revision,
        }

    @app.get("/api/v1/jobs/{job_id}")
    def get_job(job_id: str, user=Depends(identity), db=Depends(db_session)):
        j = db.get(Job, job_id)
        if not j:
            raise HTTPException(404, "not_found")
        owned(db, Candidate, j.candidate_id, user)
        return {
            "job_id": j.job_id,
            "candidate_id": j.candidate_id,
            "status": j.status,
            "attempts": j.attempts,
            "last_error": j.last_error,
        }

    @app.get("/api/v1/candidates/{candidate_id}/jobs")
    def candidate_jobs(candidate_id: str, user=Depends(identity), db=Depends(db_session)):
        owned(db, Candidate, candidate_id, user)
        jobs = db.scalars(select(Job).where(Job.candidate_id == candidate_id).order_by(Job.created_at))
        return [
            {"job_id": j.job_id, "status": j.status, "attempts": j.attempts, "last_error": j.last_error}
            for j in jobs
        ]

    @app.get("/api/v1/audit")
    def audit_log(
        limit: int = Query(50, ge=1, le=100),
        offset: int = Query(0, ge=0),
        user=Depends(identity),
        db=Depends(db_session),
    ):
        rows = db.scalars(
            select(Audit)
            .where(Audit.user_id == user)
            .order_by(Audit.created_at.desc())
            .offset(offset)
            .limit(limit)
        )
        return {
            "records": [
                {
                    "log_id": r.log_id,
                    "candidate_id": r.candidate_id,
                    "event_type": r.event_type,
                    "details": r.details,
                    "created_at": r.created_at,
                }
                for r in rows
            ]
        }

    return app


app = create_app()
