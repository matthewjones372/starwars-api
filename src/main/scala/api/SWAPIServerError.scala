package com.jones
package api

import zio.schema.*

abstract class SWAPIServerError(message: String)

object SWAPIServerError:
  case class PersonNotFound(message: String, personId: Int) extends SWAPIServerError(message)
  object PersonNotFound:
    given Schema[PersonNotFound] = DeriveSchema.gen

  case class FilmNotFound(message: String, filmId: Int) extends SWAPIServerError(message)
  object FilmNotFound:
    given Schema[FilmNotFound] = DeriveSchema.gen

  case class UnexpectedError(message: String) extends SWAPIServerError(message)

  object UnexpectedError:
    given Schema[UnexpectedError] = DeriveSchema.gen

  case class ServerError() extends SWAPIServerError("Internal server error")
  object ServerError:
    given Schema[ServerError] = DeriveSchema.gen
