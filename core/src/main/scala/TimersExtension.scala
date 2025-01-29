package readren.taskflow

import TimersExtension.NanoDuration

import java.util.function.{Consumer, Supplier}
import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Success, Try}
import scala.util.control.NonFatal


object TimersExtension {

	type NanoDuration = Long
	type NanoTime = Long

	/** Specifies what must be exposed by an instance of [[Assistant.Schedule]] created with the [[Assistant.newFixedRateSchedule]] method. */
	trait FixedRateLike {
		/** @return The number of executions of the [[Runnable]] that were skipped before the current one due to processing power saturation or negative `initialDelay`.
		 * It is calculated based on the scheduled interval, and the difference between the actual [[startingTime]] and the scheduled time:
		 * {{{ (actualTime - scheduledTime) / interval }}}
		 * A negative value means the implementation is unable to know the `scheduledTime` to calculate it and should be ignored.
		 * Intended to be accessed only within the thread that is currently running the [[Runnable]] that was scheduled with this instance of [[Assistant.Schedule]]. */
		def numOfSkippedExecutions: Long

		/** @return The [[System.nanoTime]] when the current execution started.
		 * The [[numOfSkippedExecutions]] is calculated based on this time.
		 * Intended to be accessed only within the thread that is currently running the [[Runnable]] that was scheduled with this instance of [[Assistant.Schedule]]. */
		def startingTime: NanoTime
	}

	trait Assistant {
		type Schedule

		def newDelaySchedule(delay: NanoDuration): Schedule

		def newFixedRateSchedule(initialDelay: NanoDuration, interval: NanoDuration): Schedule & FixedRateLike

		def newFixedDelaySchedule(initialDelay: NanoDuration, delay: NanoDuration): Schedule

		/** The implementation should not throw non-fatal exceptions. */
		def executeSequentiallyScheduled(schedule: Schedule, runnable: Runnable): Unit

		/** The implementation should not throw non-fatal exceptions. */
		def cancel(schedule: Schedule): Unit

		def cancelAll(): Unit

		def isActive(schedule: Schedule): Boolean
	}
}

/** Extends [[Doer]] with operations that require time delays. */
trait TimersExtension { self: Doer =>

	type TimedAssistant <: TimersExtension.Assistant

	val timedAssistant: TimedAssistant

	type Schedule = timedAssistant.Schedule

	inline def newDelaySchedule(delay: NanoDuration): Schedule =
		timedAssistant.newDelaySchedule(delay)

	inline def newFixedRateSchedule(initialDelay: NanoDuration, interval: NanoDuration): Schedule =
		timedAssistant.newFixedRateSchedule(initialDelay, interval)

	inline def executeSequentiallyScheduled(schedule: Schedule)(runnable: Runnable): Unit =
		timedAssistant.executeSequentiallyScheduled(schedule, runnable)

	inline def cancel(schedule: Schedule): Unit =
		timedAssistant.cancel(schedule)

	inline def cancelAll(): Unit =
		timedAssistant.cancelAll()


	//// Duty extension ////

	extension [A](thisDuty: Duty[A]) {

		/** Returns a [[Duty]] that behaves the same as `thisDuty`, but its execution starts only after the delay specified by the provided schedule, measured from the moment it is triggered.
		 * If the provided [[Schedule]] is a fixed rate, triggering the returned [[Duty]] causes `thisDuty` to execute periodically until the `schedule` is canceled. */
		inline def scheduled(schedule: Schedule): Duty[A] =
			new Scheduled(thisDuty, schedule)

		/** Returns a [[Duty]] that behaves the same as `thisDuty`, but its execution starts only after the provided `delay`, measured from the moment it is triggered. */
		def delayed(delay: FiniteDuration): Duty[A] =
			scheduled(newDelaySchedule(delay.toNanos))

		/** Like [[Duty.map]] but the function application is scheduled.
		 * Note that what is scheduled is the start of the function application, not the execution of `thisDuty`. The schedule's delay occurs after the execution of `thisDuty` and before the application of `f` to its result.
		 * If the provided `schedule` is a fixed rate then triggering the returned duty causes the function application be executed periodically until the `schedule` is canceled.
		 * Is equivalent to {{{ thisDuty.flatMap(a => Duty.ready(a).map(f).scheduled(schedule)) }}} but more efficient. */
		def mapScheduled[B](schedule: Schedule)(f: A => B): Duty[B] = new Duty[B] {
			override def engage(onComplete: B => Unit): Unit = {
				thisDuty.engagePortal { a => executeSequentiallyScheduled(schedule) { () => onComplete(f(a)) } }
			}
		}

