package com.matthewjones372.http.api

import zio.schema.*

final case class CharacterConnections(
  name: String,
  films: Int,
  coStars: Int,
  reach: Int,
  averageSeparation: Double
) derives Schema

final case class FilmEnsemble(title: String, cast: Int, exclusiveCast: Int) derives Schema

final case class GraphInsights(
  characters: Int,
  films: Int,
  pairs: Int,
  density: Double,
  averageSeparation: Double,
  diameter: Int,
  clusters: Int,
  connections: List[CharacterConnections],
  ensembles: List[FilmEnsemble]
) derives Schema
