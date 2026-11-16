# Metamodel conformance pipeline — architecture figure

This is an **editable LaTeX/TikZ component-and-connector architecture** of the current EMF/Ecore, XMI and exact-Alloy pipeline. It is not a plot of experimental results, and the outcome labels are *per registered invariant*.

## Build

```bash
pdflatex -interaction=nonstopmode -halt-on-error main.tex
```

The four logo image assets in `assets/` must remain adjacent to the TeX source. The included `ieee_figure_example.tex` demonstrates a `figure*` placement:

```bash
pdflatex -interaction=nonstopmode -halt-on-error ieee_figure_example.tex
```

## Shape / line semantics

| Mark | Meaning |
|---|---|
| Outer long-dashed boundary | Complete core pipeline |
| Thin cool-gray dashed rectangle / circle | Coherent architectural module |
| Solid white rectangle | Active processing component / operation |
| Pale blue document shape | File or source/generated formal artifact |
| Muted teal cylinder | Structured EMF/Ecore representation or persisted artifact bundle (not necessarily a relational database) |
| Diamond | Evidence-admissibility condition |
| Colored rounded pill | An **outcome**: green conformant; terracotta nonconformant; ochre not evaluated |
| Solid dark arrow | Data/control or recorded-artifact flow |
| Fine dotted blue-gray arrow | Declarative rule-profile dependency / evidence prerequisite, not an execution step |

The logos identify source and observer technologies, not separate processing stages.

## Scientific interpretation

- `CONFORMANT` means the registered, bounded invariant evaluation found no violating witness **for that admitted rule**, not that arbitrary software behavior was proved correct.
- `NON_CONFORMANT` means the rule evaluation reports a violation witness under the selected profile and observation.
- `NOT_EVALUATED` means the rule's necessary evidence is incomplete or the formal evaluation cannot return a justified result. The drawn `Ready?` gate illustrates the evidence-incomplete pathway; it is not an exhaustive error-state automaton.
- The dotted line from the invariant profile to the evidence-admission diamond represents evidence requirements loaded from the registry.
- The artifact collector receives the XMI, the exact Alloy instance, and recorded decisions/witnesses; the capsule verifier **replays retained formal evidence**, not the original source-observation step.

The diagram documents architectural contracts; it does **not** establish independent experimental validity. Shapes and colors are deliberately redundant and remain understandable in grayscale.

## Contents

- `main.tex`: standalone entry point
- `pipeline_modules_with_assets.tikz`: editable vector drawing
- `main.pdf`: compiled PDF (vector geometry/fonts; supplied logos embedded as raster assets)
- `preview.png`: preview of `main.pdf`
- `ieee_figure_example.tex`, `ieee_figure_example.pdf`: IEEEtran full-width figure reproduction
- `assets/`: provided C++, Python, Java, GitHub logo assets
- `QA_REPORT.md`: screenshot-and-print-size verification
- `iterations/`: evidence from compile/render/inspect/revise iterations
