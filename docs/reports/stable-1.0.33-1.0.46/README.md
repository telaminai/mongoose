# Stable release verification

Mongoose 1.0.33: **9cba720fca901e9de56a118899fd54ab37a8979e**.
Plugins 1.0.46: **56d8cb2202bfb28274b6d5fc74e048ce4ea095d4**.

Core `mvn -o -B -ntp -Dmaven.test.redirectTestOutputToFile=true clean install`: BUILD SUCCESS,
1,019 tests, zero failures/errors, 14 unchanged skips. Includes package and local installation.
Plugins `mvn -o -B -ntp -Dmaven.test.redirectTestOutputToFile=true clean verify`: BUILD SUCCESS,
333 tests, zero failures/errors/skips. Strict MkDocs passed; both core Java 21 CI runs passed.
Full commands, hashes, counts, logs and Surefire XML are retained here.

Core source under `src/` is unchanged from v1.0.33-rc1; plugin Java source is unchanged from
v1.0.46-rc1. Stable promotion changes version coordinates, current schema metadata, documentation
and stable-tag publication configuration. The RC's stress, mutation and ten-minute soak results
remain recorded under their original SHA in ../pr76-rc1-4924645; they are not new stable-commit runs.

Both RCs were publicly resolved and both fixed starter projects passed live capture and shutdown
against the published RC artifacts. Stable artifact resolution and release workflow links will be
added after publication. Client migration remains independently gated.
