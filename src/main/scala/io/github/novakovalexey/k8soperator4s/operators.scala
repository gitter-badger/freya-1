package io.github.novakovalexey.k8soperator4s

import cats.effect.Effect
import com.typesafe.scalalogging.LazyLogging
import fs2.concurrent.Queue
import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.api.model.apiextensions.CustomResourceDefinition
import io.fabric8.kubernetes.client.{KubernetesClient, Watch}
import io.github.novakovalexey.k8soperator4s.common._
import io.github.novakovalexey.k8soperator4s.common.crd.{CrdDeployer, InfoClass, InfoClassDoneable, InfoList}
import io.github.novakovalexey.k8soperator4s.resource.LabelsHelper

import scala.annotation.unused
import scala.jdk.CollectionConverters._
import scala.util.Try

object AbstractOperator {
  def getKind[T](cfg: OperatorCfg[T]): String =
    cfg.customKind.getOrElse(cfg.forKind.getSimpleName)
}

sealed abstract class AbstractOperator[F[_]: Effect, T](
  val controller: Controller[F, T],
  val client: KubernetesClient,
  val cfg: OperatorCfg[T],
  val isOpenShift: Boolean
) extends LazyLogging {
  protected val kind: String = AbstractOperator.getKind[T](cfg)

  val clientNamespace: Namespaces = Namespace(client.getNamespace)

  val watchName: String

  protected[k8soperator4s] def onAdd(entity: T, metadata: Metadata): F[Unit] =
    controller.onAdd(entity, metadata)

  protected[k8soperator4s] def onDelete(entity: T, metadata: Metadata): F[Unit] =
    controller.onDelete(entity, metadata)

  protected[k8soperator4s] def onModify(entity: T, metadata: Metadata): F[Unit] =
    controller.onModify(entity, metadata)

  protected[k8soperator4s] def onInit(): F[Unit] =
    controller.onInit()

  protected[k8soperator4s] def makeWatcher(q: Queue[F, OperatorEvent[T]]): F[(Watch, fs2.Stream[F, Unit])]

  def close(): Unit = client.close()
}

class ConfigMapOperator[F[_]: Effect, T](
  operator: Controller[F, T],
  cfg: ConfigMapConfig[T],
  client: KubernetesClient,
  isOpenShift: Boolean
) extends AbstractOperator[F, T](operator, client, cfg, isOpenShift) {

  private val selector = LabelsHelper.forKind(kind, cfg.prefix)

  protected def isSupported(@unused cm: ConfigMap): Boolean = true

  val watchName: String = "ConfigMap"

  /**
   * Call this method in the concrete operator to obtain the desired state of the system.
   *
   * @return returns the set of 'T's that correspond to the CMs that have been created in the K8s
   */
  protected def currentConfigMaps: Map[Metadata, T] = {
    val cms = {
      val _cms = client.configMaps
      if (AllNamespaces == cfg.namespace) _cms.inAnyNamespace
      else _cms.inNamespace(cfg.namespace.value)
    }

    cms
      .withLabels(selector.asJava)
      .list
      .getItems
      .asScala
      .toList
      // ignore this CM if not convertible
      .flatMap(item => Try(Option(convert(item))).getOrElse(None))
      .map { case (entity, meta) => meta -> entity }
      .toMap
  }

  protected def convert(cm: ConfigMap): (T, Metadata) =
    ConfigMapWatcher.defaultConvert(cfg.forKind, cm)

  override protected[k8soperator4s] def makeWatcher(q: Queue[F, OperatorEvent[T]]): F[(Watch, fs2.Stream[F, Unit])] =
    ConfigMapWatcher[F, T](cfg.namespace, kind, operator, client, selector, isSupported, convert, q).watch
}

object CrdOperator {

  def deployCrd[T](
    client: KubernetesClient,
    cfg: CrdConfig[T],
    isOpenShift: Boolean
  ): Either[Throwable, CustomResourceDefinition] = CrdDeployer.initCrds[T](
    client,
    cfg.prefix,
    AbstractOperator.getKind[T](cfg),
    cfg.shortNames,
    cfg.pluralName,
    cfg.additionalPrinterColumns,
    cfg.forKind,
    isOpenShift
  )
}

class CrdOperator[F[_]: Effect, T](
  controller: Controller[F, T],
  cfg: CrdConfig[T],
  client: KubernetesClient,
  isOpenShift: Boolean,
  crd: CustomResourceDefinition
) extends AbstractOperator[F, T](controller, client, cfg, isOpenShift) {

  val watchName: String = "CustomResource"

  /**
   * Call this method in the concrete operator to obtain the desired state of the system.
   *
   * @return returns the set of 'T's that correspond to the CRs that have been created in the K8s
   */
  protected def currentResources: Map[Metadata, T] = {
    val crds = {
      val _crds =
        client.customResources(crd, classOf[InfoClass[T]], classOf[InfoList[T]], classOf[InfoClassDoneable[T]])
      if (AllNamespaces == cfg.namespace) _crds.inAnyNamespace else _crds.inNamespace(cfg.namespace.value)
    }

    crds.list.getItems.asScala.toList
    // ignore this CR if not convertible
      .flatMap(i => Try(Option(convertCr(i))).getOrElse(None))
      .map { case (entity, meta) => meta -> entity }
      .toMap
  }

  protected def convertCr(info: InfoClass[_]): (T, Metadata) =
    (
      CustomResourceWatcher.defaultConvert(cfg.forKind, info),
      Metadata(info.getMetadata.getName, info.getMetadata.getNamespace)
    )

  override protected[k8soperator4s] def makeWatcher(q: Queue[F, OperatorEvent[T]]): F[(Watch, fs2.Stream[F, Unit])] =
    CustomResourceWatcher[F, T](cfg.namespace, kind, controller, convertCr, q, client, crd).watch
}
