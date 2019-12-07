package io.github.novakovalexey.k8soperator

import cats.effect.IO
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import io.fabric8.kubernetes.api.model.NamespaceBuilder
import io.fabric8.kubernetes.client.DefaultKubernetesClient
import io.fabric8.kubernetes.client.server.mock.KubernetesServer
import io.fabric8.kubernetes.client.utils.Serialization
import io.github.novakovalexey.k8soperator.common.{ConfigMapHelper, CrdHelper}
import io.github.novakovalexey.k8soperator.internal.crd.{InfoClassDoneable, InfoList}
import io.github.novakovalexey.k8soperator.internal.resource.{ConfigMapParser, CrdParser}
import io.github.novakovalexey.k8soperator.watcher.InfoClass
import org.scalatest.BeforeAndAfter
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers
import org.scalatest.propspec.AnyPropSpec
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatestplus.scalacheck.{Checkers, ScalaCheckPropertyChecks}

import scala.collection.mutable

class OperatorHelperTest
    extends AnyPropSpec
    with Matchers
    with Checkers
    with ScalaCheckPropertyChecks
    with Eventually
    with BeforeAndAfter {

  implicit val patienceCfg: PatienceConfig = PatienceConfig(scaled(Span(5, Seconds)), scaled(Span(50, Millis)))
  val server = new KubernetesServer(false, true)
  val testNs: Namespace = Namespace("test")

  property("ConfigMap helper should return current ConfigMaps in all namespaces") {
    testCmHelper(AllNamespaces)
  }

  property("ConfigMap helper should return current ConfigMaps in test namespace") {
    testCmHelper(testNs)
  }

  private def testCmHelper(ns: K8sNamespace) = {
    //given
    val cfg = ConfigMapConfig(classOf[Kerb], ns, prefix)
    val client = server.getClient
    val parser = new ConfigMapParser()
    val helper = new ConfigMapHelper[IO, Kerb](cfg, client, None, parser)

    val maps = helper.currentConfigMaps
    maps should be(Right(Map.empty))

    val currentCms = mutable.Map.empty[Metadata, Kerb]
    val namespace = new NamespaceBuilder().withNewMetadata.withName(ns.value).endMetadata.build
    client.namespaces().create(namespace)

    forAll(CM.gen[Kerb](helper.selector)) { cm =>
      //when
      cm.getMetadata.setNamespace(ns.value)
      client.configMaps().inNamespace(cm.getMetadata.getNamespace).create(cm)

      val meta = Metadata(cm.getMetadata.getName, cm.getMetadata.getNamespace)
      val spec = parseCM(parser, cm)

      currentCms += (meta -> spec)
      //then
      helper.currentConfigMaps should be(Right(currentCms))
    }
  }

  property("Crd helper should return current CRDs") {
    //given
    val cfg = CrdConfig(classOf[Kerb], testNs, prefix, checkK8sOnStartup = false)
    val client = new DefaultKubernetesClient() // mock server does not work properly with CRDs
    Serialization.jsonMapper().registerModule(DefaultScalaModule)

    val parser = new CrdParser()
    val crd = CrdHelper.deployCrd[IO, Kerb](client, cfg, None).unsafeRunSync()
    val helper = new CrdHelper[IO, Kerb](cfg, client, None, crd, parser)
    val krbClient = client
      .customResource(crd, classOf[InfoClass[Kerb]], classOf[InfoList[Kerb]], classOf[InfoClassDoneable[Kerb]])

    //when
    krbClient.inNamespace(testNs.value).delete()

    val maps = helper.currentResources
    //then
    maps should be(Right(Map.empty))

    val currentCrds = mutable.Map.empty[Metadata, Kerb]
    val ns = new NamespaceBuilder().withNewMetadata.withName(testNs.value).endMetadata.build
    client.namespaces().createOrReplace(ns)

    forAll(InfoClass.gen[Kerb](cfg.getKind)) { ic =>
      ic.getMetadata.setNamespace(testNs.value)
      //when
      krbClient
        .inNamespace(ic.getMetadata.getNamespace)
        .createOrReplace(ic)

      val meta = Metadata(ic.getMetadata.getName, ic.getMetadata.getNamespace)
      currentCrds += (meta -> ic.getSpec)
      //then
      eventually {
        helper.currentResources should be(Right(currentCrds))
      }
    }

    krbClient.inNamespace(testNs.value).delete()
  }

  before {
    server.before()
  }

  after {
    server.after()
  }
}