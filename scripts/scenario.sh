#!/bin/zsh
# Virtual Console(에뮬레이터 8088)을 HTTP로 조작해 아찔 상황을 재현한다.
# 사용: scripts/scenario.sh [cutin|swerve|end|all|reset]
set -e
command -v adb >/dev/null || { echo "adb가 PATH에 없습니다 (Android SDK platform-tools)"; exit 1; }

PORT=18088
B="http://127.0.0.1:$PORT"
adb forward tcp:$PORT tcp:8088 >/dev/null

# 콘솔은 브라우저 세션 heartbeat(3초 타임아웃)가 있어야 입력을 받는다
heartbeat() { while true; do curl -s -X POST "$B/browser/session?client=scenario" >/dev/null; sleep 1; done }
heartbeat & HB=$!
trap 'kill $HB 2>/dev/null; curl -s -X POST "$B/browser/session?client=scenario&closing=1" >/dev/null' EXIT
sleep 1.2

put() { curl -sf -X PUT "$B$1" >/dev/null || echo "  ! 실패: $1"; }
drive() { put "/vehicle/controls?speed=$1&accelerator=$2&brake=$3&steering=$4"; }
say() { echo "[$(date +%T)] $*"; }

reset() {
  say "초기화: P, 정지, 크루즈/와이퍼/등화 끔"
  drive 0 0 0 0
  put "/vehicle/driving-mode?ad_mode_state=not_ready"
  put "/vehicle/turn-signal?mode=Off"
  put "/vehicle/body?front_wiper=Off"
  put "/vehicle/lights?feature=headlamp_mode&value=Off"
  put "/vehicle/gear?gear=Park"
}

cutin() {
  say "시나리오 1: 고속도로 크루즈 중 끼어들기 → 급제동으로 운전자 개입"
  put "/vehicle/gear?gear=Drive"
  for v in 20 50 80 100; do drive $v 30 0 0; sleep 0.4; done
  put "/vehicle/driving-mode?target_speed_mps=27.778"
  # 에뮬레이터 매핑: full_autonomous → ACTIVATED, ready/longitudinal/lateral → ENABLED, not_ready → null
  put "/vehicle/driving-mode?ad_mode_state=full_autonomous"
  say "  크루즈 작동(ACTIVATED), 100km/h 순항"; sleep 3
  say "  앞차 끼어듦 → 브레이크 80%"
  for v in 92 84 76 70; do drive $v 0 80 0; sleep 0.25; done
  put "/vehicle/driving-mode?ad_mode_state=ready"
  sleep 1
  drive 70 0 0 0
  say "  브레이크 뗌, 70km/h 수동 주행"; sleep 2
}

swerve() {
  say "시나리오 2: 비 오는 밤, 이면도로 주정차 차량 급회피"
  put "/vehicle/gear?gear=Drive"
  put "/vehicle/body?front_wiper=Low"
  put "/vehicle/lights?feature=headlamp_mode&value=LowBeam"
  for v in 65 60 55 50 45 40; do drive $v 5 0 0; sleep 0.5; done; sleep 1.5
  say "  방향지시등 없이 핸들 급조작"
  drive 38 0 30 110; sleep 0.2
  drive 35 0 30 -40; sleep 0.3
  drive 35 10 0 0; sleep 2
}

end_trip() {
  say "시나리오 3: 도착 → 정차 → P (운행 종료 회고 트리거)"
  # 직전 시나리오의 속도에서 초당 약 10km/h로 감속 (급제동 기준 14km/h/s 미만)
  v=$(curl -s "$B/vehicle/state" | python3 -I -c 'import json,sys;print(int(json.load(sys.stdin)["controls"]["speed_kph"]))')
  while [ $v -gt 0 ]; do v=$(( v > 4 ? v - 4 : 0 )); drive $v 0 20 0; sleep 0.4; done
  drive 0 0 0 0
  put "/vehicle/gear?gear=Park"; sleep 1
}

case "${1:-all}" in
  cutin) cutin ;;
  swerve) swerve ;;
  end) end_trip ;;
  reset) reset ;;
  all) reset; sleep 1; cutin; swerve; end_trip; reset ;;
  *) echo "사용: $0 [cutin|swerve|end|all|reset]"; exit 1 ;;
esac
say "완료"
