const advancedToggle = document.querySelector('#advancedToggle');
const advancedPanel = document.querySelector('#advancedPanel');
const startButton = document.querySelector('#startButton');
const stopButton = document.querySelector('#stopButton');
const runMessage = document.querySelector('#runMessage');
const riskAck = document.querySelector('#riskAck');

advancedToggle.addEventListener('click', () => {
  const isExpanded = advancedToggle.getAttribute('aria-expanded') === 'true';
  advancedToggle.setAttribute('aria-expanded', String(!isExpanded));
  advancedPanel.hidden = isExpanded;
});

startButton.addEventListener('click', () => {
  if (!riskAck.checked) {
    runMessage.textContent = '请先确认“报名后无法取消”';
    return;
  }
  startButton.disabled = true;
  startButton.textContent = '正在执行…';
  runMessage.textContent = '列表初筛通过，正在复核任务详情与达人要求';

  window.setTimeout(() => {
    document.querySelector('#scannedCount').textContent = '18';
    document.querySelector('#eligibleCount').textContent = '6';
    document.querySelector('#excludedCount').textContent = '12';
    document.querySelector('#enrolledCount').textContent = '1';
    startButton.disabled = false;
    startButton.textContent = '重新开始';
    runMessage.textContent = '已达到目标：报名成功 1/1，自动停止';
  }, 800);
});

stopButton.addEventListener('click', () => {
  startButton.disabled = false;
  startButton.textContent = '开始自动报名';
  runMessage.textContent = '自动报名任务已停止';
});
