package com.jones
package client


import model.{Film, People}

import zio.*
import zio.http.{Client, URL}

abstract class ClientError(msg: String) extends RuntimeException(msg)

object ClientError:
  final case class NotFound(request: String) extends ClientError(s"Request for $request not found.")

  final case class JsonDeserializationError(msg: String) extends ClientError(s"Error decoding message: $msg")

  final case class ResponseDeserializationError(msg: String) extends ClientError(msg)

  final case class UnexpectedClientError(msg: String) extends ClientError(msg)

  final case class UnexpectedSeverError(msg: String) extends ClientError(msg)

  final case class InvalidUrl(url: String) extends ClientError(url)

  final case class RateLimited(msg: String) extends ClientError(msg)

  final case class ClientPolicyError(msg: String) extends ClientError(msg)


