# Published-artifact acceptance

Both RCs are published and independently resolved from fresh Maven caches using empty settings files.

- [Mongoose 1.0.33-rc1](https://github.com/telaminai/mongoose/releases/tag/v1.0.33-rc1), exact qualified source 49246452e5eb9879529880f264150c094547ad24.
- [Plugins 1.0.46-rc1](https://github.com/telaminai/mongoose-plugins/releases/tag/v1.0.46-rc1), exact qualified source f1f90039909e3e6c1fd8b949bbf4f0712fffa4ab.
- Core and all 19 plugin module artifacts plus dependencies resolved: BUILD SUCCESS. Checksums are retained here.

The analyser bundle and Spring/Mongoose starter were fresh copies of downloaded starter projects with the
compatibility changes in fluxtion-web PR #20 and RC version overrides. Builds used only the public-artifact
cache, with no locally installed candidate artifacts. Both builds passed: two tests each, no failures/errors/skips.
Both live runs captured all five original input rows with exact values, exported audits and stopped cleanly.
The Spring process's exit 143 is its requested SIGTERM after verification, with registry removal checked.

These results validate the fixed starter code against the published RCs. They do not claim that the current
public playground defaults already include those changes; PR #20 deployment is separate. No claim of client
migration clearance or stable-version publication is made.
