#!/usr/bin/env python3
"""Validate the Phase 5 validation assets.

What this checks (all offline, no device, no Android build):
  1. The evidence record JSON Schema is itself a valid JSON Schema.
  2. Every valid evidence example passes the schema.
  3. Every negative case (a mutation of a valid example) FAILS the schema, so the
     schema's privacy and consistency rules are proven to bite.
  4. Every golden fixture's stated expected values equal values computed here by an
     independent reference oracle written from the documented semantics.
  5. Document lint: no em dash or en dash characters, no URL hosts outside an allowlist
     (so no production endpoint can slip in), fixture and scenario cross references resolve.

What this does NOT do: it does not run any AERIVA Kotlin code. The oracles here are
written from the contract text, not from the Kotlin, so a mismatch between an oracle
and the Kotlin implementation is a finding to investigate, not a test that already ran.

Requires: python3 and the `jsonschema` package (pip install jsonschema).
Usage:    python3 validation/tools/validate_validation_assets.py [--skip-docs]
"""
import argparse
import copy
import json
import math
import re
import sys
from datetime import datetime
from pathlib import Path

try:
    from jsonschema import Draft202012Validator
except ImportError:
    sys.exit("jsonschema is required: pip install jsonschema")

VALIDATION_DIR = Path(__file__).resolve().parents[1]
REPO_ROOT = VALIDATION_DIR.parent
FIXTURES = VALIDATION_DIR / "fixtures"
SCHEMA_PATH = VALIDATION_DIR / "schema" / "evidence-record.schema.json"
EXAMPLES = FIXTURES / "evidence-examples" / "valid"
DOC_FILES = [
    "PHASE_5_MEASUREMENT_VALIDATION_HARNESS.md",
    "PHASE_5_DEVICE_TEST_MATRIX.md",
    "PHASE_5_TEST_DATA_AND_EVIDENCE_SCHEMA.md",
]
ALLOWED_DOC_HOSTS = {
    "developer.android.com", "source.android.com", "kotlinlang.org", "github.com",
    "square.github.io", "datatracker.ietf.org", "www.rfc-editor.org", "json-schema.org",
}

failures = []
checks = 0


def ok(cond, message):
    global checks
    checks += 1
    if not cond:
        failures.append(message)


def load(path):
    with open(path, encoding="utf-8") as handle:
        return json.load(handle)


# ---------------------------------------------------------------- reference oracles

def latency_oracle(samples):
    values = [s["v"] for s in samples if "v" in s and s["v"] >= 0 and math.isfinite(s["v"])]
    if not values:
        return {"result": "INSUFFICIENT_EVIDENCE"}
    average = sum(values) / len(values)
    consistent = len(values) < 2 or (max(values) - min(values)) <= average * 0.5
    return {"result": "AGGREGATED", "valid_count": len(values), "average": average,
            "min": min(values), "max": max(values), "confidence": confidence_of(len(values), consistent)}


def confidence_of(sample_count, consistent):
    if sample_count < 3:
        return "Insufficient"
    if sample_count < 10:
        return "Low"
    return "High" if consistent else "Medium"


def jitter_oracle(samples):
    times = [s["t"] for s in samples]
    if any(times[i] < times[i - 1] for i in range(1, len(times))):
        return {"result": "NULL"}
    previous = None
    differences, used = [], set()
    for index, sample in enumerate(samples):
        if "v" not in sample:
            previous = None
            continue
        if previous is not None:
            p_index, p = previous
            same_method = p.get("method", "https-h1-warm-exchange") == sample.get("method", "https-h1-warm-exchange")
            both_warm = not p.get("cold", False) and not sample.get("cold", False)
            same_net = p.get("net", 1) == sample.get("net", 1)
            if same_method and both_warm and same_net:
                differences.append(abs(sample["v"] - p["v"]))
                used.update([p_index, index])
        previous = (index, sample)
    if not differences:
        return {"result": "NULL"}
    values = [samples[i]["v"] for i in sorted(used)]
    return {"result": "DERIVED", "pair_count": len(differences), "sample_count": len(used),
            "mean_abs_ipdv": sum(differences) / len(differences), "pdv_range": max(values) - min(values)}


def udp_oracle(fx):
    sent, interval, window = fx["probes_sent"], fx["interval_ms"], fx["window_ms"]
    arrivals = [r["arrival_ms"] for r in fx["replies"]]
    ok(all(arrivals[i] >= arrivals[i - 1] for i in range(1, len(arrivals))),
       f"{fx['id']}: replies must be listed in arrival order")
    decided, answered, late, dup, ooo, ignored = {}, 0, 0, 0, 0, 0
    highest_answered = -1
    for reply in fx["replies"]:
        seq = reply["seq"]
        if seq < 0 or seq >= sent:
            ignored += 1
            continue
        if seq in decided:
            dup += 1
            continue
        delay = reply["arrival_ms"] - seq * interval
        if delay <= window:
            decided[seq] = "answered"
            answered += 1
            if seq < highest_answered:
                ooo += 1
            highest_answered = max(highest_answered, seq)
        else:
            decided[seq] = "late"
            late += 1
    return {"result": "SUCCEEDED" if answered > 0 else "FAILED_NO_RESPONSE",
            "answered_in_window": answered, "late": late, "duplicates": dup,
            "out_of_order": ooo, "ignored": ignored, "unanswered": sent - answered}


