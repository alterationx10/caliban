package caliban.client

import caliban.client.CalibanClientError.{ CommunicationError, DecodingError, ServerError }
import caliban.client.FieldBuilder.Scalar
import caliban.client.Operations.IsOperation
import caliban.client.Selection.Directive
import caliban.client.__Value.__ObjectValue
import com.github.plokhotnyuk.jsoniter_scala.core._
import sttp.client3._
import sttp.client3.jsoniter._
import sttp.model.Uri

import scala.collection.immutable.{ Map => SMap }
import scala.util.control.NonFatal

/**
 * Represents a selection from parent type `Origin` that returns a result of type `A`.
 */
sealed trait SelectionBuilder[-Origin, +A] { self =>

  private[caliban] def toSelectionSet: List[Selection]
  private[caliban] def fromGraphQL(value: __Value): Either[DecodingError, A]

  /**
   * Combines this selection with another selection, returning a tuple of both results.
   * To be combines, selections needs to have the same origin.
   */
  def ~[Origin1 <: Origin, B](that: SelectionBuilder[Origin1, B])(implicit
    zippable: Zippable[A, B]
  ): SelectionBuilder[Origin1, zippable.Out] = new SelectionBuilder[Origin1, zippable.Out] { newSelf =>
    def fromGraphQL(value: __Value): Either[DecodingError, zippable.Out] =
      for {
        v1 <- self.fromGraphQL(value)
        v2 <- that.fromGraphQL(value)
      } yield zippable.zip(v1, v2)

    def withDirective(directive: Directive): SelectionBuilder[Origin1, zippable.Out] =
      self.withDirective(directive) ~ that.withDirective(directive)

    def toSelectionSet: List[Selection] = self.toSelectionSet ++ that.toSelectionSet

    def withAlias(alias: String): SelectionBuilder[Origin1, zippable.Out] = newSelf // makes no sense, do nothing
  }

  /**
   * Maps the result of this selection to a new type `B`
   */
  def map[B](f: A => B): SelectionBuilder[Origin, B] = mapEither(f andThen (Right(_)))

  /**
   * Maps the result of this selection to a new type `B` or make the decoding fail with a DecodingError
   */
  def mapEither[B](f: A => Either[DecodingError, B]): SelectionBuilder[Origin, B] =
    SelectionBuilder.Mapping(self, f)

  /**
   * Add the given directive to the selection
   */
  def withDirective(directive: Directive): SelectionBuilder[Origin, A]

  /**
   * Use the given alias for this selection
   */
  def withAlias(alias: String): SelectionBuilder[Origin, A]

  /**
   * Parse the given response payload into the expected return type,
   * with an potential list of partial errors and an optional extensions object
   */
  def decode(payload: String): Either[CalibanClientError, (A, List[GraphQLResponseError], Option[__ObjectValue])] =
    for {
      parsed      <- try Right(readFromString[GraphQLResponse](payload))
                     catch {
                       case NonFatal(ex) => Left(DecodingError("Json deserialization error", Some(ex)))
                     }
      data        <- if (parsed.errors.nonEmpty && parsed.data.forall(_ == __Value.__NullValue))
                       Left(ServerError(parsed.errors))
                     else Right(parsed.data)
      objectValue <- data match {
                       case Some(o: __ObjectValue) => Right(o)
                       case _                      => Left(DecodingError("Result is not an object"))
                     }
      result      <- self.fromGraphQL(objectValue)
    } yield (result, parsed.errors, parsed.extensions)

  /**
   * Transforms a root selection into a GraphQL request.
   * @param useVariables if true, all arguments will be passed as variables (default: false)
   * @param queryName if specified, use the given query name
   * @param dropNullInputValues if true, drop all null values from input object arguments (default: false)
   */
  def toGraphQL[A1 >: A, Origin1 <: Origin](
    useVariables: Boolean = false,
    queryName: Option[String] = None,
    dropNullInputValues: Boolean = false
  )(implicit ev: IsOperation[Origin1]): GraphQLRequest = {
    val (fields, variables) =
      SelectionBuilder.toGraphQL(toSelectionSet, useVariables, dropNullInputValues)
    val variableDef         =
      if (variables.nonEmpty)
        s"(${variables.map { case (name, (_, typeName)) => s"$$$name: $typeName" }.mkString(",")})"
      else ""
    val nameDef             = queryName.fold("")(name => s" $name ")
    val operation           = s"${ev.operationName}$nameDef$variableDef{$fields}"
    GraphQLRequest(operation, variables.map { case (k, (v, _)) => k -> v })
  }

