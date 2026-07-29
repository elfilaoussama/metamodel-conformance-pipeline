"""
APS_01_ingestion_summary.py
Analysis Pipeline Script 01: Ingestion Summary across Java, Python, C++
Reads ingestion-export.csv from each language subdirectory in analysis-output,
produces summary statistics and publication-quality figures.
"""
import csv, re, os, sys
from pathlib import Path
from collections import defaultdict
import matplotlib.pyplot as plt
import matplotlib.ticker as mticker
import numpy as np

OUTPUT_DIR = Path(__file__).resolve().parent
ANALYSIS_DIR = OUTPUT_DIR.parent / "analysis-output"
FIG_DIR = OUTPUT_DIR / "figures"
FIG_DIR.mkdir(exist_ok=True)

plt.rcParams.update({
    "font.family": "serif", "font.size": 10,
    "axes.titlesize": 11, "axes.labelsize": 10,
    "figure.dpi": 150, "savefig.dpi": 300,
    "savefig.bbox": "tight"
})

LANG_DIRS = {"Java": "java", "Python": "python", "C++": "cpp"}
COLOURS = {"Java": "#2b83ba", "Python": "#abdda4", "C++": "#fdae61"}


def parse_java_activity(activity_str):
    """Extract violation count from mangled Java activity string."""
    match = re.search(r"(\d+)\s*violation", activity_str)
    return int(match.group(1)) if match else 0

def parse_cpp_repo(raw):
    """Strip GitHub URL prefix from C++ repo names."""
    m = re.search(r"github\.com/(.+?)(?:\.git)?$", raw)
    return m.group(1).replace("/", "__") if m else raw

def load_java(path):
    rows = []
    with open(path, newline="", encoding="utf-8") as f:
        for r in csv.DictReader(f):
            rows.append({
                "repo": r["Repository"].strip(),
                "lang": "Java",
                "status": r["Status"].strip(),
                "violations": parse_java_activity(r.get("Activity", "")),
                "verified": True,
            })
    return rows

def load_python(path):
    rows = []
    with open(path, newline="", encoding="utf-8") as f:
        for r in csv.DictReader(f):
            rows.append({
                "repo": r["Repository"].strip(),
                "lang": "Python",
                "status": "EXTRACTED",
                "violations": 0,
                "verified": False,
            })
    return rows

def load_cpp(path):
    rows = []
    with open(path, newline="", encoding="utf-8") as f:
        for r in csv.DictReader(f):
            raw = r["Repository"].strip()
            status = r["Status"].strip()
            tcount = r.get("Types", "").strip()
            rows.append({
                "repo": parse_cpp_repo(raw),
                "lang": "C++",
                "status": status,
                "violations": 0,
                "types": int(tcount) if tcount.isdigit() else 0,
                "verified": False,
            })
    return rows

