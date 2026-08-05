const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

// The brand icon that gets recolored into the animation.
const SRC = path.resolve(__dirname, '../../public/favicon.png');

(async () => {
  const b64in = fs.readFileSync(SRC).toString('base64');
  const launchOpts = {};
  if (process.env.CHROMIUM_PATH) launchOpts.executablePath = process.env.CHROMIUM_PATH;
  const browser = await chromium.launch(launchOpts);
  const page = await browser.newPage();
  const dataUri = await page.evaluate(async (src) => {
    const img = new Image();
    img.src = 'data:image/png;base64,' + src;
    await img.decode();
    const w = img.naturalWidth, h = img.naturalHeight;
    const c = document.createElement('canvas'); c.width = w; c.height = h;
    const ctx = c.getContext('2d');
    ctx.drawImage(img, 0, 0);
    const d = ctx.getImageData(0, 0, w, h);
    const p = d.data;
    let minX = w, minY = h, maxX = 0, maxY = 0;
    for (let y = 0; y < h; y++) {
      for (let x = 0; x < w; x++) {
        const i = (y * w + x) * 4;
        const r = p[i], g = p[i + 1], bl = p[i + 2], a = p[i + 3];
        const lum = 0.299 * r + 0.587 * g + 0.114 * bl;
        // alpha: opaque where dark, transparent where near-white; smooth edge
        let al;
        if (lum > 235) al = 0;
        else if (lum < 60) al = 255;
        else al = Math.round((235 - lum) / (235 - 60) * 255);
        al = Math.round(al * (a / 255));   // respect any existing transparency
        p[i] = 255; p[i + 1] = 255; p[i + 2] = 255; p[i + 3] = al;  // white silhouette
        if (al > 20) {
          if (x < minX) minX = x; if (x > maxX) maxX = x;
          if (y < minY) minY = y; if (y > maxY) maxY = y;
        }
      }
    }
    ctx.putImageData(d, 0, 0);
    // crop to bounding box + small margin
    const m = Math.round(Math.max(w, h) * 0.02);
    minX = Math.max(0, minX - m); minY = Math.max(0, minY - m);
    maxX = Math.min(w - 1, maxX + m); maxY = Math.min(h - 1, maxY + m);
    const cw = maxX - minX + 1, ch = maxY - minY + 1;
    const oc = document.createElement('canvas'); oc.width = cw; oc.height = ch;
    oc.getContext('2d').drawImage(c, minX, minY, cw, ch, 0, 0, cw, ch);
    return { uri: oc.toDataURL('image/png'), cw, ch };
  }, b64in);
  await browser.close();

  const b64 = dataUri.uri.split(',')[1];
  console.log('mask cropped size:', dataUri.cw, 'x', dataUri.ch, ' bytes:', Buffer.from(b64, 'base64').length);

  // Inline the mask straight into render.html (the MASK const), so the page
  // stays self-contained. CSS masks won't load a file:// URL in Chromium, so a
  // data URI is required here.
  const rp = path.resolve(__dirname, 'render.html');
  const html = fs.readFileSync(rp, 'utf8');
  const re = /const MASK = ["'][^"']*["'];/;
  if (!re.test(html)) throw new Error('could not find `const MASK = ...` in render.html');
  fs.writeFileSync(rp, html.replace(re, 'const MASK = ' + JSON.stringify(dataUri.uri) + ';'));
  console.log('render.html MASK updated from', path.relative(process.cwd(), SRC));
})();
