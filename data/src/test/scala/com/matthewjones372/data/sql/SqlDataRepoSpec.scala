package com.matthewjones372.data.sql

import com.matthewjones372.data.{DataRepoError, SWDataRepo}
import com.matthewjones372.sorting.{FieldOrdering, SortBy}
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import org.testcontainers.postgresql.PostgreSQLContainer
import zio.*
import zio.test.*

import javax.sql.DataSource

object SqlDataRepoSpec extends ZIOSpecDefault:

  private val dataSource: ZIO[Scope, Throwable, DataSource] =
    for
      container <- ZIO.acquireRelease(
                     ZIO.attemptBlocking {
                       val started = PostgreSQLContainer("postgres:16-alpine")
                       started.start()
                       started
                     }
                   )(container => ZIO.attemptBlocking(container.stop()).orDie)
      pool <- ZIO.acquireRelease(
                ZIO.attemptBlocking {
                  val config = HikariConfig()
                  config.setJdbcUrl(container.getJdbcUrl)
                  config.setUsername(container.getUsername)
                  config.setPassword(container.getPassword)
                  config.setMaximumPoolSize(4)
                  HikariDataSource(config)
                }
              )(pool => ZIO.attemptBlocking(pool.close()).orDie)
    yield pool

  private val seededRepo: ZLayer[Any, Throwable, SWDataRepo] =
    ZLayer.scoped {
      for
        pool      <- dataSource
        _         <- SwMigrations.migrate.provideEnvironment(ZEnvironment(pool))
        transactor = ZTransactor(pool)
        _         <- SwSeed.fromBundledData.provideEnvironment(ZEnvironment(transactor))
      yield SqlDataRepo(transactor)
    }

  private val ascendingName = Some(List(SortBy("name", FieldOrdering.ASC)))

  def spec = suite("SqlDataRepoSpec")(
    suite("order by whitelist")(
      test("maps known sort keys onto columns and always breaks ties on id") {
        assertTrue(
          SqlDataRepo.orderByClause(ascendingName) == "order by name asc, id asc",
          SqlDataRepo.orderByClause(Some(List(SortBy("height", FieldOrdering.DESC)))) ==
            "order by height desc, id asc"
        )
      },
      test("every derived sort column exists in the people table") {
        val ddl         = scala.io.Source.fromResource("db/migration/V1__initial_schema.sql").mkString
        val peopleTable = ddl.split("create table").find(_.trim.startsWith("people (")).getOrElse("")
        val columns     = "(?m)^\\s+([a-z_]+)\\s+(integer|text)".r.findAllMatchIn(peopleTable).map(_.group(1)).toSet

        assertTrue(
          columns.nonEmpty,
          SqlDataRepo.sortableColumns.values.forall(columns.contains)
        )
      },
      test("derives its keys from the character fields, leaving out the url sets") {
        assertTrue(
          SqlDataRepo.sortableColumns.keySet ==
            Set(
              "name",
              "height",
              "mass",
              "hairColor",
              "skinColor",
              "eyeColor",
              "birthYear",
              "gender",
              "homeworld",
              "url"
            ),
          SqlDataRepo.toColumn("hairColor") == "hair_color",
          SqlDataRepo.toColumn("name") == "name"
        )
      },
      test("drops sort keys that are not columns, including injection attempts") {
        val injection = Some(List(SortBy("name; drop table people --", FieldOrdering.ASC)))

        assertTrue(
          SqlDataRepo.orderByClause(injection) == "order by id asc",
          SqlDataRepo.orderByClause(Some(List(SortBy("nonsense", FieldOrdering.ASC)))) == "order by id asc",
          SqlDataRepo.orderByClause(None) == "order by id asc"
        )
      }
    ),
    suite("against postgres")(
      test("migrates, seeds and reads back the bundled data") {
        for
          repo   <- ZIO.service[SWDataRepo]
          people <- repo.getCharacters(None, None, None)
          films  <- repo.getFilms(None, None)
        yield assertTrue(people.count == 82, films.count == 6, people.results.length == 82)
      },
      test("pages without gaps or repeats") {
        for
          repo   <- ZIO.service[SWDataRepo]
          first  <- repo.getCharacters(Some(1), Some(10), None)
          second <- repo.getCharacters(Some(2), Some(10), None)
          rest   <- ZIO.foreach(3 to first.pageCount)(page => repo.getCharacters(Some(page), Some(10), None))
          all     = first.results ++ second.results ++ rest.flatMap(_.results)
        yield assertTrue(
          first.results.length == 10,
          first.results.intersect(second.results).isEmpty,
          all.length == 82,
          all.map(_.url).distinct.length == 82
        )
      },
      test("finds a person and a film by id") {
        for
          repo   <- ZIO.service[SWDataRepo]
          person <- repo.getCharacter(1)
          film   <- repo.getFilm(1)
        yield assertTrue(person.url.endsWith("/people/1/"), film.url.endsWith("/films/1/"))
      },
      test("reassembles the url sets belonging to a person") {
        for
          repo   <- ZIO.service[SWDataRepo]
          person <- repo.getCharacter(1)
        yield assertTrue(person.films.nonEmpty, person.films.forall(_.contains("/films/")))
      },
      test("fails with CharacterNotFound and FilmNotFound for unknown ids") {
        for
          repo     <- ZIO.service[SWDataRepo]
          noPerson <- repo.getCharacter(9999).exit
          noFilm   <- repo.getFilm(9999).exit
        yield assert(noPerson)(Assertion.failsWithA[DataRepoError.CharacterNotFound]) &&
          assert(noFilm)(Assertion.failsWithA[DataRepoError.FilmNotFound])
      },
      test("sorts by name in both directions") {
        for
          repo       <- ZIO.service[SWDataRepo]
          ascending  <- repo.getCharacters(None, None, ascendingName)
          descending <- repo.getCharacters(None, None, Some(List(SortBy("name", FieldOrdering.DESC))))
        yield assertTrue(
          ascending.results.map(_.name) == ascending.results.map(_.name).sorted,
          descending.results.map(_.name) == ascending.results.map(_.name).reverse
        )
      },
      test("ignores an unknown sort key rather than failing") {
        for
          repo   <- ZIO.service[SWDataRepo]
          people <- repo.getCharacters(Some(1), Some(5), Some(List(SortBy("not_a_column", FieldOrdering.ASC))))
        yield assertTrue(people.results.length == 5, people.count == 82)
      }
    ).provideShared(seededRepo)
      @@ TestAspect.sequential
      @@ TestAspect.withLiveClock
      @@ TestAspect.tag("postgres")
  ) @@ TestAspect.timeout(5.minutes)
