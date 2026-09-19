"""Durable review queue with CAS claims, expiring leases, and bounded retries."""

import argparse
import logging
import time

from sqlalchemy import and_, or_, select, update

from .config import Settings
from .db import database
from .models import Audit, Candidate, Example, Job, Review, TaskVersion, uid
from .vlm import VLM, ReviewFailure, next_status

log = logging.getLogger(__name__)


def run_once(factory, settings, provider=None, now=None):
    now = time.time() if now is None else now
    eligible = or_(
        and_(Job.status == "queued", Job.available_at <= now),
        and_(Job.status == "running", Job.lease_until < now),
    )
    token = uid()
    with factory.begin() as db:
        job = db.scalar(select(Job).where(eligible).order_by(Job.available_at, Job.job_id).limit(1))
        if job is None:
            return False
        job_id = job.job_id
        claimed = db.execute(
            update(Job)
            .where(Job.job_id == job_id, eligible)
            .values(
                status="running",
                lease_token=token,
                lease_until=now + settings.job_lease_seconds,
                attempts=Job.attempts + 1,
            )
        )
        if claimed.rowcount != 1:
            return True
        c = db.get(Candidate, job.candidate_id)
        if c.revision != job.candidate_revision or c.disposition == "ignored":
            job.status, job.lease_until = "cancelled", None
            return True
        version = db.get(TaskVersion, (c.task_id, c.task_version))
        examples = list(
            db.scalars(
                select(Example)
                .where(
                    Example.task_id == c.task_id,
                    Example.task_version == c.task_version,
                    Example.candidate_id != c.candidate_id,
                )
                .order_by(Example.created_at.desc())
                .limit(4)
            )
        )
        sample = [
            {"example_type": e.example_type, "user_annotation": e.user_annotation, "frames": e.frames}
            for e in reversed(examples)
        ]
        definition, frames, attempts = version.parsed_definition, c.sampled_frames, job.attempts
    result, failure = None, None
    try:
        if attempts > settings.job_max_attempts:
            raise ReviewFailure("retry_budget_exhausted", retryable=False)
        result = (provider or VLM(settings)).review(definition, frames, sample)
    except ReviewFailure as exc:
        failure = exc
    # Unexpected exceptions propagate. The lease makes interrupted work recoverable.
    with factory.begin() as db:
        job = db.get(Job, job_id)
        if job.lease_token != token or job.status != "running":
            return True
        # Acquire a write lock on the job without trusting a stale in-memory lease.
        locked = db.execute(
            update(Job)
            .where(Job.job_id == job_id, Job.lease_token == token, Job.status == "running")
            .values(lease_until=None)
        )
        if locked.rowcount != 1:
            return True
        c = db.get(Candidate, job.candidate_id)
        if c.revision != job.candidate_revision or c.disposition == "ignored":
            job.status = "cancelled"
            return True
        if failure and failure.retryable and attempts < settings.job_max_attempts:
            job.status, job.last_error = "queued", failure.code
            job.available_at = now + min(60, 2**attempts)
            return True
        target = "needs_review" if failure else next_status(result)
        data = {"source": "system", "reason": failure.code} if failure else result.model_dump()
        changed = db.execute(
            update(Candidate)
            .where(Candidate.candidate_id == c.candidate_id, Candidate.revision == job.candidate_revision)
            .values(status=target, review_result=data, revision=Candidate.revision + 1)
        )
        if changed.rowcount != 1:
            job.status = "cancelled"
            return True
        job.status, job.last_error = ("failed", failure.code) if failure else ("completed", None)
        if result:
            db.add(
                Review(
                    candidate_id=c.candidate_id,
                    job_id=job_id,
                    model_name=settings.vlm_model or "disabled",
                    result=data,
                )
            )
        db.add(
            Audit(
                user_id=c.user_id,
                candidate_id=c.candidate_id,
                event_type="review_" + job.status,
                details={"status": target, "retained": True, "error": job.last_error},
            )
        )
    return True


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--once", action="store_true", help="Process at most one available job")
    args = parser.parse_args()
    settings = Settings()
    engine, factory = database(settings.database_url)
    logging.basicConfig(level=logging.INFO)
    try:
        while True:
            processed = run_once(factory, settings)
            if args.once:
                break
            if not processed:
                time.sleep(1)
    except KeyboardInterrupt:
        pass
    finally:
        engine.dispose()


if __name__ == "__main__":
    main()
