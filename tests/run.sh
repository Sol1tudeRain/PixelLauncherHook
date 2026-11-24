#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
out=$(mktemp -d)
trap 'rm -rf "$out"' EXIT
javac -d "$out" app/src/main/java/dev/local/launcherdoubletap/TapTracker.java tests/TapTrackerTest.java
java -cp "$out" dev.local.launcherdoubletap.TapTrackerTest
