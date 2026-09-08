package com.matthewjones372.api.client.config

import zio.http.URL
import zio.*
import zio.config.*

final case class HttpClientConfig(
  baseUrl: URL,
  cacheSize: Int,
  maxConcurrency: Int
)

object HttpClientConfig:
  private val defaultMaxConcurrency = 8

  val config: Config[HttpClientConfig] =
    (Config
      .uri("baseUrl")
      .mapOrFail(uri =>
        URL.fromURI(uri).toRight(Config.Error.InvalidData(message = s"'$uri' is not a valid base url"))
      ) zip Config.int("cacheSize")
      zip Config.int("maxConcurrency").withDefault(defaultMaxConcurrency))
      .to[HttpClientConfig]
      .nested("clientConfig")
