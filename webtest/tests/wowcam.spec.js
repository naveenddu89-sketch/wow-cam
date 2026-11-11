const { test, expect } = require('@playwright/test');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const fs = require('node:fs');

const MIRROR = pathToFileURL(path.join(__dirname, '..', 'wowcam-mirror.html')).href;
const SHOTS = path.join(__dirname, 'screenshots');

const VIEWPORTS = [
  { name: '320x480-budget', width: 320, height: 480 },
  { name: '360x640-compact', width: 360, height: 640 },
  { name: '393x852-tall', width: 393, height: 852 },
  { name: '412x915-flagship', width: 412, height: 915 },
];

// Every control that must remain individually tappable.
const INTERACTIVE = [
  'flash',
  'flip',
  'fps',
  'filter',
  'timer',
  'zoom',
  'record',
];

test.beforeAll(() => {
  fs.mkdirSync(SHOTS, { recursive: true });
});

async function openMirror(page) {
  await page.goto(MIRROR);
  await page.evaluate(() => document.fonts.ready);
  await expect(page.getByTestId('record')).toBeVisible();
}

async function boxes(page) {
  const out = {};
  for (const id of INTERACTIVE) {
    const box = await page.getByTestId(id).boundingBox();
    expect(box, `${id} must have a layout box`).not.toBeNull();
    out[id] = box;
  }
  return out;
}

function intersectionArea(a, b) {
  const w = Math.min(a.x + a.width, b.x + b.width) - Math.max(a.x, b.x);
  const h = Math.min(a.y + a.height, b.y + b.height) - Math.max(a.y, b.y);
  return w > 0 && h > 0 ? w * h : 0;
}

async function assertLayout(page, size) {
  // 1. No scrolling / clipping at the document level.
  const metrics = await page.evaluate(() => ({
    innerWidth: window.innerWidth,
    innerHeight: window.innerHeight,
    scrollWidth: document.documentElement.scrollWidth,
    scrollHeight: document.documentElement.scrollHeight,
  }));
  expect(metrics.scrollWidth, 'no horizontal overflow').toBeLessThanOrEqual(metrics.innerWidth);
  expect(metrics.scrollHeight, 'no vertical overflow').toBeLessThanOrEqual(metrics.innerHeight);

  // 2. All controls are inside the viewport with a real touch target.
  const map = await boxes(page);
  for (const [id, b] of Object.entries(map)) {
    expect(b.x, `${id} left edge`).toBeGreaterThanOrEqual(-0.5);
    expect(b.y, `${id} top edge`).toBeGreaterThanOrEqual(-0.5);
    expect(b.x + b.width, `${id} right edge`).toBeLessThanOrEqual(size.width + 0.5);
    expect(b.y + b.height, `${id} bottom edge`).toBeLessThanOrEqual(size.height + 0.5);
    expect(Math.min(b.width, b.height), `${id} touch target`).toBeGreaterThanOrEqual(32);
  }

  // 3. Controls never overlap each other.
  for (let i = 0; i < INTERACTIVE.length; i += 1) {
    for (let j = i + 1; j < INTERACTIVE.length; j += 1) {
      const a = map[INTERACTIVE[i]];
      const b = map[INTERACTIVE[j]];
      expect(
        intersectionArea(a, b),
        `${INTERACTIVE[i]} must not overlap ${INTERACTIVE[j]}`
      ).toBeLessThanOrEqual(1);
    }
  }

  // 4. Top hardware row and bottom control stack stay in separate bands.
  const top = await page.locator('.top-row').boundingBox();
  const bottom = await page.getByTestId('bottom').boundingBox();
  expect(top.y + top.height, 'top row ends before bottom stack begins')
    .toBeLessThanOrEqual(bottom.y + 0.5);

  // 5. The record ring fits inside its 30%-wide hit area.
  const ring = await page.getByTestId('recordRing').boundingBox();
  const record = map.record;
  expect(ring.x).toBeGreaterThanOrEqual(record.x - 0.5);
  expect(ring.y).toBeGreaterThanOrEqual(record.y - 0.5);
  expect(ring.x + ring.width).toBeLessThanOrEqual(record.x + record.width + 0.5);
  expect(ring.y + ring.height).toBeLessThanOrEqual(record.y + record.height + 0.5);

  // 6. No text is clipped by its own control (loop over the text-bearing nodes).
  const clipped = await page.evaluate(() => {
    const selectors = [
      '.banner', '.fps', '.chips .chip', '.zoom-caption', '.zoom-value',
      '.elapsed', '.status', '.hint', '.audio', '.quality',
    ];
    return selectors.flatMap((selector) => {
      return Array.from(document.querySelectorAll(selector)).map((el) => {
        const box = el.getBoundingClientRect();
        const cs = getComputedStyle(el);
        const overflowX = el.scrollWidth - el.clientWidth;
        const overflowY = el.scrollHeight - el.clientHeight;
        const inViewport =
          box.x >= -0.5 && box.y >= -0.5 &&
          box.right <= window.innerWidth + 0.5 &&
          box.bottom <= window.innerHeight + 0.5;
        return {
          selector,
          text: (el.textContent || '').trim(),
          overflowX,
          overflowY,
          inViewport,
          display: cs.display,
        };
      });
    }).filter((r) => r.display !== 'none' &&
      (r.overflowX > 1 || r.overflowY > 1 || !r.inViewport));
  });
  expect(clipped, 'no clipped labels or chips').toEqual([]);
}

