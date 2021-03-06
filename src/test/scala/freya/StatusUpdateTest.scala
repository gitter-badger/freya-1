package freya

import com.fasterxml.jackson.databind.{DeserializationFeature, ObjectMapper}
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import freya.internal.crd.{AnyCrDoneable, AnyCrList}
import freya.watcher.AnyCustomResource
import io.fabric8.kubernetes.api.model.apiextensions.{CustomResourceDefinition, CustomResourceDefinitionBuilder}
import io.fabric8.kubernetes.api.model.{HasMetadata, ObjectMetaBuilder}
import io.fabric8.kubernetes.client._
import io.fabric8.kubernetes.client.dsl.{NonNamespaceOperation, Resource}
import io.fabric8.kubernetes.client.server.mock.KubernetesServer
import io.fabric8.kubernetes.client.utils.Serialization
import io.fabric8.kubernetes.internal.KubernetesDeserializer
import org.scalatest.DoNotDiscover
import org.scalatest.flatspec.AnyFlatSpec

@DoNotDiscover
class StatusUpdateTest extends AnyFlatSpec {

  private[freya] def createWatch(
    kerbClient: NonNamespaceOperation[
      AnyCustomResource,
      AnyCrList,
      AnyCrDoneable,
      Resource[AnyCustomResource, AnyCrDoneable]
    ]
  ): Watch = {
    kerbClient.watch(new Watcher[AnyCustomResource]() {
      override def eventReceived(action: Watcher.Action, resource: AnyCustomResource): Unit =
        println(s"received: $action for $resource")

      override def onClose(cause: KubernetesClientException): Unit =
        println(s"watch is closed, $cause")
    })
  }

  it should "update status" in {
    Serialization.jsonMapper().registerModule(DefaultScalaModule)
    Serialization.jsonMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    val prefix = "io.github.novakov-alexey"
    val version = "v1"
    val apiVersion = prefix + "/" + version
    val kind = classOf[Kerb].getSimpleName
    val plural = "kerbs"

    KubernetesDeserializer.registerCustomKind(apiVersion, kind, classOf[AnyCustomResource])
    KubernetesDeserializer.registerCustomKind(apiVersion, s"${kind}List", classOf[CustomResourceList[_ <: HasMetadata]])

    //val client = new DefaultKubernetesClient
    val objectMapper = new ObjectMapper
    objectMapper.registerModule(DefaultScalaModule)

    val crd = new CustomResourceDefinitionBuilder()
      .withApiVersion("apiextensions.k8s.io/v1beta1")
      .withNewMetadata
      .withName(plural + "." + prefix)
      .endMetadata
      .withNewSpec
      .withNewNames
      .withKind(kind)
      .withPlural(plural)
      .endNames
      .withGroup(prefix)
      .withVersion(version)
      .withScope("Namespaced")
      .withPreserveUnknownFields(false)
      .endSpec()
      .build()

    val kerb = Kerb("test.realm", Nil, failInTest = true)
    val anyCr = newCr(crd, kerb.asInstanceOf[AnyRef])

    val server = new KubernetesServer(false, true)
    server.before()

    val client = server.getClient

    val kerbClient = client
      .customResources(crd, classOf[AnyCustomResource], classOf[AnyCrList], classOf[AnyCrDoneable])
      .inNamespace("test")
    val watch = createWatch(kerbClient)

    kerbClient.delete(anyCr)

    val cr = kerbClient.createOrReplace(anyCr)

    anyCr.setStatus(Status(true).asInstanceOf[AnyRef])
    anyCr.getMetadata.setResourceVersion(cr.getMetadata.getResourceVersion)
    kerbClient.updateStatus(anyCr)

    watch.close()
  }

  private def newCr(crd: CustomResourceDefinition, spec: AnyRef) = {
    val anyCr = new AnyCustomResource
    anyCr.setKind(crd.getSpec.getNames.getKind)
    anyCr.setApiVersion(s"${crd.getSpec.getGroup}/${crd.getSpec.getVersion}")
    anyCr.setMetadata(
      new ObjectMetaBuilder()
        .withName("test-kerb")
        .build()
    )
    anyCr.setSpec(spec)
    anyCr
  }
}
