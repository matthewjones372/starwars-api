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
    (Config
      .uri("baseUrl")
      .mapOrFail(uri =>
        URL.fromURI(uri).toRight(Config.Error.InvalidData(message = s"'$uri' is not a valid base url"))
      ) zip Config.int("cacheSize"))
      .to[HttpClientConfig]
      .nested("clientConfig")
