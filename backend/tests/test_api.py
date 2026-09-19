import base64
from concurrent.futures import ThreadPoolExecutor

import jwt
import pytest
from conftest import auth, candidate_body, setup_session, submit
from sqlalchemy import func, select

from oneday.models import Candidate, Feedback, Job
from oneday.worker import run_once


def test_health_and_auth(client, headers):
    assert client.get("/health").json()["status"] == "ok"
    assert client.get("/api/v1/audit").status_code == 401
    token = jwt.encode({"sub": "fake", "exp": 1}, "x" * 32, algorithm="HS256")
    assert client.get("/api/v1/audit", headers={"Authorization": f"Bearer {token}"}).status_code == 401
    assert client.get("/api/v1/audit", headers=headers).status_code == 200


@pytest.mark.parametrize("text", ["狗很开心", "喝水然后跳跃", "看它玩", "狗不要喝水"])
def test_unsupported_never_silently_accepted(client, headers, text):
    r = client.post("/api/v1/tasks", headers=headers, json={"user_input": text})
    assert r.status_code == 422
    assert r.json()["supported"] is False


def test_sync_and_camera_gate(client, headers):
    _, _, body = setup_session(client, headers)
    body["sync_event"]["aligned"] = False
    assert client.post("/api/v1/sessions", headers=headers, json=body).status_code == 409
    body["sync_event"]["aligned"] = True
    body["sync_event"]["estimated_error_ms"] = 201
    assert client.post("/api/v1/sessions", headers=headers, json=body).status_code == 409
    body["sync_event"]["estimated_error_ms"] = 1
    client.post(
        "/api/v1/devices/sync",
        headers=headers,
        json={"device_id": "camera-phone", "timestamp": 1000, "camera_info": {"status": "disconnected"}},
    )
    assert client.post("/api/v1/sessions", headers=headers, json=body).status_code == 409


def test_duplicate_replay_and_missing_video(client, headers, app):
    _, session, _ = setup_session(client, headers)
    first = submit(client, headers, session)
    assert first["file_time_range"] == [4, 6]
    assert submit(client, headers, session)["candidate_id"] == first["candidate_id"]
    changed = candidate_body()
    changed["camera_recording"] = False
    r = client.post(f"/api/v1/sessions/{session['session_id']}/candidates", headers=headers, json=changed)
    assert r.status_code == 409
    changed["eventID"] = "no-video"
    missing = submit(client, headers, session, changed)
    assert missing["media_message"] == "没有对应视频"
    assert missing["file_time_range"] is None
    assert missing["captured"] is False
    with app.state.db() as db:
        assert db.scalar(select(func.count()).select_from(Job)) == 2


def test_concurrent_upload_deduplicates(client, headers, app):
    _, session, _ = setup_session(client, headers)
    url = f"/api/v1/sessions/{session['session_id']}/candidates"
    with ThreadPoolExecutor(max_workers=4) as pool:
        results = list(pool.map(lambda _: client.post(url, headers=headers, json=candidate_body()), range(8)))
    assert all(r.status_code in (201, 409) for r in results)
    assert len({r.json()["candidate_id"] for r in results if r.status_code == 201}) == 1
    with app.state.db() as db:
        assert db.scalar(select(func.count()).select_from(Candidate)) == 1
        assert db.scalar(select(func.count()).select_from(Job)) == 1


def test_privacy_validation(client, headers):
    _, session, _ = setup_session(client, headers)
    body = candidate_body()
    body["sampled_frames"] = [{"timestamp": 105, "image": base64.b64encode(b"\xff\xd8\xfftest").decode()}]
    url = f"/api/v1/sessions/{session['session_id']}/candidates"
    assert client.post(url, headers=headers, json=body).status_code == 422
    body["privacy_redacted"] = True
    assert client.post(url, headers=headers, json=body).status_code == 201
    body["eventID"] = "bad-base64"
    body["sampled_frames"][0]["image"] = "http://localhost/private"
    assert client.post(url, headers=headers, json=body).status_code == 422


def test_ownership(client, headers):
    task, session, _ = setup_session(client, headers)
    c = submit(client, headers, session)
    other = auth(client, "other-phone")
    for url in [
        f"/api/v1/tasks/{task['task_id']}",
        f"/api/v1/sessions/{session['session_id']}",
        f"/api/v1/candidates/{c['candidate_id']}",
        f"/api/v1/candidates/{c['candidate_id']}/jobs",
    ]:
        assert client.get(url, headers=other).status_code == 404
    assert client.get("/api/v1/audit", headers=other).json()["records"] == []


