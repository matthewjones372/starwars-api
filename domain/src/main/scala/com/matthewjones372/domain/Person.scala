package com.matthewjones372.domain

import com.matthewjones372.sorting.DynamicMultiSorter
import zio.schema.*
import zio.schema.Schema.primitive
import zio.schema.annotation.fieldName
import scala.math.Ordering.ordered
import scala.math.Ordered.orderingToOrdered

final case class Person(
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
) derives DynamicMultiSorter

object Person:
  // Numbers arrive from swapi as strings, and unmeasured ones as "unknown", which is absent rather than empty.
  given Schema[Option[Int]] =
    Schema.option[String].transform(_.flatMap(_.toIntOption), _.map(_.toString))

  given Schema[Person] = DeriveSchema.gen

final case class People(count: Int, results: List[Person]) extends Paged[Person] derives Schema
