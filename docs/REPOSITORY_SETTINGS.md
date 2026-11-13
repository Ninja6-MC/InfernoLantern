# Repository Settings

Task #32 adoption plan, prepared 2026-11-13. The targets below are selected configuration, not evidence that they have been applied. Live read-back and a successful normal merge-queue flow are required before the task and its temporary exception can close. No release or deployment approval is included.

## Standards and settings policy

[N6-CI-03 and N6-CI-05](https://github.com/Ninja6-MC/.github/blob/main/standards/ci.md) require observed check names, protected main and the Tier 2 queue model for this public consumer repository. [Contribution rules](https://github.com/Ninja6-MC/.github/blob/main/CONTRIBUTING.md) require branches, signed-off Conventional Commits, reviewed PRs and squash merging. Independent review is required separately from GitHub's native approval counter.

Automatic branch deletion, allowing auto-merge, the queue capacity/timeouts and the security-feature selections below are established repository settings policy for this project, not invented numbered standards. Passing Standards does not prove those live settings. The inherited [security policy](https://github.com/Ninja6-MC/.github/blob/main/SECURITY.md) requires a private reporting route; enabling the GitHub feature makes that route available rather than assuming inherited text enables it.

## Selected repository and security settings

| Setting | Target | Adoption baseline, checked 2026-11-13 |
| :--- | :--- | :--- |
| `allow_squash_merge` | `true` | Already enabled. |
| `allow_merge_commit` / `allow_rebase_merge` | Both `false` | Both enabled; disable them. |
| `squash_merge_commit_title` | `PR_TITLE` | Currently `COMMIT_OR_PR_TITLE`; use the reviewed Conventional Commit PR title as the squash subject. |
| `squash_merge_commit_message` | Retain `COMMIT_MESSAGES` | Preserve the existing signed-off short commit messages. |
| `allow_auto_merge` | `true` | Disabled; allowing it does not bypass checks, independent review or deployment gates. |
| `delete_branch_on_merge` | `true` | Disabled; enable deletion of merged remote feature branches. |
| Secret scanning | Enabled | Disabled. |
| Secret-scanning push protection | Enabled | Disabled; enable after secret scanning. |
| Dependabot vulnerability alerts | Enabled | Already enabled; alerts are not automatic security-fix PRs. |
| Dependabot security updates | Enabled | Disabled; enable and check the separate feature. |
| Private vulnerability reporting | Enabled | Disabled; verify the private report route after enabling. |

Apply only these selected fields and preserve unrelated repository settings. Secret scanning and push protection are repository `security_and_analysis` settings; Dependabot security updates and private vulnerability reporting have separate enable/read-back endpoints. Existing grouped Actions version updates remain separate from vulnerability-driven security updates. This plan does not add a Gradle version-update ecosystem or assume an enabled security-update feature has already produced a fix PR.

## Main protection and observed checks

Retain required pull requests, zero required native approvals, stale-review dismissal, administrator enforcement, linear history, resolved conversations, and disabled force pushes/deletions. Zero native approvals does not remove the independent-review requirement. No bypass actors are added. The change to required checks uses the targeted status-check endpoint so unrelated existing protections remain intact.

Require these exact contexts, each pinned with `app_id: 15368` (`github-actions`):

| Required context | Observed passing evidence |
| :--- | :--- |
| `Build and Test` | [PR #34 CI run](https://github.com/Ninja6-MC/InfernoLantern/actions/runs/43435723026) |
| `dco / Check Sign-off` | [PR #34 DCO run](https://github.com/Ninja6-MC/InfernoLantern/actions/runs/43435723600) |
| `standards / Check Standards` | [PR #34 Standards run](https://github.com/Ninja6-MC/InfernoLantern/actions/runs/43435723609) |

The successful PR #34 check runs were read back with the reporting app before selecting these targets. DCO and Standards are currently required; Build and Test is observed and ready to add. Preserve `strict: true` during queue setup and initial testing. The final Tier 2 setting is `strict: false`, only after an active queue has passed its required merge-group checks. Neither Scorecard nor path-filtered/unreported checks are added to the required list.

## Selected merge-queue ruleset

Create an active repository branch ruleset named `tier2-merge-queue` matching only `refs/heads/main`, with no excluded refs and an empty bypass-actor list. Do not duplicate an equivalent ruleset if one appears before application. The ruleset contains one `merge_queue` rule; the existing classic protection continues to supply PR, history, conversation and required-check safeguards.

| Queue parameter | Selected value |
| :--- | :--- |
| `merge_method` | `SQUASH` |
| `max_entries_to_build` | `5` |
| `min_entries_to_merge` | `1` |
| `max_entries_to_merge` | `1` |
| `min_entries_to_merge_wait_minutes` | `5` |
| `grouping_strategy` | `ALLGREEN` |
| `check_response_timeout_minutes` | `60` |

These bounded queue parameters follow the established consumer configuration and were checked against GitHub's current API contract. A one-PR minimum does not require waiting for five PRs. `ALLGREEN` requires each grouped merge commit to pass its required checks. The three workflows already declare `merge_group`; a declaration alone is not proof that those events pass in this repository.

## Adoption and verification order

1. Independently review this configuration plan before applying live mutations. Re-read the current settings immediately before changes and preserve unrelated values. Keep the existing N6-CI-05 transitional exception while evidence is pending.
2. Apply the selected squash/auto-merge/branch-deletion/title settings. Enable secret scanning, then push protection, enable Dependabot security updates and private vulnerability reporting, and retain enabled vulnerability alerts. Read each setting back; do not infer a successful enablement from the request body alone.
3. Add the three observed required checks with their explicit app IDs while retaining strict freshness. Re-read main protection and confirm all other safeguards remain intact.
4. Create and read back the active main-only queue ruleset, including its returned ID, parameters and empty bypass list. Keep `strict: true` while preparing and testing the first queue entry.
5. Once the plan PR's current head has independent review and passing required checks, add it through the normal queue path with its expected head. Record the queue/merge-group commit and the `merge_group` workflow runs. Require all three exact contexts from the selected Actions app to pass on that commit. Do not use an administrative bypass.
6. Only after the active queue is tested with passing merge-group checks, change strict freshness to `false` using the targeted required-status-check update, retaining the same app-pinned check list. If the first normal queued merge has already completed, its recorded successful queue flow supplies that evidence before the strict change. Re-read the active queue and complete protection configuration after the change.
7. Verify a normal queued squash merge actually completes, then record the PR, queue commit, run links, merge result and final setting read-backs. Check automatic feature-branch deletion and the resulting Conventional Commit subject. A queued request or a passing ordinary PR run is insufficient.
8. Verify the private report route is available without creating a fabricated vulnerability report. Verify designated independent-review access and repository discovery without publishing operational configuration. Record only the generic verification result.
9. Submit a separate evidence PR updating this record and the assigned-rule checklist and removing the narrow N6-CI-05 exception. Close #32 only after the final evidence is accurate and the normal reviewed queue flow is complete.

If setup or required merge-group checks fail before the strict change, retain `strict: true` and the exception, repair the failure and repeat the test. If the queue becomes unreliable afterward, stop further merges and restore strict freshness with the same required check list while resolving it; never remove the queue/checks to force a merge. Environment/release approvals remain maintainer-controlled throughout.

## Evidence still required

- Live API read-back of each selected repository/security feature, including distinct alerts and security-update states.
- All three app-pinned required contexts and preserved main safeguards.
- Active main-only ruleset ID, parameters and empty bypass list.
- Successful `merge_group` runs on the queue commit and a completed normal queued squash merge.
- Final `strict: false`, automatic branch-deletion evidence and Conventional Commit squash subject.
- Available private reporting route and generic confirmation of independent-review access/discovery.

All items above remain pending in this plan. Generic settings/run evidence is sufficient; do not retain player names, private server infrastructure/repositories, Discord information, credentials or operational configuration.

## API references

- [Repository merge/security settings, security updates and private reporting](https://docs.github.com/en/rest/repos/repos)
- [App-pinned required status checks](https://docs.github.com/en/rest/branches/branch-protection#update-status-check-protection)
- [Repository rulesets and queue parameters](https://docs.github.com/en/rest/repos/rules#create-a-repository-ruleset)
- [Merge-queue behavior and CI events](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/configuring-pull-request-merges/managing-a-merge-queue)