def test_uncertain_retained_and_disposition_audited(client, headers, app):
    _, session, _ = setup_session(client, headers)
    c = submit(client, headers, session)
    assert run_once(app.state.db, app.state.settings)
    url = f"/api/v1/candidates/{c['candidate_id']}"
    data = client.get(url, headers=headers).json()
    assert data["status"] == "needs_review"
    assert data["ui_status"] == "pending_confirmation"
    assert data["disposition"] == "undecided"
    assert not data["generated"]
    r = client.post(
        url + "/disposition",
        headers=headers,
        json={"action": "ignore", "expected_revision": data["revision"]},
    )
    assert r.status_code == 200
    assert r.json()["ui_status"] == "ignored"
    assert client.get(url, headers=headers).status_code == 200
    assert (
        client.post(
            url + "/disposition", headers=headers, json={"action": "keep", "expected_revision": 0}
        ).status_code
        == 409
    )
    assert "user_ignored" in [
        r["event_type"] for r in client.get("/api/v1/audit", headers=headers).json()["records"]
    ]


def test_feedback_replay_and_example_replacement(client, headers, app):
    task, session, _ = setup_session(client, headers)
    c = submit(client, headers, session)
    url = f"/api/v1/candidates/{c['candidate_id']}/feedback"
    body = {"request_id": "fb1", "user_verdict": "wrong", "user_comment": "遮挡"}
    first = client.post(url, headers=headers, json=body)
    assert first.status_code == 200, first.text
    assert client.post(url, headers=headers, json=body).json() == first.json()
    body["user_verdict"] = "correct"
    assert client.post(url, headers=headers, json=body).status_code == 409
    body["request_id"] = "fb2"
    assert client.post(url, headers=headers, json=body).status_code == 200
    examples = client.get(f"/api/v1/tasks/{task['task_id']}", headers=headers).json()["examples"]
    assert len(examples) == 1 and examples[0]["example_type"] == "positive"
    body.update(request_id="fb3", user_verdict="uncertain")
    client.post(url, headers=headers, json=body)
    assert client.get(f"/api/v1/tasks/{task['task_id']}", headers=headers).json()["examples"] == []
    with app.state.db() as db:
        assert db.scalar(select(func.count()).select_from(Feedback)) == 3


def test_standard_correction_versions_and_requeues(client, headers, app):
    task, session, _ = setup_session(client, headers, "狗返回主人并交接玩具")
    c = submit(client, headers, session)
    body = {
        "request_id": "standard1",
        "user_verdict": "wrong",
        "correction_type": "standard_error",
        "corrected_task": {"user_input": "狗交接玩具，放在脚边也算", "expected_version": 1},
    }
    url = f"/api/v1/candidates/{c['candidate_id']}/feedback"
    r = client.post(url, headers=headers, json=body)
    assert r.status_code == 200, r.text
    assert r.json()["new_task_version"] == 2
    assert r.json()["affected_candidates"] == 1
    assert client.get(f"/api/v1/tasks/{task['task_id']}?version=1", headers=headers).json()["version"] == 1
    assert client.get(f"/api/v1/tasks/{task['task_id']}", headers=headers).json()["version"] == 2
    assert (
        client.get(f"/api/v1/sessions/{session['session_id']}", headers=headers).json()["task_version"] == 1
    )
    body["request_id"] = "stale-standard"
    assert client.post(url, headers=headers, json=body).status_code == 409
    run_once(app.state.db, app.state.settings)
    run_once(app.state.db, app.state.settings)
    assert (
        client.get(f"/api/v1/candidates/{c['candidate_id']}", headers=headers).json()["status"]
        == "needs_review"
    )


def test_late_upload_after_stop(client, headers):
    _, session, _ = setup_session(client, headers)
    url = f"/api/v1/sessions/{session['session_id']}/stop"
    stop = {"stop_time": 1010, "video_file": {"path": "camera://video-1.mp4", "file_time_end": 10}}
    assert client.post(url, headers=headers, json=stop).status_code == 200
    assert client.post(url, headers=headers, json=stop).status_code == 200
    assert submit(client, headers, session)["captured"] is True
    body = candidate_body("late")
    body.update(preview_start_time=120, preview_end_time=122)
    assert submit(client, headers, session, body)["captured"] is False


def test_manual_retry_is_versioned(client, headers, app):
    _, session, _ = setup_session(client, headers)
    c = submit(client, headers, session)
    run_once(app.state.db, app.state.settings)
    url = f"/api/v1/candidates/{c['candidate_id']}"
    current = client.get(url, headers=headers).json()
    response = client.post(url + "/review", headers=headers, json={"expected_revision": current["revision"]})
    assert response.status_code == 202
    assert (
        client.post(
            url + "/review", headers=headers, json={"expected_revision": current["revision"]}
        ).status_code
        == 409
    )
    run_once(app.state.db, app.state.settings)
    jobs = client.get(url + "/jobs", headers=headers).json()
    assert len(jobs) == 2
    assert all(j["status"] == "completed" for j in jobs)
