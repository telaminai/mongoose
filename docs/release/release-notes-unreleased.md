# Unreleased changes

The dispatch allocation patch is assigned to [Mongoose 1.1.1](release-notes-1.1.1.md).
The feed caught-up signal and the at-least-once file feed changes are described in
[Mongoose 1.1.3 (unreleased)](release-notes-1.1.3.md).
Qualification and publication status must be read from its release evidence; this pointer does not claim it has shipped.

## No Chronicle usage analytics

Chronicle Queue (used by audit capture) sends a usage event to Google Analytics each time a queue is opened,
and writes a client id file, `~/.chronicle.analytics.client.id`, in the user's home directory. Mongoose
applications now make no such calls, with no setting needed:

- Mongoose no longer brings `net.openhft:chronicle-analytics` onto the classpath (it is excluded from
  `chronicle-queue`). Without that jar Chronicle's analytics client is a no-op. A build rule fails if the jar
  comes back.
- `MongooseServer` and the audit capture service set `chronicle.analytics.disable=true` before any queue is
  opened, for applications that bring the jar back through another dependency. A value you set yourself, for
  example `-Dchronicle.analytics.disable=false`, is left as it is.
