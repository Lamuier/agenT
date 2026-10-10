// Render the actual source drawables as a static review sheet. No emulator or source-image edits.
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const sharp = require('sharp');
const root = path.resolve(__dirname, '..');
const res = path.join(root, 'app/src/main/res');
const read = name => fs.readFileSync(path.join(res, `drawable/${name}.xml`), 'utf8');
const attr = (xml, name) => xml.match(new RegExp(`android:${name}="([^"]+)"`))?.[1];
const svg = (size, body, viewBox = `0 0 ${size} ${size}`) => Buffer.from(`<svg xmlns="http://www.w3.org/2000/svg" width="${size}" height="${size}" viewBox="${viewBox}">${body}</svg>`);
function color(value) {
  if (!value) return ['none', 1];
  return value.length === 9 ? [`#${value.slice(3)}`, parseInt(value.slice(1, 3), 16) / 255] : [value, 1];
}
function vector(xml, size) {
  const width = attr(xml, 'viewportWidth'), height = attr(xml, 'viewportHeight');
  let body = [...xml.matchAll(/<path\b([\s\S]*?)\/>/g)].map(match => {
    const data = match[1], [fill, fo] = color(attr(data, 'fillColor')), [stroke, so] = color(attr(data, 'strokeColor'));
    return `<path d="${attr(data, 'pathData')}" fill="${fill}" fill-opacity="${fo}" stroke="${stroke}" stroke-opacity="${so}" stroke-width="${attr(data, 'strokeWidth') || 0}" stroke-linecap="${attr(data, 'strokeLineCap') || 'butt'}" stroke-linejoin="${attr(data, 'strokeLineJoin') || 'miter'}" fill-rule="${attr(data, 'fillType') === 'evenOdd' ? 'evenodd' : 'nonzero'}"/>`;
  }).join('');
  const group = xml.match(/<group\b([\s\S]*?)>/)?.[1];
  if (group) {
    const x = attr(group, 'pivotX') || 0, y = attr(group, 'pivotY') || 0;
    body = `<g transform="translate(${x} ${y}) scale(${attr(group, 'scaleX') || 1} ${attr(group, 'scaleY') || 1}) translate(${-x} ${-y})">${body}</g>`;
  }
  return svg(size, body, `0 0 ${width} ${height}`);
}
async function launcher(size) {
  // Round adaptive-mask illustration; this does not claim Xiaomi host pixel parity.
  const foreground = read('ic_launcher_foreground');
  const inset = parseFloat(attr(foreground, 'insetLeft')) / 100;
  const canvas = size * 3, cropSize = size * 2, insetPx = Math.round(canvas * inset);
  const art = await sharp(path.join(res, 'drawable-nodpi/ic_launcher_cat_art.png'))
    .resize(canvas - 2 * insetPx, canvas - 2 * insetPx).png().toBuffer();
  const [background] = color(attr(read('ic_launcher_bg'), 'color'));
  const full = await sharp({ create: { width: canvas, height: canvas, channels: 4, background } })
    .composite([{ input: art, left: insetPx, top: insetPx }]).png().toBuffer();
  const cropped = await sharp(full).extract({ left: size / 2, top: size / 2, width: cropSize, height: cropSize })
    .resize(size, size).png().toBuffer();
  return sharp(cropped).composite([{ input: svg(size, `<circle cx="${size / 2}" cy="${size / 2}" r="${size / 2}" fill="white"/>`), blend: 'dest-in' }]).png().toBuffer();
}
async function island(style, state, size, multiple = false) {
  const xml = read(`ic_island_${style}_${state}`);
  const scale = size / 32;
  const [bg] = color(attr(xml, 'color'));
  const stroke = xml.match(/<stroke\b([^>]+)\/>/)[1];
  const [accent] = color(attr(stroke, 'color'));
  const frame = svg(size, `<rect x="${0.75 * scale}" y="${0.75 * scale}" width="${size - 1.5 * scale}" height="${size - 1.5 * scale}" rx="${10 * scale}" fill="${bg}" stroke="${accent}" stroke-width="${1.5 * scale}"/>`);
  const artSize = Math.round(26 * scale), badgeSize = Math.round(12 * scale);
  const art = style === 'cat' ? await launcher(artSize) : await sharp(vector(read('ic_island_formal_mark'), artSize)).png().toBuffer();
  const badge = await sharp(vector(read(`ic_island_badge_${state}`), badgeSize)).png().toBuffer();
  const layers = [
    { input: art, left: Math.round((size - artSize) / 2), top: Math.round((size - artSize) / 2) },
    { input: badge, left: size - badgeSize, top: size - badgeSize },
  ];
  if (multiple) {
    layers.push({ input: svg(size, '<circle cx="26" cy="26" r="24" fill="white"/><circle cx="26" cy="26" r="21" fill="#26354A"/><path d="M17,26 H35 M26,17 V35" fill="none" stroke="white" stroke-width="5" stroke-linecap="round"/>', '0 0 128 128'), left: 0, top: 0 });
  }
  return sharp(frame).composite(layers).png().toBuffer();
}
(async () => {
  const states = ['running', 'low', 'empty', 'service', 'maintenance', 'syncing'];
  const layers = [], captions = [], hashes = new Set();
  const variants = [['formal', false], ['cat', false], ['formal', true], ['cat', true]];
  for (const [row, [style, multiple]] of variants.entries()) {
    captions.push(`<text x="24" y="${34 + row * 292}" font-family="sans-serif" font-size="24" fill="#EEF2F8">${style === 'cat' ? 'Cat-speak' : 'Formal'}${multiple ? ' · Multiple attention rows' : ''}</text>`);
    for (const [col, state] of states.entries()) {
      const x = 28 + col * 178, y = 54 + row * 292;
      for (const [size, offset] of [[128, 0], [32, 150], [20, 196]]) {
        const icon = await island(style, state, size, multiple);
        layers.push({ input: icon, left: x + Math.round((128 - size) / 2), top: y + offset });
        if (size === 32) hashes.add(crypto.createHash('sha256').update(icon).digest('hex'));
      }
      captions.push(`<text x="${x + 64}" y="${y + 244}" text-anchor="middle" font-family="sans-serif" font-size="16" fill="#CBD3E3">${state}</text>`);
    }
  }
  if (hashes.size !== 24) throw new Error('Expected twenty-four visually distinct style/state/composite icons');
  layers.push({ input: Buffer.from(`<svg xmlns="http://www.w3.org/2000/svg" width="1120" height="1196">${captions.join('')}</svg>`), left: 0, top: 0 });
  const preview = path.join(root, 'dist/ui-review/island-formal-cat-states.png');
  fs.mkdirSync(path.dirname(preview), { recursive: true });
  await sharp({ create: { width: 1120, height: 1196, channels: 4, background: '#0F121B' } }).composite(layers).png().toFile(preview);
  console.log(JSON.stringify({ preview, uniqueIcons: hashes.size, sizesPx: [128, 32, 20], verification: 'static resources; not native Xiaomi rendering' }));
})().catch(error => { console.error(error.message); process.exit(1); });
