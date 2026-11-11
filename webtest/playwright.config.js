const { defineConfig } = require('@playwright/test');

module.exports = defineConfig({
  testDir: './tests',
  timeout: 90_000,
  expect: { timeout: 10_000 },
  fullyParallel: false,
  workers: 1,
  reporter: [['list'], ['html', { open: 'never' }]],
  outputDir: 'test-results',
  use: {
    headless: false,          // headed Chromium
    deviceScaleFactor: 2,
    screenshot: 'off',
    trace: 'off',
    video: 'off',
    actionTimeout: 10_000,
  },
});
