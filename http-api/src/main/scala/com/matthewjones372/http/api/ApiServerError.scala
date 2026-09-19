package com.matthewjones372.http.api

import com.matthewjones372.domain.EntityId
import zio.schema.*

abstract class ApiServerError(message: String) extends RuntimeException(message)

object ApiServerError:
  final case class CharacterNotFound(message: String, characterId: EntityId) extends ApiServerError(message)
      derives Schema
  final case class FilmNotFound(message: String, filmId: EntityId) extends ApiServerError(message) derives Schema
  final case class PathNotFound(message: String)                   extends ApiServerError(message) derives Schema
  final case class UnexpectedError(message: String)                extends ApiServerError(message) derives Schema
  final case class ServerError()                                   extends ApiServerError("Internal server error") derives Schema
