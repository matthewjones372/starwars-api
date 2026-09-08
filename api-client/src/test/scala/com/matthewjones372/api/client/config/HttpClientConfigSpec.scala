package com.matthewjones372.api.client.config

import zio.*
import zio.test.*

object HttpClientConfigSpec extends ZIOSpecDefault:
  private def load(entries: (String, String)*) =
    ZIO.config(HttpClientConfig.config).withConfigProvider(ConfigProvider.fromMap(entries.toMap))

  def spec = suite("HttpClientConfig")(
    test("falls back to a bounded default when maxConcurrency is not configured") {
      for config <- load("clientConfig.baseUrl" -> "http://localhost", "clientConfig.cacheSize" -> "10")
      yield assertTrue(config.maxConcurrency == 8, config.cacheSize == 10)
    },
    test("reads maxConcurrency when it is configured") {
      for
        config <- load(
                    "clientConfig.baseUrl"        -> "http://localhost",
                    "clientConfig.cacheSize"      -> "10",
                    "clientConfig.maxConcurrency" -> "3"
                  )
      yield assertTrue(config.maxConcurrency == 3)
    },
    test("fails rather than throwing when the base url cannot be read") {
      for result <- load("clientConfig.baseUrl" -> "://nonsense", "clientConfig.cacheSize" -> "10").exit
      yield assert(result)(Assertion.fails(Assertion.anything))
    }
  )
