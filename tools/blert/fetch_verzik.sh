#!/bin/bash
# fetch_verzik.sh scale [count] -> data/verzik/<scale>/index.json + <uuid>.json (the Verzik room's events), fastest first.
# Blert allows 120 requests a window: a rate-limited answer is dropped and retried after a minute.
scale=$1; n=${2:-50}
out=data/verzik/$scale; mkdir -p $out
[ -s $out/index.json ] || curl -s -m 60 "https://blert.io/api/v1/challenges?type=1&mode=11&scale=$scale&sort=%2Bsplits:85&extraFields=splits:85,splits:88,splits:91,splits:94&limit=$n&options=fullRecordings" > $out/index.json
for id in $(python3 -c "import json;[print(c['uuid']) for c in json.load(open('$out/index.json'))]"); do
  while ! [ -s $out/$id.json ] || grep -q '"error":"rate_limit' $out/$id.json; do
    curl -s -m 120 "https://blert.io/api/v1/raids/tob/$id/events?stage=15" > $out/$id.json
    grep -q '"error":"rate_limit' $out/$id.json && sleep 60
    sleep 1
  done
done
