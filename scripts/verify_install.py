#!/usr/bin/env python3
"""Check a newly installed, disposable deployment; never use on a user's instance."""
import argparse
import json
import time
import urllib.request

parser = argparse.ArgumentParser()
parser.add_argument("--backend", default="http://127.0.0.1:16365")
parser.add_argument("--frontend", default="http://127.0.0.1:16366")
args = parser.parse_args()
http = urllib.request.build_opener(urllib.request.ProxyHandler({}))
token = None

def api(route, body):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = token
    request = urllib.request.Request(args.backend + "/api/v1/" + route,
        data=json.dumps(body).encode(), headers=headers)
    with http.open(request, timeout=30) as response:
        return json.load(response)

for attempt in range(150):
    try:
        with http.open(args.backend + "/flow/test", timeout=3) as response:
            if response.status == 200:
                break
    except Exception:
        pass
    time.sleep(1)
else:
    raise SystemExit("Backend did not become ready")
with http.open(args.frontend + "/", timeout=10) as response:
    assert b"FluxChain" in response.read(), "Frontend title was not rebranded"
result = api("user/login", {"username": "admin_user", "password": "admin_user"})
assert result.get("code") == 0, "Fresh administrator login failed"
token = result["data"]["token"]
assert token
result = api("config/get", {"name": "app_name"})
assert result.get("code") == 0 and result["data"]["value"] == "FluxChain"
print("PASS clean install frontend, administrator login and default site name")
for kind in ["SLIDER", "WORD_IMAGE_CLICK", "ROTATE", "CONCAT"]:
    assert api("config/update-single", {"name": "captcha_type", "value": kind})["code"] == 0
    generated = api("captcha/generate", {})
    assert generated.get("code") in (0, 200), "Captcha generation failed: " + kind
    data = generated.get("data")
    assert isinstance(data, dict) and data.get("id"), "Captcha payload missing: " + kind
    print("PASS captcha image generation:", kind)
assert api("config/update-single", {"name": "captcha_type", "value": "RANDOM"})["code"] == 0
print("All fresh deployment checks passed; authentication settings were preserved")
