"""Exercise the running API without a phone, camera, or paid model. Worker must be running."""

import argparse
import time
import uuid

import httpx


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:8000")
    args = parser.parse_args()
    device = "demo-" + uuid.uuid4().hex[:10]
    with httpx.Client(base_url=args.base_url, timeout=15) as client:

        def call(path, body):
            r = client.post("/api/v1" + path, json=body)
            r.raise_for_status()
            return r.json()

        token = call("/auth/login", {"device_id": device})
        client.headers["Authorization"] = "Bearer " + token["access_token"]
        call(
            "/devices/sync",
            {
                "device_id": device,
                "timestamp": time.time(),
                "camera_info": {"status": "connected", "model": "demo-no-hardware"},
            },
        )
        task = call("/tasks", {"user_input": "狗喝水"})
        session = call(
            "/sessions",
            {
                "task_id": task["task_id"],
                "task_version": task["version"],
                "device_id": device,
                "camera_recording_id": "demo",
                "sync_event": {
                    "user_time": time.time(),
                    "preview_time": 100,
                    "aligned": True,
                    "estimated_error_ms": 10,
                },
            },
        )
        body = {
            "eventID": uuid.uuid4().hex,
            "trigger": "manual_marker",
            "preview_start_time": 104,
            "preview_end_time": 106,
            "camera_recording": False,
            "sampled_frames": [],
        }
        path = f"/sessions/{session['session_id']}/candidates"
        c = call(path, body)
        assert call(path, body)["candidate_id"] == c["candidate_id"]
        for _ in range(20):
            response = client.get(f"/api/v1/candidates/{c['candidate_id']}")
            response.raise_for_status()
            current = response.json()
            if current["status"] != "pending_review":
                break
            time.sleep(0.5)
        assert current["status"] == "needs_review", "Start python -m oneday.worker in a second terminal"
        result = call(
            f"/candidates/{c['candidate_id']}/feedback",
            {
                "request_id": uuid.uuid4().hex,
                "user_verdict": "wrong",
                "user_comment": "Demo negative example",
            },
        )
        assert result["action_taken"] == "added_negative_example"
        print("PASS: API -> eventID replay -> persistent worker -> needs_review -> feedback example")
        print("Candidate:", c["candidate_id"])
        print("Media: missing (demo intentionally provides no camera footage)")
        print("Model: no real VLM evaluation is claimed by this demo")


if __name__ == "__main__":
    main()
