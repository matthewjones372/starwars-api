package com.matthewjones372.search

import zio.test.*
import zio.*

object SWGraphSpec extends ZIOSpecDefault:
  def spec = suite("SWGraphSpec")(
    suite("bfs")(
      test("can find the shortest path between two characters") {
        val peopleFilmMap = Map(
          "Lobot"     -> Set("The Empire Strikes Back"),
          "Luke"      -> Set("Return of the Jedi", "A New Hope", "The Empire Strikes Back"),
          "Boba Fett" -> Set("A New Hope")
        )
        val graph = SWGraph(peopleFilmMap)
        val expectedPath =
          Path(
            start = "Lobot",
            end = "Boba Fett",
            path = Some(
              Chunk(("Lobot", "The Empire Strikes Back"), ("Luke", "A New Hope"), ("Boba Fett", "A New Hope"))
            )
          )

        assertTrue(
          graph
            .bfs("Lobot", "Boba Fett")
            .contains(
              expectedPath
            )
        )
      },
      test("returns None when no path exists") {
        val peopleFilmMap = Map(
          "Lobot"     -> Set("The Empire Strikes Back"),
          "Luke"      -> Set("Return of the Jedi", "A New Hope"),
          "Boba Fett" -> Set("A New Hope")
        )
        val graph = SWGraph(peopleFilmMap)

        assertTrue(
          graph
            .bfs("Lobot", "Luke")
            .isEmpty
        )
      },
      test("returns None when the start and end are the same") {
        val peopleFilmMap = Map(
          "Lobot"     -> Set("The Empire Strikes Back"),
          "Luke"      -> Set("Return of the Jedi", "A New Hope"),
          "Boba Fett" -> Set("A New Hope")
        )
        val graph = SWGraph(peopleFilmMap)

        assertTrue(
          graph
            .bfs("Lobot", "Lobot")
            .isEmpty
        )
      },
      test("Returns a None when either character doesn't exist") {
        val peopleFilmMap = Map(
          "Lobot"     -> Set("The Empire Strikes Back"),
          "Luke"      -> Set("Return of the Jedi", "A New Hope"),
          "Boba Fett" -> Set("A New Hope")
        )
        val graph = SWGraph(peopleFilmMap)

        assertTrue(
          graph
            .bfs("Lobot", "Darth Vader")
            .isEmpty
        )
      }
    )
  )
