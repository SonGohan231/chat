#!/usr/bin/env bash
set +e
gradle :free:connectedDebugAndroidTest --stacktrace
test_result=$?
adb pull /sdcard/Download/questgpt-free/ screenshots-free/
exit "$test_result"
