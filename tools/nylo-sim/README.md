# Nylocas sim

A stand-alone copy of the server's Nylocas room (`NyloSolo`) for training the bots' target choice
(`NyloCrew` with `learned=1`, weights in `content/raids/tob-bloat/src/main/resources/tob/nylo_policy.tsv`).

- `waves.json`, `prefires.json`: read off `NyloSolo.WAVES` and `NyloCrew.PREFIRES`.
- `dmg.json`: the damage each bot weapon dealt nylocas on the server (`TOB_HIT_TRACE`), small and big.
- `node train.js gens=600 pop=48 seeds=16 init=<best.json> out=<best.json>`: evolution strategies on two
  worker threads; every 10 generations it plays 200 fixed rooms and keeps the best weights.
- `node export.js <best.json> nylo_policy.tsv`: the weights as the server reads them.
