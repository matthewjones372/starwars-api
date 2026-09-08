package com.matthewjones372.sorting

import scala.annotation.nowarn
import scala.compiletime.*
import scala.deriving.*

enum FieldOrdering:
  case ASC
  case DESC

final case class SortBy(key: String, ordering: FieldOrdering)

trait DynamicMultiSorter[A]:
  /** The fields of `A`, in declaration order, that a SortBy key may name. */
  def fieldNames: List[String]

  def sort(input: List[A], sortBys: List[SortBy]): List[A]

  /**
   * Drops sorts naming something that is not a field, matching what `sort`
   * ignores.
   */
  final def validate(sortBys: List[SortBy]): List[SortBy] =
    sortBys.filter(sortBy => fieldNames.contains(sortBy.key))

object DynamicMultiSorter:
  def sort[A](input: List[A], by: List[SortBy])(using sorter: DynamicMultiSorter[A]): List[A] =
    sorter.sort(input, by)

  def fieldNames[A](using sorter: DynamicMultiSorter[A]): List[String] =
    sorter.fieldNames

  def validate[A](by: List[SortBy])(using sorter: DynamicMultiSorter[A]): List[SortBy] =
    sorter.validate(by)

  inline def derived[A <: Product](using A: Mirror.ProductOf[A]): DynamicMultiSorter[A] =
    import scala.math.Ordering.Implicits.seqOrdering

    // Sets have no intrinsic order, so compare their sorted contents rather than iteration order.
    // Only used once this method is inlined, which the unused-definition check cannot see.
    @nowarn("msg=unused local definition")
    given setOrdering[B: Ordering]: Ordering[Set[B]] = Ordering.by(_.toSeq.sorted)

    val orders         = summonAll[Tuple.Map[A.MirroredElemTypes, Ordering]]
    val fieldNames     = constValueTuple[A.MirroredElemLabels].toList.asInstanceOf[List[String]]
    val vectorOfOrders = orders.toList.asInstanceOf[List[Ordering[Any]]].zipWithIndex
    fromFieldOrderings(fieldNames, fieldNames.zip(vectorOfOrders).toMap)

  // Kept out of `derived` so the class is defined once rather than at every derivation site.
  private def fromFieldOrderings[A <: Product](
    names: List[String],
    cachedOrders: Map[String, (Ordering[Any], Int)]
  ): DynamicMultiSorter[A] =
    new DynamicMultiSorter[A]:
      override val fieldNames: List[String] = names

      override def sort(input: List[A], sortBys: List[SortBy]): List[A] =
        input.sorted(using
          (left, right) =>
            sortBys.map { sort =>
              cachedOrders
                .get(sort.key)
                .map { case (ord, idx) =>
                  val fieldOrdering = sort.ordering match
                    case FieldOrdering.ASC  => ord
                    case FieldOrdering.DESC => ord.reverse
                  fieldOrdering.compare(left.productElement(idx), right.productElement(idx))
                }
                .getOrElse(0)
            }
              .find(_ != 0)
              .getOrElse(0)
        )
