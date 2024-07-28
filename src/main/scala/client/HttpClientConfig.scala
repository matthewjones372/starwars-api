package com.jones
package client

import zio.http.URL

final case class HttpClientConfig(
  baseUrl: URL,
)
