package com.matthewjones372.http.api

import zio.schema.*

abstract class SWAPIServerError(message: String)

object SWAPIServerError:
  final case class PersonNotFound(message: String, personId: Int) extends SWAPIServerError(message) derives Schema
  final case class FilmNotFound(message: String, filmId: Int)     extends SWAPIServerError(message) derives Schema
  final case class UnexpectedError(message: String)               extends SWAPIServerError(message) derives Schema
  final case class ServerError()                                  extends SWAPIServerError("Internal server error") derives Schema
