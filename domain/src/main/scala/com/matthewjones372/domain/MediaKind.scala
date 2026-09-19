package com.matthewjones372.domain

import zio.schema.Schema

/**
 * Series have no place in the episode numbering, so they carry episode 0 and
 * are told apart by this.
 */
enum MediaKind:
  case Film, Series

object MediaKind:
  def name(mediaKind: MediaKind): String = mediaKind match
    case Film   => "film"
    case Series => "series"

  def from(value: String): Either[String, MediaKind] =
    values.find(mediaKind => name(mediaKind) == value).toRight(s"Unknown media type '$value'")

  given Ordering[MediaKind] = Ordering.by(_.ordinal)

  given Schema[MediaKind] = Schema[String].transformOrFail(from, mediaKind => Right(name(mediaKind)))
