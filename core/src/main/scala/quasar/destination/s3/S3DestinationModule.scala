/*
 * Copyright 2020 Precog Data
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package quasar.destination.s3

import slamdata.Predef._

import quasar.api.destination.DestinationError
import quasar.api.destination.DestinationError.InitializationError
import quasar.api.destination.DestinationType
import quasar.blobstore.s3.{AccessKey, Region, SecretKey}
import quasar.connector.MonadResourceErr
import quasar.connector.destination.{Destination, DestinationModule, PushmiPullyu}
import quasar.destination.s3.impl.DefaultUpload

import scala.util.Either

import argonaut.{Argonaut, Json}, Argonaut._
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.regions.{Region => AwsRegion}
import cats.data.EitherT
import cats.effect.{Concurrent, ConcurrentEffect, ContextShift, Resource, Timer}
import cats.implicits._

object S3DestinationModule extends DestinationModule {
  // Minimum 10MiB multipart uploads
  private val PartSize = 10 * 1024 * 1024
  private val Redacted = "<REDACTED>"
  private val RedactedCreds =
    S3Credentials(AccessKey(Redacted), SecretKey(Redacted), Region(Redacted))

  def destinationType = DestinationType("s3", 1L)

  def sanitizeDestinationConfig(config: Json) = config.as[S3Config].result match {
    case Left(_) =>
      Json.jEmptyObject // don't expose credentials, even if we fail to decode the configuration.
    case Right(cfg) => cfg.copy(credentials = RedactedCreds).asJson
  }

  def destination[F[_]: ConcurrentEffect: ContextShift: MonadResourceErr: Timer](
      config: Json,
      pushPull: PushmiPullyu[F]): Resource[F, Either[InitializationError[Json], Destination[F]]] = {

    val configOrError = config.as[S3Config].toEither.leftMap {
      case (err, _) =>
        DestinationError.malformedConfiguration((destinationType, config, err))
    }

    (for {
      cfg <- EitherT(Resource.pure[F, Either[InitializationError[Json], S3Config]](configOrError))
      client <- EitherT(mkClient(cfg).map(_.asRight[InitializationError[Json]]))
      upload = DefaultUpload(client, PartSize)
    } yield (S3Destination(cfg.bucket, upload): Destination[F])).value
  }
  private def mkClient[F[_]: Concurrent](cfg: S3Config): Resource[F, S3AsyncClient] = {
    val client =
      Concurrent[F].delay(
        S3AsyncClient.builder
          .credentialsProvider(StaticCredentialsProvider.create(
            AwsBasicCredentials
              .create(
                cfg.credentials.accessKey.value,
                cfg.credentials.secretKey.value)))
          .region(AwsRegion.of(cfg.credentials.region.value))
          .build)

    Resource.fromAutoCloseable[F, S3AsyncClient](client)
  }
}
