#!/usr/bin/env python3
import sys
import json
import time

BRAVE_PATH = "/Applications/Brave Browser.app/Contents/MacOS/Brave Browser"


def solve(url: str, timeout: int = 45) -> dict:
    from DrissionPage import ChromiumPage, ChromiumOptions

    co = ChromiumOptions()
    co.set_browser_path(BRAVE_PATH)
    co.set_argument("--no-first-run")
    co.set_argument("--no-default-browser-check")

    page = ChromiumPage(co)

    try:
        page.get(url)
        start = time.time()
        stable_ticks = 0

        challenge_titles = ("moment", "момент", "подожд", "проверк", "OTS", "Checking", "Verif")
        initial_cookies = page.cookies()
        initial_clearance = next((c["value"] for c in initial_cookies if c["name"] == "cf_clearance"), None)
        initial_title = page.run_js("return document.title")
        saw_challenge = any(t.lower() in initial_title.lower() for t in challenge_titles)

        while time.time() - start < timeout:
            cookies = page.cookies()
            title = page.run_js("return document.title")
            ready = page.run_js("return document.readyState")

            is_challenge = any(t.lower() in title.lower() for t in challenge_titles)

            has_clearance = any(c.get("name", "") == "cf_clearance" for c in cookies)
            clearance_value = next((c["value"] for c in cookies if c["name"] == "cf_clearance"), None)
            # A stale clearance (already present in the browser profile) is not a valid result.
            clearance_changed = has_clearance and clearance_value != initial_clearance

            # Success: challenge gone, page fully loaded, and the clearance is fresh
            # (or no challenge happened at all). Require 2 stable ticks in a row:
            # the page may reload once more after the challenge is solved.
            solved = (not is_challenge and ready == "complete"
                      and (clearance_changed or not saw_challenge))

            if solved:
                stable_ticks += 1
                if stable_ticks >= 2:
                    # Let the real page set its own cookies (site session etc.) before harvesting.
                    time.sleep(2)
                    ua = page.run_js("return navigator.userAgent")
                    return {"cookies": page.cookies(), "userAgent": ua}
            else:
                stable_ticks = 0

            time.sleep(1)

        ua = page.run_js("return navigator.userAgent")
        return {"cookies": page.cookies(), "userAgent": ua}
    finally:
        page.quit()


if __name__ == "__main__":
    url = sys.argv[1] if len(sys.argv) > 1 else "https://nowsecure.nl/"
    timeout = int(sys.argv[2]) if len(sys.argv) > 2 else 45

    try:
        result = solve(url, timeout)
        print(json.dumps(result))
    except Exception as e:
        print(json.dumps({"error": str(e)}), file=sys.stderr)
        sys.exit(1)
