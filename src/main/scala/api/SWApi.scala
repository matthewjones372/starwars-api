package com.jones
package api

import api.SWAPIServerError.*
import data.{DataRepoError, SWDataRepo}
import domain.*

import zio.*

trait SWApi:
  def getFilmFrom(id: Int): IO[SWAPIServerError, Film]

  def getFilms: IO[SWAPIServerError, Set[Film]]

  def getPerson(id: Int): IO[SWAPIServerError, People]

  def getPeople: IO[SWAPIServerError, Set[People]]

object SWApi:
  def layer = ZLayer.fromFunction(SWAPIImpl.apply)

private case class SWAPIImpl(dataRepo: SWDataRepo) extends SWApi:
  override def getFilmFrom(id: Int): IO[SWAPIServerError, Film] =
    dataRepo.getFilm(id).mapError {
      case DataRepoError.FilmNotFound(message, filmId) =>
        FilmNotFound(message, filmId)
      case err =>
        UnexpectedError(err.getMessage)
    }

  override def getPeople: IO[SWAPIServerError, Set[People]] =
    dataRepo.getPeople.mapError { err =>
      UnexpectedError(err.getMessage)
    }

  override def getPerson(id: Int): IO[SWAPIServerError, People] =
    dataRepo.getPerson(id).mapError {
      case DataRepoError.PersonNotFound(message, personId) =>
        PersonNotFound(message, personId)
      case err =>
        UnexpectedError(err.getMessage)
    }

  override def getFilms: IO[SWAPIServerError, Set[Film]] = dataRepo.getFilms.mapError { err =>
    UnexpectedError(err.getMessage)
  }
