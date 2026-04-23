# Code Quality Standards

This document describes the code quality tools and standards for the EMCIP project.

## Overview

All code quality tools are configured in the parent POM and enforced via GitHub Actions CI/CD pipeline.

## Tools

### 1. Spotless - Code Formatting

**Purpose:** Automatic code formatting to ensure consistent style.

**Configuration:**
- Google Java Format (AOSP style)
- Version: 1.22.0
- Reflow long strings enabled
- Maven goal: `spotless:check` (verify), `spotless:apply` (format)

**Usage:**
```bash
# Check formatting (CI/CD does this)
mvn spotless:check

# Apply formatting fixes
mvn spotless:apply

# Check POM formatting
mvn spotless:check -P sortpom
```

**Enforcement:** Check only (does not auto-format on build)

### 2. SortPOM - POM Ordering

**Purpose:** Ensure consistent ordering of elements in pom.xml files.

**Configuration:**
- Line separator: `\n`
- Expand empty elements: false
- Space before close empty element: true

**Usage:**
```bash
mvn sortpom:sort
```

### 3. Maven Enforcer - Version Rules

**Purpose:** Enforce minimum Maven and Java versions.

**Rules:**
- Maven: 3.8.0+
- Java: 21+

**Usage:** Automatic during build

### 4. JaCoCo - Test Coverage

**Purpose:** Measure and enforce minimum test coverage.

**Configuration:**
- Minimum coverage threshold: **80%** (LINE)
- Reports generated in `target/site/jacoco/`

**Usage:**
```bash
# Generate coverage report
mvn clean test jacoco:report

# Check coverage (fails if < 80%)
mvn clean verify
```

**Report Location:** `target/site/jacoco/index.html`

### 5. Checkstyle - Static Analysis

**Purpose:** Check code against style guidelines.

**Configuration:**
- Config: `google_checks.xml`
- Fails on error: **false** (warning only)
- Console output: enabled

**Usage:**
```bash
mvn checkstyle:check
mvn checkstyle:checkstyle  # Generate report
```

**Report Location:** `target/checkstyle-result.xml`

### 6. PMD - Static Analysis

**Purpose:** Find common programming flaws.

**Configuration:**
- Minimum priority: 3 (Medium)
- Fail on violation: **false** (warning only)

**Usage:**
```bash
mvn pmd:check
mvn pmd:pmd  # Generate report
```

**Report Location:** `target/pmd.html`

## CI/CD Integration

All quality checks run in GitHub Actions:

```yaml
# Code Quality Job
code-quality:
  steps:
    - Compile
    - Spotless check
    - Checkstyle
    - PMD
```

Build fails if:
- Tests fail
- Coverage < 80%
- Spotless formatting issues

## Local Development Workflow

Before committing:

```bash
# 1. Build and test
mvn clean install

# 2. Check formatting
mvn spotless:check

# 3. Fix formatting if needed
mvn spotless:apply

# 4. Run all checks
mvn clean verify

# 5. Check coverage
open target/site/jacoco/index.html
```

## Quality Thresholds Summary

| Tool | Threshold | Enforcement |
|------|-----------|-------------|
| Spotless | Google Java Format | Check only |
| JaCoCo | 80% coverage | Blocking |
| Checkstyle | Google checks | Warning only |
| PMD | Medium priority | Warning only |

## IDE Integration

### IntelliJ IDEA

1. **Install plugins:**
   - CheckStyle-IDEA
   - Save Actions (auto-format on save)

2. **Configure Save Actions:**
   - Activate save actions on shortcut
   - Activate save actions on batch
   - Organize imports
   - Reformat file

3. **Configure Checkstyle:**
   - Use Google Checks configuration
   - Scan all sources

### VS Code

1. **Install extensions:**
   - Extension Pack for Java
   - Checkstyle for Java

2. **Configure settings:**
   ```json
   {
     "java.format.settings.url": "https://raw.githubusercontent.com/google/styleguide/gh-pages/eclipse-java-google-style.xml",
     "java.format.settings.profile": "GoogleStyle"
   }
   ```

## Troubleshooting

### Spotless fails

```bash
# Auto-fix
mvn spotless:apply

# Then commit the changes
git add .
git commit -m "style: apply spotless formatting"
```

### Coverage fails

```bash
# Generate report to see what's missing
mvn clean test jacoco:report
open target/site/jacoco/index.html
```

### Checkstyle warnings

Checkstyle warnings don't block the build. Review `target/checkstyle-result.xml` and fix at your discretion.

## References

- [Google Java Style Guide](https://google.github.io/styleguide/javaguide.html)
- [Spotless Maven Plugin](https://github.com/diffplug/spotless/tree/main/plugin-maven)
- [JaCoCo Maven Plugin](https://www.eclemma.org/jacoco/trunk/doc/maven.html)
- [Checkstyle](https://checkstyle.sourceforge.io/)
- [PMD](https://pmd.github.io/)
