# Bot batches

Many bot teams play a room in one server at fast ticks, to compare builds without a client.

```
./gradlew -q :server:app:classes
service postgresql start
sh tools/bot-batch/batch.sh maiden tools/bot-batch/blank12.params /tmp/m.log     # 12 Maiden trios
TOB_TRIO_TRACE=1 sh tools/bot-batch/batch.sh maiden tools/bot-batch/blank12.params /tmp/m.log
python3 tools/bot-batch/maiden_check.py /tmp/m.log                                # needs TOB_TRIO_TRACE
TOB_NYLO_POLICY=weights.tsv sh tools/bot-batch/batch.sh nylo tools/bot-batch/blank12.params /tmp/n.log
```

- **Params file:** one line of crew params per team. Blank lines mean defaults.
- **`TOB_TRIO_SIZE=4`:** Maiden 4s.
- **Result lines:**
  - Maiden: kill tick, procs, leaks, heals.
  - Nylo: boss tick, waves, nulls, stalls, pillars.
- **`maiden_check.py`** reads a traced log and reports, per batch:
  - the mean kill tick and leaks;
  - how many rooms had a ranger and melee swinging off each other's tick (should be 0);
  - the mage's tbows from up close after the 50s.
- **Noise:** 12 rooms at once lag a small machine, so results are noisy. Compare builds on at least 24 rooms each.
