package com.matthewjones372.data

import com.matthewjones372.domain.EntityId

enum DataRepoError(msg: String) extends RuntimeException(msg):
  case CharacterNotFound(message: String, characterId: EntityId)
      extends DataRepoError(s"Character with id $characterId not found.")
  case FilmNotFound(message: String, filmId: EntityId) extends DataRepoError(s"Film with id $filmId not found.")
  case UnexpectedError(message: String, exception: Exception)
      extends DataRepoError(s"Data-Repo has encountered an unexpected error: $message. Exception: $exception")
  case FilmsNotFound extends DataRepoError(s"No films found.")
  case PaginatedResponseError
      extends DataRepoError("You need to provide both an offset and a fetch size or provide nothing")
