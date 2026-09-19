package com.matthewjones372.search

import zio.*

import scala.annotation.tailrec
import scala.collection.immutable.{HashSet, Queue}

class SWGraph[A: Ordering](private val peopleFilmMap: Map[A, Set[A]]) {
  // We flip the people map so that we can easily find the neighbors of a film
  private val filmPeopleMap: Map[A, Set[A]] = peopleFilmMap.foldLeft(Map.empty[A, Set[A]]) { case (acc, (k, vs)) =>
    vs.foldLeft(acc) { case (acc, v) =>
      acc.updated(v, acc.getOrElse(v, Set.empty) + k)
    }
  }

  /**
   * The number of hops between two characters, when a chain of shared films
   * connects them.
   */
  def distance(start: A, target: A): Option[Int] = bfs(start, target).map(_.length)

  def bfs(start: A, target: A): Option[Path[A]] = {
    @tailrec
    def loop(remaining: Queue[A], paths: Map[A, Chunk[(A, A)]], visited: HashSet[A]): Option[Chunk[(A, A)]] =
      if (remaining.isEmpty) None
      else {
        val (currentPoint, newRemaining) = remaining.dequeue
        val currentPath                  = paths.getOrElse(currentPoint, Chunk.empty)

        if (currentPoint == target) currentPath.lastOption.map { case (_, movie) => currentPath :+ (target -> movie) }
        else {
          val (updatedRemaining, updatedPaths, updatedVisited) = peopleFilmMap
            .get(currentPoint)
            .map { films =>
              films
                .flatMap(film => filmPeopleMap(film).map((_, film))) // Get the neighbors and the films connecting them
                .foldLeft((newRemaining, paths, visited)) { case ((remAcc, pathAcc, visitAcc), (neighbor, film)) =>
                  if (!visitAcc.contains(neighbor)) // Filter out visited nodes
                    (
                      remAcc.enqueue(neighbor),
                      pathAcc.updated(neighbor, currentPath :+ (currentPoint -> film)),
                      visitAcc + neighbor
                    )
                  else (remAcc, pathAcc, visitAcc)
                }
            }
            .getOrElse((newRemaining, paths, visited))

          loop(updatedRemaining, updatedPaths, updatedVisited)
        }
      }

    if (start == target)
      Option.when(peopleFilmMap.contains(start))(Path(start, target, Some(Chunk.empty)))
    else
      loop(Queue(start), Map(start -> Chunk.empty), HashSet(start)).map(path => Path(start, target, Some(path)))
  }

  def coStars(person: A): Set[A] =
    peopleFilmMap.getOrElse(person, Set.empty).flatMap(film => filmPeopleMap.getOrElse(film, Set.empty)) - person

  def films(person: A): Set[A] = peopleFilmMap.getOrElse(person, Set.empty)

  /**
   * Every character measured against every other, which is one breadth-first
   * search per character over the co-star graph the shared films imply.
   *
   * A `lazy val` because the data behind a graph does not change once it is
   * built, so the whole sweep is work to do once and then serve from.
   */
  lazy val connectivity: Connectivity[A] = {
    val people   = peopleFilmMap.keySet.toList
    val hopsFrom = people.map(person => person -> separations(person)).toMap
    val allHops  = hopsFrom.values.flatMap(_.values).toList

    val connections = people.map { person =>
      val hops = hopsFrom(person)
      Connections(
        node = person,
        films = films(person).size,
        coStars = coStars(person).size,
        reach = hops.size,
        averageSeparation = if (hops.isEmpty) 0.0 else hops.values.sum.toDouble / hops.size
      )
    }
      .sortBy(connection => (-connection.coStars, connection.node))

    val ensembles = filmPeopleMap.toList
      .map((film, cast) => Ensemble(film, cast.size, cast.count(films(_).size == 1)))
      .sortBy(ensemble => (-ensemble.cast, ensemble.film))

    // Each co-star pair is counted from both ends, so the edges are half the degrees.
    val pairs         = connections.map(_.coStars).sum / 2
    val possiblePairs = people.size.toDouble * (people.size - 1) / 2

    // A cluster is counted from the first of its members the fold reaches, so
    // the rest of them are already seen by the time it gets to them.
    val (_, clusters) = people.foldLeft((Set.empty[A], 0)) { case ((seen, count), person) =>
      if (seen.contains(person)) (seen, count)
      else (seen + person ++ hopsFrom(person).keySet, count + 1)
    }

    Connectivity(
      nodes = people.size,
      films = filmPeopleMap.size,
      pairs = pairs,
      density = if (possiblePairs == 0) 0.0 else pairs / possiblePairs,
      averageSeparation = if (allHops.isEmpty) 0.0 else allHops.sum.toDouble / allHops.size,
      diameter = allHops.maxOption.getOrElse(0),
      clusters = clusters,
      connections = connections,
      ensembles = ensembles
    )
  }

  private def separations(start: A): Map[A, Int] = {
    @tailrec
    def loop(frontier: Queue[(A, Int)], seen: Map[A, Int]): Map[A, Int] =
      frontier.dequeueOption match {
        case None                           => seen
        case Some(((current, depth), rest)) =>
          val found = coStars(current).filterNot(seen.contains).map(_ -> (depth + 1))
          loop(rest ++ found, seen ++ found)
      }

    if (peopleFilmMap.contains(start)) loop(Queue(start -> 0), Map(start -> 0)) - start else Map.empty
  }
}
