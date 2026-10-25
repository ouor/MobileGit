#!/bin/sh
# QA helper: delete a repo through the app UI (open detail via notification extras -> 레포 삭제 -> type name -> 삭제).
# Usage: delete_repo.sh <name>
D="adb -s d418f2d1 shell"; H="$(dirname "$0")/ui.py"
$D am start -n com.ouor.mobilegit/.app.MainActivity --es com.ouor.mobilegit.TARGET repo --es com.ouor.mobilegit.REPO "$1" >/dev/null 2>&1; sleep 2
for i in 1 2 3 4; do $D input swipe 540 2000 540 600 200; done; sleep 1
python "$H" tap "레포 삭제" >/dev/null; sleep 1
python "$H" tap "=$1" 1 >/dev/null; $D input text "$1"; sleep 0.5
python "$H" tap "=삭제" >/dev/null; sleep 2
python "$H" dump | grep -q "삭제했어요" && echo "deleted $1" || echo "FAILED $1"
