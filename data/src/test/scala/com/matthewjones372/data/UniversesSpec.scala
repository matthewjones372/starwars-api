package com.matthewjones372.data

import com.matthewjones372.domain.*
import zio.*
import zio.test.*

/**
 * One pass over every bundled dataset.
 *
 * This is the ingestion guard: a scrape that produced a relative url, an entity
 * whose id cannot be parsed out of it, or a dataset that stopped loading fails
 * here rather than on the first request that happens to ask for it.
 */
object UniversesSpec extends ZIOSpecDefault:

  private def absolute(url: String) = url.startsWith("http://") || url.startsWith("https://")

  def spec = suite("UniversesSpec")(
    test("every universe the enum names ships data") {
      for offered <- Universes.available
      yield assertTrue(offered.toSet == UniverseId.values.toSet)
    },
    test("every universe holds characters and films") {
      for
        offered <- Universes.available
        counts  <- ZIO.foreach(offered) { universe =>
                    for
                      repo   <- Universes.repo(universe)
                      people <- repo.getCharacters(None, None, None)
                      films  <- repo.getFilms(None, None)
                    yield (universe, people.count, films.count)
                  }
      yield assertTrue(counts.forall((_, people, films) => people > 0 && films > 0))
    },
    test("every url an entity carries is absolute") {
      for
        offered <- Universes.available
        bad     <- ZIO.foreach(offered) { universe =>
                 for
                   repo   <- Universes.repo(universe)
                   people <- repo.getCharacters(None, None, None)
                   films  <- repo.getFilms(None, None)
                 yield people.results.flatMap(person =>
                   (person.url +: (person.films ++ person.portrayedBy).toList) ++
                     person.links.values.flatten
                 ) ++ films.results.flatMap(film =>
                   (film.url +: (film.characters ++ film.cast).toList) ++ film.links.values.flatten
                 )
               }
      yield assertTrue(bad.flatten.forall(absolute))
    },
    test("every entity url carries the id its repo keyed it by") {
      for
        offered <- Universes.available
        ok      <- ZIO.foreach(offered) { universe =>
                for
                  repo   <- Universes.repo(universe)
                  people <- repo.getCharacters(None, None, None)
                yield people.results.forall(person => DataRepo.parseEntityId(person.url).isRight)
              }
      yield assertTrue(ok.forall(identity))
    },
    test("a character's url names the universe it belongs to") {
      for
        offered <- Universes.available
        ok      <- ZIO.foreach(offered) { universe =>
                for
                  repo   <- Universes.repo(universe)
                  people <- repo.getCharacters(None, None, None)
                yield people.results.forall(_.url.contains(s"/${universe.slug}/people/"))
              }
      yield assertTrue(ok.forall(identity))
    }
  ).provide(Universes.layer)
