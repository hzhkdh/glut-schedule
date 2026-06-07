"""
Nanning timetable debug script — test login + timetable fetch.
Usage: python nanning_timetable_debug.py <captcha_code>
First run without captcha to see the image, then run with the code.
"""
import sys
import requests
import hashlib
import re
from urllib.parse import quote
from io import BytesIO
from PIL import Image

BASE = "http://jw.glutnn.cn"
USERNAME = "5241994207"
PASSWORD = "wadrswdjy1"

session = requests.Session()
session.headers.update({
    "User-Agent": "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36",
})

def md5_hex(data: bytes) -> str:
    return hashlib.md5(data).hexdigest()

def nanning_password_hash(password: str) -> str:
    first = md5_hex(password.encode("utf-8"))
    return md5_hex(first.encode("utf-8"))

def main():
    captcha = sys.argv[1] if len(sys.argv) > 1 else None

    # Step 1: Fetch login page
    print("Step 1: Fetch login page")
    r = session.get(f"{BASE}/academic/common/security/affairLogin.jsp", timeout=10)
    print(f"  Status: {r.status_code}, Cookies: {dict(session.cookies)}")

    # Step 2: Download captcha
    print("\nStep 2: Download captcha")
    import time
    r = session.get(
        f"{BASE}/academic/getCaptcha.do?captchaCheckCode=0&random={int(time.time() * 1000)}",
        headers={"Referer": f"{BASE}/academic/common/security/affairLogin.jsp"},
        timeout=10
    )
    print(f"  Status: {r.status_code}, Content-Length: {len(r.content)}")
    # Save captcha image
    with open("nanning_captcha.png", "wb") as f:
        f.write(r.content)
    print("  Saved captcha to nanning_captcha.png")

    if captcha is None:
        print("\n  Run again with: python nanning_timetable_debug.py <4-digit-code>")
        return

    # Step 3: Validate captcha
    print(f"\nStep 3: Validate captcha '{captcha}'")
    r = session.post(
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
    print(f"  Response: {r.text.strip()}")
    if r.text.strip() != "true":
        print("  ❌ FAILED!")
        return
    print("  ✅ OK!")

    # Step 4: Login
    print("\nStep 4: Login")
    hashed = nanning_password_hash(PASSWORD)
    login_url = (
        f"{BASE}/academic/j_acegi_security_check"
        f"?j_username={quote(USERNAME)}"
        f"&j_password={quote(hashed)}"
        f"&j_captcha={quote(captcha)}"
    )
    r = session.get(
        login_url,
        headers={"Referer": f"{BASE}/academic/common/security/affairLogin.jsp"},
        allow_redirects=False,
        timeout=10
    )
    print(f"  Status: {r.status_code}, Location: {r.headers.get('Location', 'N/A')}")
    location = r.headers.get("Location", "")
    if "affairLogin" in location or "error" in location:
        print("  ❌ Login FAILED!")
        return

    # Step 5: Follow redirects
    print("\nStep 5: Follow redirects")
    url = location
    for hop in range(5):
        if not url: break
        resolved = url if url.startswith("http") else f"{BASE}{url}"
        r = session.get(resolved, allow_redirects=False, timeout=10)
        new_location = r.headers.get("Location", "")
        print(f"  Hop {hop+1}: {r.status_code} → {new_location[-70:] if new_location else 'N/A'}")
        url = new_location

    # Step 6: framePage.do
    print("\nStep 6: framePage.do")
    r = session.post(f"{BASE}/academic/personal/framePage.do", timeout=10)
    print(f"  Status: {r.status_code}, Length: {len(r.text)}")
    frame_body = r.text
    print(f"  Preview: {frame_body[:1500]}")

    internal_id_match = re.search(r'"user"\s*:\s*\{[^}]*"id"\s*:\s*(\d+)', frame_body)
    internal_id = internal_id_match.group(1) if internal_id_match else None
    print(f"  Internal user ID: {internal_id}")

    # Step 7: graphicalBasicInfo.do
    print("\nStep 7: graphicalBasicInfo.do")
    r = session.get(f"{BASE}/academic/manager/coursearrange/graphicalBasicInfo.do", timeout=10)
    print(f"  Status: {r.status_code}, Length: {len(r.text)}")
    print(f"  Preview: {r.text[:1000]}")

    ay = re.search(r'"arrangeCourseYear"\s*:\s*(\d+)', r.text)
    at = re.search(r'"arrangeCourseTerm"\s*:\s*(\d+)', r.text)
    print(f"  arrangeCourseYear={ay.group(1) if ay else 'N/A'}, arrangeCourseTerm={at.group(1) if at else 'N/A'}")

    # Step 8: showTimetable.do with student number
    print(f"\nStep 8: showTimetable.do?id={USERNAME}")
    timetable_url = f"{BASE}/academic/manager/coursearrange/showTimetable.do?id={USERNAME}&timetableType=STUDENT&sectionType=BASE"
    r = session.get(timetable_url, timeout=10)
    print(f"  Status: {r.status_code}, Length: {len(r.text)}")
    has_table = "<table" in r.text.lower()
    has_course = "<<" in r.text or "课程" in r.text
    has_day = "周一" in r.text or "星期一" in r.text
    print(f"  Has table={has_table}, course={has_course}, day={has_day}")
    print(f"  HTML preview (5000 chars):\n{r.text[:5000]}")
    if len(r.text) > 100 and has_course:
        with open("nanning_timetable_student_id.html", "w", encoding="utf-8") as f:
            f.write(r.text)
        print("  ✅ Saved to nanning_timetable_student_id.html")

    # Step 9: showTimetable.do with internal ID
    if internal_id and internal_id != USERNAME:
        print(f"\nStep 9: showTimetable.do?id={internal_id}")
        timetable_url = f"{BASE}/academic/manager/coursearrange/showTimetable.do?id={internal_id}&timetableType=STUDENT&sectionType=BASE"
        r = session.get(timetable_url, timeout=10)
        print(f"  Status: {r.status_code}, Length: {len(r.text)}")
        has_table = "<table" in r.text.lower()
        has_course = "<<" in r.text or "课程" in r.text
        has_day = "周一" in r.text or "星期一" in r.text
        print(f"  Has table={has_table}, course={has_course}, day={has_day}")
        print(f"  HTML preview (5000 chars):\n{r.text[:5000]}")
        if len(r.text) > 100 and has_course:
            with open("nanning_timetable_internal_id.html", "w", encoding="utf-8") as f:
                f.write(r.text)
            print("  ✅ Saved to nanning_timetable_internal_id.html")

    # Step 10: currcourse.jsdo
    print(f"\nStep 10: currcourse.jsdo")
    r = session.get(f"{BASE}/academic/student/currcourse/currcourse.jsdo", timeout=10)
    print(f"  Status: {r.status_code}, Length: {len(r.text)}")
    print(f"  Preview (3000 chars):\n{r.text[:3000]}")
    if len(r.text) > 100 and ("课程" in r.text or "<<" in r.text):
        with open("nanning_currcourse.html", "w", encoding="utf-8") as f:
            f.write(r.text)
        print("  ✅ Saved to nanning_currcourse.html")

if __name__ == "__main__":
    main()
