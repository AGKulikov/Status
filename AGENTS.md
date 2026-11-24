# Repository instructions

Before changing, reviewing, building, or releasing, read this file,
`docs/requirements/COMMON_RU.md` and `docs/CURRENT_REQUIREMENTS_RU.md`.
Then use `docs/requirements/README_RU.md` to select affected modules and dependencies.
`PROJECT_REQUIREMENTS_RU.md` remains the full authoritative ledger; do NOT read it
entirely by default. Read relevant module rows and use `python tools/requirements.py search`
for IDs, families AND task terms (including Russian synonyms) across the WHOLE ledger.
Inspect every matching title, then read relevant amendments/context with the `read` command.
Search is candidate discovery, not proof that unreturned requirements are irrelevant.
If scope is uncertain, expand the search and read the containing/adjacent sections.
Before implementation record affected IDs, dependencies and acceptance gates in the task
report; before closeout account for each, including OPEN/BLOCKED. Do not read all GATE
rows by default: select gates referenced by affected requirements and matching history.
The new selective reading procedure supersedes historical instructions to read the
whole ledger. It does not supersede functional requirements or acceptance gates.
After ledger changes run `python tools/requirements.py build` and `check`; generated
module files are views, never an independently edited second specification.
Avoid repeated reads of unchanged files and printing whole logs, archives or source files;
use bounded excerpts, save full evidence in files, and report paths plus concise results.

When a new user requirement, correction, rejection, or acceptance result arrives:

1. update `PROJECT_REQUIREMENTS_RU.md` in the same change;
2. never delete an older requirement—mark it superseded and link the replacement;
3. distinguish source/test completion from verification on the ECARX KX11;
4. do not describe an item as fixed unless its stated acceptance gate has passed;
5. preserve the stable signing, versioning, install-over, Git provenance, and proprietary-baseline
   rules in section 1 of the ledger.


For Geely/ECARX vehicle interfaces, also read `GEELY_KX11_KNOWLEDGE_RU.md` and the
relevant catalog/open question in `docs/geely-kx11/` before choosing IDs, zones, values,
or interpreting status. Preserve source hashes, firmware scope, and the distinction
between static routes, observed feedback, and independently verified physical effects.
Update the knowledge base with new evidence together with the requirements ledger.

Backup coverage gate: run `python tools/check_backup_inventory.py` after production
storage changes. New/changed/removed owners require review of
`docs/backup/FULL_BACKUP_CONTRACT_RU.md` and the source inventory. Never update
fingerprints merely to silence the check or describe this static check as proof
of full runtime backup/restore. New settings must be included automatically by
the future storage adapters; changing a schema/default requires migration review.

Release publication: after every issued APK, publish all project-owned source changes,
update README.md, docs/releases/<version>/README_RU.md and PROJECT_REQUIREMENTS_RU.md,
and verify the remote branch/commit. State the source commit and CI evidence; distinguish
APK checks from field acceptance. A local commit or a planned push is not publication.
Keep the current development branch visible in README when the default branch is historical.

Research persistence: after each substantive investigation, preserve new findings,
hypotheses, negative results, user corrections and next steps without waiting for an APK.
Record the source/date/hash when available, firmware scope, evidence level and limits.
Update the detailed report, relevant knowledge summary, applicable open-question state,
and requirements ledger in the same change. Keep superseded conclusions as history.
An existing chat answer or standalone report is not automatically present in Git:
verify the remote publication and keep docs/RESEARCH_INDEX_RU.md navigable.
Do not claim exhaustive historical coverage unless that comparison was actually performed.


Requirement execution and closeout: apply the existing requirement IDs and acceptance
gates before implementation, not only after delivery. For every affected item, record
SOURCE / TEST / APK / FIELD evidence or an explicit OPEN/BLOCKED condition.
Publish release verification reports with the release documentation. If evidence is
missing, distinguish an unperformed check from a performed but unpublished check;
recover and verify the latter without rewriting the historical audit. Never weaken
a gate or claim project-wide compliance from a partial audit.
