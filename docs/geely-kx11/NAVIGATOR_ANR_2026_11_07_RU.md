# Navigator cold-start recovery and ANR evidence — 07.11.2026

USER: исправить восстановление запуска и сохранять причину ANR, а не только факт.
Baseline b2434a5804071cf21390c312bb0cf20e5861cec0; KX11 Android9.
Existing private diagnostic archive7455511269533111895, session ddc-2ff6:
13.598s dispatch;33.206s ANR;33.211s killed;33.724s HOME resumed;
35.706s HOME paused;62.182s new HOME dispatch;68.077s window observed.
Native ANR root cause remains UNKNOWN: historical archive contains no stack.

SOURCE candidate: retain Android Reason/PID/Load from the same logger thread
within5s of an allowlisted ANR; preserve system kill detail through journal redaction.
Collect matching data_app_anr/system_app_anr DropBox report off MAIN, maximum
256KiB per record,16entries/tag/attempt,4attempts separated by5s. Exact Process
header and incident time interval are required. Report chunks and acquisition
status persist in separate bounded anr channels included in export and clear.
No root, permission grant, signal, force-stop, global logcat export or player UI.
Permission denial/missing report are explicit, not evidence of no ANR.

Confirmed Navigator process death after dispatch bypasses only the30s retry
wait. Fresh confirmed absence, HOME focus, no previous window, manual cancellation,
60s overall lifetime and2attempt maximum remain. ANR alone does not authorize
restart. Historical log timestamps map to boot elapsed time before retry admission.
This addresses recovery; it does not establish or fix the native ANR root cause.

TEST: local Gradle blocked downloading8.14.2 (network unreachable); remote CI pending.
APK/FIELD OPEN. Hardware gates: repeated cold boots, valid reason/main-thread stack
or explicit denial, archive after restart, unaffected manual launches and foreground apps.

## Local verification / blockers

Backup inventory98owners PASS after explicit diagnostics-storage review; diff whitespace PASS.
Android tests NOT RUN: Gradle8.14.2 download unavailable. Standalone Java tests NOT RUN:
this environment has JRE21 but no javac. Do not interpret added tests as passing tests.
Remote branch push rejected by automatic approval review as external publication not
explicitly authorized; no CI started. Changes are a local candidate, not a released fix.
Remaining review before release: diagnostic-disabled recovery behavior (current death
observer requires enabled diagnostics), DropBox denied/missing/late report tests, exact
APK signing and migration gates for the next version. No hardware claim is made.


## Release verification 07.11.2026 — supersedes the earlier local blockers

Remote source a9bb7c0fcbe1866570024df419bf962af1cacf7b, tree
ccb8defb913488adb1de6ce54e2fb0b2840e585e; CI42354372942 SUCCESS:2279Android tests
and full tool replay suite. The local Java runtime also exposes jdk.compiler via
`java com.sun.tools.javac.Main` even when a javac launcher is absent.
Seven host fixtures compiled the exact AnrEvidence source and passed denied,
unavailable, missing-after-retries, disabled, correct-process/chunk-bound,
out-of-incident-window and late-report cases. No physical Android permission claim.
Diagnostic-disabled recovery reviewed: observer is opt-in; accelerated recovery is
unavailable without enabled diagnostics/READ_LOGS, while the original30s guarded
fallback remains. The APK is recovery/evidence improvement, not a proven native
ANR root-cause fix. Device gates above remain OPEN.
Signed3.4.3/API28/v2/v3/zipalign/install-over metadata and unchanged CI payload PASS.
[Release reports and acceptance matrix](../releases/3.4.3/README_RU.md).
