package com.jones

import model.PeopleRaw

import zio.test.*
import zio.test.magnolia.*

object Generators:
  val peopleGen = for
    name      <- Gen.string
    height    <- Gen.int
    mass      <- Gen.int
    hairColor <- Gen.string
    skinColor <- Gen.string
    eyeColor  <- Gen.string
    birthYear <- Gen.string
    gender    <- Gen.string
    homeworld <- Gen.string
    films     <- Gen.listOf(Gen.string)
    vehicles  <- Gen.listOf(Gen.string)
    starships <- Gen.listOf(Gen.string)
    species   <- Gen.listOf(Gen.string)
  yield PeopleRaw(
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
