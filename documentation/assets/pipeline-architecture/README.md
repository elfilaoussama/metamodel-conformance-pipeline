# Metamodel conformance pipeline — research architecture figure

A LaTeX/TikZ rendering of the core EMF/XMI–Alloy architecture, not the historical JSON/AIE checker.

## Files

- `main.tex` — standalone build entrypoint.
- `pipeline_modules_with_assets.tikz` — editable figure source.
- `main.pdf` — compiled research figure (PDF, vector strokes and text, small embedded raster logos).
- `preview.png` — raster preview for GitHub.
- `ieee_figure_example.tex` — IEEEtran inclusion example.
- `assets/` — GitHub, Java, Python and C++ logo assets **derived from the supplied images**, downsampled for figure-size use.

## Build

From this directory, run `pdflatex main.tex`. For IEEEtran example run `pdflatex ieee_figure_example.tex`.

Keep `assets/` beside the LaTeX files, and compile from this directory so relative image paths resolve. The main figure PDF is already supplied; no CI build is required to view it.

**Scope:** This diagram concerns software architecture. It does not certify scientific correctness or imply that the experiment has been rerun. Logos are third-party marks; no rights are asserted here.