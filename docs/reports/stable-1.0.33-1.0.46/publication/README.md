# Stable publication and starter acceptance

- [Mongoose 1.0.33](https://github.com/telaminai/mongoose/releases/tag/v1.0.33): commit 9cba720fca901e9de56a118899fd54ab37a8979e.
- [Plugins 1.0.46](https://github.com/telaminai/mongoose-plugins/releases/tag/v1.0.46): commit 56d8cb2202bfb28274b6d5fc74e048ce4ea095d4.

Both publication workflows succeeded. Core and all 19 plugin module artifacts plus dependencies resolved
from fresh Maven caches with empty settings files: BUILD SUCCESS. Public artifact SHA-256 values and
complete resolution logs are retained here.

Both fixed starter projects were built against the downloaded stable artifacts: two tests each,
zero failures/errors/skips, BUILD SUCCESS. Both live runs captured all five original sample rows
with exact values, exported audit records and stopped cleanly. The Spring exit 143 is the requested
SIGTERM after successful verification; registry removal was checked.

The projects use the starter fixes from fluxtion-web PR #20 with stable version overrides. These
results do not claim the public playground defaults have already changed. That rollout is separate.
Release qualification does not clear a client for migration.
