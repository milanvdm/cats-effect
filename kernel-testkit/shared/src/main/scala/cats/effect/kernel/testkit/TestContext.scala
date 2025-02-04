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

package cats.effect.kernel
package testkit

import scala.collection.immutable.SortedSet
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.Random
import scala.util.control.NonFatal

/**
 * A `scala.concurrent.ExecutionContext` implementation and a provider of `cats.effect.Timer`
 * instances, that can simulate async boundaries and time passage, useful for testing purposes.
 *
 * Usage for simulating an `ExecutionContext`):
 * {{{
 *   implicit val ec = TestContext()
 *
 *   ec.execute(new Runnable { def run() = println("task1") })
 *
 *   ex.execute(new Runnable {
 *     def run() = {
 *       println("outer")
 *
 *       ec.execute(new Runnable {
 *         def run() = println("inner")
 *       })
 *     }
 *   })
 *
 *   // Nothing executes until `tick` gets called
 *   ec.tick()
 *
 *   // Testing the resulting state
 *   assert(ec.state.tasks.isEmpty)
 *   assert(ec.state.lastReportedFailure == None)
 * }}}
 *
 * Our `TestContext` can also simulate time passage, as we are able to builds a
 * `cats.effect.Timer` instance for any data type that has a `LiftIO` instance:
 *
 * {{{
 *   val ctx = TestContext()
 *
 *   val timer: Timer[IO] = ctx.timer[IO]
 * }}}
 *
 * We can now simulate actual time:
 *
 * {{{
 *   val io = timer.sleep(10.seconds) *> IO(1 + 1)
 *   val f = io.unsafeToFuture()
 *
 *   // This invariant holds true, because our IO is async
 *   assert(f.value == None)
 *
 *   // Not yet completed, because this does not simulate time passing:
 *   ctx.tick()
 *   assert(f.value == None)
 *
 *   // Simulating time passing:
 *   ctx.tick(10.seconds)
 *   assert(f.value == Some(Success(2))
 * }}}
 *
 * Simulating time makes this pretty useful for testing race conditions:
 *
 * {{{
 *   val never = IO.async[Int](_ => {})
 *   val timeoutError = new TimeoutException
 *   val timeout = timer.sleep(10.seconds) *> IO.raiseError[Int](timeoutError)
 *
 *   val pair = (never, timeout).parMapN(_ + _)
 *
 *   // Not yet
 *   ctx.tick()
 *   assert(f.value == None)
 *   // Not yet
 *   ctx.tick(5.seconds)
 *   assert(f.value == None)
 *
 *   // Good to go:
 *   ctx.tick(5.seconds)
 *   assert(f.value, Some(Failure(timeoutError)))
 * }}}
 *
 * @define timerExample
 *   {{{ val ctx = TestContext() // Building a Timer[IO] from this: implicit val timer:
 *   Timer[IO] = ctx.timer[IO]
 *
 * // Can now simulate time val io = timer.sleep(10.seconds) *> IO(1 + 1) val f =
 * io.unsafeToFuture()
 *
 * // This invariant holds true, because our IO is async assert(f.value == None)
 *
 * // Not yet completed, because this does not simulate time passing: ctx.tick() assert(f.value
 * == None)
 *
 * // Simulating time passing: ctx.tick(10.seconds) assert(f.value == Some(Success(2)) }}}
 */
final class TestContext private () extends ExecutionContext { self =>
  import TestContext.{State, Task}

  private[this] var stateRef = State(
    lastID = 0,
    // our epoch is negative! this is just to give us an extra 263 years of space for Prop shrinking to play
    clock = (Long.MinValue + 1).nanos,
    tasks = SortedSet.empty[Task],
    lastReportedFailure = None
  )

  /**
   * Inherited from `ExecutionContext`, schedules a runnable for execution.
   */
  def execute(r: Runnable): Unit =
    synchronized {
      stateRef = stateRef.execute(r)
    }

  /**
   * Inherited from `ExecutionContext`, reports uncaught errors.
   */
  def reportFailure(cause: Throwable): Unit =
    synchronized {
      stateRef = stateRef.copy(lastReportedFailure = Some(cause))
    }

  /**
   * Returns the internal state of the `TestContext`, useful for testing that certain execution
   * conditions have been met.
   */
  def state: State =
    synchronized(stateRef)

  /**
   * Executes just one tick, one task, from the internal queue, useful for testing that a some
   * runnable will definitely be executed next.
   *
   * Returns a boolean indicating that tasks were available and that the head of the queue has
   * been executed, so normally you have this equivalence:
   *
   * {{{
   *   while (ec.tickOne()) {}
   *   // ... is equivalent with:
   *   ec.tick()
   * }}}
   *
   * Note that ask extraction has a random factor, the behavior being like [[tick]], in order to
   * simulate nondeterminism. So you can't rely on some ordering of execution if multiple tasks
   * are waiting execution.
   *
   * @return
   *   `true` if a task was available in the internal queue, and was executed, or `false`
   *   otherwise
   */
  def tickOne(): Boolean =
    synchronized {
      val current = stateRef

      // extracting one task by taking the immediate tasks
      extractOneTask(current, current.clock) match {
        case Some((head, rest)) =>
          stateRef = current.copy(tasks = rest)
          // execute task
          try head.task.run()
          catch { case NonFatal(ex) => reportFailure(ex) }
          true
        case None =>
          false
      }
    }