for (const size of VIEWPORTS) {
  test.describe(`${size.name}`, () => {
    test.use({ viewport: { width: size.width, height: size.height } });

    test(`controls fit with no overlap at ${size.name}`, async ({ page }) => {
      await openMirror(page);
      await page.screenshot({
        path: path.join(SHOTS, `layout-${size.name}.png`),
        animations: 'disabled',
      });
      await assertLayout(page, size);
    });
  });
}

test.describe('pro controls', () => {
  test.use({ viewport: { width: 393, height: 852 } });

  test('flash, flip, timer, countdown, recording, filters and zoom behave', async ({ page }) => {
    await openMirror(page);

    const flash = page.getByTestId('flash');
    const flip = page.getByTestId('flip');
    const timer = page.getByTestId('timer');
    const filter = page.getByTestId('filter');
    const record = page.getByTestId('record');
    const hint = page.getByTestId('hint');
    const cd = page.getByTestId('countdown');

    // --- flash (back lens) -------------------------------------------------
    await expect(flash).toBeEnabled();
    await expect(flash).toHaveAttribute('aria-pressed', 'false');
    await flash.click();
    await expect(flash).toHaveAttribute('aria-pressed', 'true');
    await expect(flash).toHaveAttribute('aria-label', 'Turn light off');
    await page.screenshot({ path: path.join(SHOTS, 'controls-flash-on.png'), animations: 'disabled' });

    // --- flip to front: torch is dropped, flash is disabled ----------------
    await flip.click();
    await expect(flash).toBeDisabled();
    await expect(flash).toHaveAttribute('aria-pressed', 'false');
    await page.screenshot({ path: path.join(SHOTS, 'controls-front-camera.png'), animations: 'disabled' });

    // --- flip back: flash available again ---------------------------------
    await flip.click();
    await expect(flash).toBeEnabled();

    // --- zoom 1x -> 5x -----------------------------------------------------
    await expect(page.getByTestId('zoomValue')).toHaveText('1.0x');
    await page.getByTestId('zoom').evaluate((el) => {
      el.value = '1000';
      el.dispatchEvent(new Event('input', { bubbles: true }));
    });
    await expect(page.getByTestId('zoomValue')).toHaveText('5.0x');
    await page.screenshot({ path: path.join(SHOTS, 'controls-zoom-5x.png'), animations: 'disabled' });
    await page.getByTestId('zoom').evaluate((el) => {
      el.value = '0';
      el.dispatchEvent(new Event('input', { bubbles: true }));
    });

    // --- filter cycle normal -> vivid -> mono -> warm -> normal ------------
    await expect(filter).toHaveText('FILTER • NORMAL');
    await filter.click();
    await expect(filter).toHaveText('FILTER • VIVID');
    await expect(page.locator('body')).toHaveClass(/look-vivid/);
    await page.screenshot({ path: path.join(SHOTS, 'controls-filter-vivid.png'), animations: 'disabled' });
    await filter.click();
    await expect(filter).toHaveText('FILTER • MONO');
    await expect(page.locator('body')).toHaveClass(/look-mono/);
    await filter.click();
    await expect(filter).toHaveText('FILTER • WARM');
    await filter.click();
    await expect(filter).toHaveText('FILTER • NORMAL');
    await expect(page.locator('body')).not.toHaveClass(/look-vivid|look-mono|look-warm/);

    // --- timer 3s + non-blocking countdown --------------------------------
    await expect(timer).toHaveText('TIMER • OFF');
    await timer.click();
    await expect(timer).toHaveText('TIMER • 3S');
    await expect(timer).toHaveClass(/active/);

    await record.click();
    await expect(cd).toBeVisible();
    await expect(page.getByTestId('countdown')).toContainText('3');
    await page.screenshot({ path: path.join(SHOTS, 'controls-countdown-3s.png'), animations: 'disabled' });
    await expect(page.locator('body')).not.toHaveClass(/recording/);

    // Countdown ends by itself, then recording starts.
    await expect(page.locator('body')).toHaveClass(/recording/, { timeout: 5_000 });
    await expect(cd).toBeHidden();
    await expect(hint).toHaveText('TAP TO STOP');
    await expect(page.getByTestId('recordCore')).toHaveCSS('border-radius', '7px');
    await page.waitForTimeout(1_100);
    await expect(page.getByTestId('elapsed')).toHaveText('00:01');
    await page.screenshot({ path: path.join(SHOTS, 'controls-recording.png'), animations: 'disabled' });

    // --- stop recording ----------------------------------------------------
    await record.click();
    await expect(page.locator('body')).not.toHaveClass(/recording/);
    await expect(hint).toHaveText('TAP TO RECORD');
    await expect(page.getByTestId('elapsed')).toHaveText('00:00');

    // --- countdown is cancellable (tap overlay) ---------------------------
    await record.click();
    await expect(cd).toBeVisible();
    await cd.click();
    await expect(cd).toBeHidden();
    await expect(page.locator('body')).not.toHaveClass(/recording/);
    await expect(hint).toHaveText('TAP TO RECORD');

    // --- 10s timer cycles correctly ---------------------------------------
    await timer.click();
    await expect(timer).toHaveText('TIMER • 10S');
    await timer.click();
    await expect(timer).toHaveText('TIMER • OFF');
  });
});
