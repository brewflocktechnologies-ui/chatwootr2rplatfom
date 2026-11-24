import { defineConfig, devices } from '@playwright/test';

// Artifacts live under the repo-level artifacts/ folder (gitignored).
const ART = '../../artifacts/e2e';
// GF_FAULTS=1 selects the fault-injection suite and keeps its output separate from the golden-flow report.
const FAULTS = !!process.env.GF_FAULTS;
const HO08 = !!process.env.GF_HO08;
const OUT = FAULTS ? `${ART}/faults` : HO08 ? `${ART}/ho08` : ART;

export default defineConfig({
  testDir: './tests',
  testMatch: FAULTS ? /faults\.spec\.ts/ : HO08 ? /ho08\.spec\.ts/ : /(golden-flow|controls)\.spec\.ts/,
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 40 * 60_000,
  expect: { timeout: 30_000 },
  outputDir: `${OUT}/test-results`,
  reporter: [
    ['list'],
    ['html', { outputFolder: `${OUT}/html-report`, open: 'never' }],
    ['json', { outputFile: `${OUT}/results.json` }],
  ],
  use: {
    actionTimeout: 30_000,
    trace: 'off', // traces are started manually per browser context (see support.ts)
    screenshot: 'on',
    video: 'off',
    ...devices['Desktop Chrome'],
  },
  projects: [{ name: 'chromium', use: { browserName: 'chromium' } }],
});
