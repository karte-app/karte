#!/usr/bin/env python3
"""카르테 시스템 테스트 (Pleos Connect 에뮬레이터, release APK 대상)

Virtual Console(HTTP)로 차량 상태를 바꾸고, adb로 화면을 누르고, 화면에 보이는 글자를
uiautomator로 읽어 기대한 화면이 나왔는지 확인합니다. 사람이 말해야 하는 음성 인식은 포함하지 않습니다.

준비
  1. Pleos Connect 에뮬레이터 실행, adb 연결
  2. adb forward tcp:8088 tcp:8088   (Virtual Console)
  3. 앱 권한 허용 (README의 "실행 방법" 참고)
실행
  python3 tests/system/system_test.py [--install apk/Karte_Karte_release.apk]
환경 변수
  ANDROID_SERIAL (기본 emulator-5554), KARTE_VC (기본 http://127.0.0.1:8088)
"""
import os
import re
import subprocess
import sys
import threading
import time
import urllib.request
import xml.etree.ElementTree as ET

SERIAL = os.environ.get("ANDROID_SERIAL", "emulator-5554")
VC = os.environ.get("KARTE_VC", "http://127.0.0.1:8088")
ADB = ["adb", "-s", SERIAL]
PACKAGE = "com.karte.app"
results = []


def adb(*args, text=True):
    return subprocess.run(ADB + list(args), capture_output=True, text=text).stdout


def vc(method, path):
    req = urllib.request.Request(VC + path, method=method)
    with urllib.request.urlopen(req, timeout=5) as r:
        return r.status


def put(path):
    vc("PUT", path)


def heartbeat(stop):
    # Virtual Console은 열려 있는 세션이 있을 때만 값을 차량에 반영합니다.
    while not stop.is_set():
        try:
            vc("POST", "/browser/session?client=karte-system-test")
        except Exception:
            pass
        stop.wait(1)


