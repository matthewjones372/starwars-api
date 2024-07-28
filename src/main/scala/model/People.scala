package com.jones
package model

import zio.json.*

@jsonMemberNames(SnakeCase)
final case class People(
  name: String,
  height: Int,
  mass: Int,
  hairColor: String,
  skinColor: String,
  eyeColor: String,
  birthYear: String,
  gender: String,
  homeworld: String,
  films: Set[String],
  species: Set[String],
  vehicles: Set[String],
  starships: Set[String]
) derives JsonCodec
