package com.matthewjones372.search

import zio.*

import scala.annotation.tailrec
import scala.collection.immutable.{HashSet, Queue}

class Graph[A: Ordering](private val nodeEdges: Map[A, Set[A]]) {
  // We flip the people map so that we can easily find the neighbors of a film
  private val edgeNodes: Map[A, Set[A]] = nodeEdges.foldLeft(Map.empty[A, Set[A]]) { case (acc, (k, vs)) =>
    vs.foldLeft(acc) { case (acc, v) =>
      acc.updated(v, acc.getOrElse(v, Set.empty) + k)
    }
  }

  // Held rather than derived per call: a sweep asks for a node's neighbours once
  // per visit, and rebuilding that set from the node's films each time made the
  // sweep quadratic in allocations rather than in hops.
  private val adjacency: Map[A, Set[A]] = nodeEdges.map { case (node, edges) =>
    node -> (edges.flatMap(edge => edgeNodes.getOrElse(edge, Set.empty)) - node)
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
          val (updatedRemaining, updatedPaths, updatedVisited) = nodeEdges
            .get(currentPoint)
            .map { films =>
              films
                .flatMap(film => edgeNodes(film).map((_, film))) // Get the neighbors and the films connecting them
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
      Option.when(nodeEdges.contains(start))(Path(start, target, Some(Chunk.empty)))
    else
      loop(Queue(start), Map(start -> Chunk.empty), HashSet(start)).map(path => Path(start, target, Some(path)))
  }

  def neighbours(person: A): Set[A] = adjacency.getOrElse(person, Set.empty)

  def edgesOf(person: A): Set[A] = nodeEdges.getOrElse(person, Set.empty)

  private final case class Sweep(
    connections: List[Connections[A]],
    hopSum: Long,
    hopCount: Long,
    diameter: Int,
    seen: Set[A],
    clusters: Int
  )

  /**
   * Every character measured against every other, which is one breadth-first
   * search per character over the co-star graph the shared films imply.
   *
   * A `lazy val` because the data behind a graph does not change once it is
   * built, so the whole sweep is work to do once and then serve from.
   */
  lazy val connectivity: Connectivity[A] = {
    val people = nodeEdges.keySet.toList

    // Folded as the sweep goes rather than collected and summed afterwards.
    // Holding every node's distances at once costs the square of the node count,
    // which is affordable for one film's cast and is not for an actor graph
    // spanning every universe.
    val swept = people.foldLeft(Sweep(Nil, 0L, 0L, 0, Set.empty, 0)) { (acc, person) =>
      val hops    = separations(person)
      val hopSum  = hops.values.sum
      val visited = acc.seen.contains(person)

      Sweep(
        connections = Connections(
          node = person,
          films = edgesOf(person).size,
          coStars = neighbours(person).size,
          reach = hops.size,
          averageSeparation = if (hops.isEmpty) 0.0 else hopSum.toDouble / hops.size
        ) :: acc.connections,
        hopSum = acc.hopSum + hopSum,
        hopCount = acc.hopCount + hops.size,
        diameter = math.max(acc.diameter, hops.values.maxOption.getOrElse(0)),
        // A cluster is counted from the first of its members the fold reaches, so
        // the rest of them are already seen by the time it gets to them.
        seen = if (visited) acc.seen else acc.seen + person ++ hops.keySet,
        clusters = if (visited) acc.clusters else acc.clusters + 1
      )
    }

    val connections = swept.connections.sortBy(connection => (-connection.coStars, connection.node))

    val ensembles = edgeNodes.toList
      .map((film, cast) => Ensemble(film, cast.size, cast.count(edgesOf(_).size == 1)))
      .sortBy(ensemble => (-ensemble.cast, ensemble.film))

    // Each co-star pair is counted from both ends, so the edges are half the degrees.
    val pairs         = connections.map(_.coStars).sum / 2
    val possiblePairs = people.size.toDouble * (people.size - 1) / 2

    Connectivity(
      nodes = people.size,
      films = edgeNodes.size,
      pairs = pairs,
      density = if (possiblePairs == 0) 0.0 else pairs / possiblePairs,
      averageSeparation = if (swept.hopCount == 0) 0.0 else swept.hopSum.toDouble / swept.hopCount,
      diameter = swept.diameter,
      clusters = swept.clusters,
      connections = connections,
      ensembles = ensembles
    )
  }

  /**
   * Every chain of the shortest length between two characters, not just one of
   * them: at one hop those are the films the pair share, and beyond it they are
   * the different people the chain can go through.
   *
   * [limit] bounds the answer and the work. The routes between two characters
   * multiply out at every hop, so each step keeps at most [limit] of the ways
   * of reaching it, which is enough to hand back [limit] whole ones.
   */
  def shortestPaths(start: A, target: A, limit: Int): List[Path[A]] =
    if (limit <= 0) Nil
    else if (start == target)
      if (nodeEdges.contains(start)) List(Path(start, target, Some(Chunk.empty))) else Nil
    else {
      val hops    = separations(start)
      val depthOf = hops + (start -> 0)

      def sharedFilms(one: A, other: A): List[A] = edgesOf(one).intersect(edgesOf(other)).toList.sorted

      def stepsInto(node: A): List[(A, A)] =
        val closer = depthOf(node) - 1
        neighbours(node).toList.sorted.filter(depthOf.get(_).contains(closer)).flatMap { pred =>
          sharedFilms(pred, node).map(pred -> _)
        }

      def routesTo(node: A): List[Chunk[(A, A)]] =
        if (node == start) List(Chunk.empty)
        else
          stepsInto(node)
            .flatMap((pred, film) => routesTo(pred).map(_ :+ (pred -> film)))
            .take(limit)

      if (!hops.contains(target)) Nil
      else
        routesTo(target).take(limit).map { route =>
          // The walk closes on the target under the film that carried the last
          // hop, which is the shape `bfs` returns and `Path` renders.
          Path(start, target, Some(route :+ (target -> route.last._2)))
        }
    }

  /**
   * How many such chains there are, which is more than [shortestPaths] returns
   * once a pair has more routes than the caller asked to see.
   */
  def countShortestPaths(start: A, target: A): Int =
    if (start == target) (if (nodeEdges.contains(start)) 1 else 0)
    else {
      val hops    = separations(start)
      val depthOf = hops + (start -> 0)

      def count(node: A): Int =
        if (node == start) 1
        else
          val closer = depthOf(node) - 1
          neighbours(node).toList.filter(depthOf.get(_).contains(closer)).foldLeft(0) { (total, pred) =>
            total + count(pred) * edgesOf(pred).intersect(edgesOf(node)).size
          }

      if (!hops.contains(target)) 0 else count(target)
    }

  private def separations(start: A): Map[A, Int] = {
    @tailrec
    def loop(frontier: Queue[(A, Int)], seen: Map[A, Int]): Map[A, Int] =
      frontier.dequeueOption match {
        case None                           => seen
        case Some(((current, depth), rest)) =>
          val found = neighbours(current).filterNot(seen.contains).map(_ -> (depth + 1))
          loop(rest ++ found, seen ++ found)
      }

    if (nodeEdges.contains(start)) loop(Queue(start -> 0), Map(start -> 0)) - start else Map.empty
  }
}
