package com.matthewjones372.domain

import zio.test.Gen

object Generators:
  val peopleGen = for
    name      <- Gen.string
    height    <- Gen.option(Gen.int)
    mass      <- Gen.option(Gen.int)
    hairColor <- Gen.string
    skinColor <- Gen.string
    eyeColor  <- Gen.string
    birthYear <- Gen.string
    gender    <- Gen.option(Gen.string)
    homeworld <- Gen.option(Gen.string)
    films     <- Gen.setOf(Gen.string)
    vehicles  <- Gen.option(Gen.setOf(Gen.string))
    starships <- Gen.option(Gen.setOf(Gen.string))
    species   <- Gen.option(Gen.setOf(Gen.string))
  yield People(
    name,
    height,
    mass,
    hairColor,
    skinColor,
    eyeColor,
    birthYear,
    gender,
    homeworld,
    films,
    species,
    vehicles,
    starships
  )
