# Core pipeline architecture (TikZ, manuscript-ready)

This folder contains the current **software architecture figure** for the metamodel conformance pipeline. It represents the EMF/XMI observation and exact Alloy evaluation engine, not the earlier JSON/AIE checker used in the historical manuscript experiment.

## Files

- `pipeline_modules_with_assets.tikz` — editable LaTeX/TikZ source.
- `main.tex` — standalone entry point; compile with `pdflatex main.tex`.
- `architecture_final.pdf` — compiled PDF with editable/vector linework and type, and the user-provided raster logos.
- `preview.png` — high-resolution screenshot of the compiled PDF.
- `ieee_figure_example.tex` — two-column IEEEtran example using `figure*` and an explicit `\textwidth` scaling check.
- `ieee_test.pdf` — local rendered layout test (not part of the proposed publication).
- `assets/` — original GitHub, Java, Python, and C++ logo PNGs provided by the user.
- `QA_REPORT.md`, `qa_machine_checks.json` and `iterations/` — execution and visual review evidence.

## Scientific meaning

Source snapshots and build context reach the language observers through an explicit dispatcher. The observer outputs are normalized into a shared EMF/Ecore model and retained as `observation.xmi`. The versioned invariant profile specifies the rules and evidence prerequisites. The exact model encoder produces `repository-instance.als`; evidence-admitted work units are evaluated using Alloy/SAT4J to produce decisions and witnesses. Incomplete required evidence produces `NOT_EVALUATED`, not a positive conformance claim. The retained observation, exact Alloy instance, and decisions are inputs to capsule publication, and formal replay is separate from fresh source observation.

The figure documents architecture and **does not certify experimental results** or claim that a full Maven/Alloy run was reproduced.

## Integration

Place `pipeline_modules_with_assets.tikz`, `main.tex`, and `assets/` together. Compile in that directory, or replace the `\input{...}` call with the relative figure path from the manuscript. For an IEEE Access double-column figure, use `figure*`, not a single-column `figure`; at the tested text width the smallest displayed text was approximately 7.4 pt.

Assets remain the user-supplied PNG originals, displayed as small logos. The PDF's structural drawing and labels are vector. Rights to the third-party trademarks remain with their respective holders.