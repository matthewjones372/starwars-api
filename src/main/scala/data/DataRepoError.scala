package com.jones
package data

enum DataRepoError(msg: String) extends RuntimeException(msg):
  case PersonNotFound(message: String, personId: Int) extends DataRepoError(s"Person with id $personId not found.")
  case FilmNotFound(message: String, filmId: Int) extends DataRepoError(s"Film with id $filmId not found.")
  case UnexpectedError(message: String, exception: Exception)
      extends DataRepoError(s"Data-Repo has encountered an unexpected error: $message. Exception: $exception")
  case FilmsNotFound extends DataRepoError(s"No films found.")
