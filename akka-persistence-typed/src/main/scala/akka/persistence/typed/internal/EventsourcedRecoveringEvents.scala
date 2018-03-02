/**
 * Copyright (C) 2016-2018 Lightbend Inc. <http://www.lightbend.com/>
 */
package akka.persistence.typed.internal

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.{ Behaviors, TimerScheduler }
import akka.annotation.InternalApi
import akka.event.Logging
import akka.persistence.JournalProtocol._
import akka.persistence._
import akka.persistence.typed.internal.EventsourcedBehavior.InternalProtocol.{ IncomingCommand, JournalResponse, RecoveryTickEvent, SnapshotterResponse }
import akka.persistence.typed.internal.EventsourcedBehavior._

import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal

/***
 * INTERNAL API
 *
 * See next behavior [[EventsourcedRunning]].
 *
 */
@InternalApi
private[persistence] object EventsourcedRecoveringEvents extends EventsourcedJournalInteractions with EventsourcedStashManagement {

  @InternalApi
  private[persistence] final case class RecoveringState[State](
    seqNr:               Long,
    state:               State,
    eventSeenInInterval: Boolean = false
  )

  def apply[Command, Event, State](
    setup: EventsourcedSetup[Command, Event, State],
    state: RecoveringState[State]
  ): Behavior[InternalProtocol] =
    Behaviors.setup { _ ⇒
      startRecoveryTimer(setup.timers, setup.settings.recoveryEventTimeout)

      replayEvents(setup, state.seqNr + 1L, setup.recovery.toSequenceNr)

      withMdc(setup) {
        stay(setup, state)
      }
    }

  private def stay[Command, Event, State](
    setup: EventsourcedSetup[Command, Event, State],
    state: RecoveringState[State]
  ): Behavior[InternalProtocol] =
    Behaviors.immutable {
      case (_, JournalResponse(r))       ⇒ onJournalResponse(setup, state, r)
      case (_, SnapshotterResponse(r))   ⇒ onSnapshotterResponse(setup, r)
      case (_, RecoveryTickEvent(snap))  ⇒ onRecoveryTick(setup, state, snap)
      case (_, cmd @ IncomingCommand(_)) ⇒ onCommand(setup, cmd)
    }

  private def withMdc[C, E, S](setup: EventsourcedSetup[C, E, S])(wrapped: Behavior[InternalProtocol]) = {
    val mdc = Map(
      "persistenceId" → setup.persistenceId,
      "phase" → "recover-evnts"
    )

    Behaviors.withMdc((_: Any) ⇒ mdc, wrapped)
  }

  private def onJournalResponse[Command, Event, State](
    setup:    EventsourcedSetup[Command, Event, State],
    state:    RecoveringState[State],
    response: JournalProtocol.Response): Behavior[InternalProtocol] = {
    import setup.context.log
    try {
      response match {
        case ReplayedMessage(repr) ⇒
          // eventSeenInInterval = true
          // updateLastSequenceNr(repr)

          val newState = state.copy(
            seqNr = repr.sequenceNr,
            state = setup.eventHandler(state.state, repr.payload.asInstanceOf[Event])
          )

          stay(setup, newState)

        case RecoverySuccess(highestSeqNr) ⇒
          log.debug("Recovery successful, recovered until sequenceNr: {}", highestSeqNr)
          cancelRecoveryTimer(setup.timers)

          try onRecoveryCompleted(setup, state)
          catch { case NonFatal(ex) ⇒ onRecoveryFailure(setup, ex, highestSeqNr, Some(state)) }

        case ReplayMessagesFailure(cause) ⇒
          onRecoveryFailure(setup, cause, state.seqNr, None)

        case other ⇒
          //          stash(setup, setup.internalStash, other)
          //          Behaviors.same
          Behaviors.unhandled
      }
    } catch {
      case NonFatal(cause) ⇒
        cancelRecoveryTimer(setup.timers)
        onRecoveryFailure(setup, cause, state.seqNr, None)
    }
  }

  private def onCommand[Command, Event, State](setup: EventsourcedSetup[Command, Event, State], cmd: InternalProtocol): Behavior[InternalProtocol] = {
    // during recovery, stash all incoming commands
    stash(setup, setup.internalStash, cmd)
    Behaviors.same
  }

  // FYI, have to keep carrying all [C,E,S] everywhere as otherwise ending up with:
  //  [error] /Users/ktoso/code/akka/akka-persistence-typed/src/main/scala/akka/persistence/typed/internal/EventsourcedRecoveringEvents.scala:117:14: type mismatch;
  //  [error]  found   : akka.persistence.typed.internal.EventsourcedSetup[Command,_$1,_$2] where type _$2, type _$1
  //  [error]  required: akka.persistence.typed.internal.EventsourcedSetup[Command,_$1,Any] where type _$1
  //  [error] Note: _$2 <: Any, but class EventsourcedSetup is invariant in type State.
  //  [error] You may wish to define State as +State instead. (SLS 4.5)
  //  [error] Error occurred in an application involving default arguments.
  //  [error]         stay(setup, state.copy(eventSeenInInterval = false))
  //  [error]              ^
  protected def onRecoveryTick[Command, Event, State](setup: EventsourcedSetup[Command, Event, State], state: RecoveringState[State], snapshot: Boolean): Behavior[InternalProtocol] =
    if (!snapshot) {
      if (state.eventSeenInInterval) {
        stay(setup, state.copy(eventSeenInInterval = false))
      } else {
        cancelRecoveryTimer(setup.timers)
        val msg = s"Recovery timed out, didn't get event within ${setup.settings.recoveryEventTimeout}, highest sequence number seen ${state.seqNr}"
        onRecoveryFailure(setup, new RecoveryTimedOut(msg), state.seqNr, None) // TODO allow users to hook into this?
      }
    } else {
      // snapshot timeout, but we're already in the events recovery phase
      Behavior.unhandled
    }

  def onSnapshotterResponse[C, E, S](setup: EventsourcedSetup[C, E, S], response: SnapshotProtocol.Response): Behavior[InternalProtocol] = {
    setup.log.warning("Unexpected [{}] from SnapshotStore, already in recovering events state.", Logging.simpleName(response))
    Behaviors.unhandled // ignore the response
  }

  /**
   * Called whenever a message replay fails. By default it logs the error.
   *
   * The actor is always stopped after this method has been invoked.
   *
   * @param cause failure cause.
   * @param event the event that was processed in `receiveRecover`, if the exception was thrown there
   */
  protected def onRecoveryFailure[C, E, S](setup: EventsourcedSetup[C, E, S], cause: Throwable, sequenceNr: Long, event: Option[Any]): Behavior[InternalProtocol] = {
    returnRecoveryPermit(setup, "on recovery failure: " + cause.getMessage)
    cancelRecoveryTimer(setup.timers)

    event match {
      case Some(evt) ⇒
        setup.log.error(cause, "Exception in receiveRecover when replaying event type [{}] with sequence number [{}].", evt.getClass.getName, sequenceNr)
        Behaviors.stopped

      case None ⇒
        setup.log.error(cause, "Persistence failure when replaying events.  Last known sequence number [{}]", setup.persistenceId, sequenceNr)
        Behaviors.stopped
    }
  }

  protected def onRecoveryCompleted[C, E, S](setup: EventsourcedSetup[C, E, S], state: RecoveringState[S]): Behavior[InternalProtocol] = try {
    returnRecoveryPermit(setup, "recovery completed successfully")
    setup.recoveryCompleted(setup.commandContext, state.state)

    val running = EventsourcedRunning.HandlingCommands[C, E, S](
      setup,
      EventsourcedRunning.EventsourcedState[S](state.seqNr, state.state)
    )

    tryUnstash(setup, setup.internalStash, running)
  } finally {
    cancelRecoveryTimer(setup.timers)
  }

  // protect against snapshot stalling forever because of journal overloaded and such
  private val RecoveryTickTimerKey = "recovery-tick"
  private def startRecoveryTimer(timers: TimerScheduler[InternalProtocol], timeout: FiniteDuration): Unit =
    timers.startPeriodicTimer(RecoveryTickTimerKey, RecoveryTickEvent(snapshot = false), timeout)
  private def cancelRecoveryTimer(timers: TimerScheduler[InternalProtocol]): Unit = timers.cancel(RecoveryTickTimerKey)

}
