---
name: ci-cd
description: "CI/CD pipeline conventions. Use when modifying GitHub Actions workflows in .github/, Docker image builds, deployment scripts, or GHCR registry configuration."
user-invocable: false
---

# CI/CD Pipeline

**Use when** changing a workflow in `.github/`, a build, a deploy script or the registry setup.

**Read** `agent-os/standards/deployment/ci-cd-pipeline.md`: workflows, main pipeline, deploy, nightlies, branch protection, registry, secrets.

## Gotchas

- Jobs stay in `ci.yml`: a required check is a job name, and a job behind `uses:` is renamed `Caller / Job`.
- A new job in `ci.yml` joins the required checks of `main` in the same delivery.
- No `workflow_run`: a chained workflow that does not apply shows up as a grey, skipped line.
- Every image a workflow or Dockerfile pulls comes from the `ghcr.io/miwi35/mirror/` mirror.
- The agent guard and the freeze after a failed deploy live in Cyrus (`cyrus-pilot`), not in this repository's workflows.
- Migrations are not reverted on rollback: keep them additive.
