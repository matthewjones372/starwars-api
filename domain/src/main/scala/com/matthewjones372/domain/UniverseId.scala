package com.matthewjones372.domain

import zio.schema.Schema

/**
 * The datasets this API serves, and the path segment each one answers on.
 */
enum UniverseId(val slug: String, val label: String):
  case StarWars    extends UniverseId("starwars", "Star Wars")
  case Marvel      extends UniverseId("mcu", "Marvel Cinematic Universe")
  case MiddleEarth extends UniverseId("lotr", "The Lord of the Rings")
  case Wizarding   extends UniverseId("hp", "Harry Potter")

object UniverseId:
  val default: UniverseId = StarWars

  // Segments the api answers on that are not a universe. A slug colliding with
  // one of these would be shadowed by it, so `UniverseIdSpec` holds them apart.
  val reservedSegments: Set[String] = Set("actors", "docs", "web", "graph", "people", "films", "universes")

  def from(slug: String): Either[String, UniverseId] =
    values.find(_.slug == slug).toRight(s"Unknown universe '$slug'")

  def slugs: List[String] = values.map(_.slug).toList

  given Ordering[UniverseId] = Ordering.by(_.ordinal)

  given Schema[UniverseId] = Schema[String].transformOrFail(from, universe => Right(universe.slug))