  /**
   * Transforms a root selection into an STTP request ready to be run.
   * To access partial errors and extensions, use `toRequestWith`.
   * @param uri the URL of the GraphQL server
   * @param useVariables if true, all arguments will be passed as variables (default: false)
   * @param queryName if specified, use the given query name
   * @param dropNullInputValues if true, drop all null values from input object arguments (default: false)
   * @return an STTP request
   */
  def toRequest[A1 >: A, Origin1 <: Origin](
    uri: Uri,
    useVariables: Boolean = false,
    queryName: Option[String] = None,
    dropNullInputValues: Boolean = false
  )(implicit ev: IsOperation[Origin1]): Request[Either[CalibanClientError, A1], Any] =
    toRequestWith[A1, Origin1](uri, useVariables, queryName, dropNullInputValues)((res, _, _) => res)(ev)

  /**
   * Transforms a root selection into an STTP request ready to be run.
   * More powerful than `toRequest`, it gives you access to partial errors and extensions.
   * @param uri the URL of the GraphQL server
   * @param useVariables if true, all arguments will be passed as variables (default: false)
   * @param queryName if specified, use the given query name
   * @param dropNullInputValues if true, drop all null values from input object arguments (default: false)
   * @return an STTP request
   */
  def toRequestWith[B, Origin1 <: Origin](
    uri: Uri,
    useVariables: Boolean = false,
    queryName: Option[String] = None,
    dropNullInputValues: Boolean = false
  )(
    mapResponse: (A, List[GraphQLResponseError], Option[__ObjectValue]) => B
  )(implicit ev: IsOperation[Origin1]): Request[Either[CalibanClientError, B], Any] =
    basicRequest
      .post(uri)
      .body(toGraphQL(useVariables, queryName, dropNullInputValues))
      .mapResponse(
        _.left
          .map(CommunicationError(_))
          .flatMap(decode(_).map { case (result, errors, extensions) => mapResponse(result, errors, extensions) })
      )

  /**
   * Maps a tupled result to a type `Res` using a  function `f` with 2 parameters
   */
  def mapN[B, C, Res](f: (B, C) => Res)(implicit ev: A <:< (B, C)): SelectionBuilder[Origin, Res] =
    self.map(ev.andThen { case (b, c) => f(b, c) })

  /**
   * Maps a tupled result to a type `Res` using a  function `f` with 3 parameters
   */
  def mapN[B, C, D, Res](f: (B, C, D) => Res)(implicit ev: A <:< (B, C, D)): SelectionBuilder[Origin, Res] =
    self.map(ev.andThen { case (b, c, d) => f(b, c, d) })

  /**
   * Maps a tupled result to a type `Res` using a  function `f` with 4 parameters
   */
  def mapN[B, C, D, E, Res](f: (B, C, D, E) => Res)(implicit ev: A <:< (B, C, D, E)): SelectionBuilder[Origin, Res] =
    self.map(ev.andThen { case (b, c, d, e) => f(b, c, d, e) })

  /**
   * Maps a tupled result to a type `Res` using a  function `f` with 5 parameters
   */
  def mapN[B, C, D, E, F, Res](
    f: (B, C, D, E, F) => Res
  )(implicit ev: A <:< (B, C, D, E, F)): SelectionBuilder[Origin, Res] =
    self.map(ev.andThen { case (b, c, d, e, ff) => f(b, c, d, e, ff) })

  /**
   * Maps a tupled result to a type `Res` using a  function `f` with 6 parameters
   */
  def mapN[B, C, D, E, F, G, Res](
    f: (B, C, D, E, F, G) => Res
  )(implicit ev: A <:< (B, C, D, E, F, G)): SelectionBuilder[Origin, Res] =
    self.map(ev.andThen { case (b, c, d, e, ff, g) => f(b, c, d, e, ff, g) })

  /**
   * Maps a tupled result to a type `Res` using a  function `f` with 7 parameters
   */
  def mapN[B, C, D, E, F, G, H, Res](
    f: (B, C, D, E, F, G, H) => Res
  )(implicit ev: A <:< (B, C, D, E, F, G, H)): SelectionBuilder[Origin, Res] =
    self.map(ev.andThen { case (b, c, d, e, ff, g, h) => f(b, c, d, e, ff, g, h) })

