#!/usr/bin/env python3
"""Proves the milestone-1 promise: the official OpenAI SDK works against this gateway with
nothing changed but ``base_url``.

This is deliberately written with the real ``openai`` package rather than raw HTTP. A handcrafted
curl can accidentally match a shape the SDK would reject — only the SDK proves SDK compatibility.

    pip install openai
    python scripts/verify-openai-sdk.py [--base-url http://localhost:8080/v1] [--model mock-fast]
"""

import argparse
import sys

import openai
from openai import OpenAI


def check(label: str, condition: bool, detail: str = "") -> bool:
    mark = "PASS" if condition else "FAIL"
    print(f"  [{mark}] {label}" + (f" — {detail}" if detail else ""))
    return condition


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://localhost:8080/v1")
    parser.add_argument("--model", default="mock-fast")
    args = parser.parse_args()

    # The only two lines that differ from talking to OpenAI itself. The key is required by the
    # SDK's constructor but not yet checked by the gateway — API keys arrive in M3.
    client = OpenAI(base_url=args.base_url, api_key="not-required-until-m3")

    print(f"openai SDK {openai.__version__} -> {args.base_url} (model: {args.model})\n")
    results = []

    print("non-streaming completion:")
    completion = client.chat.completions.create(
        model=args.model,
        messages=[
            {"role": "system", "content": "You are terse."},
            {"role": "user", "content": "What is an API gateway?"},
        ],
        max_tokens=64,
    )
    results.append(check("SDK parsed the response without error", True))
    results.append(check("object type is chat.completion", completion.object == "chat.completion", completion.object))
    results.append(check("id is present", bool(completion.id), completion.id))
    results.append(check("exactly one choice", len(completion.choices) == 1))
    results.append(check("role is assistant", completion.choices[0].message.role == "assistant"))
    results.append(check("content is non-empty", bool(completion.choices[0].message.content)))
    results.append(check("finish_reason is set", bool(completion.choices[0].finish_reason)))
    results.append(
        check(
            "usage adds up",
            completion.usage.total_tokens
            == completion.usage.prompt_tokens + completion.usage.completion_tokens,
            f"{completion.usage.prompt_tokens} + {completion.usage.completion_tokens} "
            f"= {completion.usage.total_tokens}",
        )
    )

    print("\ntyped error handling:")
    try:
        client.chat.completions.create(
            model="model-that-does-not-exist",
            messages=[{"role": "user", "content": "hi"}],
        )
        results.append(check("unknown model raises NotFoundError", False, "no exception raised"))
    except openai.NotFoundError as error:
        # The SDK only raises a *typed* error if the error envelope matched OpenAI's schema.
        # A generic APIStatusError here would mean our error body is wrong.
        results.append(check("unknown model raises NotFoundError", True, str(error.status_code)))

    try:
        client.chat.completions.create(model=args.model, messages=[])
        results.append(check("empty messages raises BadRequestError", False, "no exception raised"))
    except openai.BadRequestError as error:
        results.append(check("empty messages raises BadRequestError", True, str(error.status_code)))

    print("\nstreaming (expected to be refused until M2):")
    try:
        stream = client.chat.completions.create(
            model=args.model,
            messages=[{"role": "user", "content": "hi"}],
            stream=True,
        )
        for _ in stream:
            pass
        results.append(check("stream=true is refused explicitly", False, "stream succeeded unexpectedly"))
    except openai.BadRequestError as error:
        results.append(check("stream=true is refused explicitly", True, f"HTTP {error.status_code}"))

    passed = sum(1 for r in results if r)
    print(f"\n{passed}/{len(results)} checks passed")
    return 0 if passed == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())
