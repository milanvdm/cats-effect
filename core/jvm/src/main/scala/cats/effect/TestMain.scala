/*
 * Copyright 2020-2021 Typelevel
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

package cats.effect

import scala.concurrent.duration._

object TestMain extends IOApp {

  override def run(args: List[String]): IO[ExitCode] = {
    val program = for {
      _ <- IO(println("foo"))
      _ <- IO.sleep(10.millis) // The sleep is critical to trigger the issue
      _ <- IO(println("bar"))
    } yield ()

    program.as(ExitCode.Success)
  }
}
