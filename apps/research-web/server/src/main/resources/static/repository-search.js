// Progressive enhancement: compilation stays on the server; search is always explicit.
document.documentElement.classList.add('enhanced');
// Keep a single named text field as the canonical GET value and no-JavaScript fallback.
// Suggestions and chips enhance it; adding/removing never submits a search.
for (const input of document.querySelectorAll('[data-token-options]')) {
  const field = input.closest('label');
  const editor = document.createElement('div'); editor.className = 'token-editor';
  const chips = document.createElement('div'); chips.className = 'filter-tokens';
  chips.setAttribute('aria-label', input.name + ' values');
  const options = input.dataset.tokenOptions && document.getElementById(input.dataset.tokenOptions);
  const picker = document.createElement('select'); picker.setAttribute('aria-label', 'Add ' + input.name + ' suggestion');
  picker.append(new Option('Add a suggestion...', ''));
  if (options) for (const option of options.options) picker.append(new Option(option.value, option.value));
  const message = document.createElement('span'); message.className = 'field-help'; message.setAttribute('role', 'status');
  function values() { return [...new Set(input.value.split(',').map(value => value.trim()).filter(Boolean))]; }
  function changed() { input.dispatchEvent(new Event('input', {bubbles:true})); }
  function render() {
    chips.replaceChildren();
    for (const value of values()) {
      const button = document.createElement('button'); button.type = 'button'; button.className = 'filter-token';
      button.textContent = value + ' \u00d7'; button.setAttribute('aria-label', 'Remove ' + input.name + ' ' + value);
      button.addEventListener('click', event => {
        event.preventDefault(); input.value = values().filter(item => item !== value).join(','); changed(); (options ? picker : input).focus(); });
      chips.append(button);
    }
    for (const option of picker.options) option.disabled = !!option.value && values().some(value => value.toLowerCase() === option.value.toLowerCase());
  }
  picker.addEventListener('change', () => {
    if (!picker.value) return;
    if (values().length >= 5) message.textContent = 'Remove a value first (five maximum).';
    else { input.value = [...values(), picker.value].join(','); message.textContent = ''; changed(); }
    picker.value = '';
  });
  input.addEventListener('input', render);
  editor.append(chips); if (options) editor.append(picker); editor.append(message); field.after(editor); render();
}
const form = document.querySelector('#repository-search');
if (form) {
  const controls = document.querySelector('#search-controls');
  const narrow = window.matchMedia('(max-width: 940px)');
  const panel = document.querySelector('#discovery-filters');
  const toggle = document.querySelector('#toggle-filters');
  function setFilters(visible) {
    panel.hidden = !visible;
    document.querySelector('.discovery-layout').classList.toggle('filters-hidden', !visible);
    toggle.setAttribute('aria-expanded', String(visible));
    toggle.textContent = visible ? 'Hide filters' : 'Show filters';
  }
  toggle.addEventListener('click', () => setFilters(panel.hidden));
  function arrangeFilters() { controls.open = true; setFilters(!narrow.matches || controls.dataset.hasResults !== 'true'); }
  narrow.addEventListener('change', arrangeFilters); arrangeFilters();
  let timer, pending;
  const mode = form.elements.namedItem('mode');
  const feedback = document.querySelector('#query-feedback');
  function showMode() {
    for (const node of form.querySelectorAll('[data-builder]')) node.hidden = mode.value === 'RAW';
    for (const node of form.querySelectorAll('[data-raw]')) node.hidden = mode.value !== 'RAW';
    for (const control of form.querySelectorAll('[data-builder] input,[data-builder] select')) control.disabled = mode.value === 'RAW';
  }
  async function preview() {
    pending?.abort(); pending = new AbortController(); const current = pending;
    try {
      const response = await fetch('/repositories/preview?' + new URLSearchParams(new FormData(form)), {signal:current.signal});
      if (!response.ok) throw new Error('Preview unavailable');
      const result = await response.json();
      if (current !== pending) return;
      document.querySelector('#query-preview').textContent = result.valid ? result.query : 'Check your search criteria';
      feedback.textContent = result.valid ? 'Preview only. Search GitHub to retrieve candidates.' : result.message;
      for (const control of form.querySelectorAll('[aria-invalid="true"]')) control.removeAttribute('aria-invalid');
      if (!result.valid) form.elements.namedItem(result.field)?.setAttribute('aria-invalid', 'true');
    } catch (error) {
      if (error.name !== 'AbortError') feedback.textContent = 'Live preview unavailable. Use Preview query to validate your criteria.';
    }
  }
  form.addEventListener('input', () => { showMode(); clearTimeout(timer); timer = setTimeout(preview, 250); });
  form.addEventListener('change', showMode); showMode();
  let submitting = false;
  form.addEventListener('submit', event => {
    if (submitting) { event.preventDefault(); return; }
    submitting = true; pending?.abort(); clearTimeout(timer);
    form.setAttribute('aria-busy', 'true');
    document.querySelector('#search-status').hidden = false;
    document.querySelector('#search-status-title').textContent = event.submitter?.value === 'preview' ? 'Checking search criteria…' : 'Searching GitHub…';
    document.querySelector('#search-status').scrollIntoView({block:'nearest'});
  });
  window.addEventListener('pageshow', () => { submitting = false; form.removeAttribute('aria-busy'); document.querySelector('#search-status').hidden = true; });
}
const selection = document.querySelector('#candidate-selection');
if (selection) {
  const boxes = [...selection.querySelectorAll('input[name="checked"]')];
  const initial = boxes.filter(box => box.checked).length;
  const master = document.querySelector('#select-all-candidates');
  master.addEventListener('change', () => { for (const box of boxes) box.checked = master.checked; update(); });
  function update() {
    const checked = boxes.filter(box => box.checked).length;
    master.checked = boxes.length > 0 && checked === boxes.length;
    master.indeterminate = checked > 0 && checked < boxes.length;
    master.disabled = boxes.length === 0;
    const total = Number(selection.dataset.saved) - initial + checked;
    const changed = boxes.some(box => box.checked !== box.defaultChecked);
    document.querySelector('#selection-count').textContent = `${total} selected across pages${changed ? ' · unsaved changes on this page' : ' · saved'}`;
    const configure = selection.querySelector('button[value="ADD"]');
    configure.disabled = total === 0;
    configure.title = total === 0 ? 'Select at least one repository first.' : 'Review the selected source configuration.';
    for (const box of boxes) box.closest('.repository-row').classList.toggle('is-selected', box.checked);
  }
  selection.addEventListener('change', update);
  for (const button of selection.querySelectorAll('[data-select-page]')) button.addEventListener('click', event => {
    event.preventDefault();
    for (const box of boxes) box.checked = button.dataset.selectPage === 'true'; update();
  });
  update();
  // Never claim these changes were saved, and do not silently discard them on navigation.
  let submitting = false;
  selection.addEventListener('submit', () => { submitting = true; });
  window.addEventListener('beforeunload', event => {
    if (!submitting && boxes.some(box => box.checked !== box.defaultChecked)) { event.preventDefault(); event.returnValue = ''; }
  });
}

const density = document.querySelector('#density-toggle');
if (density) {
  const lists = document.querySelectorAll('.repository-list');
  function applyDensity(compact) {
    density.setAttribute('aria-pressed', String(compact));
    for (const list of lists) list.dataset.density = compact ? 'compact' : 'comfortable';
  }
  try { applyDensity(localStorage.getItem('repository-density') === 'compact'); } catch { applyDensity(false); }
  density.addEventListener('click', () => {
    const compact = density.getAttribute('aria-pressed') !== 'true'; applyDensity(compact);
    try { localStorage.setItem('repository-density', compact ? 'compact' : 'comfortable'); } catch { /* Preference storage is optional. */ }
  });
}
