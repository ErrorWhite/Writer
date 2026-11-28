import argparse
import json
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path


PACKAGE = "io.wboat.writer"
ACTIVITY = f"{PACKAGE}/xyz.qingjian.writer.MainActivity"
FILE_NAME = "QingjianUpgradeFixture.json"
BOOK_TITLE = "青简覆盖安装验收"
CHAPTER_TITLE = "雾港第二章"
BODY = "覆盖安装前的离线书稿。"
WRITE_MARKER = "UpgradeWriteAccepted"


def adb(*args, timeout=45):
    return subprocess.run(["adb", *args], check=True, timeout=timeout, stdout=subprocess.PIPE).stdout


def hierarchy():
    adb("shell", "uiautomator", "dump", "/sdcard/qingjian-upgrade.xml", timeout=15)
    return adb("shell", "cat", "/sdcard/qingjian-upgrade.xml", timeout=10)


def find_node(labels, *, exact=True, timeout=45):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            nodes = ET.fromstring(hierarchy()).iter("node")
            for node in nodes:
                for attribute in ("text", "content-desc"):
                    value = node.get(attribute, "")
                    if any((value == label) if exact else (label in value) for label in labels):
                        return node
        except (ET.ParseError, subprocess.CalledProcessError, subprocess.TimeoutExpired):
            pass
        time.sleep(0.7)
    raise RuntimeError(f"Could not locate a visible control or manuscript value: {labels}")


def tap(node):
    bounds = list(map(int, re.findall(r"\d+", node.get("bounds", ""))))
    if len(bounds) != 4 or bounds[0] >= bounds[2] or bounds[1] >= bounds[3]:
        raise RuntimeError(f"Control has no usable screen bounds: {node.attrib}")
    x1, y1, x2, y2 = bounds
    adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))


def launch():
    adb("shell", "am", "start", "-W", "-n", ACTIVITY)


def evidence(directory, name):
    (directory / f"{name}.png").write_bytes(adb("exec-out", "screencap", "-p"))
    try:
        (directory / f"{name}.xml").write_bytes(hierarchy())
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired):
        pass


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("previous", type=Path)
    parser.add_argument("candidate", type=Path)
    parser.add_argument("evidence", type=Path)
    args = parser.parse_args()
    args.evidence.mkdir(parents=True, exist_ok=True)
    fixture = args.evidence / FILE_NAME
    fixture.write_text(json.dumps({
        "id": "signed-upgrade-book",
        "title": BOOK_TITLE,
        "activeId": "second",
        "volumes": [{"id": "volume", "title": "雾港", "chapters": [
            {"id": "first", "title": "雾港第一章", "text": "烟雨里的渡口。"},
            {"id": "second", "title": CHAPTER_TITLE, "text": BODY},
        ]}],
    }, ensure_ascii=False), encoding="utf-8")

    try:
        # Use the release package and the public import UI to populate real app data.
        adb("install", str(args.previous))
        adb("push", str(fixture), f"/sdcard/Download/{FILE_NAME}")
        launch()
        tap(find_node(("导入备份",)))
        tap(find_node(("Show roots", "Show navigation drawer", "显示根目录", "打开导航抽屉")))
        tap(find_node(("Downloads", "下载内容", "下载")))
        tap(find_node((FILE_NAME,), exact=False))
        tap(find_node(("导入并打开",)))
        find_node((BODY,), exact=False)
        evidence(args.evidence, "previous-manuscript")

        adb("shell", "am", "force-stop", PACKAGE)
        adb("install", "-r", str(args.candidate))
        launch()
        find_node((CHAPTER_TITLE,))
        find_node((BODY,))
        evidence(args.evidence, "upgraded-manuscript")
        tap(find_node(("目录",)))
        find_node(("全书 16 字",))
        find_node(("草稿 · 10 字",))
        tap(find_node((CHAPTER_TITLE,)))
        tap(find_node(("源码",)))
        tap(find_node(("章节正文",)))
        adb("shell", "input", "keyevent", "KEYCODE_MOVE_END")
        adb("shell", "input", "text", WRITE_MARKER)
        tap(find_node(("完成",)))
        find_node(("已保存",))
        adb("shell", "am", "force-stop", PACKAGE)
        launch()
        find_node((CHAPTER_TITLE,))
        find_node((BODY + WRITE_MARKER,))
        evidence(args.evidence, "upgraded-and-reopened")
        print("The upgraded release retained the previous manuscript and saved a new edit across restart.")
    except Exception:
        try:
            evidence(args.evidence, "failure")
        except (subprocess.CalledProcessError, subprocess.TimeoutExpired):
            pass
        raise
    finally:
        (args.evidence / "crash.log").write_bytes(adb("logcat", "-d", "-b", "crash"))


if __name__ == "__main__":
    main()
