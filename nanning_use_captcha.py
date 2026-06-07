"""Phase 2: validate captcha, login, and fetch timetable HTML using saved session."""
import sys
import requests
import hashlib
import re
import pickle
import os
from urllib.parse import quote

BASE = "http://jw.glutnn.cn"
USERNAME = "5241994207"
PASSWORD = "wadrswdjy1"
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))

def md5_hex(data):
    return hashlib.md5(data).hexdigest()

def nanning_password_hash(password):
    first = md5_hex(password.encode("utf-8"))
    return md5_hex(first.encode("utf-8"))

def main():
    captcha = sys.argv[1] if len(sys.argv) > 1 else None
    if not captcha:
        print("Usage: python nanning_use_captcha.py <4-digit-code>")
        return

    # Load saved session
    session_path = os.path.join(SCRIPT_DIR, "nanning_session.pkl")
    with open(session_path, "rb") as f:
        cookies = pickle.load(f)

    s = requests.Session()
    for k, v in cookies.items():
        s.cookies.set(k, v)
    s.headers.update({"User-Agent": "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36"})
    print(f"Loaded session: {dict(s.cookies)}")

    # Step 3: Validate captcha
    print(f"\nStep 3: Validate captcha '{captcha}'")
    r = s.post(
        f"{BASE}/academic/checkCaptcha.do?captchaCode={quote(captcha)}",
        headers={
            "Referer": f"{BASE}/academic/common/security/affairLogin.jsp",
            "X-Requested-With": "XMLHttpRequest",
            "Origin": BASE,
            "Accept": "text/plain, */*; q=0.01",
        },
        data=b"",
        timeout=10
    )
    result = r.text.strip()
    print(f"  Response: {result}")
    if result != "true":
        print("  FAILED! Captcha wrong or session expired.")
        return
    print("  OK!")

    # Step 4: Login
    print("\nStep 4: Login")
    hashed = nanning_password_hash(PASSWORD)
    login_url = (
        f"{BASE}/academic/j_acegi_security_check"
        f"?j_username={quote(USERNAME)}"
        f"&j_password={quote(hashed)}"
        f"&j_captcha={quote(captcha)}"
    )
    r = s.get(
        login_url,
        headers={"Referer": f"{BASE}/academic/common/security/affairLogin.jsp"},
        allow_redirects=False,
        timeout=10
    )
    print(f"  Status: {r.status_code}, Location: {r.headers.get('Location', 'N/A')}")
    location = r.headers.get("Location", "")
    if "affairLogin" in location or "error" in location:
        print("  Login FAILED!")
        return

    # Step 5: Follow redirects
    print("\nStep 5: Follow redirects")
    url = location
    for hop in range(5):
        if not url: break
        resolved = url if url.startswith("http") else f"{BASE}{url}"
        r = s.get(resolved, allow_redirects=False, timeout=10)
        new_location = r.headers.get("Location", "")
        print(f"  Hop {hop+1}: {r.status_code} -> {new_location[-80:] if new_location else 'N/A'}")
        url = new_location

    # Step 6: framePage.do
    print("\nStep 6: framePage.do")
    r = s.post(f"{BASE}/academic/personal/framePage.do", timeout=10)
    print(f"  Status: {r.status_code}, Length: {len(r.text)}")
    frame_body = r.text
    print(f"  Preview: {frame_body[:1500]}")

    internal_id_match = re.search(r'"user"\s*:\s*\{[^}]*"id"\s*:\s*(\d+)', frame_body)
    internal_id = internal_id_match.group(1) if internal_id_match else None
    print(f"  Internal user ID: {internal_id}")

    # Step 7: graphicalBasicInfo.do
    print("\nStep 7: graphicalBasicInfo.do")
    r = s.get(f"{BASE}/academic/manager/coursearrange/graphicalBasicInfo.do", timeout=10)
    print(f"  Status: {r.status_code}, Length: {len(r.text)}")
    print(f"  Preview: {r.text[:1000]}")

    ay = re.search(r'"arrangeCourseYear"\s*:\s*(\d+)', r.text)
    at = re.search(r'"arrangeCourseTerm"\s*:\s*(\d+)', r.text)
    year_id = ay.group(1) if ay else None
    term_id = at.group(1) if at else None
    print(f"  arrangeCourseYear={year_id}, arrangeCourseTerm={term_id}")

    # Step 8: showTimetable.do (what scheduleApp currently uses)
    print(f"\nStep 8: showTimetable.do (scheduleApp current approach)")
    for sid in [USERNAME, internal_id]:
        if not sid: continue
        timetable_url = f"{BASE}/academic/manager/coursearrange/showTimetable.do?id={sid}&timetableType=STUDENT&sectionType=BASE"
        r = s.get(timetable_url, timeout=10)
        print(f"  id={sid}: Status={r.status_code}, Length={len(r.text)}")
        has_table = "<table" in r.text.lower()
        has_course = "<<" in r.text or "课程" in r.text
        has_day = "周一" in r.text or "星期一" in r.text
        print(f"  Has table={has_table}, course={has_course}, day={has_day}")
        if len(r.text) > 100:
            fname = os.path.join(SCRIPT_DIR, f"nanning_showTimetable_{sid}.html")
            with open(fname, "w", encoding="utf-8") as f:
                f.write(r.text)
            print(f"  Saved to {fname}")
            print(f"  HTML preview (3000 chars):\n{r.text[:3000]}")

    # Step 9: currcourse.jsdo (what GlutAssistantN uses)
    print(f"\nStep 9: currcourse.jsdo (GlutAssistantN approach)")
    r = s.get(f"{BASE}/academic/student/currcourse/currcourse.jsdo", timeout=10)
    print(f"  Status: {r.status_code}, Length: {len(r.text)}")
    has_table = "<table" in r.text.lower()
    has_course = "<<" in r.text or "课程" in r.text
    has_day = "周一" in r.text or "星期一" in r.text
    has_infolist = "infolist_common" in r.text
    print(f"  Has table={has_table}, course={has_course}, day={has_day}, infolist_common={has_infolist}")
    if len(r.text) > 100:
        fname = os.path.join(SCRIPT_DIR, "nanning_currcourse.html")
        with open(fname, "w", encoding="utf-8") as f:
            f.write(r.text)
        print(f"  Saved to {fname}")
        print(f"  HTML preview (5000 chars):\n{r.text[:5000]}")

    print("\nDone! HTML files saved for analysis.")

if __name__ == "__main__":
    main()
