# Repository Settings

Task #32 configuration and live evidence, verified 2026-11-13. The selected settings were applied after independent review of [PR #35](https://github.com/Ninja6-MC/InfernoLantern/pull/35), then verified through API read-back and a completed normal merge-queue flow. This record removes the satisfied N6-CI-05 transition; it does not claim this evidence change has itself completed review or queue execution. No release or deployment approval is included.

## Standards and settings policy

[N6-CI-03 and N6-CI-05](https://github.com/Ninja6-MC/.github/blob/main/standards/ci.md) require observed check names, protected main and the Tier 2 queue model for this public consumer repository. [Contribution rules](https://github.com/Ninja6-MC/.github/blob/main/CONTRIBUTING.md) require branches, signed-off Conventional Commits, reviewed PRs and squash merging. Independent review is required separately from GitHub's native approval counter.

Automatic branch deletion, allowing auto-merge, the queue capacity/timeouts and the security-feature selections below are established repository settings policy for this project, not invented numbered standards. Passing Standards does not prove those live settings. The inherited [security policy](https://github.com/Ninja6-MC/.github/blob/main/SECURITY.md) requires a private reporting route; the GitHub private-reporting feature is explicitly enabled.

## Verified repository and security settings

| Setting | Verified value |
| :--- | :--- |
| `allow_squash_merge` | `true` |
| `allow_merge_commit` / `allow_rebase_merge` | Both `false` |
| `squash_merge_commit_title` | `PR_TITLE`; the reviewed Conventional Commit PR title supplies the squash subject. |
| `squash_merge_commit_message` | `COMMIT_MESSAGES`; the existing signed-off short commit messages are retained. |
| `allow_auto_merge` | `true`; checks, independent review and deployment gates remain required. |
| `delete_branch_on_merge` | `true`; the merged PR #35 branch was subsequently absent. |
| Secret scanning | Enabled. |
| Secret-scanning push protection | Enabled. |
| Dependabot vulnerability alerts | Enabled, verified separately from security updates. |
| Dependabot security updates | Enabled and not paused. |
| Private vulnerability reporting | Enabled; use the repository's [private report route](https://github.com/Ninja6-MC/InfernoLantern/security/advisories/new) under the inherited policy. No fabricated report was submitted. |

The merge/security settings were read back from the repository API. Vulnerability alerts, security updates and private reporting were checked through their separate feature endpoints. Existing grouped Actions version updates remain separate from vulnerability-driven security updates. Enabling security updates does not establish that a fix PR has already been produced, and no Gradle version-update ecosystem was added by this task. Unrelated settings were preserved.

## Verified main protection and required checks

Main retains required pull requests, zero required native approvals, stale-review dismissal, administrator enforcement, linear history, resolved conversations, and disabled force pushes/deletions. Zero native approvals does not remove the independent-review requirement. No bypass actor was added. The status-check update used the targeted endpoint to preserve other protections.

All three exact contexts are required and pinned with `app_id: 15368` (`github-actions`). Their successful ordinary-PR check runs were observed on PR #34 before requiring them; the following queue runs then proved their `merge_group` behavior:

| Required context | Passing merge-group evidence |
| :--- | :--- |
| `Build and Test` | [CI run 43439005460](https://github.com/Ninja6-MC/InfernoLantern/actions/runs/43439005460) |
| `dco / Check Sign-off` | [DCO run 43439006438](https://github.com/Ninja6-MC/InfernoLantern/actions/runs/43439006438) |
| `standards / Check Standards` | [Standards run 43439006501](https://github.com/Ninja6-MC/InfernoLantern/actions/runs/43439006501) |

The final protection read-back has `strict: false` with the same app-pinned check list. Strict freshness remained enabled until the active queue's required checks and completed normal queue flow were verified. Neither Scorecard nor path-filtered/unreported checks are required.

## Verified merge-queue ruleset

Ruleset [27204997](https://github.com/Ninja6-MC/InfernoLantern/rules/27204997), named `tier2-merge-queue`, is active and matches only `refs/heads/main`, with no excluded refs and an empty bypass-actor list. It contains one `merge_queue` rule. Existing classic protection supplies the PR, history, conversation and required-check safeguards.

| Queue parameter | Verified value |
| :--- | :--- |
| `merge_method` | `SQUASH` |
| `max_entries_to_build` | `5` |
| `min_entries_to_merge` | `1` |
| `max_entries_to_merge` | `1` |
| `min_entries_to_merge_wait_minutes` | `5` |
| `grouping_strategy` | `ALLGREEN` |
| `check_response_timeout_minutes` | `60` |

These bounded parameters follow established consumer configuration and were checked against GitHub's API contract. A one-PR minimum does not require waiting for five PRs. `ALLGREEN` requires each grouped merge commit to pass its required checks.

## Completed adoption evidence

1. The configuration plan in PR #35 received two independent cold reviews. The review probes were reproduced before the unchanged verdicts were posted, and all ordinary PR checks passed before queue entry.
2. Merge/security settings were applied and read back. All three observed app-pinned checks became required while strict freshness and the other main safeguards remained active. The main-only queue ruleset was created and read back without bypass actors.
3. PR #35 entered the normal queue at position 1. Its merge-group commit was `598e8cfc08c9af05ff7962eda9a4d572a756bd33`; all three linked runs reported `event: merge_group`, that same commit, and success from app 15368.
4. The queue completed the squash merge of PR #35 on 2026-11-13 at 19:33:10 IST as commit [598e8cfc08c9](https://github.com/Ninja6-MC/InfernoLantern/commit/598e8cfc08c9af05ff7962eda9a4d572a756bd33), with subject `docs: plan repository settings and queue adoption (#35)`. No administrative bypass was used.
5. Only after that passing, completed queue flow was strict freshness changed to `false`. Final API read-back confirmed the active queue, unchanged app-pinned check list and all retained main safeguards.
6. The merged remote branch `chore/repository-settings` returned HTTP 404 on read-back, verifying automatic feature-branch deletion. Independent-review access and repository discovery were verified; operational configuration is not published here.

The merge-group CI ran all 16 build-contract tests and verified `InfernoLantern-0.0.0-SNAPSHOT-g598e8cfc08c9.jar`, with the same embedded plugin version and built commit identity. Its logged SHA-256 was `108c2b498d625c77d0551c046580b0474fe1b21d2dc426d4bf224bedf99ab13a`. This is build/source evidence, not gameplay or Java/Bedrock compatibility evidence.

The normal queue flow and final setting read-backs satisfy the narrow N6-CI-05 transition, so `.github/standards-exceptions.yml` now contains `exceptions: []`. Future publication requirements remain separately tracked in #28. This task does not approve deployment gates or claim that unexecuted features/providers are supported.

## Maintaining the controls

For later changes, re-read the current settings and preserve unrelated values. Require passing checks and an independent review of the current head before adding a PR to the queue. If the queue becomes unreliable, stop further merges and restore strict freshness with the same required check list while resolving the problem; never remove the queue/checks to force a merge. Environment/release approvals remain maintainer-controlled.

Record only generic repository settings and reproducible run evidence. Do not retain player names, private server infrastructure/repositories, Discord information, credentials or operational configuration.

## API references

- [Repository merge/security settings, security updates and private reporting](https://docs.github.com/en/rest/repos/repos)
- [App-pinned required status checks](https://docs.github.com/en/rest/branches/branch-protection#update-status-check-protection)
- [Repository rulesets and queue parameters](https://docs.github.com/en/rest/repos/rules#create-a-repository-ruleset)
- [Merge-queue behavior and CI events](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/configuring-pull-request-merges/managing-a-merge-queue)
