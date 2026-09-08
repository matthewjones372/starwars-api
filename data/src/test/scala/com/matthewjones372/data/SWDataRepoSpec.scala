package com.matthewjones372.data

import com.matthewjones372.domain.*
import com.matthewjones372.sorting.{FieldOrdering, SortBy}
import zio.*
import zio.test.*

object SWDataRepoSpec extends ZIOSpecDefault:

  private def personWithId(id: Int, name: String = "", height: Option[Int] = None) =
    People(
      name = if name.isEmpty then s"person-$id" else name,
      height = height,
      mass = None,
      hairColor = "none",
      skinColor = "none",
      eyeColor = "none",
      birthYear = "unknown",
      gender = None,
      homeworld = None,
      films = Set.empty,
      species = None,
      vehicles = None,
      starships = None,
      url = s"http://localhost:8080/people/$id/"
    )

  private def filmWithId(id: Int) =
    Film(
      title = s"film-$id",
      episodeId = id,
      openingCrawl = "",
      director = "",
      producer = "",
      releaseDate = "",
      characters = Set.empty,
      planets = Set.empty,
      starships = Set.empty,
      vehicles = Set.empty,
      species = Set.empty,
      created = "",
      edited = "",
      url = s"http://localhost:8080/films/$id/"
    )

  private val thirtyPeople = (1 to 30).map(id => personWithId(id)).toList
  private val thirtyFilms  = (1 to 30).map(filmWithId).toList

  private val repo = SWDataRepo.fromEntities(thirtyPeople, thirtyFilms)

  def spec = suite("SWDataRepoSpec")(
    suite("paginate")(
      test("a page holds exactly the requested page size") {
        val page = SWDataRepo.paginate((1 to 30).toList, Some(1), Some(10))
        assertTrue(page.length == 10, page == (1 to 10).toList)
      },
      test("consecutive pages do not overlap") {
        val first  = SWDataRepo.paginate((1 to 30).toList, Some(1), Some(10))
        val second = SWDataRepo.paginate((1 to 30).toList, Some(2), Some(10))
        assertTrue(first.intersect(second).isEmpty, second == (11 to 20).toList)
      },
      test("every page is reachable and the pages tile the data") {
        val pages = (1 to 3).toList.map(page => SWDataRepo.paginate((1 to 30).toList, Some(page), Some(10)))
        assertTrue(pages.flatten == (1 to 30).toList)
      },
      test("a final partial page is not padded") {
        val page = SWDataRepo.paginate((1 to 25).toList, Some(3), Some(10))
        assertTrue(page == (21 to 25).toList)
      },
      test("a page beyond the data is empty") {
        assertTrue(SWDataRepo.paginate((1 to 30).toList, Some(99), Some(10)).isEmpty)
      },
      test("a page without a fetch size uses the default page size") {
        val page = SWDataRepo.paginate((1 to 30).toList, Some(2), None)
        assertTrue(page == (11 to 20).toList)
      },
      test("a fetch size without a page takes from the start") {
        assertTrue(SWDataRepo.paginate((1 to 30).toList, None, Some(5)) == (1 to 5).toList)
      },
      test("neither a page nor a fetch size returns everything") {
        assertTrue(SWDataRepo.paginate((1 to 30).toList, None, None) == (1 to 30).toList)
      }
    ),
    suite("parseEntityId")(
      test("reads the trailing id from an entity url") {
        assertTrue(
          SWDataRepo.parseEntityId("http://localhost:8080/people/80/") == Right(80),
          SWDataRepo.parseEntityId("http://localhost:8080/films/6") == Right(6)
        )
      },
      test("fails rather than throwing on a url with no numeric id") {
        assertTrue(
          SWDataRepo.parseEntityId("http://localhost:8080/people/").isLeft,
          SWDataRepo.parseEntityId("not a url at all").isLeft
        )
      }
    ),
    suite("getPeople")(
      test("returns the total count alongside a single page of results") {
        for
          repo   <- repo
          people <- repo.getPeople(Some(1), Some(10), None)
        yield assertTrue(people.count == 30, people.results.length == 10)
      },
      test("pages are stable and ordered by id regardless of map ordering") {
        for
          repo   <- SWDataRepo.fromEntities(scala.util.Random.shuffle(thirtyPeople), thirtyFilms)
          first  <- repo.getPeople(Some(1), Some(10), None)
          second <- repo.getPeople(Some(2), Some(10), None)
        yield assertTrue(
          first.results.map(_.name) == (1 to 10).map(id => s"person-$id").toList,
          second.results.map(_.name) == (11 to 20).map(id => s"person-$id").toList
        )
      },
      test("repeated requests for the same page return the same results") {
        for
          repo  <- repo
          one   <- repo.getPeople(Some(2), Some(10), None)
          again <- repo.getPeople(Some(2), Some(10), None)
        yield assertTrue(one.results == again.results)
      },
      test("pageCount covers every person exactly once") {
        for
          repo   <- repo
          first  <- repo.getPeople(Some(1), Some(10), None)
          rest   <- ZIO.foreach(2 to first.pageCount)(page => repo.getPeople(Some(page), Some(10), None))
          fetched = first.results ++ rest.flatMap(_.results)
        yield assertTrue(fetched.length == 30, fetched.map(_.name).distinct.length == 30)
      },
      test("sorts by the requested field") {
        val people = List(personWithId(1, "Chewbacca"), personWithId(2, "Ackbar"), personWithId(3, "Boba"))
        for
          repo   <- SWDataRepo.fromEntities(people, Nil)
          sorted <- repo.getPeople(None, None, Some(List(SortBy("name", FieldOrdering.ASC))))
        yield assertTrue(sorted.results.map(_.name) == List("Ackbar", "Boba", "Chewbacca"))
      },
      test("sorts descending when asked") {
        val people = List(personWithId(1, "Chewbacca"), personWithId(2, "Ackbar"), personWithId(3, "Boba"))
        for
          repo   <- SWDataRepo.fromEntities(people, Nil)
          sorted <- repo.getPeople(None, None, Some(List(SortBy("name", FieldOrdering.DESC))))
        yield assertTrue(sorted.results.map(_.name) == List("Chewbacca", "Boba", "Ackbar"))
      }
    ),
    suite("getFilms")(
      test("returns the total count alongside a single page of results") {
        for
          repo  <- repo
          films <- repo.getFilms(Some(1), Some(10))
        yield assertTrue(films.count == 30, films.results.length == 10)
      },
      test("consecutive pages do not repeat a film") {
        for
          repo   <- repo
          first  <- repo.getFilms(Some(1), Some(10))
          second <- repo.getFilms(Some(2), Some(10))
        yield assertTrue(first.results.intersect(second.results).isEmpty)
      }
    ),
    suite("lookup by id")(
      test("finds a person and a film by the id in their url") {
        for
          repo   <- repo
          person <- repo.getPerson(7)
          film   <- repo.getFilm(7)
        yield assertTrue(person.name == "person-7", film.title == "film-7")
      },
      test("fails with PersonNotFound for an unknown person") {
        for
          repo   <- repo
          result <- repo.getPerson(999).exit
        yield assert(result)(Assertion.failsWithA[DataRepoError.PersonNotFound])
      },
      test("fails with FilmNotFound for an unknown film") {
        for
          repo   <- repo
          result <- repo.getFilm(999).exit
        yield assert(result)(Assertion.failsWithA[DataRepoError.FilmNotFound])
      }
    ),
    suite("layer")(
      test("loads the bundled star wars data from the classpath") {
        for
          repo   <- ZIO.service[SWDataRepo]
          people <- repo.getPeople(None, None, None)
          films  <- repo.getFilms(None, None)
          person <- repo.getPerson(1)
          film   <- repo.getFilm(1)
        yield assertTrue(
          people.count == 82,
          films.count == 6,
          person.name.nonEmpty,
          film.title.nonEmpty
        )
      },
      test("pages the bundled data without gaps or repeats") {
        for
          repo   <- ZIO.service[SWDataRepo]
          first  <- repo.getPeople(Some(1), Some(10), None)
          rest   <- ZIO.foreach(2 to first.pageCount)(page => repo.getPeople(Some(page), Some(10), None))
          fetched = first.results ++ rest.flatMap(_.results)
        yield assertTrue(fetched.length == 82, fetched.map(_.url).distinct.length == 82)
      }
    ).provideShared(SWDataRepo.layer),
    suite("fromEntities")(
      test("fails rather than throwing when an entity url has no id") {
        for
          result <- SWDataRepo.fromEntities(List(personWithId(1).copy(url = "http://localhost:8080/people/")), Nil).exit
        yield assert(result)(Assertion.failsWithA[DataRepoError.UnexpectedError])
      }
    )
  )
