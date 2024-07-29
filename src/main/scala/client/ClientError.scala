package com.jones
package client

enum ClientError(msg: String) extends RuntimeException(msg):
  case NotFound(request: String) extends ClientError(s"Request for $request not found.")
  case JsonDeserializationError(msg: String) extends ClientError(s"Error decoding message: $msg)")
  case ResponseDeserializationError(msg: String) extends ClientError(msg)
  case UnexpectedClientError(msg: String) extends ClientError(msg)
  case UnexpectedSeverError(msg: String) extends ClientError(msg)
  case InvalidUrl(url: String) extends ClientError(url)
  case RateLimited(msg: String) extends ClientError(msg)
  case ClientPolicyError(msg: String, exception: Throwable) extends ClientError(msg)
  case UnreachableError extends ClientError("Unreachable error")