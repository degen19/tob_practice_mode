const fs = require('fs');
const { WEAPON_NAMES } = require('./sim');
const { FEATURES, NF: NFA, NP, POST_FEATURES, killP } = require('./policy');
const NF = NFA + NP;
const [, , src, dst] = process.argv;
const j = JSON.parse(fs.readFileSync(src));
const lines = [`# Nylocas bot policy, trained in the stand-alone sim (gen ${j.gen}, sim boss ${j.val.boss.toFixed(2)})`];
['G0', 'G1', 'G2', 'R0', 'R1', 'R2', 'M0', 'M1', 'M2'].forEach((r, k) => {
  for (let i = 0; i < NF; i++) {
    const name = i < FEATURES.length ? FEATURES[i] : i < NFA ? WEAPON_NAMES[i - FEATURES.length] : POST_FEATURES[i - NFA];
    lines.push(['w', r, name, j.theta[k * NF + i].toFixed(5)].join('\t'));
  }
});
for (const w of WEAPON_NAMES) for (const big of [0, 1]) lines.push(['kill', w, big, Array.from({ length: 16 }, (_, h) => killP(w, !!big, h + 1).toFixed(4)).join(',')].join('\t'));
fs.writeFileSync(dst, lines.join('\n') + '\n');
console.log(lines.length, 'lines');