  /**
   * Maps a tupled result to a type `Res` using a  function `f` with 8 parameters
   */
  def mapN[B, C, D, E, F, G, H, I, Res](
    f: (B, C, D, E, F, G, H, I) => Res
  )(implicit ev: A <:< (B, C, D, E, F, G, H, I)): SelectionBuilder[Origin, Res] =
    self.map(ev.andThen { case (b, c, d, e, ff, g, h, i) => f(b, c, d, e, ff, g, h, i) })

  /**
   * Maps a tupled result to a type `Res` using a  function `f` with 9 parameters
   */
  def mapN[B, C, D, E, F, G, H, I, J, Res](
    f: (B, C, D, E, F, G, H, I, J) => Res
  )(implicit ev: A <:< (B, C, D, E, F, G, H, I, J)): SelectionBuilder[Origin, Res] =
    self.map(ev.andThen { case (b, c, d, e, ff, g, h, i, j) => f(b, c, d, e, ff, g, h, i, j) })

  /**
   * Maps a tupled result to a type `Res` using a  function `f` with 10 parameters
   */
  def mapN[B, C, D, E, F, G, H, I, J, K, Res](
    f: (B, C, D, E, F, G, H, I, J, K) => Res
  )(implicit ev: A <:< (B, C, D, E, F, G, H, I, J, K)): SelectionBuilder[Origin, Res] =
    self.map(ev.andThen { case (b, c, d, e, ff, g, h, i, j, k) => f(b, c, d, e, ff, g, h, i, j, k) })

  /**
   * Maps a tupled result to a type `Res` using a  function `f` with 11 parameters
   */
  def mapN[B, C, D, E, F, G, H, I, J, K, L, Res](
    f: (B, C, D, E, F, G, H, I, J, K, L) => Res
  )(implicit ev: A <:< (B, C, D, E, F, G, H, I, J, K, L)): SelectionBuilder[Origin, Res] =
    self.map(ev.andThen { case (b, c, d, e, ff, g, h, i, j, k, l) => f(b, c, d, e, ff, g, h, i, j, k, l) })

  /**
   * Maps a tupled result to a type `Res` using a  function `f` with 12 parameters
   */
  def mapN[B, C, D, E, F, G, H, I, J, K, L, M, Res](
    f: (B, C, D, E, F, G, H, I, J, K, L, M) => Res
  )(implicit ev: A <:< (B, C, D, E, F, G, H, I, J, K, L, M)): SelectionBuilder[Origin, Res] =
    self.map(ev.andThen { case (b, c, d, e, ff, g, h, i, j, k, l, m) => f(b, c, d, e, ff, g, h, i, j, k, l, m) })

  /**
   * Maps a tupled result to a type `Res` using a  function `f` with 13 parameters
   */
  def mapN[B, C, D, E, F, G, H, I, J, K, L, M, N, Res](
    f: (B, C, D, E, F, G, H, I, J, K, L, M, N) => Res
  )(implicit ev: A <:< (B, C, D, E, F, G, H, I, J, K, L, M, N)): SelectionBuilder[Origin, Res] =
    self.map(ev.andThen { case (b, c, d, e, ff, g, h, i, j, k, l, m, n) => f(b, c, d, e, ff, g, h, i, j, k, l, m, n) })

  /**
   * Maps a tupled result to a type `Res` using a  function `f` with 14 parameters
   */
  def mapN[B, C, D, E, F, G, H, I, J, K, L, M, N, O, Res](
    f: (B, C, D, E, F, G, H, I, J, K, L, M, N, O) => Res
  )(implicit ev: A <:< (B, C, D, E, F, G, H, I, J, K, L, M, N, O)): SelectionBuilder[Origin, Res] =
    self.map(ev.andThen { case (b, c, d, e, ff, g, h, i, j, k, l, m, n, o) =>
      f(b, c, d, e, ff, g, h, i, j, k, l, m, n, o)
    })

  /**
   * Maps a tupled result to a type `Res` using a  function `f` with 15 parameters
   */
  def mapN[B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Res](
    f: (B, C, D, E, F, G, H, I, J, K, L, M, N, O, P) => Res
  )(implicit ev: A <:< (B, C, D, E, F, G, H, I, J, K, L, M, N, O, P)): SelectionBuilder[Origin, Res] =
    self.map(ev.andThen { case (b, c, d, e, ff, g, h, i, j, k, l, m, n, o, p) =>
      f(b, c, d, e, ff, g, h, i, j, k, l, m, n, o, p)
    })

