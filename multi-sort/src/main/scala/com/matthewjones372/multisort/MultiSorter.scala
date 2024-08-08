import scala.deriving.*
import scala.compiletime.*

enum FieldOrdering:
  case ASC
  case DESC

final case class SortBy(key: String, ordering: FieldOrdering)

trait MultiSorter[A]:
  def sort(input: List[A], sortBys: List[SortBy]): List[A]

object MultiSorter:
  def sort[A](input: List[A], by: List[SortBy])(using sorter: MultiSorter[A]): List[A] =
    sorter.sort(input, by)

  inline def derived[A <: Product](using A: Mirror.ProductOf[A]): MultiSorter[A] =
    val orders         = summonAll[Tuple.Map[A.MirroredElemTypes, Ordering]]
    val fieldNames     = constValueTuple[A.MirroredElemLabels].toList.asInstanceOf[List[String]]
    val vectorOfOrders = orders.toList.asInstanceOf[List[Ordering[Any]]].zipWithIndex
    val cachedOrders   = fieldNames.zip(vectorOfOrders).toMap

    new MultiSorter[A] {
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

object Example extends App:
  import scala.math.Ordering.Implicits.*

  // we don't have to do that manual work for every case class we create, the Multi sorter will be generated at compile time
  case class Starship(name: String, age: Int) derives MultiSorter // we derive a MultiSorter for this type

  case class People(name: String, films: List[String], url: String) derives MultiSorter

  val starships = List(Starship("Enterprise", 50), Starship("Falcon", 100), Starship("Z", 3))

  val people = List(
    People("Luke Skywalker", List("A New Hope", "ZFILM"), "luke.com"),
    People("Darth Vader", List("A New Hope", "A Film"), "darth.com"),
    People("Bart Simpson", List("B Film", "ZFILM"), "bart.com")
  )

  println {
    MultiSorter.sort(
      starships,
      List(
        SortBy("name", FieldOrdering.DESC),
        SortBy("age", FieldOrdering.ASC)
      )
    )
  } // List(Starship(Z,3), Starship(Falcon,100), Starship(Enterprise,50))

  println {
    MultiSorter.sort(
      people,
      List(
        SortBy("films", FieldOrdering.DESC)
      )
    )
  } // List(People(Bart Simpson,List(B Film, ZFILM),bart.com), People(Luke Skywalker,List(A New Hope, ZFILM),luke.com), People(Darth Vader,List(A New Hope, A Film),darth.com))
