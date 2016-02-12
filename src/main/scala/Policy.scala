package retry

import odelay.{ Delay, Timer }
import scala.concurrent.{ Future, ExecutionContext }
import scala.concurrent.duration.{ Duration, FiniteDuration }
import scala.language.implicitConversions
import scala.util.control.NonFatal
import java.util.concurrent.TimeUnit

// This case class and its implicit conversions allow us to accept both
// `() => Future[T]` and `Future[T]`-by-name as Policy.apply arguments.
// Note that these two types are the same after erasure.
case class PromiseWrapper[T](
  promise: () => Future[T]
)

object PromiseWrapper {
  implicit def fromFuture[T](promise: () => Future[T]): PromiseWrapper[T] = PromiseWrapper(promise)
  implicit def toFuture[T](pw: PromiseWrapper[T]): () => Future[T] = pw.promise
}

object Directly {

  /** Retry immediately after failure forever */
  def forever: Policy =
    new Policy {
      def apply[T]
        (promise: PromiseWrapper[T])
        (implicit success: Success[T],
         executor: ExecutionContext): Future[T] =
         retry(promise, promise)
    }

  /** Retry immediately after failure for a max number of times */
  def apply(max: Int = 3): Policy =
    new CountingPolicy {
      def apply[T]
        (promise: PromiseWrapper[T])
        (implicit success: Success[T],
         executor: ExecutionContext): Future[T] = {
          def run(max: Int): Future[T] = countdown(max, promise, run)
          run(max)
        }
    }
}


object Pause {

  /** Retry with a pause between attempts forever */
  def forever(delay: FiniteDuration = Defaults.delay)
   (implicit timer: Timer): Policy =
    new Policy { self =>
      def apply[T]
        (promise: PromiseWrapper[T])
        (implicit success: Success[T],
         executor: ExecutionContext): Future[T] =
         retry(promise, { () =>
           Delay(delay)(self(promise)).future.flatMap(identity)
         })
    }

  /** Retry with a pause between attempts for a max number of times */
  def apply(max: Int = 4, delay: FiniteDuration = Defaults.delay)
   (implicit timer: Timer): Policy =
    new CountingPolicy {
      def apply[T]
        (promise: PromiseWrapper[T])
        (implicit success: Success[T],
         executor: ExecutionContext): Future[T] = {
          def run(max: Int): Future[T] = countdown(
            max, promise,
            c => Delay(delay)(run(c)).future.flatMap(identity))
          run(max)
        }
    }
}

object Backoff {
  private def nextDelay(calculatedDelay: FiniteDuration, maxDelay: Duration): FiniteDuration =
    maxDelay match {
      case _: Duration.Infinite => calculatedDelay
      case delay: FiniteDuration => List(calculatedDelay, delay).min
    }

  /** Retry with exponential backoff forever */
  def forever(delay: FiniteDuration = Defaults.delay, base: Int = 2, maxDelay: Duration = Defaults.maxDelay)
   (implicit timer: Timer): Policy =
    new Policy {
      def apply[T]
        (promise: PromiseWrapper[T])
        (implicit success: Success[T],
         executor: ExecutionContext): Future[T] = {
          def run(delay: FiniteDuration): Future[T] = retry(promise, { () =>
            Delay(delay) {
              run(nextDelay(delay * base, maxDelay))
            }.future.flatMap(identity)
          })
          run(delay)
        }
    }

  /** Retry with exponential backoff for a max number of times */
  def apply(
    max: Int = 8,
    delay: FiniteDuration = Defaults.delay,
    base: Int = 2,
    maxDelay: Duration = Defaults.maxDelay)
   (implicit timer: Timer): Policy =
    new CountingPolicy {
      def apply[T]
        (promise: PromiseWrapper[T])
        (implicit success: Success[T],
         executor: ExecutionContext): Future[T] = {
          def run(max: Int, delay: FiniteDuration): Future[T] = countdown(
            max, promise,
            count => Delay(delay) {
              run(count, nextDelay(delay * base, maxDelay))
            }.future.flatMap(identity))
          run(max, delay)
        }
    }
}

/** A retry policy in which the a failure determines the way a future should be retried.
 *  The partial function provided may define the domain of both the success OR exceptional
 *  failure of a future fails explicitly.
 *
 *  {{{
 *  val policy = retry.When {
 *    case RetryAfter(retryAt) => retry.Pause(delay = retryAt)
 *  }
 *  val future = policy(issueRequest)
 *  }}}
 *
 *  If the result is not defined for the depends block, the future will not
 *  be retried.
 */
object When {
  type Depends = PartialFunction[Any, Policy]
  def apply(depends: Depends): Policy =
    new Policy {
      def apply[T](promise: PromiseWrapper[T])
        (implicit success: Success[T],
         executor: ExecutionContext): Future[T] = {
         val fut = promise()
         fut.flatMap { res =>
           if (success.predicate(res) || !depends.isDefinedAt(res)) fut
           else depends(res)(promise)
         }.recoverWith {
           case NonFatal(e) =>
             if (depends.isDefinedAt(e)) depends(e)(promise) else fut
         }
      }
    }
}

/** Retry policy that incorporates a count */
trait CountingPolicy extends Policy {
  protected def countdown[T](
    max: Int,
    promise: () => Future[T],
    orElse: Int => Future[T])
    (implicit success: Success[T],
     executor: ExecutionContext): Future[T] = {
      // consider this successful if our predicate says so _or_
      // we've reached the end out our countdown
      val countedSuccess = success.or(max < 1)
      retry(promise, () => orElse(max - 1), { f: Future[T] =>
        if (max < 1) f else orElse(max - 1)
      })(countedSuccess, executor)
    }
}

/** A Policy defines an interface for applying a future with retry semantics
 *  specific to implementations
 */
trait Policy {

  def apply[T](pw: PromiseWrapper[T])
    (implicit success: Success[T],
     executor: ExecutionContext): Future[T]

  def apply[T](promise: => Future[T])
    (implicit success: Success[T],
    executor: ExecutionContext): Future[T] =
      apply { () => promise }

  protected def retry[T](
    promise: () => Future[T],
    orElse: () => Future[T],
    recovery: Future[T] => Future[T] = identity(_: Future[T]))
    (implicit success: Success[T],
     executor: ExecutionContext): Future[T] = {
      val fut = promise()
      fut.flatMap { res =>
        if (success.predicate(res)) fut
        else orElse()
      }.recoverWith {
        case NonFatal(_) => recovery(fut)
      }
    }
}
