package com.matthewjones372.sorting

import scala.deriving.*
import scala.compiletime.*

enum FieldOrdering:
  case ASC
  case DESC

final case class SortBy(key: String, ordering: FieldOrdering)

trait DynamicMultiSorter[A]:
  def sort(input: List[A], sortBys: List[SortBy]): List[A]

object DynamicMultiSorter:
  def sort[A](input: List[A], by: List[SortBy])(using sorter: DynamicMultiSorter[A]): List[A] =
    sorter.sort(input, by)

  inline def derived[A <: Product](using A: Mirror.ProductOf[A]): DynamicMultiSorter[A] =
    val orders         = summonAll[Tuple.Map[A.MirroredElemTypes, Ordering]]
    val fieldNames     = constValueTuple[A.MirroredElemLabels].toList.asInstanceOf[List[String]]
    val vectorOfOrders = orders.toList.asInstanceOf[List[Ordering[Any]]].zipWithIndex
    val cachedOrders   = fieldNames.zip(vectorOfOrders).toMap

    new DynamicMultiSorter[A] {
      override def sort(input: List[A], sortBys: List[SortBy]): List[A] =
        input.sorted { (left, right) =>
          sortBys.iterator.map { sort =>
            cachedOrders
              .get(sort.key)
              .map { case (ord, idx) =>
                val rightOrderOrders = sort.ordering match {
                  case FieldOrdering.ASC  => ord
                  case FieldOrdering.DESC => ord.reverse
                }
                rightOrderOrders.compare(left.productElement(idx), right.productElement(idx))
              }
              .getOrElse(0)
          }
            .find(_ != 0)
            .getOrElse(0)
        }
    }
