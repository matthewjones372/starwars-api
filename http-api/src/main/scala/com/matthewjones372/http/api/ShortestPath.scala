package com.matthewjones372.http.api

import zio.schema.*

final case class PathStep(person: String, film: String) derives Schema

final case class Chain(steps: List[PathStep]) derives Schema

final case class ShortestPath(
  start: String,
  end: String,
  films: Int,
  steps: List[PathStep],
  alternatives: List[Chain],
  chains: Int
) derives Schema
