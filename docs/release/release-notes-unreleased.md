# Control-plane release notes

The control-plane changes are assigned to **Mongoose 1.0.33-rc1**. See the
[versioned release notes](release-notes-1.0.33-rc1.md) for behaviour changes, compatibility and known limits.
Publication and qualification status is recorded in the [RC release procedure](rc-release-procedure.md).
Client migration requires its own [checklist](client-migration-1.0.33.md).

## Overload and loss policy (telaminai/mongoose#79, unreleased)

- **New:** an `overload` block on `eventFeeds` and `pipes`.
  - Policy `DROP_AFTER_SPIN` (today's behaviour) or `BLOCK_WITH_DEADLINE`, with overrides per subscriber group.
  - Settings `quietPeriodMillis` and `killSwitch`.
  - Programmatic: `OverloadPolicyConfig`, `MongooseServer.setFeedOverload`.
  - Guide: [Configuring overload and loss](../guide/overload-configuration.md).
- **Default unchanged:** a feed without an `overload` block behaves as before, including health that stays DEGRADED
  after a loss. Boot logs `feed 'X' has no explicit overload policy`.
- **Behaviour change:** `slowConsumerStrategy: DISCONNECT` or `EXIT_PROCESS` now **fails boot**. `BACKOFF` maps to
  `DROP_AFTER_SPIN`, with a warning.
- **New API:**
  - `PublishRefusedException`, thrown by a refused blocking publish.
  - `OverloadState`, `OverloadKillSwitch` and `OverloadLoss`.
  - `MongooseServer.setOverloadKillSwitch`, `clearOverloadState` and `overloadStates`.
  - Admin commands `overload.status` and `overload.clear <feed>`.
  - Publisher accessors `refusedCount()`, `downgradedCount()` and `getStateAudit()`.
  - `GroupReplayer.losses()` and `lossless()`.
- **New counters:** `queue.<path>.refused`, `.blocked`, `.blockedNanos`, `.blockedMaxNanos`, and
  `feed.<name>.downgraded`.
- **Recording:** a new replay entry `Dropped` (CSV `DROPPED`) marks where a queue dropped items. Readers older than
  this change reject such a store rather than misread it.
- **Compatibility:** the pre-#79 all-arguments constructors of `EventFeedConfig` and `HandlerPipeConfig` are kept.
