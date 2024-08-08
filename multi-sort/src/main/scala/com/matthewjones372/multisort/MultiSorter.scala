import scala.deriving.*
import scala.compiletime.*

enum FieldOrdering:
  case ASC
  case DESC

trait Transformer[A]:
  def transform(value: A): Any

object Transformer:
  def apply[A](using t: Transformer[A]): Transformer[A] = t

  given Transformer[String] with
    def transform(value: String): Any = value

  given Transformer[Int] with
    def transform(value: Int): Any = value

  given lengthTransformer: Transformer[String] with
    def transform(value: String): Any = value.length

final case class SortBy[T](key: String, ordering: FieldOrdering, transformer: Option[Transformer[T]] = None)

trait Sorter[A]:
  def sort(input: List[A], sortBys: List[SortBy[_]]): List[A]

object Sorter:
  def sort[A](input: List[A], sortBys: List[SortBy[_]])(using sorter: Sorter[A]): List[A] =
    sorter.sort(input, sortBys)

  inline def derived[A <: Product](using A: Mirror.ProductOf[A]): Sorter[A] =
    val orders         = summonAll[Tuple.Map[A.MirroredElemTypes, Ordering]]
    val fieldNames     = constValueTuple[A.MirroredElemLabels].toList.asInstanceOf[List[String]]
    val vectorOfOrders = orders.toList.asInstanceOf[List[Ordering[Any]]].zipWithIndex
    val cachedOrders   = fieldNames.zip(vectorOfOrders).toMap

    new Sorter[A] {
      override def sort(input: List[A], sortBys: List[SortBy[_]]): List[A] =
        input.sorted { (a, b) =>
          sortBys.iterator.map { sort =>
            cachedOrders
              .get(sort.key)
              .map { case (ord, idx) =>
                val rightOrderOrders = sort.ordering match {
                  case FieldOrdering.ASC  => ord
                  case FieldOrdering.DESC => ord.reverse
                }

                val valueA = sort.transformer
                  .map(_.asInstanceOf[Transformer[Any]].transform(a.productElement(idx)))
                  .getOrElse(a.productElement(idx))

                val valueB = sort.transformer
                  .map(_.asInstanceOf[Transformer[Any]].transform(b.productElement(idx)))
                  .getOrElse(b.productElement(idx))

                rightOrderOrders.compare(valueA, valueB)
              }
              .getOrElse(0)
          }
            .find(_ != 0)
            .getOrElse(0)
        }
    }

object Example extends App:
  case class Starship(name: String, age: Int)

  val starships = List(Starship("Enterprise", 50), Starship("Falcon", 100), Starship("Z", 3))

  val sortBys = List(
    SortBy("name", FieldOrdering.ASC, Some(Transformer.lengthTransformer)),
    SortBy("age", FieldOrdering.DESC, None)
  )

  given Sorter[Starship] = Sorter.derived[Starship]

  val sortedStarships = Sorter.sort(starships, sortBys)

  sortedStarships.foreach(println)
