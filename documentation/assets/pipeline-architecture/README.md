# Caption-ready pipeline architecture (2026-11-17)

This folder contains the **current editable research architecture figure** for the metamodel-conformance pipeline. It is a schematic of the scientific engine, not an experimental-results chart or a claim of complete source-language correctness.

The figure has **no title inside the outer dashed system boundary**, because its figure title belongs in the manuscript caption. It uses the user's GitHub, Java, Python, and C++ logo assets; the GitHub mark is drawn at 1.25 times the earlier height (0.775 cm). The final module names include **Verification Artifacts** and **Formal Replay**.

## Build (LaTeX)

Run from this folder so the `assets/` relative paths resolve:

```bash
pdflatex -interaction=nonstopmode -halt-on-error main.tex
```

For a two-column IEEEtran placement example:

```bash
pdflatex -interaction=nonstopmode -halt-on-error ieee_figure_example.tex
```

## Files

- `pipeline_modules_with_assets.tikz`: the fully editable source of the architecture, including all module boundaries, connectors, glyphs, and labels
- `main.tex`: standalone LaTeX entry point, which inputs the TikZ file
- `main.pdf`: compiled single-page architecture figure, with vector lines and fonts and embedded logo PNGs
- `preview.png`: PNG rendered from the compiled `main.pdf` for GitHub preview
- `ieee_figure_example.tex` / `ieee_figure_example.pdf`: a full-width IEEEtran inclusion example and its compiled rendering
- `assets/github_logo.png`, `assets/java_logo.png`, `assets/python_logo.png`, `assets/cpp_logo.png`: required user-provided logo assets

## Visual and architectural conventions

- Long-dashed outline: boundary of the pipeline; fine dashed outlines: architectural modules
- Plain rectangular boxes: active components or processing operations; document shapes: saved or input files
- Cylinders: structured observation or retained artifact collection (not necessarily database services)
- Diamond: admissibility decision; labeled rounded boxes: invariant verdicts and replay status
- Solid arrows: execution/data/provenance flow; dotted arrow: rule-profile evidence requirements
- `Formal Replay` verifies preserved formal inputs and decisions; it does not independently re-observe the original source project

**Important:** The folder also contains QA-related files from *older* diagram revisions. Treat those as historical, not as a current quality certificate. The current PDF and preview in this folder were recompiled and visually checked on 2026-11-17. Previous revisions remain recoverable from Git history.

Detailed end-to-end architecture and semantic limitations: [`../../explanation/end-to-end-architecture.md`](../../explanation/end-to-end-architecture.md).