def throughput_oracle(fx):
    if fx["bytes_transferred"] == 0:
        return {"result": "FAILED", "failure": "Timeout", "stage": "RESPONSE"}
    out = {"result": "SUCCEEDED",
           "bits_per_second": fx["bytes_transferred"] * 8 / (fx["transfer_ms"] / 1000)}
    if "intervals" in fx:
        ok(sum(i["bytes"] for i in fx["intervals"]) == fx["bytes_transferred"],
           f"{fx['id']}: interval bytes must sum to bytes_transferred")
        rest = fx["intervals"][1:]
        remaining_ms = fx["transfer_ms"] - fx["interval_ms"]
        out["ramp_excluded_bits_per_second"] = sum(i["bytes"] for i in rest) * 8 / (remaining_ms / 1000)
    return out


def dns_oracle(fx):
    if "pair" in fx:
        return {"tier": "ESTIMATION", "relation": "HIT_FASTER" if fx["pair"]["hit_ms"] < fx["pair"]["miss_ms"] else "MISS_FASTER_OR_EQUAL"}
    outcome = fx["lookup"]["outcome"]
    if outcome == "RESOLVED":
        return {"tier": "ESTIMATION", "record": "EVIDENCE_SAMPLE", "failure": None}
    return {"tier": "ESTIMATION", "record": "FAILURE", "failure": {"type": "DnsFailure", "kind": outcome}}


INTERCEPTION_TLS_KINDS = {"HOSTNAME_MISMATCH", "CERTIFICATE_INVALID"}


def https_oracle(fx):
    l1 = fx["l1"]
    flag = fx["portal_flag_start"] or fx["portal_flag_end"]
    kind_type = l1["type"]
    signal = False
    if kind_type == "Success":
        if l1["nonce_ok"] and not l1.get("malformed"):
            return {"l2": "Succeeded", "state": "AVAILABLE"}
        base = {"l2": "InvalidResponse", "kind": "MALFORMED" if l1.get("malformed") else "NONCE_MISMATCH", "state": "UNAVAILABLE"}
        signal = True
    elif kind_type == "DnsFailed":
        return {"l2": "DnsFailure", "kind": l1["kind"], "state": "DNS_FAILURE"}
    elif kind_type == "ConnectFailed":
        return {"l2": "EndpointFailure", "kind": l1["kind"], "state": "UNAVAILABLE"}
    elif kind_type == "TimedOut":
        return {"l2": "Timeout", "stage": l1["stage"], "state": "TIMEOUT"}
    elif kind_type == "TlsFailed":
        base = {"l2": "TlsFailure", "kind": l1["kind"], "state": "TLS_FAILURE"}
        signal = l1["kind"] in INTERCEPTION_TLS_KINDS
    elif kind_type == "HttpUnexpected":
        if l1["redirect"]:
            base = {"l2": "UnexpectedRedirect", "state": "UNAVAILABLE"}
            signal = True
        else:
            base = {"l2": "InvalidResponse", "kind": "UNEXPECTED_STATUS", "state": "UNAVAILABLE"}
    elif kind_type == "ResponseTooLarge":
        return {"l2": "InvalidResponse", "kind": "TOO_LARGE", "state": "UNAVAILABLE"}
    elif kind_type == "NetworkChangedMidCall":
        return {"l2": "NetworkChangedDuringMeasurement", "state": "UNKNOWN"}
    elif kind_type == "Blocked":
        return {"l2": "BlockedByDevicePolicy", "state": "UNKNOWN"}
    elif kind_type == "Unclassified":
        return {"l2": "Unclassified", "state": "UNKNOWN", "defect_signal": True}
    else:
        raise ValueError(f"unknown l1 type {kind_type}")
    if signal and flag:
        return {"l2": "CaptivePortalSuspected", "state": "CAPTIVE_PORTAL"}
    return base


def parse_time(text):
    return datetime.fromisoformat(text.replace("Z", "+00:00"))


# ---------------------------------------------------------------- checks

def close(a, b):
    return math.isclose(a, b, rel_tol=1e-9, abs_tol=1e-9)


