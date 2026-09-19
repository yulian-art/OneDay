import base64
import binascii
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator


class Input(BaseModel):
    model_config = ConfigDict(extra="forbid", allow_inf_nan=False, str_strip_whitespace=True)


class LoginInput(Input):
    device_id: str = Field(min_length=1, max_length=128)
    device_name: str = Field(default="", max_length=128)
    app_version: str = Field(default="", max_length=40)


class DeviceInput(Input):
    device_id: str = Field(min_length=1, max_length=128)
    camera_info: dict = Field(default_factory=dict)
    clip_info: dict = Field(default_factory=dict)
    timestamp: float = Field(ge=0)


class TaskInput(Input):
    user_input: str = Field(min_length=2, max_length=1500)
    target_subjects: dict = Field(default_factory=dict)
    interaction_direction: dict = Field(default_factory=dict)


class TaskUpdate(TaskInput):
    expected_version: int = Field(ge=1)


class SyncEvent(Input):
    user_time: float = Field(ge=0)
    preview_time: float = Field(ge=0)
    file_time: float = Field(0, ge=0)
    button_time: float | None = None
    aligned: bool
    estimated_error_ms: float = Field(ge=0)


class SessionInput(Input):
    task_id: str
    task_version: int = Field(ge=1)
    device_id: str
    camera_recording_id: str = Field(min_length=1, max_length=128)
    sync_event: SyncEvent
    privacy_pet_only: bool = True


class VideoFile(Input):
    path: str = Field(min_length=1, max_length=1024)
    file_time_start: float = Field(0, ge=0)
    file_time_end: float = Field(ge=0)

    @model_validator(mode="after")
    def ordered(self):
        if self.file_time_end < self.file_time_start:
            raise ValueError("Video time range is reversed")
        return self


class StopInput(Input):
    stop_time: float = Field(ge=0)
    video_file: VideoFile | None = None


class Frame(Input):
    timestamp: float = Field(ge=0)
    image: str = Field(min_length=8, max_length=262144)
    mime_type: Literal["image/jpeg", "image/png"] = "image/jpeg"
    detections: list[dict] = Field(default_factory=list, max_length=30)

    @model_validator(mode="after")
    def image_valid(self):
        try:
            decoded = base64.b64decode(self.image, validate=True)
        except (ValueError, binascii.Error) as exc:
            raise ValueError("Frame must contain valid base64") from exc
        magic = b"\xff\xd8\xff" if self.mime_type == "image/jpeg" else b"\x89PNG\r\n\x1a\n"
        if not decoded.startswith(magic):
            raise ValueError("Frame signature does not match mime_type")
        return self


class CandidateInput(Input):
    event_id: str = Field(min_length=1, max_length=128, alias="eventID")
    trigger: str = Field(min_length=1, max_length=80)
    preview_start_time: float = Field(ge=0)
    preview_end_time: float = Field(ge=0)
    sampled_frames: list[Frame] = Field(default_factory=list, max_length=8)
    metadata: dict = Field(default_factory=dict)
    camera_recording: bool
    video_file: str | None = Field(None, max_length=1024)
    # Attestation from the Android redaction pipeline; backend does not perform face redaction.
    privacy_redacted: bool = False

    @model_validator(mode="after")
    def ordered(self):
        if self.preview_end_time < self.preview_start_time:
            raise ValueError("Candidate time range is reversed")
        if any(
            not self.preview_start_time <= f.timestamp <= self.preview_end_time for f in self.sampled_frames
        ):
            raise ValueError("Frame timestamp is outside candidate range")
        return self


class CorrectedTask(Input):
    user_input: str = Field(min_length=2, max_length=1500)
    expected_version: int = Field(ge=1)
    added_exclusions: list[str] = Field(default_factory=list, max_length=20)
    removed_exclusions: list[str] = Field(default_factory=list, max_length=20)

    @field_validator("added_exclusions", "removed_exclusions")
    @classmethod
    def bounded_exclusions(cls, value):
        if any(not item.strip() or len(item) > 200 for item in value):
            raise ValueError("Exclusions must be nonempty and at most 200 characters")
        return value


class FeedbackInput(Input):
    request_id: str = Field(min_length=1, max_length=128)
    user_verdict: Literal["correct", "wrong", "uncertain"]
    correction_type: Literal["judgment_error", "standard_error"] = "judgment_error"
    user_comment: str = Field(default="", max_length=1500)
    corrected_task: CorrectedTask | None = None

    @model_validator(mode="after")
    def correction(self):
        if (self.correction_type == "standard_error") != (self.corrected_task is not None):
            raise ValueError("corrected_task is required only for standard_error")
        return self


class DispositionInput(Input):
    action: Literal["keep", "ignore"]
    expected_revision: int = Field(ge=0)


class RetryInput(Input):
    expected_revision: int = Field(ge=0)


class ReviewResult(Input):
    verdict: Literal["match", "no_match", "uncertain"]
    confidence: float = Field(ge=0, le=1)
    reason: str = Field(min_length=1, max_length=2000)
    key_area_visible: bool
    stages_detected: list[str] = Field(default_factory=list, max_length=20)
    exclusions_triggered: list[str] = Field(default_factory=list, max_length=20)
