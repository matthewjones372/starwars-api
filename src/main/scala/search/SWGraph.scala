package com.jones
package search

import zio.Chunk

import scala.annotation.tailrec
import scala.collection.immutable.{HashSet, Queue}

class SWGraph[A](private val peopleFilmMap: Map[A, Set[A]]) {
  private val filmPeopleMap: Map[A, Set[A]] = peopleFilmMap.foldLeft(Map.empty[A, Set[A]]) { case (acc, (k, vs)) =>
    vs.foldLeft(acc) { case (acc, v) =>
      acc.updated(v, acc.getOrElse(v, Set.empty) + k)
    }
  }

  def bfs(start: A, target: A): Option[Path[A]] = {
    @tailrec
    def loop(remaining: Queue[A], paths: Map[A, Chunk[(A, A)]], visited: HashSet[A]): Option[Chunk[(A, A)]] =
      if (remaining.isEmpty) None
      else {
        val (currentPoint, newRemaining) = remaining.dequeue
        val currentPath                  = paths.getOrElse(currentPoint, Chunk.empty)
        if (currentPoint == target) {
          Some((target, currentPath.head._1) +: currentPath)
        } else {
          val (updatedRemaining, updatedPaths, updatedVisited) = peopleFilmMap
            .get(currentPoint)
            .map { films =>
              films
                .flatMap(film => filmPeopleMap(film).map((_, film))) // Get the neighbors and the films connecting them
                .foldLeft((newRemaining, paths, visited)) { case ((remAcc, pathAcc, visitAcc), (neighbor, film)) =>
                  if (!visitAcc.contains(neighbor)) {
                    (
                      remAcc.enqueue(neighbor),
                      pathAcc.updated(neighbor, currentPath :+ (currentPoint -> film)),
                      visitAcc + neighbor
                    )
                  } else (remAcc, pathAcc, visitAcc)
                }
            }
            .getOrElse((newRemaining, paths, visited))

          loop(updatedRemaining, updatedPaths, updatedVisited)
        }
      }

    loop(Queue(start), Map(start -> Chunk.empty), HashSet(start)).map(path => Path(start, target, Some(path.reverse)))
  }
}