def compare(fx_id, label, expected, actual):
    for key, want in expected.items():
        if key in ("reason", "known_implementation_divergence"):
            continue
        got = actual.get(key)
        if isinstance(want, float) or isinstance(got, float):
            ok(got is not None and close(float(want), float(got)),
               f"{fx_id} {label}.{key}: expected {want}, oracle {got}")
        else:
            ok(want == got, f"{fx_id} {label}.{key}: expected {want!r}, oracle {got!r}")


def check_latency():
    data = load(FIXTURES / "latency-series.json")
    for fx in data["fixtures"]:
        expected = fx["expected"]
        if expected["latency"]["result"] != "NOT_ASSERTED":
            compare(fx["id"], "latency", expected["latency"], latency_oracle(fx["samples"]))
        compare(fx["id"], "jitter", expected["jitter"], jitter_oracle(fx["samples"]))
        divergence = fx.get("known_implementation_divergence")
        if divergence:
            print(f"  note {fx['id']}: {divergence['status']} against {divergence['impl_ref']}")
    return [f["id"] for f in data["fixtures"]]


def check_simple(name, oracle):
    data = load(FIXTURES / f"{name}.json")
    for fx in data["fixtures"]:
        compare(fx["id"], "expected", fx["expected"], oracle(fx))
    return [f["id"] for f in data["fixtures"]]


def check_freshness_confidence():
    data = load(FIXTURES / "freshness-confidence.json")
    ids = []
    for fx in data["freshness"]:
        produced, now = parse_time(fx["produced_at"]), parse_time(fx["now"])
        until = parse_time(fx["valid_until"]) if fx["valid_until"] else None
        stale = until is not None and now > until
        age_ms = round((now - produced).total_seconds() * 1000)
        ok(stale == fx["expected"]["stale"], f"{fx['id']}: stale expected {fx['expected']['stale']}, oracle {stale}")
        ok(age_ms == fx["expected"]["age_ms"], f"{fx['id']}: age_ms expected {fx['expected']['age_ms']}, oracle {age_ms}")
        ids.append(fx["id"])
    for fx in data["latency_confidence"]:
        got = confidence_of(fx["sample_count"], fx["consistent"])
        ok(got == fx["expected"], f"{fx['id']}: expected {fx['expected']}, oracle {got}")
        ids.append(fx["id"])
    return ids


def negative_cases():
    """(case id, source example prefix, mutation). Every case must FAIL the schema."""
    def m_ssid(d): d["network"]["ssid"] = "SomeNetwork"
    def m_url(d): d["endpoint"]["endpoint_ref"] = "example.test/path"
    def m_pass_not_executed(d): d["verdict"]["result"] = "PASS"
    def m_emulator_physical_layer(d): d["layer"] = "L4"
    def m_bugreport(d): d["logs"].append({"kind": "OTHER", "ref": "logs/x.txt", "sha256": "0" * 64, "redaction_reviewed": True, "contains_bugreport": True})
    def m_cancelled_with_samples(d): d["measurement"]["outcome_kind"] = "CANCELLED_NO_RESULT"
    def m_synthetic_false_fixture_id(d): d["synthetic"] = False
    def m_failed_without_failure(d): d["measurement"]["outcome_kind"] = "MEASURED_FAILED"
    def m_short_commit(d): d["build"]["git_commit"] = "abc123"
    def m_gps(d): d["gps"] = {"lat": 0, "lon": 0}
    def m_pme_role(d): d["endpoint"]["role"] = "PME"
    def m_abs_log_path(d): d["logs"].append({"kind": "OTHER", "ref": "/sdcard/x.txt", "sha256": "0" * 64, "redaction_reviewed": True, "contains_bugreport": False})
    def m_missing_start(d): del d["execution"]["started_at"]
    def m_phone_number(d): d["network"]["carrier_label"] = "x" * 40
    def m_dotdot_log(d): d["logs"].append({"kind": "OTHER", "ref": "logs/../x.txt", "sha256": "0" * 64, "redaction_reviewed": True, "contains_bugreport": False})
    return [
        ("N01-ssid-field", "FIXTURE-EV-001", m_ssid),
        ("N02-endpoint-url", "FIXTURE-EV-001", m_url),
        ("N03-pass-without-execution", "FIXTURE-EV-001", m_pass_not_executed),
        ("N04-emulator-on-physical-layer", "FIXTURE-EV-003", m_emulator_physical_layer),
        ("N05-bugreport-attachment", "FIXTURE-EV-001", m_bugreport),
        ("N06-cancelled-with-samples", "FIXTURE-EV-003", m_cancelled_with_samples),
        ("N07-fixture-id-on-real-record", "FIXTURE-EV-001", m_synthetic_false_fixture_id),
        ("N08-failed-without-failure", "FIXTURE-EV-003", m_failed_without_failure),
        ("N09-short-git-commit", "FIXTURE-EV-001", m_short_commit),
        ("N10-location-field", "FIXTURE-EV-001", m_gps),
        ("N11-production-endpoint-role", "FIXTURE-EV-001", m_pme_role),
        ("N12-absolute-log-path", "FIXTURE-EV-001", m_abs_log_path),
        ("N13-executed-without-start", "FIXTURE-EV-003", m_missing_start),
        ("N14-overlong-carrier-label", "FIXTURE-EV-001", m_phone_number),
        ("N15-parent-dir-log-path", "FIXTURE-EV-001", m_dotdot_log),
    ]


