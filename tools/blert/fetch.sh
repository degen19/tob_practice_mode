#!/bin/bash
# fetch.sh mode scale count -> raids/<mode>_<scale>/<uuid>/<stage>.json
mode=$1; scale=$2; n=$3
out=raids/${mode}_${scale}; mkdir -p $out
curl -s -m 60 "https://blert.io/api/v1/challenges?type=1&mode=$mode&scale=$scale&status=1&limit=$n&options=fullRecordings" > $out/index.json
for id in $(python3 -c "import json,sys;[print(c['uuid']) for c in json.load(open('$out/index.json'))]"); do
  mkdir -p $out/$id
  for s in 10 11 12 13 14 15; do
    [ -s $out/$id/$s.json ] || curl -s -m 90 "https://blert.io/api/v1/raids/tob/$id/events?stage=$s" > $out/$id/$s.json
    sleep 0.3
  done
done
