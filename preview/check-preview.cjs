const { chromium } = require('playwright');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

async function main() {
  const browser = await chromium.launch({
    executablePath: 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
    headless: true,
  });
  const page = await browser.newPage({ viewport: { width: 432, height: 862 } });
  await page.goto(pathToFileURL(path.resolve(__dirname, 'index.html')).href);

  await page.locator('#advancedToggle').click();
  if (!(await page.locator('#advancedPanel').isVisible())) {
    throw new Error('高级设置未展开');
  }

  await page.locator('#startButton').click();
  await page.getByText('预演完成：符合 6 项，已排除 12 项').waitFor();

  const forbiddenButtons = await page.locator('button').filter({ hasText: /报名|申请入驻/ }).count();
  if (forbiddenButtons !== 0) {
    throw new Error(`发现 ${forbiddenButtons} 个报名或申请入驻按钮`);
  }

  await page.screenshot({
    path: path.resolve(__dirname, 'dewu-filter-preview-expanded.png'),
    fullPage: true,
  });

  console.log('PREVIEW_OK advanced=visible result=completed forbiddenButtons=0');
  await browser.close();
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