def check_schema():
    schema = load(SCHEMA_PATH)
    Draft202012Validator.check_schema(schema)
    ok(True, "schema is valid")
    validator = Draft202012Validator(schema)
    examples = {}
    for path in sorted(EXAMPLES.glob("*.json")):
        doc = load(path)
        errors = list(validator.iter_errors(doc))
        ok(not errors, f"{path.name}: valid example rejected: {[e.message for e in errors][:2]}")
        examples[doc["test_id"]] = doc
    for case_id, source, mutate in negative_cases():
        doc = copy.deepcopy(examples[source])
        mutate(doc)
        errors = list(validator.iter_errors(doc))
        ok(bool(errors), f"{case_id}: mutation was ACCEPTED by the schema but must be rejected")
    return len(examples), len(negative_cases())


DASHES = ("\u2014", "\u2013")
URL_RE = re.compile(r"https?://([A-Za-z0-9.-]+)")


def lint_text(path, allow_hosts, forbid_urls):
    text = path.read_text(encoding="utf-8")
    ok(not any(d in text for d in DASHES), f"{path.relative_to(REPO_ROOT)}: contains an em or en dash")
    for host in URL_RE.findall(text):
        if forbid_urls and host not in {"json-schema.org"}:
            ok(False, f"{path.relative_to(REPO_ROOT)}: URL host {host} not allowed in data files")
        elif not forbid_urls:
            ok(host in allow_hosts, f"{path.relative_to(REPO_ROOT)}: URL host {host} is not on the allowlist")
    return text


def check_docs_and_lint(all_fixture_ids, skip_docs):
    for path in sorted(VALIDATION_DIR.rglob("*")):
        if path.is_file() and path.suffix in {".json", ".py", ".md"} and "__pycache__" not in path.parts:
            lint_text(path, ALLOWED_DOC_HOSTS, forbid_urls=path.suffix == ".json")
    if skip_docs:
        return
    texts = {}
    for name in DOC_FILES:
        path = REPO_ROOT / name
        ok(path.exists(), f"missing document {name}")
        if path.exists():
            texts[name] = lint_text(path, ALLOWED_DOC_HOSTS, forbid_urls=False)
    joined = "\n".join(texts.values())
    referenced = set(re.findall(r"FIX-[A-Z]+-\d{3}", joined))
    for fid in referenced:
        ok(fid in all_fixture_ids, f"documents reference unknown fixture {fid}")
    for fid in all_fixture_ids:
        ok(fid in referenced, f"fixture {fid} is not described in any Phase 5 document")
    harness = texts.get("PHASE_5_MEASUREMENT_VALIDATION_HARNESS.md", "")
    for letter in "ABCDEFGHIJKLM":
        ok(re.search(rf"^\| NC-{letter}\b", harness, re.M) is not None, f"scenario NC-{letter} has no table row in the harness document")
    matrix = texts.get("PHASE_5_DEVICE_TEST_MATRIX.md", "")
    defined_dm = set(re.findall(r"^\| (DM-[A-Z0-9-]+) \|", matrix, re.M))
    for ref in set(re.findall(r"\bDM-[A-Z0-9-]+\b", joined)):
        ok(ref in defined_dm, f"reference to undefined matrix row {ref}")
    for ref in set(re.findall(r"\bNC-[A-Z]\b", joined)):
        ok(ref[-1] in "ABCDEFGHIJKLM", f"reference to undefined scenario {ref}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--skip-docs", action="store_true", help="skip the Phase 5 document cross-reference checks")
    args = parser.parse_args()

    ids = []
    ids += check_latency()
    ids += check_simple("udp-trains", udp_oracle)
    ids += check_simple("throughput", throughput_oracle)
    ids += check_simple("dns-outcomes", dns_oracle)
    ids += check_simple("https-outcomes", https_oracle)
    ids += check_freshness_confidence()
    examples, negatives = check_schema()
    check_docs_and_lint(set(ids), args.skip_docs)

    print(f"fixtures checked: {len(ids)}; valid examples: {examples}; negative schema cases: {negatives}; assertions: {checks}")
    if failures:
        print(f"FAILED: {len(failures)} problem(s)")
        for message in failures:
            print(f"  - {message}")
        sys.exit(1)
    print("OK: all assertions passed")


if __name__ == "__main__":
    main()
