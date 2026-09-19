package com.matthewjones372.api.client

// `api-client` is published, so the pre-rename entry point stays resolvable for a
// release. Nothing in this build may reference it: -Werror turns the deprecation
// warning into a compile failure.
@deprecated("Renamed to UniverseClient", "0.2.0")
type SWAPIClientService = UniverseClient

@deprecated("Renamed to UniverseClient", "0.2.0")
val SWAPIClientService: UniverseClient.type = UniverseClient
