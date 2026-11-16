# Architecture diagram: iterative visual and technical QA

**Scope:** The existing EMF/Ecore/XMI/Alloy core-architecture figure, not scientific verification of the pipeline. Based on the preceding `pipeline_architecture_review_fixed` version.

## Rendered revision loops

| Iteration | Screenshot / intervention | Defect and disposition |
|---|---|---|
| 01 | `iterations/iteration_01.png` | Raised shared-model core; enlarged Evidence admission; gave artifacts and documents consistent tint. Detected overlapping conformant/nonconformant ellipse shapes, Replay status collision, and disconnected observer output. |
| 02 | `iterations/iteration_02.png` | Replaced ellipses with compact rounded *status pills* (not execution rectangles), separated replay status, connected observer rail. Detected a small diagonal observer link and long gaps in dotted prerequisite routing. |
| 03 | `iterations/iteration_03.png`, `iterations/ieee_page_03.png` | Orthogonalized observer output; shortened broken prerequisite-wire gaps; compiled in IEEEtran. Detected text under 7 pt after scaling, particularly status identifiers. |
| 04 | `iterations/iteration_04.png`, `preview.png` | Increased verdict labels, yes/no, replay text, and rule-profile filename to ~7.2 pt effective at IEEE full width; no overlap in final viewed screenshot. |

## Geometry and meaning

- **Dashed enclosing boundary**: entire research pipeline; **finer dashed containers**: architectural modules; center observation module remains a circle.
- **Rectangular processing nodes**: operations / adapters / solver / capsule services.
- **Pale-blue wavy document nodes**: source/generated formal files, including `observation.xmi`, `repository-instance.als`, and the verification capsule.
- **Muted-teal cylinders**: structured EMF/Ecore observation representation and retained artifact bundle, **not** arbitrary processing.
- **Evidence diamond**: readiness/admissibility gate; solid dark-blue arrows indicate main data/control flows, thin pale dotted arrow indicates a rule-prerequisite dependency.
- **Verdict pills**: `CONFORMANT` (green), `NON_CONFORMANT` (terracotta), `NOT_EVALUATED` (ochre). Colors are reinforced with direct text labels. Non-conformance is based on a violating witness, not on a general claim that the source program is invalid.

## Final checks

- LaTeX compilation of standalone source and IEEEtran `figure*`: **passed**.
- `main.pdf` has one page; IEEE test document has two pages. Both are viewable.
- PDF diagram text is editable/vector; the **four user-provided logos remain raster images**, and are embedded as assets, not redrawn.
- No `Overfull \\hbox` or `Overfull \\vbox` warnings were found in the final standalone and IEEE build logs.
- Measured IEEE figure text in PDF: representative regular labels **7.23 pt**, registry filename **7.15 pt**, statuses **7.23 pt**. These measurements are based on the tested full-width two-column IEEEtran placement, not a single-column placement.
- Source code and graphical symbols denote the observed pipeline's *contract*. This figure does **not** demonstrate correctness of the pipeline's scientific judgments or that an experiment has been executed.
- The missing-evidence branch visually explains one way to obtain `NOT_EVALUATED`; solver/tool failures may also yield that outcome and are documented in the README rather than drawn as an additional crossing arrow.

## Remaining practical consideration

At a narrower journal width, all labels would shrink; keep the Figure* full double-column layout. The diagram retains only a small number of right-angle connectors where artifact provenance crosses the formal-module boundary; these are visible and distinguishable from dashed module outlines. Figure appearance and exact fonts should be checked once in the final manuscript's actual composition.
