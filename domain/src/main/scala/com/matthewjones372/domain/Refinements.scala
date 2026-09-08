package com.matthewjones372.domain

import zio.prelude.{Assertion, Subtype}
import zio.schema.Schema

/** Entity ids in the swapi url space start at 1. */
object EntityId extends Subtype[Int]:
  override inline def assertion = Assertion.greaterThan(0)

  def from(value: Int): Either[String, Type] =
    make(value).toEitherWith(_.mkString(", "))

  given Schema[Type] = Schema[Int].transformOrFail(from, id => Right(id))

type EntityId = EntityId.Type

/** Pages are numbered from 1, so page 0 is a request that cannot be served. */
object PageNumber extends Subtype[Int]:
  override inline def assertion = Assertion.greaterThan(0)

  val first: Type = PageNumber(1)

  def from(value: Int): Either[String, Type] =
    make(value).toEitherWith(_.mkString(", "))

type PageNumber = PageNumber.Type

/** An upper bound keeps a single request from pulling the whole table. */
object PageSize extends Subtype[Int]:
  override inline def assertion = Assertion.between(1, 100)

  val default: Type = PageSize(10)

  def from(value: Int): Either[String, Type] =
    make(value).toEitherWith(_.mkString(", "))

type PageSize = PageSize.Type