  /**
   * Maps a tupled result to a type `Res` using a  function `f` with 16 parameters
   */
  def mapN[B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, Res](
    f: (B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q) => Res
  )(implicit ev: A <:< (B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q)): SelectionBuilder[Origin, Res] =
    self.map(ev.andThen { case (b, c, d, e, ff, g, h, i, j, k, l, m, n, o, p, q) =>
      f(b, c, d, e, ff, g, h, i, j, k, l, m, n, o, p, q)
    })

  /**
   * Maps a tupled result to a type `Res` using a  function `f` with 17 parameters
   */
  def mapN[B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, Res](
    f: (B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R) => Res
  )(implicit ev: A <:< (B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R)): SelectionBuilder[Origin, Res] =
    self.map(ev.andThen { case (b, c, d, e, ff, g, h, i, j, k, l, m, n, o, p, q, r) =>
      f(b, c, d, e, ff, g, h, i, j, k, l, m, n, o, p, q, r)
    })

  /**
   * Maps a tupled result to a type `Res` using a  function `f` with 18 parameters
   */
  def mapN[B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, Res](
    f: (B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S) => Res
  )(implicit ev: A <:< (B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S)): SelectionBuilder[Origin, Res] =
    self.map(ev.andThen { case (b, c, d, e, ff, g, h, i, j, k, l, m, n, o, p, q, r, s) =>
      f(b, c, d, e, ff, g, h, i, j, k, l, m, n, o, p, q, r, s)
    })

  /**
   * Maps a tupled result to a type `Res` using a  function `f` with 19 parameters
   */
  def mapN[B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, Res](
    f: (B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T) => Res
  )(implicit ev: A <:< (B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T)): SelectionBuilder[Origin, Res] =
    self.map(ev.andThen { case (b, c, d, e, ff, g, h, i, j, k, l, m, n, o, p, q, r, s, t) =>
      f(b, c, d, e, ff, g, h, i, j, k, l, m, n, o, p, q, r, s, t)
    })

  /**
   * Maps a tupled result to a type `Res` using a  function `f` with 20 parameters
   */
  def mapN[B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, Res](
    f: (B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U) => Res
  )(implicit ev: A <:< (B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U)): SelectionBuilder[Origin, Res] =
    self.map(ev.andThen { case (b, c, d, e, ff, g, h, i, j, k, l, m, n, o, p, q, r, s, t, u) =>
      f(b, c, d, e, ff, g, h, i, j, k, l, m, n, o, p, q, r, s, t, u)
    })

  /**
   * Maps a tupled result to a type `Res` using a  function `f` with 21 parameters
   */
  def mapN[B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V, Res](
    f: (B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V) => Res
  )(implicit ev: A <:< (B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V)): SelectionBuilder[Origin, Res] =
    self.map(ev.andThen { case (b, c, d, e, ff, g, h, i, j, k, l, m, n, o, p, q, r, s, t, u, v) =>
      f(b, c, d, e, ff, g, h, i, j, k, l, m, n, o, p, q, r, s, t, u, v)
    })

  /**
   * Maps a tupled result to a type `Res` using a  function `f` with 22 parameters
   */
  def mapN[B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V, W, Res](
    f: (B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V, W) => Res
  )(implicit
    ev: A <:< (B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V, W)
  ): SelectionBuilder[Origin, Res] =
    self.map(ev.andThen { case (b, c, d, e, ff, g, h, i, j, k, l, m, n, o, p, q, r, s, t, u, v, w) =>
      f(b, c, d, e, ff, g, h, i, j, k, l, m, n, o, p, q, r, s, t, u, v, w)
    })
}

object SelectionBuilder {

  val __typename: SelectionBuilder[Any, String] = Field("__typename", Scalar[String]())
  def pure[A](a: A): SelectionBuilder[Any, A]   = Pure(a)

