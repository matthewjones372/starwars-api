package com.matthewjones372.search

import zio.test.*
import zio.*

import scala.collection.immutable.Queue

object SWGraphSpec extends ZIOSpecDefault:

  private val peopleFilmMap = Map(
    "Lobot"     -> Set("The Empire Strikes Back"),
    "Luke"      -> Set("Return of the Jedi", "A New Hope", "The Empire Strikes Back"),
    "Boba Fett" -> Set("A New Hope")
  )

  private val disconnected = Map(
    "Lobot"     -> Set("The Empire Strikes Back"),
    "Luke"      -> Set("Return of the Jedi", "A New Hope"),
    "Boba Fett" -> Set("A New Hope")
  )

  private val graphGen: Gen[Any, Map[String, Set[String]]] =
    for
      peopleCount <- Gen.int(2, 7)
      filmCount   <- Gen.int(1, 4)
      films        = (0 until filmCount).map(i => s"film$i").toList
      chosen      <- Gen.listOfN(peopleCount)(Gen.setOfBounded(1, filmCount)(Gen.elements(films*)))
    yield chosen.zipWithIndex.map { case (fs, i) => s"person$i" -> fs }.toMap

  private def shortestHops[A](peopleFilms: Map[A, Set[A]], start: A, target: A): Option[Int] =
    val filmPeople = peopleFilms.toList
      .flatMap((person, films) => films.map(_ -> person))
      .groupMap(_._1)(_._2)
      .view
      .mapValues(_.toSet)
      .toMap

    def loop(frontier: Queue[(A, Int)], seen: Set[A]): Option[Int] =
      frontier.dequeueOption match
        case None                           => None
        case Some(((current, depth), rest)) =>
          if current == target then Some(depth)
          else
            val neighbours = peopleFilms
              .getOrElse(current, Set.empty)
              .flatMap(film => filmPeople.getOrElse(film, Set.empty))
              .filterNot(seen)
            loop(rest ++ neighbours.map(_ -> (depth + 1)), seen ++ neighbours)

    if start == target then Option.when(peopleFilms.contains(start))(0)
    else loop(Queue(start -> 0), Set(start))

  private def edgesAreReal(peopleFilms: Map[String, Set[String]], path: Chunk[(String, String)]): Boolean =
    path.sliding(2).forall {
      case Chunk((person, film), (next, _)) =>
        peopleFilms.get(person).exists(_.contains(film)) && peopleFilms.get(next).exists(_.contains(film))
      case _ => true
    }

  def spec = suite("SWGraphSpec")(
    suite("bfs")(
      test("can find the shortest path between two characters") {
        val expectedPath =
          Path(
            start = "Lobot",
            end = "Boba Fett",
            path = Some(
              Chunk(("Lobot", "The Empire Strikes Back"), ("Luke", "A New Hope"), ("Boba Fett", "A New Hope"))
            )
          )

        assertTrue(SWGraph(peopleFilmMap).bfs("Lobot", "Boba Fett").contains(expectedPath))
      },
      test("returns None when no path exists") {
        assertTrue(SWGraph(disconnected).bfs("Lobot", "Luke").isEmpty)
      },
      test("returns None between genuinely disconnected components") {
        val twoComponents = Map(
          "Lobot"     -> Set("The Empire Strikes Back"),
          "Lando"     -> Set("The Empire Strikes Back"),
          "Luke"      -> Set("A New Hope"),
          "Boba Fett" -> Set("A New Hope")
        )
        val graph = SWGraph(twoComponents)

        assertTrue(
          graph.bfs("Lobot", "Luke").isEmpty,
          graph.bfs("Lobot", "Lando").exists(_.length == 1)
        )
      },
      test("returns a zero length path when the start and end are the same") {
        val result = SWGraph(peopleFilmMap).bfs("Lobot", "Lobot")

        assertTrue(result.exists(_.length == 0), result.exists(_.path.contains(Chunk.empty)))
      },
      test("distance reports the hops the path would take") {
        val graph = SWGraph(peopleFilmMap)

        assertTrue(
          graph.distance("Lobot", "Boba Fett") == graph.bfs("Lobot", "Boba Fett").map(_.length),
          graph.distance("Lobot", "Boba Fett").contains(2),
          graph.distance("Lobot", "Lobot").contains(0),
          SWGraph(disconnected).distance("Lobot", "Luke").isEmpty
        )
      },
      test("Returns a None when either character doesn't exist") {
        val graph = SWGraph(disconnected)

        assertTrue(
          graph.bfs("Lobot", "Darth Vader").isEmpty,
          graph.bfs("Darth Vader", "Darth Vader").isEmpty
        )
      },
      test("never reports a length longer than the true shortest path") {
        check(graphGen, Gen.int(0, 6), Gen.int(0, 6)) { (peopleFilms, from, to) =>
          val people = peopleFilms.keys.toList.sorted
          val start  = people(from % people.length)
          val target = people(to % people.length)

          assertTrue(SWGraph(peopleFilms).bfs(start, target).map(_.length) == shortestHops(peopleFilms, start, target))
        }
      },
      test("only returns paths whose consecutive people really share the film joining them") {
        check(graphGen, Gen.int(0, 6), Gen.int(0, 6)) { (peopleFilms, from, to) =>
          val people = peopleFilms.keys.toList.sorted
          val start  = people(from % people.length)
          val target = people(to % people.length)

          val edgesHold = SWGraph(peopleFilms)
            .bfs(start, target)
            .flatMap(_.path)
            .forall(edgesAreReal(peopleFilms, _))

          assertTrue(edgesHold)
        }
      },
      test("agrees with a reference search on whether a path exists at all") {
        check(graphGen, Gen.int(0, 6), Gen.int(0, 6)) { (peopleFilms, from, to) =>
          val people = peopleFilms.keys.toList.sorted
          val start  = people(from % people.length)
          val target = people(to % people.length)

          assertTrue(
            SWGraph(peopleFilms).bfs(start, target).isDefined == shortestHops(peopleFilms, start, target).isDefined
          )
        }
      }
    ),
    suite("shortestPaths")(
      test("returns one chain per film a pair share, when they share a film at all") {
        val twoFilms = Map(
          "Luke"  -> Set("A New Hope", "Return of the Jedi"),
          "Vader" -> Set("A New Hope", "Return of the Jedi")
        )
        val routes = SWGraph(twoFilms).shortestPaths("Luke", "Vader", 10)

        assertTrue(
          routes.length == 2,
          routes.forall(_.length == 1),
          routes.flatMap(_.path.toList.flatten).map(_._2).distinct.sorted ==
            List("A New Hope", "Return of the Jedi")
        )
      },
      test("returns every equally short chain through a different character") {
        // Lobot reaches Boba Fett through Luke or through Leia, both in two hops.
        val twoMiddles = Map(
          "Lobot"     -> Set("The Empire Strikes Back"),
          "Luke"      -> Set("The Empire Strikes Back", "A New Hope"),
          "Leia"      -> Set("The Empire Strikes Back", "A New Hope"),
          "Boba Fett" -> Set("A New Hope")
        )
        val routes = SWGraph(twoMiddles).shortestPaths("Lobot", "Boba Fett", 10)

        assertTrue(
          routes.length == 2,
          routes.forall(_.length == 2),
          routes.flatMap(_.path.toList.flatten).map(_._1).distinct.sorted ==
            List("Boba Fett", "Leia", "Lobot", "Luke")
        )
      },
      test("never returns a chain longer than the shortest") {
        val withDetour = Map(
          "Lobot"     -> Set("The Empire Strikes Back"),
          "Luke"      -> Set("The Empire Strikes Back", "A New Hope"),
          "Han"       -> Set("The Empire Strikes Back", "Return of the Jedi"),
          "Leia"      -> Set("Return of the Jedi", "A New Hope"),
          "Boba Fett" -> Set("A New Hope")
        )
        val graph  = SWGraph(withDetour)
        val routes = graph.shortestPaths("Lobot", "Boba Fett", 10)

        assertTrue(
          routes.nonEmpty,
          routes.forall(route => graph.distance("Lobot", "Boba Fett").contains(route.length))
        )
      },
      test("agrees with the single path search on the chains that exist at all") {
        check(graphGen, Gen.int(0, 6), Gen.int(0, 6)) { (peopleFilms, from, to) =>
          val graph  = SWGraph(peopleFilms)
          val people = peopleFilms.keys.toList.sorted
          val start  = people(from % people.length)
          val target = people(to % people.length)
          val routes = graph.shortestPaths(start, target, 10)

          assertTrue(
            routes.nonEmpty == graph.bfs(start, target).isDefined,
            routes.forall(route => graph.bfs(start, target).map(_.length).contains(route.length))
          )
        }
      },
      test("only returns chains whose consecutive people really share the film joining them") {
        check(graphGen, Gen.int(0, 6), Gen.int(0, 6)) { (peopleFilms, from, to) =>
          val people = peopleFilms.keys.toList.sorted
          val start  = people(from % people.length)
          val target = people(to % people.length)

          val hold = SWGraph(peopleFilms)
            .shortestPaths(start, target, 10)
            .flatMap(_.path)
            .forall(edgesAreReal(peopleFilms, _))

          assertTrue(hold)
        }
      },
      test("returns no more than it was asked for, and none at all for a limit of none") {
        check(graphGen, Gen.int(0, 6), Gen.int(0, 6)) { (peopleFilms, from, to) =>
          val graph  = SWGraph(peopleFilms)
          val people = peopleFilms.keys.toList.sorted
          val start  = people(from % people.length)
          val target = people(to % people.length)

          assertTrue(
            graph.shortestPaths(start, target, 2).length <= 2,
            graph.shortestPaths(start, target, 0).isEmpty
          )
        }
      },
      test("never returns the same chain twice") {
        check(graphGen, Gen.int(0, 6), Gen.int(0, 6)) { (peopleFilms, from, to) =>
          val people = peopleFilms.keys.toList.sorted
          val start  = people(from % people.length)
          val target = people(to % people.length)
          val routes = SWGraph(peopleFilms).shortestPaths(start, target, 20).map(_.path)

          assertTrue(routes.distinct.length == routes.length)
        }
      },
      test("counts every chain, including the ones past the limit it was shown") {
        val twoMiddles = Map(
          "Lobot"     -> Set("The Empire Strikes Back"),
          "Luke"      -> Set("The Empire Strikes Back", "A New Hope"),
          "Leia"      -> Set("The Empire Strikes Back", "A New Hope"),
          "Boba Fett" -> Set("A New Hope")
        )
        val graph = SWGraph(twoMiddles)

        assertTrue(
          graph.countShortestPaths("Lobot", "Boba Fett") == 2,
          graph.shortestPaths("Lobot", "Boba Fett", 1).length == 1,
          graph.countShortestPaths("Lobot", "Lobot") == 1,
          SWGraph(disconnected).countShortestPaths("Lobot", "Luke") == 0
        )
      },
      test("counts what it returns whenever the limit is not what held it back") {
        check(graphGen, Gen.int(0, 6), Gen.int(0, 6)) { (peopleFilms, from, to) =>
          val graph   = SWGraph(peopleFilms)
          val people  = peopleFilms.keys.toList.sorted
          val start   = people(from % people.length)
          val target  = people(to % people.length)
          val routes  = graph.shortestPaths(start, target, 50)
          val counted = graph.countShortestPaths(start, target)

          assertTrue(routes.length >= 50 || routes.length == counted)
        }
      }
    ),
    suite("connectivity")(
      test("ranks characters by how many co-stars they have") {
        val ranked = SWGraph(peopleFilmMap).connectivity.connections

        assertTrue(
          ranked.map(_.node) == List("Luke", "Boba Fett", "Lobot"),
          ranked.head.coStars == 2,
          ranked.last.coStars == 1
        )
      },
      test("counts the films a character is in, who they share them with, and who they can reach") {
        val byName = SWGraph(peopleFilmMap).connectivity.connections.map(c => c.node -> c).toMap

        assertTrue(
          byName("Luke").films == 3,
          byName("Luke").coStars == 2,
          byName("Luke").reach == 2,
          byName("Lobot").films == 1,
          byName("Lobot").coStars == 1,
          byName("Lobot").reach == 2
        )
      },
      test("averages the hops from a character to everyone it can reach") {
        val byName = SWGraph(peopleFilmMap).connectivity.connections.map(c => c.node -> c).toMap

        assertTrue(
          byName("Luke").averageSeparation == 1.0,
          byName("Lobot").averageSeparation == 1.5,
          byName("Boba Fett").averageSeparation == 1.5
        )
      },
      test("reports a character nobody shares a film with as reaching no one") {
        val byName = SWGraph(disconnected).connectivity.connections.map(c => c.node -> c).toMap

        assertTrue(
          byName("Lobot").coStars == 0,
          byName("Lobot").reach == 0,
          byName("Lobot").averageSeparation == 0.0
        )
      },
      test("counts each co-star pair once rather than from both ends") {
        val connectivity = SWGraph(peopleFilmMap).connectivity

        assertTrue(
          connectivity.pairs == 2,
          connectivity.connections.map(_.coStars).sum == connectivity.pairs * 2,
          connectivity.density == 2.0 / 3.0
        )
      },
      test("reports the widest separation in the graph as its diameter") {
        assertTrue(
          SWGraph(peopleFilmMap).connectivity.diameter == 2,
          SWGraph(peopleFilmMap).connectivity.averageSeparation == 8.0 / 6.0
        )
      },
      test("counts one cluster when shared films join everyone, and one per island when they do not") {
        assertTrue(
          SWGraph(peopleFilmMap).connectivity.clusters == 1,
          SWGraph(disconnected).connectivity.clusters == 2
        )
      },
      test("sizes each film's cast and counts the characters it alone carries") {
        val ensembles = SWGraph(peopleFilmMap).connectivity.ensembles

        assertTrue(
          ensembles == List(
            Ensemble("A New Hope", 2, 1),
            Ensemble("The Empire Strikes Back", 2, 1),
            Ensemble("Return of the Jedi", 1, 0)
          )
        )
      },
      test("never disagrees with the path search about who is reachable and how far away") {
        check(graphGen) { peopleFilms =>
          val graph  = SWGraph(peopleFilms)
          val people = peopleFilms.keys.toList

          val agrees = graph.connectivity.connections.forall { connection =>
            val hops = people
              .filterNot(_ == connection.node)
              .flatMap(other => graph.distance(connection.node, other))

            connection.reach == hops.size &&
            connection.averageSeparation == (if hops.isEmpty then 0.0 else hops.sum.toDouble / hops.size)
          }

          assertTrue(agrees)
        }
      }
    )
  )
