# Repository instructions

Before changing, reviewing, building, or releasing this project, read
`PROJECT_REQUIREMENTS_RU.md` completely. Treat it as the source of truth for user requirements.

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
