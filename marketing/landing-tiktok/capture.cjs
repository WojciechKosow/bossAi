const { chromium } = require('playwright');
const { spawn } = require('child_process');
const ffmpegPath = require('ffmpeg-static');
const path = require('path');

const FPS = 30;
const TOTAL = 20.2;
const HOLD = 0.8;              // freeze last frame
const OUT = path.resolve(__dirname, 'toucan-tiktok.mp4');
const W = 1080, H = 1920;

function write(stream, buf) {
  return new Promise((res) => { stream.write(buf) ? res() : stream.once('drain', res); });
}

(async () => {
  const totalFrames = Math.round((TOTAL + HOLD) * FPS);
  const lastT = TOTAL;

  const ff = spawn(ffmpegPath, [
    '-y',
    '-f', 'image2pipe', '-c:v', 'mjpeg', '-framerate', String(FPS), '-i', '-',
    '-c:v', 'libx264', '-preset', 'slow', '-crf', '18',
    '-pix_fmt', 'yuv420p', '-profile:v', 'high', '-level', '4.2',
    '-movflags', '+faststart',
    '-vf', `scale=${W}:${H}`,
    OUT,
  ], { stdio: ['pipe', 'inherit', 'inherit'] });

  // Use CHROMIUM_PATH if set, otherwise let Playwright resolve its own bundled Chromium.
  const launchOpts = {
    args: ['--force-color-profile=srgb', '--font-render-hinting=none', '--disable-lcd-text', '--hide-scrollbars'],
  };
  if (process.env.CHROMIUM_PATH) launchOpts.executablePath = process.env.CHROMIUM_PATH;
  const browser = await chromium.launch(launchOpts);
  const page = await browser.newPage({ viewport: { width: W, height: H }, deviceScaleFactor: 1 });
  await page.goto('file://' + path.resolve(__dirname, 'render.html'));
  await page.waitForFunction('window.__ready === true', { timeout: 30000 });

  const start = Date.now();
  for (let f = 0; f < totalFrames; f++) {
    const t = Math.min(f / FPS, lastT);
    await page.evaluate((tt) => window.seek(tt), t);
    const buf = await page.screenshot({ type: 'jpeg', quality: 96, clip: { x: 0, y: 0, width: W, height: H } });
    await write(ff.stdin, buf);
    if (f % 60 === 0) {
      const pct = ((f / totalFrames) * 100).toFixed(0);
      const eta = ((Date.now() - start) / (f + 1) * (totalFrames - f) / 1000).toFixed(0);
      console.log(`frame ${f}/${totalFrames} (${pct}%)  eta ${eta}s`);
    }
  }
  ff.stdin.end();
  await browser.close();

  await new Promise((res, rej) => {
    ff.on('close', (code) => code === 0 ? res() : rej(new Error('ffmpeg exit ' + code)));
  });
  console.log('DONE ->', OUT, '  frames:', totalFrames, '  duration:', (totalFrames / FPS).toFixed(2) + 's');
})();
