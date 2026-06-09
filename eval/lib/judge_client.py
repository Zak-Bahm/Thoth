"""Minimal OpenAI-compatible client for a local llama-server (the LLM-as-judge backend).

Stdlib only (urllib). Targets llama.cpp's /v1/chat/completions with `response_format` json_schema,
which llama-server converts to a GBNF grammar and enforces — so the reply is schema-valid JSON.
Qwen2.5 has no thinking mode, so grammar enforcement is clean and we run greedy (temp 0).
"""

from __future__ import annotations

import json
import urllib.error
import urllib.request
from typing import Any

# Structured verdict the judge must return. Described in the prompt too — llama.cpp does NOT
# inject the schema into the prompt, it only constrains the output.
VERDICT_SCHEMA: dict[str, Any] = {
    "type": "object",
    "additionalProperties": False,
    "properties": {
        "correctness": {
            "type": "string",
            "enum": ["correct", "partially_correct", "incorrect", "abstained"],
        },
        "faithfulness": {
            "type": "string",
            "enum": ["supported", "partial", "unsupported", "not_applicable"],
        },
        "answer_relevance": {
            "type": "string",
            "enum": ["relevant", "partial", "irrelevant"],
        },
        "rationale": {"type": "string"},
    },
    "required": ["correctness", "faithfulness", "answer_relevance", "rationale"],
}


class JudgeError(RuntimeError):
    pass


def judge(
    base_url: str,
    model: str,
    system: str,
    user: str,
    schema: dict[str, Any] = VERDICT_SCHEMA,
    seed: int = 0,
    temperature: float = 0.0,
    timeout: float = 120.0,
) -> dict[str, Any]:
    """One judging call. Returns the parsed verdict dict (schema-enforced by llama-server)."""
    payload = {
        "model": model,
        "messages": [
            {"role": "system", "content": system},
            {"role": "user", "content": user},
        ],
        "temperature": temperature,
        "seed": seed,
        "response_format": {
            "type": "json_schema",
            "json_schema": {"name": "verdict", "strict": True, "schema": schema},
        },
    }
    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        base_url.rstrip("/") + "/v1/chat/completions",
        data=data,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            body = json.loads(resp.read().decode("utf-8"))
    except urllib.error.URLError as e:
        raise JudgeError(f"judge request failed: {e}") from e
    try:
        content = body["choices"][0]["message"]["content"]
        return json.loads(content)
    except (KeyError, IndexError, json.JSONDecodeError) as e:
        raise JudgeError(f"unparseable judge response: {e}: {body}") from e


def health(base_url: str, timeout: float = 5.0) -> bool:
    try:
        with urllib.request.urlopen(base_url.rstrip("/") + "/health", timeout=timeout) as resp:
            return resp.status == 200
    except Exception:
        return False
