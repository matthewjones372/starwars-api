package com.matthewjones372.api.client

import zio.*
import zio.http.*
import ClientError.*

extension (response: Response)
  def bodyOrClientError(url: URL): IO[ClientError, String] =
    if response.status.isSuccess then
      response.body.asString.orElseFail(ClientError.ResponseDeserializationError("Error decoding response"))
    else if response.status.code == 404 then ZIO.fail(ClientError.NotFound(url.encode))
    else if response.status.code == 429 then ZIO.fail(ClientError.RateLimited("Rate limited"))
    else if response.status.isClientError then ZIO.fail(ClientError.UnexpectedClientError("Client error"))
    else if response.status.isServerError then
      ZIO.fail(ClientError.UnexpectedSeverError(s"Server error ${response.status} - url $url"))
    else ZIO.fail(ClientError.UnexpectedClientError(s"Unknown error ${response.status}"))

def decodeUrlString(url: String): IO[InvalidUrl, URL] =
  ZIO.fromEither(URL.decode(url)).orElseFail(InvalidUrl(url))
