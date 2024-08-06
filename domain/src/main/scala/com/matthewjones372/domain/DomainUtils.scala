package com.matthewjones372.domain

import zio.Chunk
import zio.schema.codec.{BinaryCodec, DecodeError}

import java.nio.CharBuffer
import java.nio.charset.StandardCharsets

extension (json: CharSequence)
  def to[A](using codec: BinaryCodec[A]): Either[DecodeError, A] =
    codec.decode(Chunk.fromByteBuffer(StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(json))))

def encodeAs[A](a: A)(using codec: BinaryCodec[A]): String =
  new String(codec.encode(a).toArray, StandardCharsets.UTF_8)
