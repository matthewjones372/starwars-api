package com.matthewjones372.data

import com.matthewjones372.domain.*
import zio.*
import zio.test.*

object ActorRepoSpec extends ZIOSpecDefault:
  def spec = suite("ActorRepoSpec")(
    test("loads the bundled actors") {
      for
        repo   <- ZIO.service[ActorRepo]
        actors <- repo.getActors(None, None, None)
      yield assertTrue(actors.count > 1000, actors.results.forall(_.name.nonEmpty))
    },
    test("an actor's urls are absolute, including the ones inside their roles") {
      for
        repo   <- ZIO.service[ActorRepo]
        actors <- repo.getActors(None, None, None)
        urls    = actors.results.flatMap(actor =>
                 (actor.url +: actor.films.toList) ++ actor.roles.flatMap(role => List(role.character, role.film))
               )
      yield assertTrue(urls.forall(_.startsWith("http")))
    },
    // The reason the actor graph is worth building at all: it is the only edge
    // in this API that joins one dataset to another.
    test("some actors appear in more than one universe") {
      for
        repo   <- ZIO.service[ActorRepo]
        actors <- repo.getActors(None, None, None)
        across  = actors.results.filter(_.universes.size > 1)
      yield assertTrue(
        across.nonEmpty,
        across.exists(_.name == "Christopher Lee"),
        across.find(_.name == "Christopher Lee").exists(_.universes.size == 2)
      )
    },
    test("an actor is listed under each universe they appear in") {
      for
        repo   <- ZIO.service[ActorRepo]
        inLotr <- repo.inUniverse(UniverseId.MiddleEarth, None, None)
        inMcu  <- repo.inUniverse(UniverseId.Marvel, None, None)
      yield assertTrue(
        inLotr.count > 0,
        inMcu.count > 0,
        inLotr.results.forall(_.universes.contains(UniverseId.MiddleEarth))
      )
    },
    test("a film's cast and a character's portrayals are found by url") {
      for
        repo   <- ZIO.service[ActorRepo]
        actors <- repo.getActors(None, None, None)
        anyRole = actors.results.flatMap(_.roles).head
        cast   <- repo.castOf(anyRole.film)
        played <- repo.portraying(anyRole.character)
      yield assertTrue(cast.count > 0, played.count > 0)
    },
    test("an actor id that is not there is a typed failure") {
      for
        repo   <- ZIO.service[ActorRepo]
        result <- repo.getActor(EntityId(999999)).either
      yield assertTrue(result.left.exists(_.isInstanceOf[DataRepoError.ActorNotFound]))
    }
  ).provide(ActorRepo.layer)
