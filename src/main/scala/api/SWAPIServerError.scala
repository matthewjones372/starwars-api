package com.jones
package api

import zio.schema.*

abstract class SWAPIServerError(message: String)

object SWAPIServerError:
  final case class PersonNotFound(message: String, personId: Int) extends SWAPIServerError(message)
  final case class FilmNotFound(message: String, filmId: Int)     extends SWAPIServerError(message)
  final case class UnexpectedError(message: String)               extends SWAPIServerError(message)
  final case class ServerError()                                  extends SWAPIServerError("Internal server error")

  given Schema[PersonNotFound]  = DeriveSchema.gen
  given Schema[FilmNotFound]    = DeriveSchema.gen
  given Schema[UnexpectedError] = DeriveSchema.gen
  given Schema[ServerError]     = DeriveSchema.gen
