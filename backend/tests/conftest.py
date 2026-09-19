from pathlib import Path

import pytest
from alembic import command
from alembic.config import Config
from fastapi.testclient import TestClient

from oneday.config import Settings
from oneday.main import create_app


def migrate(url, revision="head"):
    config = Config(str(Path(__file__).parents[1] / "alembic.ini"))
    config.attributes["database_url"] = url
    command.upgrade(config, revision)
    return config


@pytest.fixture
def app(tmp_path):
    url = f"sqlite:///{tmp_path / 'test.db'}"
    migrate(url)
    app = create_app(Settings(database_url=url, _env_file=None))
    yield app
    app.state.engine.dispose()


@pytest.fixture
def client(app):
    with TestClient(app) as client:
        yield client


def auth(client, device="test-phone"):
    response = client.post("/api/v1/auth/login", json={"device_id": device})
    assert response.status_code == 200, response.text
    return {"Authorization": "Bearer " + response.json()["access_token"]}


@pytest.fixture
def headers(client):
    return auth(client)


def setup_session(client, headers, task_text="狗喝水"):
    response = client.post(
        "/api/v1/devices/sync",
        headers=headers,
        json={"device_id": "camera-phone", "timestamp": 1000, "camera_info": {"status": "connected"}},
    )
    assert response.status_code == 200, response.text
    response = client.post("/api/v1/tasks", headers=headers, json={"user_input": task_text})
    assert response.status_code == 201, response.text
    task = response.json()
    body = {
        "task_id": task["task_id"],
        "task_version": 1,
        "device_id": "camera-phone",
        "camera_recording_id": "rec-1",
        "sync_event": {
            "user_time": 1000,
            "preview_time": 100,
            "file_time": 0,
            "aligned": True,
            "estimated_error_ms": 20,
        },
    }
    response = client.post("/api/v1/sessions", headers=headers, json=body)
    assert response.status_code == 201, response.text
    return task, response.json(), body


def candidate_body(event="event-1"):
    return {
        "eventID": event,
        "trigger": "periodic_sample",
        "preview_start_time": 104,
        "preview_end_time": 106,
        "sampled_frames": [],
        "camera_recording": True,
        "video_file": "camera://video-1.mp4",
    }


def submit(client, headers, session, body=None):
    r = client.post(
        f"/api/v1/sessions/{session['session_id']}/candidates", headers=headers, json=body or candidate_body()
    )
    assert r.status_code == 201, r.text
    return r.json()