		/** Like [[Duty.map]] but the function application is delayed.
		 * Note that what is delayed is the start of function application, not the start of the execution of `thisDuty`. The delay occurs after the execution of `thisDuty` and before the application of `f` to its result.
		 * Is equivalent to {{{ thisDuty.flatMap(a => Duty.ready(a).map(f).delayed(delay)) }}} but more efficient. */
		def mapDelayed[B](delay: FiniteDuration)(f: A => B): Duty[B] =
			mapScheduled(newDelaySchedule(delay.toNanos))(f)


		/** Like [[Duty.flatMap]] but the function application is scheduled.
		 * Note that what is scheduled is the function application, not the execution of `thisDuty`. The schedule's delay occurs after the execution of `thisDuty` and before the application of `f` to its result.
		 * If the provided `schedule` is a fixed rate then triggering the returned duty causes the function application be executed periodically until the `schedule` is canceled.
		 * Is equivalent to {{{ thisDuty.flatMap(a => Duty.ready(a).flatMap(f).scheduled(schedule)) }}} but more efficient. */
		def flatMapScheduled[B](schedule: Schedule)(f: A => Duty[B]): Duty[B] = new Duty[B] {
			override def engage(onComplete: B => Unit): Unit = {
				thisDuty.engagePortal { a => executeSequentiallyScheduled(schedule) { () => f(a).engagePortal(onComplete) } }
			}
		}

		/** Like [[Duty.flatMap]] but the function application is delayed.
		 * Note that what is delayed is the function application, not the execution of `thisDuty`. The delay occurs after the execution of `thisDuty` and before the application of `f` to its result.
		 * Is equivalent to {{{ thisDuty.flatMap(a => Duty.ready(a).flatMap(f).delayed(delay)) }}} but more efficient. */
		def flatMapDelayed[B](delay: FiniteDuration)(f: A => Duty[B]): Duty[B] =
			flatMapScheduled(newDelaySchedule(delay.toNanos))(f)

		/**
		 * Returns a [[Duty]] that behaves the same as `thisDuty` but wraps its result in [[Maybe.some]] if the duty's execution takes less time than the delay of the provided [[Schedule]].
		 * Otherwise, the result is [[Maybe.empty]].
		 *
		 * Canceling the `schedule` within the [[Doer]] `DoSiThEx` before the delay elapses effectively removes the time constraint, treating the timeout's delay as infinite.
		 * The provided [[Schedule]] may be a fixed rate one, in which case triggering the returned duty would cause an inert operation be executed periodically until the schedule is cancelled.
		 *
		 * @param schedule a [[Schedule]] whose delay is the maximum duration allowed for the duty to complete before returning [[Maybe.empty]].
		 * @return a [[Duty]] that wraps the result of this duty with [[Maybe.some]] if `thisDuty` completes within the timeout, or completes with [[Maybe.empty]] when the timeout elapses.
		 */
		inline def timeLimited(schedule: Schedule): Duty[Maybe[A]] = {
			new TimeLimited[A, Maybe[A]](thisDuty, schedule, identity)
		}

		/**
		 * Returns a [[Duty]] that behaves the same as `thisDuty` but wraps its result in [[Maybe.some]] if the duty's execution takes less time than the provided `timeout`.
		 * Otherwise, the result is [[Maybe.empty]].
		 *
		 * @param timeout the maximum duration allowed for the duty to complete before returning [[Maybe.empty]].
		 * @return a [[Duty]] that wraps the result of this duty with [[Maybe.some]] if the task completes within the timeout, or completes with [[Maybe.empty]] when the timeout elapses.
		 */
		inline def timeLimited(timeout: FiniteDuration): Duty[Maybe[A]] =
			timeLimited(newDelaySchedule(timeout.toNanos))

		/**
		 * Returns a [[Duty]] that behaves the same as `thisDuty` but retries its execution if it does not complete within the delay of the specified [[Schedule]].
		 * The duty will be retried until it completes within the `timeout` or the maximum number of retries (`maxRetries`) is reached, whichever occurs first.
		 * If this duty has side effects, they will be performed once for the initial execution and once for each retry, resulting in a total of one plus the number of retries.
		 *
		 * @param timeout    the maximum duration to allow for each execution of the duty before it is retried.
		 * @param maxRetries the maximum number of retries allowed.
		 * @return           a [[Task]] that produces [[Maybe[A]]] indicating the result of the task execution, or [[Maybe.empty]] if it fails to complete within the allowed retries.
		 */
		def retriedWhileTimeout(timeout: Schedule, maxRetries: Int): Duty[Maybe[A]] = {
			thisDuty.timeLimited(timeout).repeatedUntilSome(Integer.MAX_VALUE) { (retries, result) =>
				result.fold {
					if retries < maxRetries then Maybe.empty
					else Maybe.some(Maybe.empty)
				}(a => Maybe.some(Maybe.some(a)))
			}
		}
	}

