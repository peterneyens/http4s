---
layout: default
title: http4s
---

```tut:invisible
import scalaz.concurrent.Task
import scalaz.stream.Process
import org.http4s._
import org.http4s.dsl._
import org.http4s.server._
```

http4s is an Scala library for building HTTP server and client
applications.

## Audience ##

http4s strives to appeal to Scala users of all levels.  http4s fully
embraces functional programming and types for maximum composability
and safety.  We aim to make the library and documentation accessible
to those just beginning their functional programming journey, without
hiding concepts that are well understood by those further along.

## Quickstart ##

We start by creating a simple build definition.  http4s is designed to
support multiple server implementations, client implementations, and
DSLs/libraries/frameworks for declaring services.  In this tutorial,
we will use Blaze, an fast NIO2 network library, for both server and
client.  We will use http4s-dsl, a simple syntactic sugar based on
pattern matching, for our services.

Create a new directory with the following build.sbt:

```scala
scalaVersion := "2.11.7"

lazy val Http4sVersion = "0.11.0-SNAPSHOT"

libraryDependencies ++= Seq(
  "org.http4s" %% "http4s-blaze-server" % Http4sVersion
  "org.http4s" %% "http4s-blaze-client" % Http4sVersion
  "org.http4s" %% "http4s-dsl"          % Http4sVersion
)
```

Alternative modules to be discussed later include a servlet backend
for Tomcat and Jetty, as well as the self-documenting Rho DSL.

Moving right on, run `sbt console` and we can start developing from
our REPL.

## Defining an HTTP service ##

http4s server applications are built upon an `HttpService`.  An
`HttpService` receives a `Request` and returns a `Task[Response]`.
The `Task[Response]` is an asynchronous computation that generates a
response to the client.  (Formally, an `HttpService` is a
[Kleisli][yokota-kleisli] arrow in the [Task][perrett-task] monad.
Users unfamiliar with Kleisli and Task are encouraged to follow the
links at their own pace, though beginners can proceed and still be
productive.)

[yokota-kleisli]: http://eed3si9n.com/learning-scalaz/Composing+monadic+functions.html
[perrett-task]: http://timperrett.com/2014/07/20/scalaz-task-the-missing-documentation

First, let's import the core types of http4s along with the
http4s-dsl.

```tut:silent
import org.http4s._
import org.http4s.dsl._
```

Constructing an HTTP service in http4s-dsl is as simple as pattern
matching the request to `HttpService.apply`:

```tut
val service = HttpService {
  case GET -> Root / name => Ok(s"Hello, $name.")
}
```

But what does it do?  We hope it's self-apparent, but in case not,
let's build a client to try it out.

## Client ##

