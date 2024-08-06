package com.matthewjones372.domain

import zio.schema.*
import zio.schema.Schema.primitive
import zio.schema.annotation.fieldName

final case class People(
  name: String,
  height: Option[Int],
  mass: Option[Int],
  @fieldName("hair_color") hairColor: String,
  @fieldName("skin_color") skinColor: String,
  @fieldName("eye_color") eyeColor: String,
  @fieldName("birth_year") birthYear: String,
  gender: Option[String],
  homeworld: Option[String],
  films: Set[String],
  species: Option[Set[String]],
  vehicles: Option[Set[String]],
  starships: Option[Set[String]],
  url: String
)

object People:
  given Schema[Option[Int]] = primitive[String].transform(_.toIntOption, _.map(_.toString).getOrElse(""))
  given Schema[People]      = DeriveSchema.gen

final case class Peoples(count: Int, results: List[People]) extends Paged[People] derives Schema
