package com.matthewjones372.api.client.config

import zio.http.URL
import zio.*
import zio.config.*

final case class HttpClientConfig(
  baseUrl: URL,
  cacheSize: Int
)

object HttpClientConfig:
  val config: Config[HttpClientConfig] =
    (Config.uri("baseUrl").map(uri => URL.fromURI(uri).get) zip Config.int("cacheSize"))
      .to[HttpClientConfig]
      .nested("clientConfig")
