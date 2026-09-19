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
    // Star Wars keeps swapi's entities while its cast comes from Wikidata, and
    // the two number their films and characters differently. Both spell a url
    // the same way, so a role that was not translated points at whatever else
    // holds that number -- Harrison Ford billed in Rogue One, playing someone
    // he has never played.
    test("every role points at an entity the api actually serves") {
      for
        actors    <- ZIO.serviceWithZIO[ActorRepo](_.getActors(None, None, None))
        universes <- ZIO.service[Universes]
        checked   <- ZIO.foreach(actors.results.flatMap(_.roles).distinct) { role =>
                     for
                       repo      <- universes.repo(role.universe)
                       id        <- ZIO.fromEither(DataRepo.parseEntityId(role.character)).orElseFail(role)
                       filmId    <- ZIO.fromEither(DataRepo.parseEntityId(role.film)).orElseFail(role)
                       character <- repo.getCharacter(id)
                       film      <- repo.getFilm(filmId)
                     // The names, not the urls: a url that was never translated
                     // still resolves, just to somebody else.
                     yield character.name.equalsIgnoreCase(role.characterName) &&
                       film.title.equalsIgnoreCase(role.filmTitle)
                   }
      yield assertTrue(checked.nonEmpty, checked.forall(identity))
    },
    test("a film's cast and its actors' film lists agree") {
      for
        actors    <- ZIO.serviceWithZIO[ActorRepo](_.getActors(None, None, None))
        universes <- ZIO.service[Universes]
        byUrl      = actors.results.map(actor => actor.url -> actor).toMap
        offered    = universes.available
        agreed    <- ZIO.foreach(offered) { universe =>
                    for
                      repo  <- universes.repo(universe)
                      films <- repo.getFilms(None, None)
                    yield films.results.forall(film =>
                      film.cast.forall(url => byUrl.get(url).forall(_.films.contains(film.url)))
                    )
                  }
      yield assertTrue(agreed.forall(identity))
    },
    test("an actor id that is not there is a typed failure") {
      for
        repo   <- ZIO.service[ActorRepo]
        result <- repo.getActor(EntityId(999999)).either
      yield assertTrue(result.left.exists(_.isInstanceOf[DataRepoError.ActorNotFound]))
    }
  ).provide(ActorRepo.layer, Universes.layer)
