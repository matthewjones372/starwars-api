package com.jones
package model

import zio.json.*

@jsonMemberNames(SnakeCase)
final case class PeopleRaw(
  name: String,
  height: Int,
  mass: Int,
  hairColor: String,
  skinColor: String,
  eyeColor: String,
  birthYear: String,
  gender: String,
  homeworld: String,
  films: List[String],
  species: List[String],
  vehicles: List[String],
  starships: List[String]
) derives JsonCodec
