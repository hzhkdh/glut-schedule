"""Phase 1: fetch captcha image and save session."""
import requests
import time
import pickle
import os

BASE = "http://jw.glutnn.cn"
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))

s = requests.Session()
s.headers.update({"User-Agent": "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36"})

# Step 1: Get login page
r = s.get(f"{BASE}/academic/common/security/affairLogin.jsp", timeout=10)
print(f"Login page: {r.status_code}, JSESSIONID={s.cookies.get('JSESSIONID', 'N/A')}")

# Step 2: Download captcha
r = s.get(
    f"{BASE}/academic/getCaptcha.do?captchaCheckCode=0&random={int(time.time() * 1000)}",
    headers={"Referer": f"{BASE}/academic/common/security/affairLogin.jsp"},
    timeout=10
)
captcha_path = os.path.join(SCRIPT_DIR, "nanning_captcha2.png")
with open(captcha_path, "wb") as f:
    f.write(r.content)
print(f"Captcha saved: {len(r.content)} bytes -> {captcha_path}")

# Save session cookies
session_path = os.path.join(SCRIPT_DIR, "nanning_session.pkl")
with open(session_path, "wb") as f:
    pickle.dump(dict(s.cookies), f)
print(f"Session saved -> {session_path}")
print("\nNow open nanning_captcha2.png and run nanning_use_captcha.py <code>")
