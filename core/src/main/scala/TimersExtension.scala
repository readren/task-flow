package readren.taskflow

import TimersExtension.TimerKey

import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Success, Try}


object TimersExtension {
	type TimerKey = Long

	trait Assistant {
		/** The implementation should not throw non-fatal exceptions. */
		def queueForSequentialExecutionDelayed(key: TimerKey, delay: FiniteDuration, runnable: Runnable): Unit

		/** The implementation should not throw non-fatal exceptions. */
		def cancelDelayedExecution(key: TimerKey): Unit
	}
}

/** Extends [[Doer]] with operations that require time delays. */
trait TimersExtension { self: Doer =>

	val timedAssistant: TimersExtension.Assistant

	/** Do not refer to this instance variable. It is private to the [[genTimerKey]] method. */
	private var lastTimerId: TimerKey = 0;

	def genTimerKey(): TimerKey = {
		lastTimerId += 1;
		lastTimerId
	}

	inline def queueForSequentialExecutionDelayed(key: TimerKey, delay: FiniteDuration)(runnable: Runnable): Unit =
		timedAssistant.queueForSequentialExecutionDelayed(key, delay, runnable)

	inline def cancelDelayedExecution(key: TimerKey): Unit =
		timedAssistant.cancelDelayedExecution(key)


	//// Duty extension ////

	extension [A](thisDuty: Duty[A]) {

		def delayed(delay: FiniteDuration, timerKey: TimerKey = genTimerKey()): Duty[A] = {
			new Duty[A] {
				override def engage(onComplete: A => Unit): Unit =
					queueForSequentialExecutionDelayed(timerKey, delay)(() => thisDuty.engagePortal(onComplete))
			}
		}

		/**
		 * Returns a [[Duty]] that behaves the same as `thisDuty` but wraps its result in [[Maybe.some]] if the duty completes within the specified `timeout`.
		 * If the duty exceeds the `timeout`, it returns [[Maybe.empty]] instead.
		 *
		 * The `timerKey` parameter specifies the identifier of the timer used to track the elapsed time.
		 * Canceling this timer within the [[Doer]] `DoSiThEx` before the `timeout` elapses effectively removes the time constraint, treating the `timeout` as infinite.
		 *
		 * @param timeout    the maximum duration allowed for the duty to complete before returning [[Maybe.empty]].
		 * @param timerKey   an identifier for the timer, which can be canceled to disable the timeout constraint; defaults to a generated key via `genTimerKey()`.
		 * @return           a [[Duty]] that produces [[Maybe.some]] if the task completes within the timeout, or [[Maybe.empty]] otherwise.
		 */
		inline def timeLimited(timeout: FiniteDuration, timerKey: TimerKey = genTimerKey()): Duty[Maybe[A]] = {
			new TimeLimited[A, Maybe[A]](thisDuty, timeout, identity, timerKey)
		}

		/**
		 * Returns a [[Duty]] that behaves the same as `thisDuty` but retries its execution if it does not complete within the specified `timeout`.
		 * The duty will be retried until it completes within the `timeout` or the maximum number of retries (`maxRetries`) is reached, whichever occurs first.
		 * If this duty has side effects, they will be performed once for the initial execution and once for each retry, resulting in a total of one plus the number of retries.
		 *
		 * @param timeout    the maximum duration to allow for each execution of the duty before it is retried.
		 * @param maxRetries the maximum number of retries allowed.
		 * @return           a [[Task]] that produces [[Maybe[A]]] indicating the result of the task execution, or [[Maybe.empty]] if it fails to complete within the allowed retries.
		 */
		def retriedWhileTimeout(timeout: FiniteDuration, maxRetries: Int): Duty[Maybe[A]] = {
			thisDuty.timeLimited(timeout).repeatedUntilSome(Integer.MAX_VALUE) { (retries, result) =>
				result.fold {
					if retries < maxRetries then Maybe.empty
					else Maybe.some(Maybe.empty)
				}(a => Maybe.some(Maybe.some(a)))
			}
		}
	}


	/** Used by [[timeLimited]] and [[timeBounded]].
	 */
	final class TimeLimited[A, B](duty: Duty[A], timeout: FiniteDuration, f: Maybe[A] => B, timerKey: TimerKey = genTimerKey()) extends Duty[B] {
		override def engage(onComplete: B => Unit): Unit = {
			var hasElapsed = false;
			var hasCompleted = false;
			duty.trigger(true) { a =>
				if (!hasElapsed) {
					cancelDelayedExecution(timerKey);
					hasCompleted = true;
					onComplete(f(Maybe.some(a)))
				}
			}
			queueForSequentialExecutionDelayed(timerKey, timeout) {
				() =>
					if (!hasCompleted) {
						hasElapsed = true;
						onComplete(f(Maybe.empty))
					}
			}
		}
	}


	//// Task extension ////

	extension [A](thisTask: Task[A]) {

