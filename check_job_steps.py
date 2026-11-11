import urllib.request
import json

url = 'https://api.github.com/repos/hongnhan100/touchpanel-android/actions/runs/43061355619/jobs'
req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
try:
    with urllib.request.urlopen(req) as resp:
        data = json.loads(resp.read().decode('utf-8'))

    for job in data.get('jobs', []):
        print('Job:', job.get('name'), 'Conclusion:', job.get('conclusion'))
        for step in job.get('steps', []):
            print(f"  Step: {step.get('name')} -> {step.get('conclusion')}")
except Exception as e:
    print("Error:", e)
