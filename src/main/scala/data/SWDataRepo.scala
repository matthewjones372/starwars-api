package com.jones
package data

import domain.*

import zio.*
import zio.concurrent.ConcurrentMap
import zio.json.*

trait SWDataRepo:
  def getFilm(id: Int): IO[DataRepoError, Film]
  def getPerson(id: Int): IO[DataRepoError, People]
  def getPeople: IO[DataRepoError, Set[People]]
  def getFilms: IO[DataRepoError, Set[Film]]

object SWDataRepo:
  def layer: RLayer[Any, SWDataRepo] = ZLayer.fromZIO {
    for
      peopleData <- ConcurrentMap.empty[Int, People]
      filmData   <- ConcurrentMap.empty[Int, Film]
      peopleJson <- ZIO.readFile("src/main/resources/people_data.json")
      peoples    <- ZIO.fromEither(peopleJson.fromJson[List[People]]).mapError(e => new RuntimeException(e))
      _          <- peopleData.putAll(peoples.zipWithIndex.map((p, id) => (id, p))*)
      filmJson   <- ZIO.readFile("src/main/resources/film_data.json")
      films      <- ZIO.fromEither(filmJson.fromJson[List[Film]]).mapError(e => new RuntimeException(e))
      _          <- filmData.putAll(films.zipWithIndex.map((f, id) => (id, f))*)
    yield InMemoryDataRepo(peopleData, filmData)
  }

private final case class InMemoryDataRepo(peopleData: ConcurrentMap[Int, People], filmData: ConcurrentMap[Int, Film])
    extends SWDataRepo:

  override def getFilms: IO[DataRepoError, Set[Film]] =
    filmData.toList.map(_.map { case (_, film) => film }).map(_.toSet)

  override def getFilm(id: Int): IO[DataRepoError, Film] =
    filmData.get(id).someOrFail(DataRepoError.FilmNotFound("Film not found", id))

  override def getPerson(id: Int): IO[DataRepoError, People] =
    peopleData.get(id).someOrFail(DataRepoError.PersonNotFound("Person not found", id))

  override def getPeople: IO[DataRepoError, Set[People]] =
    peopleData.toList.map(_.map { case (_, person) => person }).map(_.toSet)
