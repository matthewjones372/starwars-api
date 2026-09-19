package com.matthewjones372.data

import com.matthewjones372.domain.UniverseId
import zio.*

trait Universes:
  def repo(universe: UniverseId): IO[DataRepoError, DataRepo]
  def available: List[UniverseId]

object Universes:
  def repo(universe: UniverseId): ZIO[Universes, DataRepoError, DataRepo] =
    ZIO.serviceWithZIO[Universes](_.repo(universe))

  def available: URIO[Universes, List[UniverseId]] = ZIO.serviceWith[Universes](_.available)

  // A universe is on offer when its data is on the classpath. The path codec
  // knows every slug the enum names, including the ones no dataset ships yet,
  // so the two have to be told apart here rather than at the route.
  private def bundled(universe: UniverseId): Task[Boolean] =
    ZIO.attemptBlocking(Option(getClass.getResourceAsStream(s"/${universe.slug}_people.json")).isDefined)

  /**
   * Every bundled universe read at startup.
   *
   * Eager rather than lazy: the whole of it is a few megabytes of entities, and
   * reading it now turns a malformed dataset into a failure to start rather
   * than into the first request that happens to ask for it.
   */
  val layer: RLayer[Any, Universes] = ZLayer.fromZIO {
    for
      offered <- ZIO.filter(UniverseId.values.toList)(bundled)
      repos   <- ZIO.foreach(offered)(universe => DataRepo.of(universe).map(universe -> _))
      _       <- ZIO.logInfo(s"Serving ${offered.map(_.slug).mkString(", ")}")
    yield InMemoryUniverses(repos.toMap)
  }

  def fromRepos(repos: Map[UniverseId, DataRepo]): Universes = InMemoryUniverses(repos)

final private case class InMemoryUniverses(repos: Map[UniverseId, DataRepo]) extends Universes:
  override def repo(universe: UniverseId): IO[DataRepoError, DataRepo] =
    ZIO.fromOption(repos.get(universe)).orElseFail(DataRepoError.UniverseNotAvailable(universe))

  override def available: List[UniverseId] = repos.keys.toList.sorted
