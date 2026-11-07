// Native details remain usable without JavaScript. Avoid a long menu above mobile reading.
const chapters = document.querySelector('.manual-navigation');
if (chapters) {
  const narrow = matchMedia('(max-width: 1000px)');
  chapters.open = !narrow.matches;
  narrow.addEventListener('change', event => { chapters.open = !event.matches; });
}
