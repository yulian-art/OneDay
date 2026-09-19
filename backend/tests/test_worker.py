import time
from concurrent.futures import ThreadPoolExecutor

import httpx
import pytest
from conftest import candidate_body, setup_session, submit
from sqlalchemy import select

from oneday.config import Settings
from oneday.models import Job
from oneday.schemas import ReviewResult
from oneday.vlm import VLM, ReviewFailure
from oneday.worker import run_once


class Provider:
    def __init__(self, verdict="match", visible=True, confidence=0.95):
        self.result = ReviewResult(
            verdict=verdict, key_area_visible=visible, confidence=confidence, reason="test evidence"
        )
        self.calls = 0

    def review(self, *args):
        self.calls += 1
        return self.result


@pytest.mark.parametrize(
    "verdict,visible,confidence,expected",
    [
        ("match", True, 0.95, "preliminary_match"),
        ("match", False, 0.95, "needs_review"),
        ("no_match", True, 0.2, "needs_review"),
        ("no_match", True, 0.95, "rejected"),
    ],
)
def test_evidence_state_rules(client, headers, app, verdict, visible, confidence, expected):
    _, s, _ = setup_session(client, headers)
    c = submit(client, headers, s)
    run_once(app.state.db, app.state.settings, Provider(verdict, visible, confidence))
    data = client.get(f"/api/v1/candidates/{c['candidate_id']}", headers=headers).json()
    assert data["status"] == expected
    assert data["disposition"] == "undecided"


def test_retries_exhaust_to_review_not_delete(client, headers, app):
    class Broken:
        def review(self, *args):
            raise ReviewFailure("provider_transport_error")

    _, s, _ = setup_session(client, headers)
    c = submit(client, headers, s)
    now = time.time()
    for attempt in range(3):
        assert run_once(app.state.db, app.state.settings, Broken(), now + attempt * 100)
    with app.state.db() as db:
        j = db.scalar(select(Job))
        assert j.status == "failed" and j.attempts == 3
        assert j.last_error == "provider_transport_error"
    assert (
        client.get(f"/api/v1/candidates/{c['candidate_id']}", headers=headers).json()["status"]
        == "needs_review"
    )
    assert not run_once(app.state.db, app.state.settings, Broken(), now + 1000)


def test_expired_lease_recovered_by_new_worker(client, headers, app):
    _, s, _ = setup_session(client, headers)
    c = submit(client, headers, s)
    with app.state.db.begin() as db:
        j = db.scalar(select(Job))
        j.status, j.lease_until, j.lease_token, j.attempts = "running", time.time() - 1, "dead-process", 1
    assert run_once(app.state.db, app.state.settings, Provider())
    assert (
        client.get(f"/api/v1/candidates/{c['candidate_id']}", headers=headers).json()["status"]
        == "preliminary_match"
    )


def test_late_ai_cannot_overwrite_user_correction(client, headers, app):
    _, s, _ = setup_session(client, headers)
    c = submit(client, headers, s)
    url = f"/api/v1/candidates/{c['candidate_id']}"

    class Racing(Provider):
        def review(self, *args):
            r = client.post(
                url + "/feedback",
                headers=headers,
                json={"request_id": "during-review", "user_verdict": "wrong"},
            )
            assert r.status_code == 200
            return super().review(*args)

    run_once(app.state.db, app.state.settings, Racing())
    data = client.get(url, headers=headers).json()
    assert data["status"] == "rejected"
    assert data["review_result"]["source"] == "human"
    with app.state.db() as db:
        assert db.scalar(select(Job)).status == "cancelled"


def test_two_workers_only_one_model_call(client, headers, app):
    _, s, _ = setup_session(client, headers)
    submit(client, headers, s)
    provider = Provider()
    with ThreadPoolExecutor(max_workers=2) as pool:
        list(pool.map(lambda _: run_once(app.state.db, app.state.settings, provider), range(2)))
    assert provider.calls == 1


def test_feedback_examples_reused_by_next_candidate(client, headers, app):
    _, s, _ = setup_session(client, headers)
    c = submit(client, headers, s)
    client.post(
        f"/api/v1/candidates/{c['candidate_id']}/feedback",
        headers=headers,
        json={"request_id": "example", "user_verdict": "wrong", "user_comment": "只是靠近水碗"},
    )
    submit(client, headers, s, candidate_body("event2"))

    class Capture(Provider):
        def review(self, definition, frames, examples):
            assert len(examples) == 1
            assert examples[0]["example_type"] == "negative"
            assert examples[0]["user_annotation"] == "只是靠近水碗"
            return super().review(definition, frames, examples)

    provider = Capture()
    run_once(app.state.db, app.state.settings, provider)
    run_once(app.state.db, app.state.settings, provider)
    assert provider.calls == 1


@pytest.mark.parametrize(
    "status,content,code,retry",
    [
        (429, {}, "provider_http_429", True),
        (401, {}, "provider_http_401", False),
        (200, {"choices": [{"message": {"content": "not json"}}]}, "invalid_model_response", True),
    ],
)
def test_vlm_response_validation(status, content, code, retry):
    settings = Settings(
        vlm_provider="openai_compatible",
        vlm_model="test-vision",
        vlm_api_key="test-key",
        vlm_base_url="https://model.example/v1",
        _env_file=None,
    )
    transport = httpx.MockTransport(lambda req: httpx.Response(status, json=content))
    with httpx.Client(transport=transport) as client:
        with pytest.raises(ReviewFailure) as error:
            VLM(settings, client).review(
                {}, [{"timestamp": 1, "mime_type": "image/jpeg", "image": "abc"}], []
            )
        assert error.value.code == code and error.value.retryable is retry


def test_vlm_protocol_and_fewshot():
    import json

    expected = Provider().result

    def handler(request):
        assert str(request.url) == "https://model.example/v1/chat/completions"
        assert request.headers["authorization"] == "Bearer test-key"
        body = json.loads(request.content)
        assert body["model"] == "test-vision"
        text = json.dumps(body, ensure_ascii=False)
        assert "negative" in text and "reference_label" in text and "data:image/jpeg;base64," in text
        return httpx.Response(200, json={"choices": [{"message": {"content": expected.model_dump_json()}}]})

    settings = Settings(
        vlm_provider="openai_compatible",
        vlm_model="test-vision",
        vlm_api_key="test-key",
        vlm_base_url="https://model.example/v1",
        _env_file=None,
    )
    frame = {"timestamp": 1, "mime_type": "image/jpeg", "image": "abc"}
    with httpx.Client(transport=httpx.MockTransport(handler)) as client:
        result = VLM(settings, client).review(
            {}, [frame], [{"example_type": "negative", "user_annotation": "no", "frames": [frame]}]
        )
    assert result == expected
