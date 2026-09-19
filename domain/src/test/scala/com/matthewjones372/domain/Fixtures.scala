package com.matthewjones372.domain

/**
 * Entity builders shared by every module's tests.
 *
 * `data`, `http-api` and `search` all depend on `domain % "test->test"`, so a
 * change to the entity shape lands here once rather than in each of them.
 */
object Fixtures:
  def characterWithId(
    id: Int,
    name: String = "",
    attributes: Map[String, String] = Map.empty,
    films: Set[String] = Set.empty,
    universe: String = "starwars",
    baseUrl: String = "http://localhost:8080"
  ): Character =
    Character(
      name = if name.isEmpty then s"person-$id" else name,
      films = films,
      portrayedBy = Set.empty,
      attributes = attributes,
      links = Map.empty,
      url = s"$baseUrl/$universe/people/$id/"
    )

  def filmWithId(
    id: Int,
    title: String = "",
    characters: Set[String] = Set.empty,
    universe: String = "starwars",
    baseUrl: String = "http://localhost:8080"
  ): Film =
    Film(
      title = if title.isEmpty then s"film-$id" else title,
      episodeId = id,
      director = "",
      producer = "",
      releaseDate = "",
      characters = characters,
      cast = Set.empty,
      attributes = Map.empty,
      links = Map.empty,
      url = s"$baseUrl/$universe/films/$id/",
      mediaType = None
    )
