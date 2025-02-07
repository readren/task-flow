package readren.taskflow.akka

import ActorBasedDoer.Procedure
import ActorBasedSchedulingDoer.SchedulingAide

import akka.actor.typed.scaladsl.{ActorContext, Behaviors, TimerScheduler}
import akka.actor.typed.{Behavior, Scheduler}
import readren.taskflow.{AbstractDoer, SchedulingExtension}
import readren.taskflow.SchedulingExtension.NanoDuration

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration
import scala.reflect.Typeable

object ActorBasedSchedulingDoer {

	trait SchedulingAide extends ActorExtension.Aide, SchedulingExtension.Assistant

	sealed trait Plan

	case class SingleTime(delay: NanoDuration) extends Plan

	case class FixedRate(initialDelay: NanoDuration, interval: NanoDuration) extends Plan

	case class FixedDelay(initialDelay: NanoDuration, delay: NanoDuration) extends Plan

	/** A [[Behavior]] factory that provides access to an [[ActorBasedSchedulingDoer]] whose DoSiThEx (doer single thread executor) is the actor corresponding to the provided [[ActorContext]]. */
	def setup[A: Typeable](ctxA: ActorContext[A], timerScheduler: TimerScheduler[A])(frontier: ActorBasedSchedulingDoer => Behavior[A]): Behavior[A] = {
		val aide = buildSchedulingAide(ctxA.asInstanceOf[ActorContext[Procedure]], timerScheduler.asInstanceOf[TimerScheduler[Procedure]])
		val doer: ActorBasedSchedulingDoer = new ActorBasedSchedulingDoer(aide);
		val behaviorA = frontier(doer)
		val interceptor = ActorBasedDoer.buildProcedureInterceptor[A](aide)
		Behaviors.intercept(() => interceptor)(behaviorA).narrow
	}

	private def buildSchedulingAide[A >: Procedure](ctx: ActorContext[A], timerScheduler: TimerScheduler[A]): SchedulingAide = {
		val aide = ActorBasedDoer.buildAide(ctx)
		new SchedulingAide {
			override def executeSequentially(runnable: Runnable): Unit =
				aide.executeSequentially(runnable)

			override def current: ActorExtension.Aide =
				ActorBasedDoer.currentAssistant.get

			override def reportFailure(cause: Throwable): Unit =
				aide.reportFailure(cause)

			override def akkaScheduler: Scheduler =
				aide.akkaScheduler

			override type Schedule = Plan

			override def newDelaySchedule(delay: NanoDuration): SingleTime = SingleTime(delay)

			override def newFixedRateSchedule(initialDelay: NanoDuration, interval: NanoDuration): FixedRate = FixedRate(initialDelay, interval)

			override def newFixedDelaySchedule(initialDelay: NanoDuration, delay: NanoDuration): FixedDelay = FixedDelay(initialDelay, delay)

			override def scheduleSequentially(schedule: Schedule, runnable: Runnable): Unit = {
				schedule match {
					case SingleTime(delay) => timerScheduler.startSingleTimer(schedule, Procedure(runnable), FiniteDuration(delay, TimeUnit.NANOSECONDS))
					case FixedRate(initialDelay, interval) => timerScheduler.startTimerAtFixedRate(schedule, Procedure(runnable), FiniteDuration(initialDelay, TimeUnit.NANOSECONDS), FiniteDuration(interval, TimeUnit.NANOSECONDS))
					case FixedDelay(initialDelay, delay) => timerScheduler.startTimerWithFixedDelay(schedule, Procedure(runnable), FiniteDuration(initialDelay, TimeUnit.NANOSECONDS), FiniteDuration(delay, TimeUnit.NANOSECONDS))
				}
			}

			override def cancel(schedule: Schedule): Unit =
				timerScheduler.cancel(schedule)

			override def cancelAll(): Unit =
				timerScheduler.cancelAll()

			override def isActive(schedule: Schedule): Boolean =
				timerScheduler.isTimerActive(schedule)
		}
	}
}

/** A [[Doer]], extended with scheduling and akka-actor related operations, whose DoSiThEx (doer single thread executor) is an akka-actor. */
class ActorBasedSchedulingDoer(schedulingAide: SchedulingAide) extends AbstractDoer, ActorExtension, SchedulingExtension {
	override type Assistant = SchedulingAide
	override val assistant: Assistant = schedulingAide
}