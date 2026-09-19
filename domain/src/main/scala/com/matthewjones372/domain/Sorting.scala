package com.matthewjones372.domain

import com.matthewjones372.sorting.{DynamicMultiSorter, FieldOrdering, SortBy}

/**
 * Sorting over entities whose sortable values are partly fields and partly
 * entries in an attribute bag.
 *
 * A stable sort applied from the last key back to the first is the composite
 * comparator, which is what lets the two kinds of key mix without one Ordering
 * that has to know about both.
 */
object Sorting:
  private val characterFields = DynamicMultiSorter.fieldNames[Character].toSet
  private val filmFields      = DynamicMultiSorter.fieldNames[Film].toSet

  def characters(data: List[Character], sortBy: List[SortBy]): List[Character] =
    apply(data, sortBy, characterFields, _.attributes)

  def films(data: List[Film], sortBy: List[SortBy]): List[Film] =
    apply(data, sortBy, filmFields, _.attributes)

  private def apply[A: DynamicMultiSorter](
    data: List[A],
    sortBy: List[SortBy],
    fields: Set[String],
    attributes: A => Map[String, String]
  ): List[A] =
    sortBy.reverse.foldLeft(data) { (sorted, by) =>
      if fields.contains(by.key) then DynamicMultiSorter.sort(sorted, List(by))
      else sorted.sortBy(entity => attributes(entity).get(by.key))(using ordering(by.ordering))
    }

  // Attribute values are strings, so "9" would sort after "172" unless the ones
  // that are numbers are compared as numbers.
  //
  // Numbers, then text, then absent -- and that grouping does not flip with the
  // direction, so reversing the sort reverses the values without floating the
  // characters nobody recorded a value for to the top.
  private def group(value: Option[String]): Int =
    value.fold(2)(text => if text.toDoubleOption.isDefined then 0 else 1)

  private def ordering(direction: FieldOrdering): Ordering[Option[String]] =
    val (byNumber, byText) = direction match
      case FieldOrdering.ASC  => (Ordering.Double.TotalOrdering, Ordering.String)
      case FieldOrdering.DESC => (Ordering.Double.TotalOrdering.reverse, Ordering.String.reverse)

    Ordering
      .by[Option[String], Int](group)
      .orElseBy[Double](_.flatMap(_.toDoubleOption).getOrElse(0.0))(using byNumber)
      .orElseBy[String](_.filter(_.toDoubleOption.isEmpty).getOrElse(""))(using byText)