	final class Scheduled[A, B](duty: Duty[A], schedule: Schedule) extends Duty[A] {
		override def engage(onComplete: A => Unit): Unit =
			executeSequentiallyScheduled(schedule)(() => duty.engagePortal(onComplete))
	}

	/** Used by [[timeLimited]] and [[timeBounded]].
	 */
	final class TimeLimited[A, B](duty: Duty[A], timeout: Schedule, f: Maybe[A] => B) extends Duty[B] {
		override def engage(onComplete: B => Unit): Unit = {
			var hasElapsed = false;
			var hasCompleted = false;
			duty.trigger(true) { a =>
				if (!hasElapsed) {
					cancel(timeout);
					hasCompleted = true;
					onComplete(f(Maybe.some(a)))
				}
			}
			executeSequentiallyScheduled(timeout) {
				() =>
					if (!hasCompleted) {
						hasElapsed = true;
						onComplete(f(Maybe.empty))
					}
			}
		}
	}

	extension (companion: Duty.type) {
		inline def schedule[A](schedule: Schedule)(supplier: Supplier[A]): Duty[A] =
			new Book(schedule, supplier)

		def delay[A](duration: FiniteDuration)(supplier: Supplier[A]): Duty[A] =
			new Book(newDelaySchedule(duration.toNanos), supplier)
	}

	final class Book[A](schedule: Schedule, supplier: Supplier[A]) extends Duty[A] {
		override def engage(onComplete: A => Unit): Unit =
			executeSequentiallyScheduled(schedule)(() => onComplete(supplier.get))
	}

	//// Task extension ////

	extension [A](thisTask: Task[A]) {

		/** Like [[Duty.scheduled]] but for [[Task]]s. */
		def appointed(schedule: Schedule): Task[A] = new Task[A] {
			override def engage(onComplete: Try[A] => Unit): Unit = {
				executeSequentiallyScheduled(schedule)(() => thisTask.engagePortal(onComplete))
			}
		}


		/** Like [[Duty.delayed]] but for [[Task]]s. */
		def postponed(delay: FiniteDuration): Task[A] =
			appointed(newDelaySchedule(delay.toNanos))

		/**
		 * Returns a [[Task]] that behaves the same as `thisTask` but wraps its result in [[Maybe.some]] if the task's execution takes less time than the delay of the provided [[Schedule]].
		 * Otherwise, the result is [[Maybe.empty]].
		 *
		 * Canceling the `schedule` within the [[Doer]] `DoSiThEx` before the delay elapses effectively removes the time constraint, treating the timeout's delay as infinite.
		 * The provided [[Schedule]] may be a fixed rate one, in which case triggering the returned task would cause an inert operation be executed periodically until the schedule is cancelled.
		 *
		 * @param schedule a [[Schedule]] whose delay is the maximum duration allowed for the `thisTask` to complete before returning [[Maybe.empty]].
		 * @return a [[Duty]] that wraps the result of this duty with [[Maybe.some]] if the task completes within the timeout, or completes with [[Maybe.empty]] when the timeout elapses.
		 */
		def timeBounded(schedule: Schedule): Task[Maybe[A]] = new Task[Maybe[A]] {
			override def engage(onComplete: Try[Maybe[A]] => Unit): Unit = {
				val timeLimitedDuty = new TimeLimited[Try[A], Try[Maybe[A]]](thisTask, schedule, mtA => mtA.fold(Success(Maybe.empty))(_.map(Maybe.some)))
				timeLimitedDuty.engagePortal(onComplete)
			}
		}

		/**
		 * Returns a [[Task]] that behaves the same as `thisTask` but wraps its result in [[Maybe.some]] if the task's execution takes less time than `timeout`.
		 * Otherwise, the result is [[Maybe.empty]].
		 *
		 * @param timeout the maximum duration allowed for the `thisTask` to complete before returning [[Maybe.empty]].
		 * @return a [[Duty]] that wraps the result of this duty with [[Maybe.some]] if the task completes within the timeout, or completes with [[Maybe.empty]] when the timeout elapses.
		 */
		def timeBounded(timeout: FiniteDuration): Task[Maybe[A]] =
			timeBounded(newDelaySchedule(timeout.toNanos))

