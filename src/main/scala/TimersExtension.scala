package readren.taskflow

import scala.concurrent.duration.FiniteDuration
import scala.util.{Try, Success, Failure}


object TimersExtension {
	trait Assistant extends TaskDomain.Assistant {
		/** The implementation should not throw non-fatal exceptions. */
		def queueForSequentialExecutionDelayed(key: Long, delay: FiniteDuration, runnable: Runnable): Unit

		/** The implementation should not throw non-fatal exceptions. */
		def cancelDelayedExecution(key: Long): Unit
	}
}

trait TimersExtension(assistant: TimersExtension.Assistant) { self: TaskDomain =>

	import TimersExtension._;

	private var secuenciador: Long = 0;

	private def genIdTemporizador(): Long = {
		secuenciador += 1;
		secuenciador
	}

	inline def queueForSequentialExecutionDelayed(key: Long, delay: FiniteDuration)(runnable: Runnable): Unit = assistant.queueForSequentialExecutionDelayed(key, delay, runnable)

	inline def cancelDelayedExecution(key: Long): Unit = assistant.cancelDelayedExecution(key)

	/** Truco para agregar operaciones al objeto [[AmigoFutures.Task]]. Para que funcione se requiere que esta clase esté importada. */
	extension (companion: Task.type) {
		/** Crea una tarea, llamémosla "bucle", que al ejecutarla ejecuta la `tarea` supervisada recibida y, si consume mas tiempo que el margen recibido, la vuelve a ejecutar. Este ciclo se repite hasta que el tiempo que consume la ejecución de la tarea supervisada no supere el margen, o se acaben los reintentos.
		 * La ejecución de la tarea bucle completará cuando:
		 * - el tiempo que demora la ejecución de la tarea supervisada en completar esta dentro del margen, en cuyo caso el resultado de la tarea bucle sería `Some(resultadoTareaMonitoreada)`,
		 * - se acaben los reintentos, en cuyo caso el resultado de la tarea bucle sería `None`.
		 */
		def reintentarSiTranscurreMargen[A](cantReintentos: Int, margen: FiniteDuration)(taskBuilder: Int => Task[Try[A]]): Task[Option[A]] = {
			companion.retryUntilRight[Unit, A](cantReintentos) { attemptsAlreadyMade =>
				taskBuilder(attemptsAlreadyMade).timed(margen).transform {

					case Success(ota) => ota match {

						case Some(Success(a)) => Success(Right(a))

						case Some(Failure(falla)) => Failure(falla)

						case None => Success(Left(()))
					}

					case Failure(falla) => Failure(falla);
				}
			}.map {
				case Right(a) => Some(a);
				case Left(_) => None
			}
		}

		/** Crea una tarea que ejecuta repetidamente la tarea recibida mientras el resultado de ella sea `None` y no se supere la `maximaCantEjecuciones` indicada; esperando la `pausa` indicada entre el fin de una ejecución y el comienzo de la siguiente. */
		def repitePausadamenteMientrasResultadoVacio[A](maximaCantEjecuciones: Int, pausa: FiniteDuration)(tarea: Task[Option[Try[A]]]): Task[Option[A]] =
			new DelayedLoop[A](maximaCantEjecuciones, pausa)(tarea)

		/** Creates a [[Task]] that does nothing for the specified `duration`. */
		def sleep(duration: FiniteDuration): Task[Unit] = {
			Task.never.timed(duration).map(_ => ())
		}
	}

	/** Truco para agregar operaciones a las instancias de [[Task]]. Para que funcione se requiere que esta clase esté importada. */
	extension [A](task: Task[A]) {
		/** Da una [[Task]] que al ejecutarla:
		 *  - inicia simultáneamente la ejecución de esta [[Task]] y un temporizador con el `margen` de tiempo recibido. Si:
		 *  	- el margen de tiempo transcurre antes, llama a `cuandoCompleta` con `Success(None)`.
		 *  	- la ejecución completa normalmente con el resultado `a`, llama a `cuandoCompleta` con `Success(Some(a))`
		 *  	- la ejecución termina abruptamente, llama a `cuandoCompleta` con `Failure(causa)`.
		 */
		def timed(margen: FiniteDuration): Task[Option[A]] =
			new Timed[A](task, margen);
	}

	/** [[Task]] que al ejecutarla:
	 *  - inicia simultáneamente la ejecución de la [[Task]] `tarea` y un temporizador con el `margen` de tiempo recibido. Si:
	 *  	- la ejecución de la tarea completa antes, llama a `cuandoCompleta` con `Some(resultado)` el resultado, sea exitoso o fallido, envuelto con [[Some]].
	 *  	- el margen de tiempo transcurre antes, llama a `cuandoCompleta` con `Success(None)`.
	 *
	 * $workaroundAbstractMethodError
	 */
	class Timed[A](task: Task[A], timeout: FiniteDuration) extends Task[Option[A]] {
		override def attempt(isRunningInDoSiThEx: Boolean)(onComplete: Try[Option[A]] => Unit): Unit = {
			def work(): Unit = {
				var hasElapsed = false;
				var hasCompleted = false;
				val timerId = genIdTemporizador();
				task.attempt(isRunningInDoSiThEx = true) { tryA =>
					if (!hasElapsed) {
						cancelDelayedExecution(timerId);
						hasCompleted = true;
						onComplete(tryA map Some.apply)
					}
				}
				queueForSequentialExecutionDelayed(timerId, timeout) {
					() =>
						if (!hasCompleted) {
							hasElapsed = true;
							onComplete(Success(None))
						}
				};
			}

			if (isRunningInDoSiThEx) work();
			else queueForSequentialExecution(() => work());
		}
	}

	class DelayedLoop[A](maxNumberOfExecutions: Int, delay: FiniteDuration)(task: Task[Option[Try[A]]]) extends Task[Option[A]] {
		override def attempt(isRunningInDoSiThEx: Boolean)(onComplete: Try[Option[A]] => Unit): Unit = {

			def loop(remainingExecutions: Int): Unit = {
				task.attempt(isRunningInDoSiThEx = true) {
					case Success(Some(Success(a))) => onComplete(Success(Some(a)))
					case Success(Some(Failure(e))) => onComplete(Failure(e))
					case Success(None) =>
						if (remainingExecutions > 1) {
							val timerId = genIdTemporizador();
							queueForSequentialExecutionDelayed(timerId, delay) { () => loop(remainingExecutions - 1) }
						} else
							onComplete(Success(None))
					case Failure(e) => onComplete(Failure(e))
				}
			}

			def work(): Unit = {
				if (maxNumberOfExecutions <= 0) {
					onComplete(Success(None))
				} else {
					loop(maxNumberOfExecutions);
				}
			}

			if (isRunningInDoSiThEx) work();
			else queueForSequentialExecution(() => work());
		}
	}
}