  /**
   * Triggers execution by going through the queue of scheduled tasks and executing them all,
   * until no tasks remain in the queue to execute.
   *
   * Order of execution isn't guaranteed, the queued `Runnable`s are being shuffled in order to
   * simulate the needed nondeterminism that happens with multi-threading.
   *
   * {{{
   *   implicit val ec = TestContext()
   *
   *   val f = Future(1 + 1).flatMap(_ + 1)
   *   // Execution is momentarily suspended in TestContext
   *   assert(f.value == None)
   *
   *   // Simulating async execution:
   *   ec.tick()
   *   assert(f.value, Some(Success(2)))
   * }}}
   *
   * @param time
   *   is an optional parameter for simulating time passing;
   */
  def tick(time: FiniteDuration = Duration.Zero): Unit = {
    val targetTime = this.stateRef.clock + time
    var hasTasks = true

    while (hasTasks) synchronized {
      val current = this.stateRef

      extractOneTask(current, targetTime) match {
        case Some((head, rest)) =>
          stateRef = current.copy(clock = head.runsAt, tasks = rest)
          // execute task
          try head.task.run()
          catch {
            case ex if NonFatal(ex) =>
              reportFailure(ex)
          }

        case None =>
          stateRef = current.copy(clock = targetTime)
          hasTasks = false
      }
    }
  }

  def tickAll(time: FiniteDuration = Duration.Zero): Unit = {
    tick(time)

    // some of our tasks may have enqueued more tasks
    if (!this.stateRef.tasks.isEmpty) {
      tickAll(time)
    }
  }

  def schedule(delay: FiniteDuration, r: Runnable): () => Unit =
    synchronized {
      val current: State = stateRef
      val (cancelable, newState) = current.scheduleOnce(delay, r, cancelTask)
      stateRef = newState
      cancelable
    }

  def derive(): ExecutionContext =
    new ExecutionContext {
      def execute(runnable: Runnable): Unit = self.execute(runnable)
      def reportFailure(cause: Throwable): Unit = self.reportFailure(cause)
    }

  def now(): FiniteDuration = stateRef.clock

  private def extractOneTask(
      current: State,
      clock: FiniteDuration): Option[(Task, SortedSet[Task])] =
    current.tasks.headOption.filter(_.runsAt <= clock) match {
      case Some(value) =>
        val firstTick = value.runsAt
        val forExecution = {
          val arr = current.tasks.iterator.takeWhile(_.runsAt == firstTick).take(10).toArray
          arr(Random.nextInt(arr.length))
        }

        val remaining = current.tasks - forExecution
        Some((forExecution, remaining))

      case None =>
        None
    }

  private def cancelTask(t: Task): Unit =
    synchronized {
      stateRef = stateRef.copy(tasks = stateRef.tasks - t)
    }
}

object TestContext {

  /**
   * Builder for [[TestContext]] instances.
   */
  def apply(): TestContext =
    new TestContext

  /**
   * Used internally by [[TestContext]], represents the internal state used for task scheduling
   * and execution.
   */
  final case class State(
      lastID: Long,
      clock: FiniteDuration,
      tasks: SortedSet[Task],
      lastReportedFailure: Option[Throwable]) {
    assert(
      !tasks.headOption.exists(_.runsAt < clock),
      "The runsAt for any task must never be in the past")

    /**
     * Returns a new state with the runnable scheduled for execution.
     */
    private[TestContext] def execute(runnable: Runnable): State = {
      val newID = lastID + 1
      val task = Task(newID, runnable, clock)
      copy(lastID = newID, tasks = tasks + task)
    }

    /**
     * Returns a new state with a scheduled task included.
     */
    private[TestContext] def scheduleOnce(
        delay: FiniteDuration,
        r: Runnable,
        cancelTask: Task => Unit): (() => Unit, State) = {

      val d = if (delay >= Duration.Zero) delay else Duration.Zero
      val newID = lastID + 1

      val task = Task(newID, r, this.clock + d)
      val cancelable = () => cancelTask(task)

      (
        cancelable,
        copy(
          lastID = newID,
          tasks = tasks + task
        ))
    }
  }

  /**
   * Used internally by [[TestContext]], represents a unit of work pending execution.
   */
  final case class Task(id: Long, task: Runnable, runsAt: FiniteDuration)

  /**
   * Internal API — defines ordering for [[Task]], to be used by `SortedSet`.
   */
  private[TestContext] object Task {
    implicit val ordering: Ordering[Task] =
      new Ordering[Task] {
        val longOrd = implicitly[Ordering[Long]]

        def compare(x: Task, y: Task): Int =
          x.runsAt.compare(y.runsAt) match {
            case nonZero if nonZero != 0 =>
              nonZero
            case _ =>
              longOrd.compare(x.id, y.id)
          }
      }
  }
}