  case class Field[Origin, A](
    name: String,
    builder: FieldBuilder[A],
    alias: Option[String] = None,
    arguments: List[Argument[_]] = Nil,
    directives: List[Directive] = Nil
  ) extends SelectionBuilder[Origin, A] { self =>
    override def fromGraphQL(value: __Value): Either[DecodingError, A] =
      value match {
        case __ObjectValue(fields) =>
          fields.find { case (o, _) =>
            alias.getOrElse(name) + math.abs(self.hashCode) == o || alias.contains(o) || name == o
          }.toRight(DecodingError(s"Missing field $name"))
            .flatMap(v => builder.fromGraphQL(v._2))
        case _                     => Left(DecodingError(s"Invalid field type $name"))
      }

    override def withDirective(directive: Directive): SelectionBuilder[Origin, A] =
      self.copy(directives = directive :: directives)

    override def toSelectionSet: List[Selection] =
      List(Selection.Field(alias, name, arguments, directives, builder.toSelectionSet, self.hashCode))

    override def withAlias(alias: String): SelectionBuilder[Origin, A] = self.copy(alias = Some(alias))
  }
  case class Mapping[Origin, A, B](builder: SelectionBuilder[Origin, A], f: A => Either[DecodingError, B])
      extends SelectionBuilder[Origin, B] {
    override def fromGraphQL(value: __Value): Either[DecodingError, B] = builder.fromGraphQL(value).flatMap(f)

    override def withDirective(directive: Directive): SelectionBuilder[Origin, B] =
      Mapping(builder.withDirective(directive), f)

    override def toSelectionSet: List[Selection] = builder.toSelectionSet

    override def withAlias(alias: String): SelectionBuilder[Origin, B] = Mapping(builder.withAlias(alias), f)
  }
  case class Pure[A](a: A) extends SelectionBuilder[Any, A] { self =>
    override private[caliban] def toSelectionSet = Nil

    override private[caliban] def fromGraphQL(value: __Value) = Right(a)

    /**
     * Add the given directive to the selection
     */
    override def withDirective(directive: Directive): SelectionBuilder[Any, A] = self

    /**
     * Use the given alias for this selection
     */
    override def withAlias(alias: String): SelectionBuilder[Any, A] = self
  }

  def combineAll[Origin, A](
    head: SelectionBuilder[Origin, A],
    tail: SelectionBuilder[Origin, A]*
  ): SelectionBuilder[Origin, List[A]] =
    tail
      .foldLeft(head.map(List(_))) { case (res, sel) =>
        (res ~ sel).map { case (as, a) => a :: as }
      }
      .map(_.reverse)

  def toGraphQL(
    fields: List[Selection],
    useVariables: Boolean,
    dropNullInputValues: Boolean = false,
    variables: SMap[String, (__Value, String)] = SMap()
  ): (String, SMap[String, (__Value, String)]) = {
    val fieldNames            = fields.collect { case f: Selection.Field => f }.groupBy(_.name).map { case (k, v) => k -> v.size }
    val (fields2, variables2) = fields
      .foldLeft((List.empty[String], variables)) {
        case ((fields, variables), Selection.InlineFragment(onType, selection)) =>
          val (f, v) = toGraphQL(selection, useVariables, dropNullInputValues, variables)
          (s"... on $onType{$f}" :: fields, v)

        case ((fields, variables), Selection.Field(alias, name, arguments, directives, selection, code)) =>
          // format arguments
          val (args, variables2) = arguments
            .foldLeft((List.empty[String], variables)) { case ((args, variables), a) =>
              val (a2, v2) = a.toGraphQL(useVariables, dropNullInputValues, variables)
              (a2 :: args, v2)
            }
          val argString          = args.filterNot(_.isEmpty).reverse.mkString(",") match {
            case ""   => ""
            case args => s"($args)"
          }

          // format directives
          val (dirs, variables3) = directives
            .foldLeft((List.empty[String], variables2)) { case ((dirs, variables), d) =>
              val (d2, v2) = d.toGraphQL(useVariables, dropNullInputValues, variables)
              (d2 :: dirs, v2)
            }
          val dirString          = dirs.reverse.mkString(" ") match {
            case ""   => ""
            case dirs => s" $dirs"
          }

          // format aliases
          val aliasString = (if (fieldNames.get(alias.getOrElse(name)).exists(_ > 1))
                               Some(alias.getOrElse(name) + math.abs(code))
                             else alias).fold("")(_ + ":")

          // format selection
          val (sel, variables4) = toGraphQL(selection, useVariables, dropNullInputValues, variables3)
          val selString         = if (sel.nonEmpty) s"{$sel}" else ""

          (s"$aliasString$name$argString$dirString$selString" :: fields, variables4)
      }
    (fields2.reverse.mkString(" "), variables2)
  }
}
