#!/usr/bin/env bash
# Preserve the test exit code while collecting screenshots, including on failures.
set +e
gradle :reborn:connectedDebugAndroidTest --stacktrace
test_result=$?
adb pull /sdcard/Download/questgpt2/ screenshots/
exit "$test_result"
