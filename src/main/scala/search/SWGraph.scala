package com.jones
package search

import zio.*

import scala.annotation.tailrec

class SWGraph(private val peopleFilmMap: Map[String, Set[String]]) {
  private val filmPeopleMap = peopleFilmMap.foldLeft(Map.empty[String, Set[String]]) { case (acc, (k, vs)) =>
    vs.foldLeft(acc) { case (acc, v) =>
      acc.updated(v, acc.getOrElse(v, Set.empty) + k)
    }
  }

  def bfs(start: String, target: String): Option[Chunk[(String, String)]] = {
    @tailrec
    def loop(remaining: Chunk[String], paths: Map[String, Chunk[(String, String)]]): Option[Chunk[(String, String)]] =
      if (remaining.isEmpty) None
      else {
        val currentPoint = remaining.head
        val currentPath  = paths.getOrElse(currentPoint, Chunk.empty)
        if (currentPoint == target) {
          currentPath.lastOption.map(_._2).map(l => (target, l) +: currentPath.reverse)
        } else {
          val (newRemaining, newPaths) = peopleFilmMap
            .get(currentPoint)
            .map { films =>
              films
                .flatMap(film => filmPeopleMap(film).map((_, film)))          // Get the neighbors and the films connecting them
                .filterNot { case (neighbor, _) => paths.contains(neighbor) } // Filter out visited nodes
                .foldLeft((remaining.tail, paths)) { case ((remAcc, pathAcc), (neighbor, film)) =>
                  (
                    remAcc :+ neighbor,
                    pathAcc.updated(neighbor, currentPath :+ (currentPoint -> film))
                  )
                }
            }
            .getOrElse((remaining.tail, paths))

          loop(newRemaining, newPaths)
        }
      }

    loop(Chunk(start), Map(start -> Chunk.empty))
  }
}
