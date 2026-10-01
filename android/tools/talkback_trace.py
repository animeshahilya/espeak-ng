"""TalkBack-like utterance trace from the connected phone's real screens,
for PhraseCacheReplayDeviceTest (the natural-voice phrase cache's hit rate
without hours of real use, and without driving TalkBack, whose adb-injected
gestures and key shortcuts proved unreliable).

Dumps each screen's accessibility tree (TalkBack off), turns every labelled
node into what TalkBack says ("label, Button"), with the usage hint
("Double-tap to activate") as its own utterance as TalkBack speaks it, then
visits the screens in a shuffled, weighted order with revisits.

  python tools/talkback_trace.py            # writes trace.txt
  adb push trace.txt /sdcard/Android/data/com.animeshahilya.espeakng/files/trace.txt
"""
import os, re, random, subprocess, time
import xml.etree.ElementTree as ET

os.environ["MSYS_NO_PATHCONV"] = "1"


def sh(*a):
    return subprocess.run(["adb", "shell", *a], capture_output=True, text=True, encoding="utf-8").stdout


SCREENS = [
    ("settings", ["am", "start", "-a", "android.settings.SETTINGS"]),
    ("wifi", ["am", "start", "-a", "android.settings.WIRELESS_SETTINGS"]),
    ("display", ["am", "start", "-a", "android.settings.DISPLAY_SETTINGS"]),
    ("sound", ["am", "start", "-a", "android.settings.SOUND_SETTINGS"]),
    ("a11y", ["am", "start", "-a", "android.settings.ACCESSIBILITY_SETTINGS"]),
    ("apps", ["am", "start", "-a", "android.settings.APPLICATION_SETTINGS"]),
    ("clock", ["monkey", "-p", "com.google.android.deskclock", "-c", "android.intent.category.LAUNCHER", "1"]),
    ("calc", ["monkey", "-p", "com.google.android.calculator", "-c", "android.intent.category.LAUNCHER", "1"]),
    ("contacts", ["monkey", "-p", "com.google.android.contacts", "-c", "android.intent.category.LAUNCHER", "1"]),
    ("dialer", ["monkey", "-p", "com.google.android.dialer", "-c", "android.intent.category.LAUNCHER", "1"]),
    ("files", ["monkey", "-p", "com.google.android.documentsui", "-c", "android.intent.category.LAUNCHER", "1"]),
]

ROLE = {"Button": "Button", "ImageButton": "Button", "Switch": "Switch", "CheckBox": "Checkbox",
        "RadioButton": "Radio button", "EditText": "Edit box", "SeekBar": "Slider", "TextView": None}


def utterances(xml):
    out = []
    for n in ET.fromstring(xml).iter("node"):
        label = (n.get("content-desc") or n.get("text") or "").strip()
        if not label or len(label) > 120:
            continue
        cls = n.get("class", "").split(".")[-1]
        parts = [label]
        role = ROLE.get(cls)
        if role:
            parts.append(role)
        if cls in ("Switch", "CheckBox"):
            parts.append("On" if n.get("checked") == "true" else "Off")
        out.append(", ".join(parts))
        if n.get("clickable") == "true":
            out.append("Double-tap to activate")  # TalkBack's hint is its own utterance
    return out


screens = {}
for name, cmd in SCREENS:
    sh(*cmd)
    time.sleep(3)
    sh("uiautomator", "dump", "/sdcard/ui.xml")
    xml = sh("cat", "/sdcard/ui.xml")
    try:
        screens[name] = utterances(xml[xml.index("<?xml"):])
    except (ValueError, ET.ParseError):
        screens[name] = []
    sh("input", "keyevent", "HOME")
    time.sleep(1)
    print(name, len(screens[name]))

# A session: home-ish screens visited more often, each read top to bottom
# (TalkBack swipe-through), with the app/screen order shuffled.
rnd = random.Random(1)
weights = {"settings": 4, "wifi": 2, "display": 1, "sound": 1, "a11y": 2, "apps": 1,
           "clock": 2, "calc": 2, "contacts": 2, "dialer": 3, "files": 1}
visits = [s for s, w in weights.items() for _ in range(w)]
rnd.shuffle(visits)
trace = []
for s in visits:
    items = screens.get(s, [])
    trace += items[: rnd.randint(max(1, len(items) // 2), max(1, len(items)))]
open("trace.txt", "w", encoding="utf-8").write("\n".join(trace))
print(len(trace), "utterances,", len(set(trace)), "distinct")