def main():
    data = {}
    data["Java"]     = load_java(ANALYSIS_DIR / "java" / "ingestion-export.csv")
    data["Python"]   = load_python(ANALYSIS_DIR / "python" / "ingestion-export.csv")
    data["C++"]      = load_cpp(ANALYSIS_DIR / "cpp" / "ingestion-export.csv")

    # ---- FIGURE 1: Pipeline stage completion ----
    fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(9, 3.8))

    langs = list(LANG_DIRS)
    extracted = [len(data[l]) for l in langs]
    verified  = [sum(1 for r in data[l] if r["verified"]) for l in langs]
    failed    = [sum(1 for r in data[l] if r["status"] == "FAILED") for l in langs]
    x = np.arange(len(langs))
    w = 0.35

    b1 = ax1.bar(x - w/2, extracted, w, label="Extracted", color="#5e9cd3", edgecolor="white")
    b2 = ax1.bar(x + w/2, verified,  w, label="Verified",  color="#d94f4f", edgecolor="white")
    ax1.set_xticks(x); ax1.set_xticklabels(langs); ax1.set_ylabel("Repositories")
    ax1.set_title("Pipeline Throughput by Language")
    ax1.legend(fontsize=8)
    for bar in b1:
        ax1.text(bar.get_x() + bar.get_width()/2, bar.get_height() + 3,
                 str(int(bar.get_height())), ha="center", fontsize=8)
    for bar in b2:
        ax1.text(bar.get_x() + bar.get_width()/2, bar.get_height() + 3,
                 str(int(bar.get_height())), ha="center", fontsize=8)

    # ---- FIGURE 2 (right): Java status distribution ----
    jv = data["Java"]
    sat_count    = sum(1 for r in jv if r["status"] == "COMPLETED")
    unsat_count  = sum(1 for r in jv if r["status"] == "VIOLATIONS")
    label_colours = ["#2ca02c", "#d62728"]
    ax2.pie([sat_count, unsat_count], labels=["SAT (0 violations)", "UNSAT"],
            autopct="%1.1f%%", colors=label_colours, startangle=90,
            textprops={"fontsize": 9}, wedgeprops={"edgecolor": "white", "linewidth": 1})
    ax2.set_title(f"Java Verification Outcome (n={len(jv)})")

    fig.tight_layout()
    fig.savefig(FIG_DIR / "fig_01_pipeline_throughput.pdf")
    fig.savefig(FIG_DIR / "fig_01_pipeline_throughput.png")
    plt.close(fig)

    # ---- FIGURE 2: Java violation distribution ----
    viol_vals = [r["violations"] for r in jv if r["violations"] > 0]
    fig, ax = plt.subplots(figsize=(7, 3.5))
    bins = np.logspace(np.log10(max(1, min(viol_vals))), np.log10(max(viol_vals)+1), 40)
    ax.hist(viol_vals, bins=bins, color="#d94f4f", edgecolor="white", alpha=0.85)
    ax.set_xscale("log"); ax.set_xlabel("Violations per repository (log scale)")
    ax.set_ylabel("Frequency")
    ax.set_title(f"Java Violation Count Distribution (n={len(viol_vals)} repos with violations)")
    ax.axvline(np.median(viol_vals), color="black", linestyle="--", linewidth=0.8,
               label=f"Median = {np.median(viol_vals):.0f}")
    ax.legend(fontsize=8)
    fig.tight_layout()
    fig.savefig(FIG_DIR / "fig_02_java_violation_distribution.pdf")
    fig.savefig(FIG_DIR / "fig_02_java_violation_distribution.png")
    plt.close(fig)

    # ---- FIGURE 3: C++ type counts ----
    cpp_data = data["C++"]
    cpp_types = [r["types"] for r in cpp_data if r["types"] > 0]
    if cpp_types:
        fig, ax = plt.subplots(figsize=(7, 3.5))
        ax.hist(cpp_types, bins=50, color=COLOURS["C++"], edgecolor="white", alpha=0.85)
        ax.set_xlabel("Extracted types per repository")
        ax.set_ylabel("Frequency")
        ax.set_title(f"C++ Type Extraction (n={len(cpp_types)} repos)")
        ax.axvline(np.median(cpp_types), color="black", linestyle="--", linewidth=0.8,
                   label=f"Median = {np.median(cpp_types):.0f}")
        ax.legend(fontsize=8)
        fig.tight_layout()
        fig.savefig(FIG_DIR / "fig_03_cpp_type_distribution.pdf")
        fig.savefig(FIG_DIR / "fig_03_cpp_type_distribution.png")
        plt.close(fig)

    # ---- SUMMARY STATISTICS ----
    print("=" * 62)
    print("          PIPELINE INGESTION SUMMARY REPORT")
    print("=" * 62)
    for lang in langs:
        d = data[lang]
        v = sum(1 for r in d if r["verified"])
        f = sum(1 for r in d if r["status"] == "FAILED")
        print(f"\n  {lang}: {len(d)} repos")
        print(f"    Extracted: {len(d) - f}   Verified: {v}   Failed: {f}")
        if lang == "Java":
            vi = [r["violations"] for r in d if r["violations"] > 0]
            z = sum(1 for r in d if r["violations"] == 0)
            print(f"    SAT (0 violations): {z}   UNSAT: {len(vi)}")
            if vi:
                print(f"    Violations: min={min(vi)}  median={np.median(vi):.0f}  "
                      f"max={max(vi)}  mean={np.mean(vi):.1f}")
        if lang == "C++":
            tc = [r["types"] for r in d if r["types"] > 0]
            if tc:
                print(f"    Types:      min={min(tc)}  median={np.median(tc):.0f}  "
                      f"max={max(tc)}  mean={np.mean(tc):.1f}")

    print(f"\n  Overall: {sum(len(data[l]) for l in langs)} repos ingested")
    print(f"  Figures saved to: {FIG_DIR}")
    print("=" * 62)


if __name__ == "__main__":
    main()
