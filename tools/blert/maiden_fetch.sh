#!/bin/bash
# maiden_fetch.sh <scale> [count]: the fastest Normal Maiden rooms of a scale -> maiden/<scale>/<uuid>.json (+ index.json).
# Blert allows about 120 requests a window: on a rate-limit reply it waits and retries.
s=$1; n=${2:-50}; out=maiden/$s; mkdir -p $out
curl -s -m 60 "https://blert.io/api/v1/challenges?type=1&mode=11&scale=$s&limit=$n&sort=%2Bsplits:6&extraFields=splits:6&options=fullRecordings" > $out/index.json
for id in $(python3 -I -c "import json;[print(c['uuid']) for c in json.load(open('$out/index.json'))]"); do
  f=$out/$id.json
  for try in 1 2 3 4; do
    python3 -I -c "import json,sys;sys.exit(0 if isinstance(json.load(open('$f')),list) else 1)" 2>/dev/null && break
    curl -s -m 120 "https://blert.io/api/v1/raids/tob/$id/events?stage=10" > $f
    python3 -I -c "import json,sys;sys.exit(0 if isinstance(json.load(open('$f')),list) else 1)" 2>/dev/null || sleep 30
  done
  sleep 1
done
