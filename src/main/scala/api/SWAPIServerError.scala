package com.jones
package api

import zio.schema.*

abstract class SWAPIServerError(message: String)

object SWAPIServerError:
  case class PersonNotFound(message: String, personId: Int) extends SWAPIServerError(message)
  object PersonNotFound:
    given Schema[PersonNotFound] = DeriveSchema.gen

  case class ServerError() extends SWAPIServerError("Internal server error")
  object ServerError:
    given Schema[ServerError] = DeriveSchema.gen
