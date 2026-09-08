# AGENTS.md

Guidance for AI agents working in this repository.

## Comments

This codebase is deliberately comment-light: 6 comment lines across ~1500 lines of
source. Keep it that way.

- Do not add comments that restate what the code does.
- Do not add scaladoc. There is none in this repository; do not introduce it.
- Do not add section banners, `TODO`s, or commented-out code.
- Do not narrate a change in a comment. The commit message and PR body are for that.

A comment is justified only when it explains *why* something non-obvious is the way it
is, and a reader would otherwise get it wrong. The existing ones are the model:

```scala
// We flip the people map so that we can easily find the neighbors of a film
```

If you cannot write the reason in one line, prefer a clearer name or a smaller function.

## Style

- Scala 3 syntax throughout: significant indentation, `given`/`using`, `enum`.
- scalafmt is authoritative (`.scalafmt.conf`, 120 columns). Run `sbt scalafmtAll`
  before committing; CI runs `scalafmtCheckAll`.
- Services are a `trait` + companion `object` holding accessors and a `ZLayer`.
  Implementations are `final private case class ... extends TheTrait`.
- Errors are `enum E(msg: String) extends RuntimeException(msg)`, one case per failure.
- Prefer typed failures over throwing. Do not `throw` inside effectful code, and do not
  use `.get` on `Option`/`Either` outside tests.

## Tests

- zio-test: `object XSpec extends ZIOSpecDefault`, assertions via `assertTrue`.
- Every module owns its tests under `<module>/src/test/scala`.
- A bug fix lands with a test that fails without it.

## Build

- `sbt compile` / `sbt test` / `sbt scalafmtCheckAll` must all pass before a PR.
- Module layout and dependencies live in `build.sbt`; versions in
  `project/Dependencies.scala`. Add a version there, never inline in `build.sbt`.
