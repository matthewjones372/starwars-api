package com.matthewjones372.search

final case class Connections[A](
  node: A,
  films: Int,
  coStars: Int,
  reach: Int,
  averageSeparation: Double
)

final case class Ensemble[A](film: A, cast: Int, exclusiveCast: Int)

final case class Connectivity[A](
  nodes: Int,
  films: Int,
  pairs: Int,
  density: Double,
  averageSeparation: Double,
  diameter: Int,
  clusters: Int,
  connections: List[Connections[A]],
  ensembles: List[Ensemble[A]]
)
