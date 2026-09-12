#!/bin/bash
# Waits for the Android package service to be ready before running
# instrumented tests, then runs them.
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
# Root cause this script exists to work around in the first place:
# sys.boot_completed (what the emulator-runner action waits on) can
# report true before the package service is actually registered --
# confirmed from an earlier CI run of this same job, which failed
# installing an APK with "Can't find service: package" even though the
# emulator had already booted.

set -euo pipefail

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

./gradlew connectedDebugAndroidTest --stacktrace
