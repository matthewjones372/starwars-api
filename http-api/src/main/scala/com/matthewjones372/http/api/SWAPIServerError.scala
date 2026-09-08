package com.matthewjones372.http.api

import com.matthewjones372.domain.EntityId
import zio.schema.*

abstract class SWAPIServerError(message: String) extends RuntimeException(message)

object SWAPIServerError:
  final case class CharacterNotFound(message: String, characterId: EntityId) extends SWAPIServerError(message)
      derives Schema
  final case class FilmNotFound(message: String, filmId: EntityId) extends SWAPIServerError(message) derives Schema
  final case class PathNotFound(message: String)                   extends SWAPIServerError(message) derives Schema
  final case class UnexpectedError(message: String)                extends SWAPIServerError(message) derives Schema
  final case class ServerError()                                   extends SWAPIServerError("Internal server error") derives Schema
