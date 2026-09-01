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
  await page.getByText('请先确认“报名后无法取消”').waitFor();
  await page.locator('#riskAck').check();
  await page.locator('#startButton').click();
  await page.getByText('已达到目标：报名成功 1/1，自动停止').waitFor();

  const applyToJoinButtons = await page.locator('button').filter({ hasText: /申请入驻/ }).count();
  if (applyToJoinButtons !== 0) {
    throw new Error(`发现 ${applyToJoinButtons} 个申请入驻按钮`);
  }

  await page.screenshot({
    path: path.resolve(__dirname, 'dewu-filter-preview-expanded.png'),
    fullPage: true,
  });

  console.log('PREVIEW_OK execution=expanded riskAck=required result=targetReached applyToJoinButtons=0');
  await browser.close();
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
