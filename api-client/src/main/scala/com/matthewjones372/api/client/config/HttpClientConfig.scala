package com.matthewjones372.api.client.config

import com.matthewjones372.domain.UniverseId
import zio.http.URL
import zio.*
import zio.config.*

/**
 * @param cacheSize
 *   entries held per cached entity type, films and characters each having their
 *   own, rather than a single budget the two compete for.
 * @param universe
 *   the dataset to read, which the server carries as the first path segment.
 */
final case class HttpClientConfig(
  baseUrl: URL,
  cacheSize: Int,
  maxConcurrency: Int,
  universe: UniverseId
):
  def entityUrl(entity: String): URL = baseUrl / universe.slug / entity

object HttpClientConfig:
  private val defaultMaxConcurrency = 8

  val config: Config[HttpClientConfig] =
    (Config
      .uri("baseUrl")
      .mapOrFail(uri =>
        URL.fromURI(uri).toRight(Config.Error.InvalidData(message = s"'$uri' is not a valid base url"))
      ) zip Config.int("cacheSize")
      zip Config.int("maxConcurrency").withDefault(defaultMaxConcurrency)
      zip Config
        .string("universe")
        .mapOrFail(slug => UniverseId.from(slug).left.map(message => Config.Error.InvalidData(message = message)))
        .withDefault(UniverseId.default))
      .to[HttpClientConfig]
      .nested("clientConfig")
