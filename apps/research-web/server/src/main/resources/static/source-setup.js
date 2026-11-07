// Source-specific hints and unsaved selections enhance the same native HTML forms.
const editor = document.getElementById('source-editor');
if (editor) {
  const origin = editor.elements.origin;
  const locationLabel = document.getElementById('source-location-label');
  const help = document.getElementById('source-location-help');
  const git = document.getElementById('git-source-settings');
  const updateSource = () => {
    const local = origin.value === 'LOCAL';
    locationLabel.textContent = local ? 'Local source directory' : 'GitHub repository URL';
    help.textContent = local ? `Choose a directory under ${help.dataset.inputRoot}.` : 'Use a public repository URL, for example https://github.com/owner/repository.';
    git.hidden = local;
    for (const field of git.querySelectorAll('input')) field.disabled = local;
  };
  origin.addEventListener('change', updateSource);
  updateSource();
}
const selection = document.getElementById('project-selection');
if (selection) {
  const boxes = [...selection.querySelectorAll('input[name=selected]')];
  const saved = boxes.map(box => box.checked);
  const otherSelected = Number(selection.dataset.otherSelected);
  const updateSelection = () => {
    const count = otherSelected + boxes.filter(box => box.checked).length;
    const changed = boxes.some((box, index) => box.checked !== saved[index]);
    document.getElementById('project-selection-count').textContent = `${count} selected for this run`;
    document.getElementById('project-selection-help').textContent = count === 0
      ? 'Select at least one source to continue.'
      : changed
          ? 'Selection changed. Review or Save selection to keep it.'
          : 'Review the whole selection. Waiting projects start as workers become available.';
    for (const button of selection.querySelectorAll('button[value=ANALYZE],button[value=ACQUIRE]')) button.disabled = count === 0;
  };
  selection.addEventListener('change', updateSelection);
  updateSelection();
}
const error = document.querySelector('[role=alert]');
if (error) { error.tabIndex = -1; error.focus(); }
