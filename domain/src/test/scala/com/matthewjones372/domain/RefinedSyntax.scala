package com.matthewjones372.domain

/**
 * Refinement of values a test has already computed. Prefer the literal
 * constructors, which check the assertion at compile time; these are for values
 * only known at runtime, where a failure means the test is wrong.
 */
extension (value: Int)
  def asPageNumber: PageNumber = PageNumber.from(value).fold(sys.error, identity)
  def asPageSize: PageSize     = PageSize.from(value).fold(sys.error, identity)
  def asEntityId: EntityId     = EntityId.from(value).fold(sys.error, identity)
