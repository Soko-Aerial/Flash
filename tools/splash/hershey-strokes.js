// Emit the raw single-line strokes of a word in one SVG stroke font as Kotlin intArrayOf(...)
// literals: y flipped (y-down), letters laid out by advance width, then shifted so the word's
// bounding box starts at (0, 0). Usage: node hershey-strokes.js <font.svg> [word]
//
// Generated ui/theme/.../FlashInkSplashGlyphs.kt (UI-056, ADR-080) from svg_fonts/HersheyScript1.svg of
// the hersheytext 2.0.0 npm package (`npm pack hersheytext@2.0.0`). Developer tool, not product code.
// The font's licence forbids only one output format (NTIS "xxx yyy:"), which this is not; see NOTICE.
const fs = require('fs');
const [file, word = 'Flash'] = process.argv.slice(2);

function attr(tag, name) { const m = tag.match(new RegExp('\\s' + name + '="([^"]*)"')); return m ? m[1] : null; }
const src = fs.readFileSync(file, 'utf8');
const ascent = +attr(src.match(/<font-face[^>]*>/)[0], 'ascent');
const defAdv = +attr(src.match(/<font [^>]*>/)[0], 'horiz-adv-x');
const glyphs = {};
for (const g of src.match(/<glyph [^>]*>/g)) {
  const u = attr(g, 'unicode'); if (u == null || u.length !== 1) continue;
  glyphs[u] = { adv: +(attr(g, 'horiz-adv-x') || defAdv), d: attr(g, 'd') || '' };
}
function strokes(d) {
  const t = d.trim().split(/\s+/); const res = []; let cur = null;
  for (let i = 0; i < t.length;) {
    if (t[i] === 'M') { cur = []; res.push(cur); cur.push([+t[i + 1], +t[i + 2]]); i += 3; }
    else if (t[i] === 'L') { cur.push([+t[i + 1], +t[i + 2]]); i += 3; }
    else { cur.push([+t[i], +t[i + 1]]); i += 2; }
  }
  return res;
}
let x = 0; const all = [];
for (const ch of word) {
  const g = glyphs[ch]; if (!g) throw new Error('no glyph ' + ch);
  for (const st of strokes(g.d)) all.push({ ch, pts: st.map(([px, py]) => [px + x, ascent - py]) });
  x += g.adv;
}
let minX = 1e9, minY = 1e9, maxX = -1e9, maxY = -1e9;
for (const s of all) for (const [px, py] of s.pts) { minX = Math.min(minX, px); minY = Math.min(minY, py); maxX = Math.max(maxX, px); maxY = Math.max(maxY, py); }
const W = Math.round(maxX - minX), H = Math.round(maxY - minY);
console.log(`// box ${W} x ${H}, ${all.length} strokes, ${all.reduce((a, s) => a + s.pts.length, 0)} points`);
for (const s of all) {
  const nums = s.pts.flatMap(([px, py]) => [Math.round(px - minX), Math.round(py - minY)]);
  console.log(`    // ${s.ch}\n    intArrayOf(${nums.join(', ')}),`);
}
