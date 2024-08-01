package com.jones
package domain

import zio.json.*
import zio.schema.*
import zio.schema.annotation.fieldName

@jsonMemberNames(SnakeCase)
final case class People(
  name: String,
  height: String,
  mass: String,
  @fieldName("hair_color") hairColor: String,
  @fieldName("skin_color") skinColor: String,
  eyeColor: String,
  @fieldName("birth_year") birthYear: String,
  gender: String,
  homeworld: String,
  films: Set[String],
  species: Set[String],
  vehicles: Set[String],
  starships: Set[String]
) derives JsonCodec

object People:
  given Schema[People] = DeriveSchema.gen

@jsonMemberNames(SnakeCase)
final case class Peoples(count: Int, next: Option[String], previous: Option[String], results: List[People])
    extends Paged[People] derives JsonCodec
