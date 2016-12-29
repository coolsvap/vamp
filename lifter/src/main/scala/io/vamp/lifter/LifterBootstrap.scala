package io.vamp.lifter

import akka.actor.{ ActorRef, ActorSystem }
import io.vamp.common.akka.{ ActorBootstrap, IoC }
import io.vamp.common.config.Config
import io.vamp.lifter.artifact.ArtifactInitializationActor
import io.vamp.lifter.persistence.ElasticsearchPersistenceInitializationActor
import io.vamp.lifter.pulse.ElasticsearchPulseInitializationActor
import io.vamp.persistence.PersistenceBootstrap
import io.vamp.pulse.PulseBootstrap

class LifterBootstrap extends ActorBootstrap {

  private val config = Config.config("vamp.lifter")

  private val pulseEnabled = config.boolean("pulse.enabled")

  private val artifactEnabled = config.boolean("artifact.enabled")

  def createActors(implicit actorSystem: ActorSystem): List[ActorRef] = {

    val persistence = if (config.boolean("persistence.enabled")) {
      PersistenceBootstrap.databaseType match {
        case "elasticsearch" ⇒ IoC.createActor[ElasticsearchPersistenceInitializationActor] :: Nil
        case _               ⇒ Nil
      }
    }
    else Nil

    val pulse = if (pulseEnabled) {
      PulseBootstrap.`type` match {
        case "elasticsearch" ⇒ IoC.createActor[ElasticsearchPulseInitializationActor] :: Nil
        case _               ⇒ Nil
      }
    }
    else Nil

    val artifact = if (artifactEnabled)
      IoC.createActor[ArtifactInitializationActor] :: Nil
    else Nil

    persistence ++ pulse ++ artifact
  }
}
