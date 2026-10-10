// Static resource preview only. Does not start an emulator or modify source artwork.
// Run with the bundled Node runtime and NODE_PATH pointing to its node_modules.
const fs = require('fs');
const path = require('path');
const sharp = require('sharp');

const root = path.resolve(__dirname, '..');
const res = path.join(root, 'app/src/main/res');
const foreground = fs.readFileSync(path.join(res, 'drawable/ic_launcher_foreground.xml'), 'utf8');
const insets = ['Left', 'Top', 'Right', 'Bottom'].map(edge => {
  const value = foreground.match(new RegExp(`android:inset${edge}="([0-9.]+)%"`));
  if (!value) throw new Error(`Missing fractional inset ${edge}`);
  return Number(value[1]) / 100;
});
if (!insets.every(value => value === insets[0])) throw new Error('Preview requires symmetric insets');
const source = path.join(res, 'drawable-nodpi/ic_launcher_cat_art.png');
const background = fs.readFileSync(path.join(res, 'drawable/ic_launcher_bg.xml'), 'utf8')
  .match(/android:color="(#[A-Fa-f0-9]{6})"/)[1];
const mono = fs.readFileSync(path.join(res, 'drawable/ic_launcher_monochrome.xml'), 'utf8');
const paths = [...mono.matchAll(/android:pathData="([^"]+)"/g)].map(match =>
  `<path d="${match[1]}" fill="#386287" fill-rule="evenodd"/>`).join('');
const scale = Number(mono.match(/android:scaleX="([0-9.]+)"/)[1]);
const monoPaths = `<g transform="translate(54 54) scale(${scale}) translate(-54 -54)">${paths}</g>`;
const svg = body => Buffer.from(`<svg xmlns="http://www.w3.org/2000/svg" width="216" height="216" viewBox="0 0 108 108">${body}</svg>`);

async function adaptive(shape, size, themed = false) {
  // Android displays the central 72dp of the 108dp adaptive drawable.
  const canvas = size * 3;
  const cropSize = size * 2;
  const insetPx = Math.round(canvas * insets[0]);
  const image = themed
    ? await sharp(svg(monoPaths)).resize(canvas, canvas).toBuffer()
    : await sharp(source).resize(canvas - insetPx * 2, canvas - insetPx * 2).toBuffer();
  let raw = await sharp({ create: { width: canvas, height: canvas, channels: 4,
    background: themed ? '#E5F2FF' : background } })
    .composite([{ input: image, left: themed ? 0 : insetPx, top: themed ? 0 : insetPx }])
    .png().toBuffer();
  raw = await sharp(raw).extract({ left: size / 2, top: size / 2, width: cropSize, height: cropSize })
    .resize(size, size).png().toBuffer();
  const mask = shape === 'circle'
    ? `<circle cx="${size / 2}" cy="${size / 2}" r="${size / 2}" fill="white"/>`
    : `<rect width="${size}" height="${size}" rx="${size * 0.23}" fill="white"/>`;
  return sharp(raw).composite([{ input: Buffer.from(`<svg xmlns="http://www.w3.org/2000/svg" width="${size}" height="${size}">${mask}</svg>`), blend: 'dest-in' }]).png().toBuffer();
}

(async () => {
  const preview = path.join(root, 'dist/ui-review/launcher-cat-preview.png');
  fs.mkdirSync(path.dirname(preview), { recursive: true });
  const placements = [];
  const captions = [];
  for (const [column, shape, themed] of [[0, 'circle', false], [1, 'squircle', false], [2, 'circle', true]]) {
    for (const [row, size] of [[0, 192], [1, 96], [2, 48]]) {
      const left = 40 + column * 256 + (192 - size) / 2;
      const top = 56 + row * 250 + (192 - size) / 2;
      placements.push({ input: await adaptive(shape, size, themed), left, top });
      captions.push(`<text x="${40 + column * 256 + 96}" y="${282 + row * 250}" text-anchor="middle" font-family="sans-serif" font-size="18" fill="#304660">${themed ? 'Themed' : shape} · ${size}px</text>`);
    }
  }
  placements.push({ input: Buffer.from(`<svg xmlns="http://www.w3.org/2000/svg" width="824" height="816">${captions.join('')}</svg>`), left: 0, top: 0 });
  await sharp({ create: { width: 824, height: 816, channels: 4, background: '#F4F6FB' } })
    .composite(placements).png().toFile(preview);
  console.log(JSON.stringify({ preview, verification: 'static adaptive-mask preview; not native launcher rendering' }));
})().catch(error => { console.error(error.message); process.exit(1); });
