# Experimental Design

## Overview

The experiment evaluates nine structural conditions against type models extracted from 224 repositories across Java, Python, and C++. The experiment proceeds in five sequential phases, each implemented in the `experiment/` directory.

## Phase 01: Exploration

**Script:** `experiment/01_exploration/exp_01_distributions.py`

Walks all repositories with completed extraction, reads `extraction.json` from each, and records type count, executable count, field count, abstract type count, and inheritance proxy metrics. Produces `exploration_summary.csv` and distribution figures.

## Phase 02: Normalisation

**Script:** `experiment/02_normalisation/nrm_01_intervals.py`

Computes IQR-based type count intervals per language (P25-P75), determines the common N (minimum repositories in IQR across all languages), and sets target N = min(100, common N). Produces `normalisation_params.json`.

| Language | IQR Interval |
|----------|-------------|
| Java | [23, 197] |
| Python | [23, 234] |
| C++ | [193, 386] |

## Phase 03: Selection

**Script:** `experiment/03_selection/sel_01_corpus.py`

Scans all repositories with extraction JSON, filters by IQR interval, stratifies by type count tertile (small/medium/large), and samples exactly N repositories per language (random seed 42 for reproducibility). Produces `selected_repos.csv`.

| Language | Selected | Tertiles |
|----------|----------|----------|
| Java | 75 | small [23,42], medium [43,101], large [102,197] |
| Python | 75 | small [23,54], medium [55,106], large [107,234] |
| C++ | 74 | small [193,237], medium [238,293], large [294,386] |

## Phase 04: Verification

**Script:** `experiment/04_verification/batch_v2.py`

Runs the `BatchRunner` against every repository in `selected_repos.csv`. For each repository, it maps the extraction JSON to AIE format, invokes the invariant checker, and records the verification outcome. Produces `batch_results_<lang>.csv`.

## Phase 05: Analysis

**Scripts:** `experiment/05_analysis/`

- `quick_breakdown.py` — per condition per language violation counts
- `per_repo_dist.py` — per repository distribution statistics
- `regenerate_all_figs.py` — generates all empirical figures for the paper
- `regenerate_figures.py` — generates type distribution figure

## Corpus Design

Repositories are drawn from GitHub using the search API with language as the sole content filter. Every repository satisfies: permissive license (MIT, Apache-2.0, BSD-3-Clause), not a fork, not archived, at least one commit pushed after January 2021, and repository size not exceeding 50 MB.

Repositories are stratified by star bracket:

| Bracket | Java/Python | C++ |
|---------|------------|-----|
| Average use | 25–100 stars | 10–100 stars |
| High use | 100–1000 stars | 100–500 stars |
| Elite | 1000+ stars | 500+ stars |

The experimental corpus uses IQR bounded type count intervals to control for repository scale: only repositories whose type count falls within the central 50% of each language's distribution are eligible for selection. This excludes both trivial projects (few types, no inheritance) and outlier repositories (thousands of types from monorepos or generated code).
