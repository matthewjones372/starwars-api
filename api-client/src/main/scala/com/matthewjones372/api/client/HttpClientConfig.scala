package com.matthewjones372.api.client

import zio.http.URL

final case class HttpClientConfig(
  baseUrl: URL,
  cacheSize: Int
)
