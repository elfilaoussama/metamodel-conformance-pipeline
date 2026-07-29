package com.verification;

import java.util.ArrayList;
import java.util.List;

/**
 * Machine-readable verification result.
 */
public class VerificationReport {

    public String result; // SAT / UNSAT
    public List<Violation> violations = new ArrayList<>();

    public static class Violation {
        public Integer line;          // 1-based line in .recore when available
        public String invariantName;  // e.g., UniqueClassIds
        public String description;    // usually "Invariant <Name>: <formula>"
        public String formula;        // Kodkod formula string (fallback)

        public Violation() {
        }

        public Violation(Integer line, String description, String formula) {
            this.line = line;
            this.description = description;
            this.formula = formula;
        }

        public Violation(Integer line, String invariantName, String description, String formula) {
            this.line = line;
            this.invariantName = invariantName;
            this.description = description;
            this.formula = formula;
        }
    }
}