		/**
		 * Returns a [[Task]] that behaves the same as `thisTask` but retries its execution if it does not complete within the specified `timeout`.
		 * The task will be retried until it completes within the `timeout` or the maximum number of retries (`maxRetries`) is reached, whichever occurs first.
		 * If this task has side effects, they will be performed once for the initial execution and once for each retry, resulting in a total of one plus the number of retries.
		 *
		 * @param timeout the maximum duration to allow for each execution of the task before it is retried.
		 * @param maxRetries the maximum number of retries allowed.
		 * @return a [[Task]] that produces [[Maybe[A]]] indicating the result of the task execution, or [[Maybe.empty]] if it fails to complete within the allowed retries
		 */
		def reiteratedWhileTimeout(timeout: FiniteDuration, maxRetries: Int): Task[Maybe[A]] = {
			thisTask.timeBounded(newDelaySchedule(timeout.toNanos)).reiteratedHardyUntilSome[Maybe[A]](Integer.MAX_VALUE) { (retries, result) =>
				result match {
					case Success(mA) =>
						mA.fold {
							if retries < maxRetries then Maybe.empty
							else Maybe.some(Success(Maybe.empty))
						}(a => Maybe.some(Success(Maybe.some(a))))
					case Failure(cause) =>
						Maybe.some(Failure(cause))
				}
			}
		}
	}

	/** Truco para agregar operaciones al objeto [[AmigoFutures.Task]]. Para que funcione se requiere que esta clase esté importada. */
	extension (companion: Task.type) {

		/** Creates a [[Task]] that does nothing for the specified `duration`. */
		def sleeps(duration: FiniteDuration): Task[Unit] = {
			Task.unit.postponed(duration)
		}

		def appoint[A](schedule: Schedule)(supplier: Supplier[A]): Task[A] = new Task[A] {
			override def engage(onComplete: Try[A] => Unit): Unit = {
				executeSequentiallyScheduled(schedule) { () =>
					try onComplete(Success(supplier.get))
					catch {
						case NonFatal(e) => onComplete(Failure(e))
					}
				}
			}
		}

		/** Crea una tarea, llamémosla "bucle", que al ejecutarla ejecuta la `tarea` supervisada recibida y, si consume mas tiempo que el margen recibido, la vuelve a ejecutar. Este ciclo se repite hasta que el tiempo que consume la ejecución de la tarea supervisada no supere el margen, o se acaben los reintentos.
		 * La ejecución de la tarea bucle completará cuando:
		 * - el tiempo que demora la ejecución de la tarea supervisada en completar esta dentro del margen, en cuyo caso el resultado de la tarea bucle sería `Some(resultadoTareaMonitoreada)`,
		 * - se acaben los reintentos, en cuyo caso el resultado de la tarea bucle sería `None`.
		 */
		def retryWhileTimeout[A](maxRetries: Int, timeout: FiniteDuration)(taskBuilder: Int => Task[Try[A]]): Task[Maybe[A]] = {
			companion.attemptUntilRight[Unit, A](maxRetries) { attemptsAlreadyMade =>
				val task: Task[Try[A]] = taskBuilder(attemptsAlreadyMade)
				task.timeBounded(timeout).transform {
					case Success(mtA) =>
						mtA.fold(Success(Left(()))) {
							case Success(a) => Success(Right(a))
							case Failure(falla) => Failure(falla)
						}

					case Failure(falla) => Failure(falla);
				}
			}.map {
				case Right(a) => Maybe.some(a);
				case Left(_) => Maybe.empty
			}
		}

		/** Crea una tarea que ejecuta repetidamente la tarea recibida mientras el resultado de ella sea `Maybe.empty` y no se supere la `maximaCantEjecuciones` indicada; esperando la `pausa` indicada entre el fin de una ejecución y el comienzo de la siguiente. */
		def reiterateDelayedWhileEmpty[A](maximaCantEjecuciones: Int, pausa: Schedule)(tarea: Task[Maybe[Try[A]]]): Task[Maybe[A]] =
			new DelayedLoop[A](maximaCantEjecuciones, pausa)(tarea)
	}

	final class DelayedLoop[A](maxNumberOfExecutions: Int, delay: Schedule)(task: Task[Maybe[Try[A]]]) extends Task[Maybe[A]] {
		override def engage(onComplete: Try[Maybe[A]] => Unit): Unit = {
			def loop(remainingExecutions: Int): Unit = {
				task.trigger(true) {
					case Success(mtA) =>
						mtA.fold {
							if (remainingExecutions > 1) {
								executeSequentiallyScheduled(delay) { () => loop(remainingExecutions - 1) }
							} else
								onComplete(Success(Maybe.empty))
						} {
							case Success(a) => onComplete(Success(Maybe.some(a)))
							case Failure(e) => onComplete(Failure(e))
						}
					case Failure(e) => onComplete(Failure(e))
				}
			}

			if (maxNumberOfExecutions <= 0) {
				onComplete(Success(Maybe.empty))
			} else {
				loop(maxNumberOfExecutions);
			}
		}
	}
}
