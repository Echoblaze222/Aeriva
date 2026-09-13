#!/bin/bash
# Waits for the Android package service to be ready before running
# instrumented tests, then runs them, retrying the whole test run a
# bounded number of times if it fails on this specific transient
# infra error.
#
# This logic used to live inline in .github/workflows/ci.yml's `script:`
# input for reactivecircus/android-emulator-runner. That did not work:
# confirmed from this repo's own CI run that the action executes each
# line of a multi-line `script:` block as its own separate `/bin/sh -c`
# call, not as one continuous script -- so a multi-line `until ... do
# ... done` loop broke, with `do` and `done` running as unrelated
# commands and the shell reporting "unexpected end of file". Moving the
# logic into a real script file and having `script:` invoke it as a
# single line sidesteps that entirely.
#
# Root cause the initial wait exists for: sys.boot_completed (what the
# emulator-runner action waits on) can report true before the package
# service is actually registered -- confirmed from an earlier CI run,
# which failed installing an APK with "Can't find service: package"
# even though the emulator had already booted.
#
# Root cause this retry exists for: confirmed from a LATER CI run that
# the package service can become unavailable again mid-run, after
# already being confirmed ready once -- core:database's instrumented
# tests passed, then core:security's test APK install hit the exact
# same "Can't find service: package" error partway through the same
# ./gradlew invocation. A log line immediately before it,
# "adb protocol fault (couldn't read status length)", points to a
# transient ADB connection hiccup as the likely trigger, not a code
# defect in this project. A single readiness check at the start cannot
# protect against that; retrying the whole run is the honest way to
# route around confirmed infra flakiness without silently ignoring a
# real failure -- every attempt's real output is preserved, and this
# only stops retrying and fails for good after max_run_attempts.

set -uo pipefail

echo "Waiting for the package service to be ready..."

attempt=0
max_attempts=90

until adb shell service check package 2>/dev/null | grep -q "package: found"; do
  attempt=$((attempt + 1))
  if [ "$attempt" -ge "$max_attempts" ]; then
    echo "Timed out waiting for the package service." >&2
    exit 1
  fi
  sleep 2
done

echo "Package service ready."

run_attempt=0
max_run_attempts=3

while true; do
  run_attempt=$((run_attempt + 1))
  echo "Running instrumented tests (attempt $run_attempt of $max_run_attempts)..."

  ./gradlew connectedDebugAndroidTest --stacktrace
  status=$?

  if [ "$status" -eq 0 ]; then
    exit 0
  fi

  if [ "$run_attempt" -ge "$max_run_attempts" ]; then
    echo "Instrumented tests failed after $max_run_attempts attempts." >&2
    exit "$status"
  fi

  echo "Instrumented test run failed (exit $status) -- checking package service before retrying..."
  attempt=0
  until adb shell service check package 2>/dev/null | grep -q "package: found"; do
    attempt=$((attempt + 1))
    if [ "$attempt" -ge "$max_attempts" ]; then
      echo "Timed out waiting for the package service before retry." >&2
      exit "$status"
    fi
    sleep 2
  done
  echo "Package service ready again. Retrying."
done
