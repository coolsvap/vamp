package io.vamp.persistence

import io.vamp.common.akka.{ ActorSystemProvider, ExecutionContextProvider }
import io.vamp.common.notification.NotificationProvider
import io.vamp.common.{ Artifact, Namespace, Reference }
import io.vamp.model.artifact._
import io.vamp.persistence.notification.ArtifactNotFound

import scala.concurrent.Future
import scala.reflect._

trait ArtifactExpansionSupport extends ArtifactExpansion with ArtifactSupport {
  this: ActorSystemProvider with ExecutionContextProvider with NotificationProvider ⇒

  protected def readExpanded[T <: Artifact: ClassTag](name: String)(implicit namespace: Namespace): Future[Option[T]] = artifactForIfExists[T](name)
}

trait ArtifactExpansion {
  this: NotificationProvider with ExecutionContextProvider ⇒

  protected def readExpanded[T <: Artifact: ClassTag](name: String)(implicit namespace: Namespace): Future[Option[T]]

  protected def expandReferences(artifact: Option[Artifact])(implicit namespace: Namespace): Future[Option[Artifact]] =
    if (artifact.isDefined) expandReferences(artifact.get).map(Option(_)) else Future.successful(None)

  protected def expandReferences(artifact: Artifact)(implicit namespace: Namespace): Future[Artifact] = artifact match {
    case deployment: Deployment        ⇒ Future.successful(deployment)
    case blueprint: DefaultBlueprint   ⇒ expandClusters(blueprint.clusters).map(clusters ⇒ blueprint.copy(clusters = clusters))
    case breed: DefaultBreed           ⇒ expandBreed(breed)
    case sla: Sla                      ⇒ expandSla(sla)
    case route: DefaultRoute           ⇒ expandRoute(route)
    case escalation: GenericEscalation ⇒ Future.successful(escalation)
    case condition: DefaultCondition   ⇒ Future.successful(condition)
    case scale: DefaultScale           ⇒ Future.successful(scale)
    case workflow: Workflow            ⇒ expandWorkflow(workflow)
    case _                             ⇒ Future.successful(artifact)
  }

  protected def expandBreed(breed: DefaultBreed)(implicit namespace: Namespace): Future[DefaultBreed] = Future.sequence {
    breed.dependencies.map(item ⇒ expandIfReference[DefaultBreed, BreedReference](item._2).map(breed ⇒ (item._1, breed)))
  } map {
    result ⇒ breed.copy(dependencies = result.toMap)
  }

  protected def expandClusters(clusters: List[Cluster])(implicit namespace: Namespace): Future[List[Cluster]] = Future.sequence {
    clusters.map { cluster ⇒
      for {
        services ← expandServices(cluster.services)
        sla ← cluster.sla match {
          case Some(sla: SlaReference) ⇒ readExpanded[GenericSla](sla.name) map {
            case Some(slaDefault: GenericSla) ⇒ Some(slaDefault.copy(escalations = sla.escalations))
            case _                            ⇒ throwException(ArtifactNotFound(sla.name, classOf[GenericSla]))
          }
          case Some(sla) ⇒ Future.successful(Some(sla))
          case None      ⇒ Future.successful(None)
        }
        routing ← expandGateways(cluster.gateways)
      } yield cluster.copy(services = services, sla = sla, gateways = routing)
    }
  }

  protected def expandGateways(gateways: List[Gateway])(implicit namespace: Namespace): Future[List[Gateway]] = Future.sequence {
    gateways.map { gateway ⇒ expandGateway(gateway) }
  }

  protected def expandGateway(gateway: Gateway)(implicit namespace: Namespace): Future[Gateway] = {
    expandRoutes(gateway.routes).map { routes ⇒ gateway.copy(routes = routes) }
  }

  protected def expandRoutes(routes: List[Route])(implicit namespace: Namespace): Future[List[Route]] = Future.sequence {
    routes.map { route ⇒
      expandIfReference[DefaultRoute, RouteReference](route).flatMap(route ⇒ expandReferences(route).asInstanceOf[Future[Route]])
    }
  }

  protected def expandServices(services: List[Service])(implicit namespace: Namespace): Future[List[Service]] = Future.sequence {
    services.map { service ⇒
      for {
        scale ← if (service.scale.isDefined) expandIfReference[DefaultScale, ScaleReference](service.scale.get).map(Option(_)) else Future.successful(None)
        breed ← expandIfReference[DefaultBreed, BreedReference](service.breed)
      } yield service.copy(scale = scale, breed = breed)
    }
  }

  protected def expandSla(sla: Sla)(implicit namespace: Namespace): Future[Sla] = Future.sequence {
    sla.escalations.map(expandIfReference[GenericEscalation, EscalationReference])
  } map {
    escalations ⇒
      sla match {
        case s: GenericSla                   ⇒ s.copy(escalations = escalations)
        case s: EscalationOnlySla            ⇒ s.copy(escalations = escalations)
        case s: ResponseTimeSlidingWindowSla ⇒ s.copy(escalations = escalations)
      }
  }

  protected def expandRoute(route: DefaultRoute)(implicit namespace: Namespace): Future[DefaultRoute] = route.condition match {
    case Some(condition) ⇒ expandIfReference[DefaultCondition, ConditionReference](condition).map(condition ⇒ route.copy(condition = Option(condition)))
    case _               ⇒ Future.successful(route)
  }

  protected def expandWorkflow(workflow: Workflow)(implicit namespace: Namespace): Future[Workflow] = for {
    breed ← expandIfReference[DefaultBreed, BreedReference](workflow.breed)
    scale ← if (workflow.scale.isDefined) expandIfReference[DefaultScale, ScaleReference](workflow.scale.get).map(Option(_)) else Future.successful(None)
  } yield {
    workflow.copy(scale = scale, breed = breed)
  }

  protected def expandIfReference[D <: Artifact: ClassTag, R <: Reference: ClassTag](artifact: Artifact)(implicit namespace: Namespace): Future[D] = artifact match {
    case referencedArtifact if referencedArtifact.getClass == classTag[R].runtimeClass ⇒
      readExpanded[D](referencedArtifact.name) map {
        case Some(defaultArtifact) if defaultArtifact.getClass == classTag[D].runtimeClass ⇒ defaultArtifact.asInstanceOf[D]
        case _ ⇒ throwException(ArtifactNotFound(referencedArtifact.name, classTag[D].runtimeClass))
      }
    case defaultArtifact if defaultArtifact.getClass == classTag[D].runtimeClass ⇒ Future.successful(defaultArtifact.asInstanceOf[D])
  }
}

trait ArtifactShrinkage {
  this: NotificationProvider ⇒

  protected def onlyReferences(artifact: Option[Artifact]): Option[Artifact] = artifact.flatMap(a ⇒ Some(onlyReferences(a)))

  protected def onlyReferences(artifact: Artifact): Artifact = artifact match {
    case blueprint: DefaultBlueprint ⇒ blueprint.copy(clusters = blueprint.clusters.map(cluster ⇒ cluster.copy(services = cluster.services.map(service ⇒ service.copy(breed = BreedReference(service.breed.name))))))
    case breed: DefaultBreed         ⇒ breed.copy(dependencies = breed.dependencies.map(item ⇒ item._1 → BreedReference(item._2.name)))
    case _                           ⇒ artifact
  }
}
