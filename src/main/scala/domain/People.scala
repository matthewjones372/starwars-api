package com.jones
package domain

import zio.json.*
import zio.schema.*
import zio.schema.annotation.fieldName

@jsonMemberNames(SnakeCase)
final case class People(
  name: String,
  height: Option[Int],
  mass: Option[Int],
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
) derives JsonCodec,
      Schema

object People:
  given JsonDecoder[Option[Int]] = JsonDecoder[String].map(_.toIntOption)
  given JsonEncoder[Option[Int]] = JsonEncoder[String].contramap(_.map(_.toString).getOrElse(""))

final case class Peoples(count: Int, results: List[People])
    extends Paged[People] derives JsonCodec
