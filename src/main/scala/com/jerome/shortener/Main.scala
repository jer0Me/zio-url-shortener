package com.jerome.shortener

import cats.effect.{ExitCode => CatsExitCode}
import com.jerome.shortener.domain.repository.UrlRepository
import com.jerome.shortener.infrastructure.config.Config
import com.jerome.shortener.infrastructure.database._
import com.jerome.shortener.infrastructure.repository.DoobieUrlRepository
import com.jerome.shortener.infrastructure.routes.UrlRoutes
import org.http4s.implicits._
import org.http4s.server.Router
import org.http4s.server.blaze.BlazeServerBuilder
import org.http4s.server.middleware.CORS
import zio._
import zio.blocking.Blocking
import zio.clock.Clock
import zio.console.{Console, putStrLn}
import zio.interop.catz._
import zio.logging.Logging
import zio.logging.slf4j.Slf4jLogger

object Main extends App {

  type AppEnvironment = Config with Logging with Clock with Console with DBTransactor with UrlRepository
  type AppTask[A]     = RIO[AppEnvironment, A]

  private val appLayer =
    Slf4jLogger.make((_, msg) => msg) >+>
      Config.live >+>
      Blocking.live >+>
      H2DBTransactor.live >+>
      DoobieUrlRepository.live

  override def run(args: List[String]): ZIO[ZEnv, Nothing, ExitCode] = {
    val program: ZIO[AppEnvironment, Throwable, Unit] =
      for {
        _ <- UrlRepository.createTable
        _ <- buildServer
      } yield ()

    program
      .provideCustomLayer(appLayer)
      .tapError(error => putStrLn(s"Error occurred executing service: $error"))
      .exitCode
  }

  private def buildServer: ZIO[AppEnvironment, Throwable, Unit] =
    for {
      apiConfig <- Config.apiConfig
      _ <- ZIO
        .runtime[AppEnvironment]
        .flatMap { implicit rts =>
          val httpApp = Router[AppTask](
            "" -> UrlRoutes.routes
          ).orNotFound

          BlazeServerBuilder[AppTask](rts.platform.executor.asEC)
            .bindHttp(apiConfig.port, apiConfig.baseUrl)
            .withHttpApp(CORS(httpApp))
            .serve
            .compile[AppTask, AppTask, CatsExitCode]
            .drain
        }
    } yield ()
}
