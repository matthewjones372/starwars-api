package com.jones
package domain

trait Paged[A]:
  def count: Int
  def results: List[A]

  final def pageCount: Int =
    if results.isEmpty then 0
    else {
      val fullPages      = count / results.length
      val hasPartialPage = if (count % results.length == 0) 0 else 1
      fullPages + hasPartialPage
    }