		def postponed(delay: FiniteDuration, timerKey: TimerKey = genTimerKey()): Task[A] = {
			new Task[A] {
				override def engage(onComplete: Try[A] => Unit): Unit =
					queueForSequentialExecutionDelayed(timerKey, delay)(() => thisTask.engagePortal(onComplete))
			}
		}

		/**
		 * Returns a [[Task]] that behaves the same as this task but wraps its result in [[Maybe.some]] if the task completes within the specified `timeout`.
		 * If the task exceeds the `timeout`, it returns [[Maybe.empty]] instead.
		 *
		 * The `timerKey` parameter specifies the identifier of the timer used to track the elapsed time.
		 * Canceling this timer within the [[Doer]] `DoSiThEx` before the `timeout` elapses effectively removes the time constraint, treating the `timeout` as infinite.
		 *
		 * @param timeout    the maximum duration allowed for the task to complete before returning [[Maybe.empty]].
		 * @param timerKey   an identifier for the timer, which can be canceled to disable the timeout constraint; defaults to a generated key via `genTimerKey()`.
		 * @return           a [[Task]] that produces [[Maybe.some]] if the task completes within the timeout, or [[Maybe.empty]] otherwise.
		 */
		def timeBounded(timeout: FiniteDuration, timerKey: TimerKey = genTimerKey()): Task[Maybe[A]] = new Task[Maybe[A]] {
			override def engage(onComplete: Try[Maybe[A]] => Unit): Unit = {
				val timeLimitedDuty = new TimeLimited[Try[A], Try[Maybe[A]]](thisTask, timeout, mtA => mtA.fold(Success(Maybe.empty))(_.map(Maybe.some)), timerKey)
				timeLimitedDuty.engagePortal(onComplete)
			}
		}

		/**
		 * Returns a [[Task]] that behaves the same as this task but retries its execution if it does not complete within the specified `timeout`.
		 * The task will be retried until it completes within the `timeout` or the maximum number of retries (`maxRetries`) is reached, whichever occurs first.
		 * If this task has side effects, they will be performed once for the initial execution and once for each retry, resulting in a total of one plus the number of retries.
		 *
		 * @param timeout    the maximum duration to allow for each execution of the task before it is retried.
		 * @param maxRetries the maximum number of retries allowed.
		 * @return           a [[Task]] that produces [[Maybe[A]]] indicating the result of
		 *                   the task execution, or [[Maybe.empty]] if it fails to complete within
		 *                   the allowed retries
		 */
		def reiteratedWhileTimeout(timeout: FiniteDuration, maxRetries: Int): Task[Maybe[A]] = {
			thisTask.timeBounded(timeout).reiteratedHardyUntilSome[Maybe[A]](Integer.MAX_VALUE) { (retries, result) =>
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

		/** Crea una tarea, llamémosla "bucle", que al ejecutarla ejecuta la `tarea` supervisada recibida y, si consume mas tiempo que el margen recibido, la vuelve a ejecutar. Este ciclo se repite hasta que el tiempo que consume la ejecución de la tarea supervisada no supere el margen, o se acaben los reintentos.
		 * La ejecución de la tarea bucle completará cuando:
		 * - el tiempo que demora la ejecución de la tarea supervisada en completar esta dentro del margen, en cuyo caso el resultado de la tarea bucle sería `Some(resultadoTareaMonitoreada)`,
		 * - se acaben los reintentos, en cuyo caso el resultado de la tarea bucle sería `None`.
		 */
		def reintentarSiTranscurreMargen[A](cantReintentos: Int, timeout: FiniteDuration)(taskBuilder: Int => Task[Try[A]]): Task[Maybe[A]] = {
			companion.attemptUntilRight[Unit, A](cantReintentos) { attemptsAlreadyMade =>
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

		/** Crea una tarea que ejecuta repetidamente la tarea recibida mientras el resultado de ella sea `None` y no se supere la `maximaCantEjecuciones` indicada; esperando la `pausa` indicada entre el fin de una ejecución y el comienzo de la siguiente. */
		def repitePausadamenteMientrasResultadoVacio[A](maximaCantEjecuciones: Int, pausa: FiniteDuration)(tarea: Task[Maybe[Try[A]]]): Task[Maybe[A]] =
			new DelayedLoop[A](maximaCantEjecuciones, pausa)(tarea)
	}

	class DelayedLoop[A](maxNumberOfExecutions: Int, delay: FiniteDuration, timerKey: TimerKey = genTimerKey())(task: Task[Maybe[Try[A]]]) extends Task[Maybe[A]] {
		override def engage(onComplete: Try[Maybe[A]] => Unit): Unit = {
			def loop(remainingExecutions: Int): Unit = {
				task.trigger(true) {
					case Success(mtA) =>
						mtA.fold {
							if (remainingExecutions > 1) {
								queueForSequentialExecutionDelayed(timerKey, delay) { () => loop(remainingExecutions - 1) }
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
