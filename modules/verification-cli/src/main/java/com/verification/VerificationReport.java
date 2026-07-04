package com.verification;

import java.util.ArrayList;
import java.util.List;

public class VerificationReport {
    private String result;
    private List<Violation> violations;

    public VerificationReport() {
        this.violations = new ArrayList<>();
    }

    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }

    public List<Violation> getViolations() { return violations; }
    public void setViolations(List<Violation> violations) {
        this.violations = violations != null ? violations : new ArrayList<>();
    }

    public void addViolation(Violation v) { violations.add(v); }

    public static class Violation {
        private Integer line;
        private String invariantName;
        private String description;
        private String formula;

        public Violation() {}

        public Violation(Integer line, String invariantName, String description, String formula) {
            this.line = line;
            this.invariantName = invariantName;
            this.description = description;
            this.formula = formula;
        }

        public Integer getLine() { return line; }
        public void setLine(Integer line) { this.line = line; }

        public String getInvariantName() { return invariantName; }
        public void setInvariantName(String invariantName) { this.invariantName = invariantName; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }

        public String getFormula() { return formula; }
        public void setFormula(String formula) { this.formula = formula; }
    }
}
