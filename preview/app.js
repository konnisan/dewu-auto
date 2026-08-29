const advancedToggle = document.querySelector('#advancedToggle');
const advancedPanel = document.querySelector('#advancedPanel');
const startButton = document.querySelector('#startButton');
const stopButton = document.querySelector('#stopButton');
const runMessage = document.querySelector('#runMessage');

advancedToggle.addEventListener('click', () => {
  const isExpanded = advancedToggle.getAttribute('aria-expanded') === 'true';
  advancedToggle.setAttribute('aria-expanded', String(!isExpanded));
  advancedPanel.hidden = isExpanded;
});

startButton.addEventListener('click', () => {
  startButton.disabled = true;
  startButton.textContent = '正在扫描页面…';
  runMessage.textContent = '正在解析可见任务，不会点击报名按钮';

  window.setTimeout(() => {
    document.querySelector('#scannedCount').textContent = '18';
    document.querySelector('#eligibleCount').textContent = '6';
    document.querySelector('#excludedCount').textContent = '12';
    startButton.disabled = false;
    startButton.textContent = '重新筛选预演';
    runMessage.textContent = '预演完成：符合 6 项，已排除 12 项';
  }, 800);
});

stopButton.addEventListener('click', () => {
  startButton.disabled = false;
  startButton.textContent = '开始筛选预演';
  runMessage.textContent = '预演已停止，不会操作报名按钮';
});
