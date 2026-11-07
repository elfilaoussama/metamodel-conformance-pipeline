// Poll only this review's active attempts. The native page and refresh link remain usable.
const attempts = [...document.querySelectorAll('[data-run-id]')]
  .filter(link => ['QUEUED', 'RUNNING', 'CANCELLING'].includes(link.dataset.runState));
const statusUrl = document.body.dataset.statusUrl;
if (attempts.length && statusUrl) {
  const connection = document.getElementById('review-connection');
  let pending = false;
  setInterval(async () => {
    if (pending || document.hidden) return;
    pending = true;
    try {
      const response = await fetch(statusUrl, {credentials: 'same-origin'});
      if (!response.ok) throw new Error('Status unavailable');
      const states = new Map((await response.json()).map(run => [run.id, run.state]));
      if (attempts.some(link => !states.has(link.dataset.runId))) throw new Error('Attempt missing');
      if (attempts.some(link => states.get(link.dataset.runId) !== link.dataset.runState)) location.reload();
      if (connection) connection.textContent = 'Live status connected. Checking every 2 seconds.';
    } catch {
      if (connection) connection.textContent = 'Live updates unavailable. Use Refresh attempts to check status.';
    } finally { pending = false; }
  }, 2000);
}
