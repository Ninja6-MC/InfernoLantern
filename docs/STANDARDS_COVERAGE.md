# Standards Coverage

Task #4 source/build coverage and task #32 live-control evidence against the [assigned-rule register](https://github.com/Ninja6-MC/.github/blob/main/standards/README.md), checked 2026-11-13. This checklist distinguishes source/build evidence from live repository controls. A passing mechanical check alone does not prove service configuration. The register currently assigns 27 IDs; `N6-CMD-*` is reserved and has no assigned rule.

| Rule | Classification | Evidence or remaining work |
| :--- | :--- | :--- |
| N6-BRANCH-01 | Verified | Main requires PRs in the live protection read-back; this scaffold is delivered through its own PR, not a root/direct-main commit. |
| N6-BRANCH-02 | Verified | The build change uses `chore/plugin-build`. |
| N6-BRANCH-03 | Verified | The branch describes the build change. |
| N6-COMMIT-01 | Verified | Scaffold commit subjects follow Conventional Commits; review and DCO checks cover the submitted head. |
| N6-COMMIT-02 | Verified | Every scaffold commit is signed off; shared DCO runs on the real PR. |
| N6-COMMIT-03 | Verified | Sign-offs use the maintainer's configured name and reachable email; the automated gate checks format, not reachability. |
| N6-COMMIT-04 | Verified | Scaffold PR title uses Conventional Commits for squash merging. |
| N6-COMMIT-05 | Verified | Commit subjects are short; implementation rationale and validation belong in the PR body. |
| N6-CI-01 | Verified | Existing DCO workflow calls the shared immutable workflow; no local fork added. |
| N6-CI-02 | Verified | Both shared callers use the doubled `.github/.github/workflows` path. |
| N6-CI-03 | Verified | Live main requires `Build and Test`, `dco / Check Sign-off` and `standards / Check Standards`, each pinned to Actions app 15368. All were observed on PR #34 and passed on the PR #35 merge group; see [repository settings evidence](REPOSITORY_SETTINGS.md). |
| N6-CI-04 | Verified | Workflow YAML is committed with LF endings under canonical attributes; staged line-ending checks verify actual files. |
| N6-CI-05 | Verified | Active main-only Tier 2 queue ruleset 27204997 has no bypass actors. PR #35 completed its normal queued squash merge after all app-pinned checks passed. Strict freshness was then disabled, with PRs, zero native approvals, stale-review dismissal, administrator enforcement, linear history, resolved conversations and disabled force-push/deletion preserved. The satisfied transitional exception is removed; see [live evidence](REPOSITORY_SETTINGS.md). |
| N6-CI-06 | Verified | Every action/shared workflow uses a 40-character commit SHA. Grouped Actions Dependabot remains configured; shared caller updates remain maintained under the canonical policy. |
| N6-CI-07 | Verified | CI requests only `contents: read`; existing callers and Scorecard declare their explicit grants. |
| N6-CI-08 | Verified | Read-only checkout has `persist-credentials: false`; no publication credential is introduced. |
| N6-CI-09 | Verified in source/build and real PR/queue runs | Version tests cover tag validity/reachability, distance, exact tags, no tags, merge HEAD and shallow rejection. Actual packaging verifies the sole JAR filename and embedded version. The PR #35 merge-group [CI run](https://github.com/Ninja6-MC/InfernoLantern/actions/runs/43439005460) verified the snapshot identifying built commit `598e8cfc08c9` and recorded its digest. Clean-version packaging remains separate from snapshots. |
| N6-REPO-01 | Verified | Contribution, security and conduct files remain inherited; repository-specific Java 25/build instructions are in the build guide. GPL-3.0 remains explicitly present. |
| N6-REPO-02 | Conditional / not yet applicable | No local issue-template directory is introduced; inherited templates remain available. Any future local template requires `config.yml`. |
| N6-REPO-03 | Verified | The build does not edit machine-managed organization assets. |
| N6-REPO-04 | Verified | Existing `.editorconfig`/`.gitattributes` retain canonical template contents. |
| N6-REPO-05 | Verified | `.github/standards-exceptions.yml` remains present with `exceptions: []` after live queue verification satisfied the sole recorded transition. Empty exceptions alone are not treated as evidence of live compliance. |
| N6-REL-01 | Conditional / not yet applicable; tracked #28 | CI only retains Actions artifacts. No workflow can publish a release; release-process/changelog requirements apply before #28 introduces publication. |
| N6-REL-03 | Conditional / not yet applicable; tracked #28 | Candidate build-once/promotion is not implemented or claimed by snapshot retention. |
| N6-REL-04 | Conditional / not yet applicable | No Modrinth destination, publishing action or token exists. Apply if a destination is selected later. |
| N6-REL-05 | Conditional / not yet applicable; tracked #28 | Destination preflights, separate gated jobs and maintainer approvals must precede publication. This scaffold adds none. |
| N6-REL-06 | Conditional / not yet applicable | No Modrinth description/publisher exists. Apply if that destination is selected. |

No exception is invented for Java version guidance: Java 25 is the explicit repository baseline approved in #3, and no assigned rule ID governs the inherited Java 21 text. The completed queue transition does not authorize bypassing checks or independent review, weakening main protection, approving environment gates, or claiming gameplay/client compatibility.
