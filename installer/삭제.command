#!/bin/bash
# 벨로스터 N 계기판 삭제 — macOS. Finder에서 더블클릭. 설치.command 를 --uninstall 로 실행한다.
exec bash "$(dirname "$0")/설치.command" --uninstall "$@"
