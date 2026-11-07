# Interface design rationale

The interface is a scientific workbench. Its purpose is to make experimental
inputs, actions, state and evidence understandable. A restrained appearance alone
does not make a tool scientifically valid or usable.

## Evidence from established tools

The following primary documentation was reviewed on 7 November 2026. These are
design precedents, not a claim that all scientific software follows one standard.

| Precedent | Documented pattern | Decision for Experiment Workspace |
|---|---|---|
| [JupyterLab interface](https://jupyterlab.readthedocs.io/en/stable/user/interface.html) | Stable navigation, central work area and contextual tools | Keep task navigation consistent across source, execution, results and documentation. |
| [JupyterLab running processes](https://jupyterlab.readthedocs.io/en/stable/user/running.html) | Server computation can outlive an open document | Distinguish leaving a page from cancelling a job; keep attempts available in history. |
| [Galaxy histories](https://training.galaxyproject.org/training-material/topics/galaxy-interface/tutorials/history/tutorial.html) | Inputs, analysis steps and outputs remain inspectable in history | Keep configuration receipts, failures and results linked to their attempts. |
| [KNIME nodes and workflows](https://docs.knime.com/build/nodes-workflows/) | Configuration, execution and result state are explicit | Separate readiness, queued/running/terminal state and scientific outcome. |
| [W3C status messages](https://www.w3.org/WAI/WCAG22/Understanding/status-messages.html) | Status updates must be programmatically identifiable | Use explicit status text, accessible state regions and messages independent of color. |
| [W3C animation from interactions](https://www.w3.org/WAI/WCAG22/Understanding/animation-from-interactions.html) | Nonessential motion can be disabled | Remove decorative motion; do not use animation to imply measured scientific progress. |
| [Diataxis](https://diataxis.fr/) | Tutorials, guides, explanations and reference answer different needs | Organize the same versioned manual for the repository and the application. |

The first four are established interaction patterns. W3C provides accessibility
criteria. Diataxis provides a documentation framework. None is a certification
that this application is usable or scientifically correct.

## Selected visual template

The current template is a compact workbench with graphite navigation, neutral
reading surfaces, teal action controls and a source launcher beside contextual
method information. The source launcher replaces the earlier three-card home
page. Search, tables, result inspection and the manual share the same design roles.

The [EMBL-EBI Visual Framework](https://stable.visual-framework.dev/) provides a
scientific-web precedent for reusable components, explicit labels and structured
design tokens. [Carbon color guidance](https://www.carbondesignsystem.com/building-blocks/foundations/color/overview)
informs the separation of neutral layers, action colors and semantic feedback.
This is an adaptation of those principles in native Thymeleaf and CSS, not an
EMBL or IBM branded interface or a dependency on their full frontend frameworks.

`design-tokens.css` owns the palette, typography and timing roles.
`workspace-shell.css` owns the shared layout, navigation, controls and motion.
Feature styles consume these roles rather than redefining the shell on each page.
Outcome colors remain separate for conformance, violation and unknown evidence;
labels and exact tables accompany every color-coded result.

[Carbon productive motion](https://www.carbondesignsystem.com/building-blocks/foundations/motion/overview)
informs brief interaction feedback: 120 ms color transitions and 180 ms disclosure
or selection feedback. These transitions are finite. There are no animated chart
entrances, perpetual pulsing indicators or invented solver-progress animations.
The operating system's reduced-motion preference disables all transitions and
animations. Recorded state and messages communicate running work independently
of motion.

## Interaction principles

Use task names that describe an action or record. Keep advanced parameters
available without making them prerequisites for a first small run. Give examples
that fill editable fields. Make page selection and cross-page selection distinct.
Review configuration before execution. Do not create an arbitrary repository
limit from the number of worker slots.

Display actual attempt state, worker messages and timestamps. Do not invent a
percentage complete for a stage the engine does not report. An elapsed or updated
time is not an estimate of remaining runtime. Preserve explicit cancellation and
terminal errors. Avoid automatic navigation away from evidence the user is reading.

Use restrained colors, readable labels, tabular numbers, clear table headers,
consistent spacing and visible keyboard focus. Use text alongside status color.
Charts support exact tables and witness inspection; they do not replace them.
Keep essential forms and documentation usable without JavaScript or third-party
fonts, chart services or content-delivery networks.

## Documentation and scientific interpretation

Link help from the point of use. Keep architecture, scientific semantics and
operational details separate but connected. Publish the same Markdown sources
inside the application so instructions do not silently diverge. Diagrams must
identify responsibilities and limitations, with a textual explanation for users
who cannot inspect the image.

## Validation and remaining usability work

Automated checks should exercise native forms, selection, error recovery,
documentation links, keyboard navigation, narrow layouts, representative contrast
and reduced-motion behavior. Use small end-to-end source examples for scientific
transitions. These checks can reveal defects but cannot establish practitioner
usability. A later observed task study with researchers and practitioners should
measure completion, errors, interpretation accuracy and recovery without coaching.
