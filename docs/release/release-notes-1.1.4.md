# Mongoose 1.1.4

Chronicle usage analytics is disabled by default. The analytics dependency is excluded and an enforcer rule prevents its accidental return. When another dependency supplies it, Mongoose disables analytics before opening queues unless the application explicitly configures the property.

Published to the Fluxtion public Repsy repository; no Maven Central publication for this release. Use https://repo.repsy.io/mvn/fluxtion/fluxtion-public in Maven repository configuration.