def nodes():
    """화면에 보이는 (글자, 가운데 x, 가운데 y) 목록"""
    for _ in range(4):
        adb("shell", "uiautomator", "dump", "/sdcard/karte_ui.xml")
        xml = subprocess.run(ADB + ["exec-out", "cat", "/sdcard/karte_ui.xml"], capture_output=True).stdout.decode("utf-8", "ignore")
        found = []
        try:
            root = ET.fromstring(xml)
        except ET.ParseError:
            root = None
        if root is not None:
            for node in root.iter("node"):
                text = node.get("text", "")
                m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
                if text and m and node.get("package") == PACKAGE:
                    x1, y1, x2, y2 = map(int, m.groups())
                    found.append((text, (x1 + x2) // 2, (y1 + y2) // 2))
        if found:
            return found
        time.sleep(0.7)
    return []


def texts():
    return [t for t, _, _ in nodes()]


def wait_for(predicate, seconds=10):
    """predicate(화면 글자 목록)가 참이 될 때까지 기다립니다. 마지막으로 읽은 화면을 돌려줍니다."""
    end = time.time() + seconds
    seen = []
    while time.time() < end:
        seen = texts()
        if predicate(seen):
            return True, seen
        time.sleep(0.6)
    return False, seen


def has(seen, text):
    return text in seen


def contains(seen, part):
    return any(part in t for t in seen)


def tap(text, exact=True):
    for t, x, y in nodes():
        if (t == text) if exact else (text in t):
            adb("shell", "input", "tap", str(x), str(y))
            return True
    return False


def check(name, ok, detail=""):
    results.append((name, ok))
    print(("PASS  " if ok else "FAIL  ") + name + (("   <- " + detail) if (detail and not ok) else ""), flush=True)


def reset_vehicle():
    for lamp in ("TPMS", "ABS"):
        put(f"/vehicle/telltales/simulator?color=Red&state=Off&type={lamp}")
    put("/vehicle/turn-signal?mode=Off")
    put("/vehicle/controls?speed=0&accelerator=0&brake=0&steering=0")
    put("/vehicle/gear?gear=Park")


def main():
    if "--install" in sys.argv:
        apk = sys.argv[sys.argv.index("--install") + 1]
        out = subprocess.run(ADB + ["install", "-r", apk], capture_output=True, text=True)
        print("install:", (out.stdout + out.stderr).strip().splitlines()[-1])

    # Virtual Console에 연결되지 않으면 포트 전달을 한 번 시도합니다.
    try:
        vc("POST", "/browser/session?client=karte-system-test")
    except Exception:
        adb("forward", "tcp:8088", "tcp:8088")
        try:
            vc("POST", "/browser/session?client=karte-system-test")
        except Exception as e:
            print(f"Virtual Console({VC})에 연결할 수 없습니다: {e}")
            sys.exit(2)

    stop = threading.Event()
    threading.Thread(target=heartbeat, args=(stop,), daemon=True).start()
    time.sleep(2)
    try:
        run()
    finally:
        try:
            reset_vehicle()
        finally:
            stop.set()

    failed = [n for n, ok in results if not ok]
    print(f"\n{len(results) - len(failed)}/{len(results)} passed")
    sys.exit(1 if failed else 0)


def run():
    # ---------- 준비: 정차 상태에서 앱을 새로 띄우고 기록을 비웁니다.
    reset_vehicle()
    adb("shell", "am", "force-stop", PACKAGE)
    adb("shell", "am", "start", "-n", f"{PACKAGE}/.MainActivity")
    ok, seen = wait_for(lambda s: has(s, "증상 말하기") and has(s, "전체 삭제"), 20)
    check("S0 앱 실행", ok, str(seen[:6]))
    if not has(seen, "증상 기록 0건"):
        tap("전체 삭제")
        wait_for(lambda s: has(s, "삭제"), 5)
        tap("삭제")
    ok, seen = wait_for(lambda s: has(s, "증상 기록 0건"), 8)
    check("S0 기록 비우기", ok)

    # ---------- S1 정차 화면
    ok, seen = wait_for(lambda s: has(s, "P") and has(s, "시속 0km"), 8)
    check("S1 정차 중 차량 상태 표시 (기어 P, 시속 0km)", ok, str(seen))
    check("S1 정차 중 기록·입력·리포트·정비소 버튼 표시",
          all(has(seen, t) for t in ("직접 입력", "예시 기록", "리포트", "정비소 안내")), str(seen))

    # ---------- S2 주행 전환
    put("/vehicle/gear?gear=Drive")
    time.sleep(1)
    put("/vehicle/controls?speed=24&accelerator=10&brake=0&steering=0")
    ok, seen = wait_for(lambda s: has(s, "주행 중") and has(s, "시속 24km"), 10)
    check("S2 기어 D에서 주행 화면으로 전환, 속도 반영", ok and has(seen, "D"), str(seen))
    check("S2 주행 중 목록·입력·리포트·정비소 버튼 숨김",
          not any(has(seen, t) for t in ("직접 입력", "예시 기록", "전체 삭제", "리포트", "정비소 안내")), str(seen))
    check("S2 주행 중에도 증상 말하기 버튼 유지", has(seen, "증상 말하기"))

    # ---------- S3 브레이크, 조향각 (콘솔 -48 입력이 SDK에서는 오른쪽 +48)
    put("/vehicle/controls?speed=22&accelerator=0&brake=45&steering=-48")
    ok, seen = wait_for(lambda s: has(s, "밟는 중") and has(s, "오른쪽 48°") and has(s, "시속 22km"), 10)
    check("S3 브레이크·조향각·속도 실시간 반영", ok, str(seen))

    # ---------- S4 경고등 자동 기록
    put("/vehicle/telltales/simulator?color=Red&state=On&type=TPMS")
    ok, seen = wait_for(lambda s: has(s, "증상 기록 1건") and has(s, "타이어 공기압"), 10)
    check("S4 경고등이 켜지면 조작 없이 기록 1건 생성", ok, str(seen))
    check("S4 기록 안내 문구 표시", contains(seen, "경고등 켜짐: 타이어 공기압(TPMS)"), str(seen))
    put("/vehicle/controls?speed=21&accelerator=0&brake=45&steering=-48")
    time.sleep(3)
    check("S4 같은 경고등이 켜진 동안 중복 기록하지 않음", has(texts(), "증상 기록 1건"))

    # ---------- S5 정차 복귀
    put("/vehicle/controls?speed=0&accelerator=0&brake=60&steering=0")
    time.sleep(1)
    put("/vehicle/gear?gear=Park")
    time.sleep(1)
    put("/vehicle/controls?speed=0&accelerator=0&brake=0&steering=0")
    ok, seen = wait_for(lambda s: has(s, "리포트") and has(s, "자동 기록"), 10)
    check("S5 기어 P에서 목록 다시 표시", ok, str(seen))
    check("S5 자동 기록에 그 순간의 스냅샷 요약 표시",
          has(seen, "경고등 켜짐: 타이어 공기압(TPMS)") and contains(seen, "기어 D · 브레이크 밟는 중 · 핸들 오른쪽 48°"), str(seen))

    # ---------- S6 예시 기록과 반복 조건
    tap("예시 기록")
    ok, seen = wait_for(lambda s: has(s, "증상 기록 6건"), 8)
    check("S6 예시 5건 추가 (총 6건)", ok, str(seen[:8]))
    check("S6 반복 조건 표시 (소음 3건, TPMS 경고등 2건)",
          has(seen, "소음 증상 3건") and has(seen, "타이어 공기압(TPMS) 경고등 2건") and has(seen, "3/3") and has(seen, "2/2"), str(seen))
    check("S6 예시 기록은 '예시'로 구분 표시", has(seen, "예시"))

    # ---------- S7 기록 상세
    tap("자동 기록")
    ok, seen = wait_for(lambda s: has(s, "증상 기록 상세"), 8)
    check("S7 기록 상세 열림", ok, str(seen))
    check("S7 상세에 스냅샷 항목 표시",
          all(has(seen, t) for t in ("속도", "기어", "브레이크", "핸들", "누적 주행거리", "켜진 경고등", "위치"))
          and has(seen, "오른쪽 48°") and has(seen, "타이어 공기압(TPMS)"), str(seen))
    check("S7 에뮬레이터가 주지 않는 주행 모드는 '확인 불가'로 표시", has(seen, "확인 불가"), str(seen))
    tap("닫기")
    wait_for(lambda s: not has(s, "증상 기록 상세"), 5)

    # ---------- S8 리포트
    tap("리포트")
    ok, seen = wait_for(lambda s: has(s, "정비소용 증상 리포트"), 8)
    check("S8 정비소용 리포트 열림", ok, str(seen))
    check("S8 리포트에 반복 조건과 전체 기록 표시",
          has(seen, "반복되는 조건") and has(seen, "증상 기록 6건 (최신순)") and has(seen, "소음 증상 3건"), str(seen))

    # ---------- S9 리포트를 연 채 주행을 시작하면 팝업이 닫힘
    put("/vehicle/gear?gear=Drive")
    ok, seen = wait_for(lambda s: has(s, "주행 중") and not has(s, "정비소용 증상 리포트"), 10)
    check("S9 주행 시작 시 열려 있던 팝업 닫힘", ok, str(seen))

    # ---------- S10 경고등 꺼짐 반영, 정리
    put("/vehicle/telltales/simulator?color=Red&state=Off&type=TPMS")
    ok, seen = wait_for(lambda s: has(s, "없음") and not has(s, "타이어 공기압"), 10)
    check("S10 경고등이 꺼지면 상태 칸에 반영", ok, str(seen))
    put("/vehicle/gear?gear=Park")
    ok, seen = wait_for(lambda s: has(s, "전체 삭제"), 10)
    tap("전체 삭제")
    wait_for(lambda s: has(s, "삭제"), 5)
    tap("삭제")
    ok, seen = wait_for(lambda s: has(s, "증상 기록 0건"), 8)
    check("S10 전체 삭제", ok, str(seen[:6]))


if __name__ == "__main__":
    main()
