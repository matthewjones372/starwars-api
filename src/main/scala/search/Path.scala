package com.jones
package search

import zio.Chunk

final case class Path[A](start: A, end: A, path: Option[Chunk[(A, A)]]):
  def length: Int = path.map(_.length - 1).getOrElse(0)

  private def render = path.map {
    _.toList.zipWithIndex.map { case ((name, movie), index) =>
      val indentation = " " * index
      val color       = Path.colors(index % Path.colors.length)
      s"${indentation}${color}${name} -> ${movie}${Path.resetColor}"
    }
  }.getOrElse(List.empty)

  override def toString: String = render.mkString("\n")

object Path:
  // ANSI color codes
  private val colors = List(
    "\u001b[31m", // Red
    "\u001b[32m", // Green
    "\u001b[33m", // Yellow
    "\u001b[34m", // Blue
    "\u001b[35m", // Magenta
    "\u001b[36m", // Cyan
    "\u001b[37m"  // White
  )
  private val resetColor = "\u001b[0m"
