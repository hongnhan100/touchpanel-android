import urllib.request
import json

url = 'https://api.github.com/repos/hongnhan100/touchpanel-android/actions/runs/43061355619/jobs'
req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
try:
    with urllib.request.urlopen(req) as resp:
        data = json.loads(resp.read().decode('utf-8'))
        job_id = data['jobs'][0]['id']
        print(f"Job ID: {job_id}")

    log_url = f"https://api.github.com/repos/hongnhan100/touchpanel-android/actions/jobs/{job_id}/logs"
    log_req = urllib.request.Request(log_url, headers={'User-Agent': 'Mozilla/5.0'})
    with urllib.request.urlopen(log_req) as resp:
        log_text = resp.read().decode('utf-8', 'ignore')
        lines = log_text.splitlines()
        print(f"Total log lines: {len(lines)}")
        print("\n=== LAST 60 LINES OF BUILD LOG ===")
        for line in lines[-60:]:
            print(line)
except Exception as e:
    print("Error:", e)
