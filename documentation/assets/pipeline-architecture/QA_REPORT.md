# Pipeline architecture visual QA and semantic correspondence

## Basis

- Existing `documentation/assets/pipeline-architecture/pipeline_modules_with_assets.tikz` from GitHub at commit `984e56af00bcd232e75dafddbfb7fde4873da1a3`.
- User-provided render review: global flow, dispatch fan-out, connector corridors, retained artifact inputs, alignment, vocabulary, print size, and logo consistency.
- User's established design: one outer dashed boundary; horizontal and vertical dashed modules, plus one circular shared-observation module; limited black/gray palette and no legend.

## Seven actual render-and-review passes

| Pass | Inspection and correction |
| --- | --- |
| 01 | Rebuilt source entry at the upper left, made Dispatch a true fan-out, established independent XMI/yes/no connector routes, and added XMI/Alloy/decision provenance bus. Initial screenshot revealed tight source nodes, an evidence-heading collision, and oversized artifact store. |
| 02 | Separated source elements, moved the evidence feed to a shared XMI bus, reduced the artifact store label, and moved yes/no annotations. Screenshot showed the retained ellipse / profile-file layout still needed work. |
| 03 | Merged the two declarative rule files into one compact document node, removed dangling metadata-arrowheads and revised the replay region. Screenshot found replay nodes colliding. |
| 04 | Shortened replay status to `Status` and moved the replay components into a side-by-side chain. |
| 05 | Adjusted replay padding; compiled inside IEEEtran and inspected several 310-dpi crops. Found `NOT_EVALUATED` extending beyond its dashed boundary. |
| 06 | Moved `NOT_EVALUATED` wholly inside Evidence admission and added orthogonal spacing and readable yes/no annotations; preserved an unobstructed input corridor. |
| 07 | Increased separation between the XMI-to-encoder route and the rule-prerequisite route, and slightly reduced the single combined rule filename label to allow padding. |

## Status against the reviewer comments

| Reviewer point | Result |
| --- | --- |
| Provider looped along full upper/left outline | Fixed: top-left source module points directly into Dispatch. |
| Dispatch appeared bypassed | Fixed: Dispatch feeds one branching rail with three explicit tool arrows. |
| Spiraling main sequence | Simplified: source -> observers -> shared model -> formal verification -> retained artifacts; independent replay branches from the capsule. |
| Parallel connector congestion | Improved with separate corridors; source XMI feeds the encoder above, evidence below, and provenance along an independent lower rail. |
| Circle's title inconsistent | Fixed: centered title breaks the circle's upper dashed arc. Circle retained by design. |
| Work units arrow to Alloy not visible | Confirmed explicit arrow with a visible gap. |
| Oblique/diagonal paths | Main links are orthogonal; small arrowheads connect to component anchors. |
| Yes/no annotation too small | Both set at `\small`, placed adjacent to their branches. |
| Replay connector angled | Reorganized as a horizontal capsule -> capsule verifier -> status path. |
| Arrows ended at dashed module borders | Functional arrows terminate on their actual component nodes. |
| Registry/Alloy file merge cramped | A compact combined document node with a centered output was substituted; semantics of two declarative inputs retained. |
| Artifact store showed only decision provenance | Three source paths merge before the retained store: XMI, exact Alloy instance, and decisions. |
| Uneven module alignment | Left source/observers and right profile/formal modules share aligned edges. |
| Tight module padding | Fixed known overlap of NOT_EVALUATED and replay; minor profile label spacing validated visually. |
| Ellipse semantics unclear | Ellipses used only for states; solver and admission are processing components. |
| Gray fills / shape legend | Not used, respecting user request for restrained monochrome diagram and no legend. |
| Print legibility | IEEEtran `figure*` run: main text ~7.87 pt; compact rulefile label ~7.44 pt. Do not place single-column. |
| Jagged logos | Supplied full-resolution PNGs used as small embedded observer/source icons; embedded source resolution is >1,000 ppi at displayed size. No external logos were substituted. |

## Automated checks

- `pdflatex` standalone and IEEE integration both compiled with no overflow or fatal warnings.
- Every critical architecture label is present in the PDF's searchable text.
- The standalone PDF page is approximately 592 x 405 pt, sized for a `figure*` placement.
- The drawing and text are vector. Four logos are the only raster content, with high effective embedded resolution.
- `qa_machine_checks.json` records relevant measured font sizes and PDF metadata.

## Limitations

- Visual review remains qualitative and does not establish formal source-observer correctness or invariant soundness.
- Artifact-provenance lines intentionally occupy a narrow corridor below the evidence and verification modules. They represent retained inputs, not runtime control flow.
- This figure is too wide for an IEEE single column; use a full-width `figure*`.
- PDF output uses the four user-provided raster logos because those are the requested image assets; they are not vector-traced substitutes.
