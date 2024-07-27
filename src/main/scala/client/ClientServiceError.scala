package com.jones
package client

import scala.util.control.NoStackTrace

abstract class ClientServiceError(msg: String) extends RuntimeException(msg) with NoStackTrace

object ClientServiceError:
  final case class NotFound(id: Int)                 extends ClientServiceError(s"Person with id $id not found")
  final case class DeserializationError(msg: String) extends ClientServiceError(msg)
  final case class UnexpectedError(msg: String)      extends ClientServiceError(msg)
