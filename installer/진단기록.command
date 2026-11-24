#!/bin/bash
# 벨로스터 N 계기판 진단 기록 — macOS. Finder에서 더블클릭. 설치.command 를 --diag 로 실행한다(설치·변경 없음).
exec bash "$(dirname "$0")/설치.command" --diag "$@"
