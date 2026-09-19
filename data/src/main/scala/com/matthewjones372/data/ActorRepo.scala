package com.matthewjones372.data

import com.matthewjones372.domain.*
import com.matthewjones372.sorting.SortBy
import zio.*
import zio.schema.codec.JsonCodec.schemaBasedBinaryCodec

trait ActorRepo:
  def getActor(id: EntityId): IO[DataRepoError, Actor]
  def getActors(from: Option[PageNumber], fetchSize: Option[PageSize], sortBy: Option[List[SortBy]]): IO[
    DataRepoError,
    Actors
  ]
  def inUniverse(universe: UniverseId, from: Option[PageNumber], fetchSize: Option[PageSize]): IO[
    DataRepoError,
    Actors
  ]
  def castOf(filmUrl: String): IO[DataRepoError, Actors]
  def portraying(characterUrl: String): IO[DataRepoError, Actors]

object ActorRepo:
  private[data] def resolved(baseUrl: String)(actor: Actor): Actor =
    val at = DataRepo.resolve(baseUrl)
    actor.copy(
      films = actor.films.map(at),
      roles = actor.roles.map(role => role.copy(character = at(role.character), film = at(role.film))),
      url = at(actor.url)
    )

  def fromActors(actors: List[Actor]): IO[DataRepoError, ActorRepo] =
    ZIO
      .foreach(actors)(actor =>
        ZIO
          .fromEither(DataRepo.parseEntityId(actor.url))
          .mapBoth(
            message => DataRepoError.UnexpectedError(message, new IllegalArgumentException(message)),
            id => id -> actor
          )
      )
      .map(byId => InMemoryActorRepo(byId.toMap))

  // The server is also built over a single repo in tests, where no actor data is
  // loaded; an empty one answers honestly rather than failing to start.
  val empty: ActorRepo = InMemoryActorRepo(Map.empty)

  val layer: RLayer[Any, ActorRepo] = ZLayer.fromZIO {
    for
      baseUrl <- DataRepo.publicUrl
      json    <- DataRepo.readResource("actors.json")
      actors  <- ZIO
                  .fromEither(json.to[List[Actor]])
                  .mapError(error => new RuntimeException(s"Failed to parse actor data: $error"))
      _    <- ZIO.logInfo(s"Parsed ${actors.size} actors")
      repo <- fromActors(actors.map(resolved(baseUrl)))
    yield repo
  }

final private case class InMemoryActorRepo(actorsById: Map[EntityId, Actor]) extends ActorRepo:
  private val ordered = actorsById.toList.sortBy(_._1).map(_._2)

  override def getActor(id: EntityId): IO[DataRepoError, Actor] =
    ZIO.fromOption(actorsById.get(id)).orElseFail(DataRepoError.ActorNotFound("Actor not found", id))

  override def getActors(
    from: Option[PageNumber],
    fetchSize: Option[PageSize],
    sortBy: Option[List[SortBy]]
  ): IO[DataRepoError, Actors] =
    val sorted = sortBy.fold(ordered)(Sorting.actors(ordered, _))
    ZIO.succeed(Actors(ordered.size, DataRepo.paginate(sorted, from, fetchSize)))

  override def inUniverse(
    universe: UniverseId,
    from: Option[PageNumber],
    fetchSize: Option[PageSize]
  ): IO[DataRepoError, Actors] =
    val matching = ordered.filter(_.universes.contains(universe))
    ZIO.succeed(Actors(matching.size, DataRepo.paginate(matching, from, fetchSize)))

  override def castOf(filmUrl: String): IO[DataRepoError, Actors] =
    val matching = ordered.filter(_.films.contains(filmUrl))
    ZIO.succeed(Actors(matching.size, matching))

  override def portraying(characterUrl: String): IO[DataRepoError, Actors] =
    val matching = ordered.filter(_.roles.exists(_.character == characterUrl))
    ZIO.succeed(Actors(matching.size, matching))
