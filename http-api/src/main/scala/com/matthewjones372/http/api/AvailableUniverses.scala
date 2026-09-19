package com.matthewjones372.http.api

import zio.schema.*

final case class UniverseSummary(slug: String, label: String) derives Schema

final case class AvailableUniverses(count: Int, results: List[UniverseSummary]) derives Schema
