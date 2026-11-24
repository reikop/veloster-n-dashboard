#!/bin/bash
# 벨로스터 N 계기판 삭제 — 리눅스. install.sh 를 --uninstall 로 실행한다(두 앱 삭제).
exec bash "$(dirname "$0")/install.sh" --uninstall "$@"
