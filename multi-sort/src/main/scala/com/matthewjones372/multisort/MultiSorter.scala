package com.matthewjones372.multisort

import scala.deriving.*
import scala.compiletime.*

enum FieldOrdering:
  case ASC
  case DESC

final case class SortBy(key: String, ordering: FieldOrdering)

trait Sorter[A]:
  def sort(input: List[A], sortBys: List[SortBy]): List[A]

object Sorter:
  def sort[A](input: List[A], sortBys: List[SortBy])(using sorter: Sorter[A]): List[A] =
    sorter.sort(input, sortBys)

  inline def derived[A <: Product](using A: Mirror.ProductOf[A]): Sorter[A] =
    val orders         = summonAll[Tuple.Map[A.MirroredElemTypes, Ordering]]
    val fieldNames     = constValueTuple[A.MirroredElemTypes].toList.asInstanceOf[List[String]]
    val vectorOfOrders = orders.toList.asInstanceOf[List[Ordering[Any]]].zipWithIndex
    val cachedOrders   = fieldNames.zip(vectorOfOrders).toMap

    new Sorter[A] {
      override def sort(input: List[A], sortBys: List[SortBy]): List[A] = {
        val chained = Function.chain[List[A]] {
          sortBys.map { sort =>
            cachedOrders
              .get(sort.key)
              .map { case (ord, idx) =>
                val rightOrderOrders = sort.ordering match {
                  case FieldOrdering.ASC  => ord
                  case FieldOrdering.DESC => ord.reverse
                }

                (a: List[A]) => a.sortBy(value => value.productElement(idx))(rightOrderOrders)
              }
              .getOrElse(identity[List[A]])
          }
        }
        chained(input)
      }
    }

object Test extends App:
  case class Starship(name: String, age: Int) derives Sorter

  val res = Sorter.sort(List(Starship("A", 22), Starship("B", 33)), List(SortBy("name", FieldOrdering.ASC)))
  println(res)
