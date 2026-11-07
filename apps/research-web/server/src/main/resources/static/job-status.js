// Only running jobs need polling. All navigation and forms work without JS.
if (document.body.dataset.active === "true") {
  const run = document.body.dataset.run;
  const updated = document.body.dataset.updated;
  const connection = document.getElementById("connection-state");
  let pending = false;
  setInterval(async () => {
    if (pending) return;
    pending = true;
    try {
      const response = await fetch(`/api/v1/runs/${encodeURIComponent(run)}`, { credentials: "same-origin" });
      if (!response.ok) throw new Error("Status unavailable");
      const current = await response.json();
      if (current.updated !== updated) location.reload();
      if (connection) connection.textContent = "Status checked at " + new Date().toISOString().slice(11, 19) + " UTC. Worker progress is shown above.";
    } catch { if (connection) connection.textContent = "Refresh status manually"; }
    finally { pending = false; }
  }, 2000);
}
