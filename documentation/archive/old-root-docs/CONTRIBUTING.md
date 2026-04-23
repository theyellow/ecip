# Contributing to EMCIP

Thank you for your interest in contributing to the Enterprise Messenger Community Intelligence Platform (EMCIP).

## Git Workflow

This project uses **GitHub Flow** - a simple, branch-based workflow.

### Branching Strategy

```
main (protected)
  ↑
feature/my-feature  ← PR →  Code Review → Merge
```

- **`main`** - Production-ready code, always deployable
- **Feature branches** - All development happens here

### How to Contribute

1. **Create a feature branch from `main`:**
   ```bash
   git checkout main
   git pull origin main
   git checkout -b feature/your-feature-name
   ```

2. **Make your changes:**
   - Write code
   - Add tests
   - Update documentation

3. **Commit your changes:**
   ```bash
   git add .
   git commit -m "feat: descriptive message about your changes"
   ```

4. **Push to GitHub:**
   ```bash
   git push origin feature/your-feature-name
   ```

5. **Create a Pull Request:**
   - Open PR against `main` branch
   - Fill in PR template
   - Request review from maintainers
   - Ensure CI checks pass

6. **After review approval:**
   - Squash and merge to `main`
   - Delete feature branch

### Commit Message Convention

Use conventional commits format:

- `feat:` New feature
- `fix:` Bug fix
- `docs:` Documentation changes
- `test:` Adding or updating tests
- `refactor:` Code refactoring
- `chore:` Build process, dependencies, etc.

Examples:
```
feat: implement message ingestion from TDLib
fix: resolve NPE in intent classifier
docs: update architecture diagram
test: add integration tests for Kafka connectivity
```

### Code Quality Requirements

Before submitting PR:

1. **Build passes:**
   ```bash
   mvn clean install -DskipTests
   ```

2. **Tests pass:**
   ```bash
   mvn test
   ```

3. **Code coverage ≥ 80%:**
   ```bash
   mvn jacoco:report
   ```

4. **Code style compliance:**
   ```bash
   mvn spotless:check
   mvn checkstyle:check
   mvn pmd:check
   ```

5. **Auto-fix formatting (if needed):**
   ```bash
   mvn spotless:apply
   ```

### Branch Protection Rules (Maintainers Only)

The `main` branch is protected with:
- Required pull request reviews (1 approval minimum)
- No direct pushes to `main`
- Required status checks (CI must pass)
- Up-to-date branch requirement

### Local Development Setup

See [README.md](README.md) for prerequisites and build instructions.

### Questions?

Open an issue or contact: dev@emcip.io
