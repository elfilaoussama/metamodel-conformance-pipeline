"""Minimal figure: per-invariant violation bars for all 3 languages."""
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np

FIGDIR = r"E:\Foundational oo fomalisation\journal_paper_draft\figures"

plt.rcParams.update({"font.family": "serif", "font.size": 8})

invariants = [
    "Interface Policy", "Abstraction Policy", "Local Method Namespace",
    "Identifier Integrity", "Acyclic Gen.", "Gen. Kind", "Static Method",
    "Override", "Excl. Owner.", "Inher. Conflict", "Inher. Method",
    "Inher. Attr", "Local/Inher.", "Impl Binding"
]
java   = [1151, 1065, 804, 11, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0]
python = [421, 6471, 18, 0, 9, 8, 5, 0, 0, 0, 0, 0, 0, 0]
cpp    = [0, 15, 1783, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0]

fig, ax = plt.subplots(figsize=(10, 5))
x = np.arange(len(invariants))
w = 0.22

ax.bar(x - w, java,   w*0.9, color="#2b83ba", label="Java (SAT=6)")
ax.bar(x,     python, w*0.9, color="#abdda4", label="Python (SAT=30)")
ax.bar(x + w, cpp,    w*0.9, color="#fdae61", label="C++ (SAT=0)")

ax.set_xticks(x)
ax.set_xticklabels(invariants, rotation=45, ha="right", fontsize=7)
ax.set_ylabel("Violation count")
ax.set_yscale("symlog", linthresh=1)
ax.set_title("Per-invariant violation distribution")
ax.legend(fontsize=8)
fig.tight_layout()
fig.savefig(f"{FIGDIR}/fig_violation_distribution.pdf", dpi=300)
fig.savefig(f"{FIGDIR}/fig_violation_distribution.png", dpi=300)
plt.close(fig)
print("Figure regenerated.")
