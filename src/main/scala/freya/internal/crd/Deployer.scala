package freya.internal.crd

import cats.effect.Sync
import cats.implicits._
import com.fasterxml.jackson.databind.DeserializationFeature
import com.typesafe.scalalogging.LazyLogging
import freya.internal.AnsiColors._
import freya.internal.api.CrdApi
import freya.watcher.SpecClass
import freya.{AdditionalPrinterColumn, OperatorCfg}
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.apiextensions._
import io.fabric8.kubernetes.client.utils.Serialization
import io.fabric8.kubernetes.client.{CustomResourceList, KubernetesClient, KubernetesClientException}
import io.fabric8.kubernetes.internal.KubernetesDeserializer

import scala.jdk.CollectionConverters._

private[freya] object Deployer extends LazyLogging {

  def deployCrd[F[_]: Sync, T](
    client: KubernetesClient,
    cfg: OperatorCfg.Crd[T],
    isOpenShift: Option[Boolean]
  ): F[CustomResourceDefinition] =
    for {
      _ <- Sync[F].delay(Serialization.jsonMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false))

      kind = cfg.getKind
      crds <- Sync[F].delay(
        CrdApi.list(client).filter(p => kind == p.getSpec.getNames.getKind && cfg.prefix == p.getSpec.getGroup)
      )

      crd <- crds match {
        case h :: _ =>
          Sync[F].delay(
            logger
              .info(s"CustomResourceDefinition for $kind has been found in the K8s, so we are skipping its creation.")
          ) *>
              h.pure[F]
        case Nil if cfg.deployCrd =>
          createCrd[F, T](
            client,
            cfg.prefix,
            kind,
            cfg.shortNames,
            cfg.getPluralCaseInsensitive,
            cfg.additionalPrinterColumns,
            cfg.forKind,
            isOpenShift
          )
        case _ =>
          Sync[F].raiseError[CustomResourceDefinition](
            new RuntimeException(s"CustomResourceDefinition for $kind no found. Auto-deploy is disabled.")
          )
      }

      _ <- Sync[F].delay {
        // register the new crd for json serialization
        val apiVersion = s"${cfg.prefix}/${crd.getSpec.getVersion}"
        KubernetesDeserializer.registerCustomKind(apiVersion, kind, classOf[SpecClass])
        KubernetesDeserializer.registerCustomKind(
          apiVersion,
          s"${kind}List",
          classOf[CustomResourceList[_ <: HasMetadata]]
        )
      }
    } yield crd

  private def createCrd[F[_]: Sync, T](
    client: KubernetesClient,
    apiPrefix: String,
    kind: String,
    shortNames: List[String],
    pluralName: String,
    additionalPrinterColumns: List[AdditionalPrinterColumn],
    infoClass: Class[T],
    isOpenshift: Option[Boolean]
  ) =
    for {
      _ <- Sync[F].delay(logger.info(s"Creating CustomResourceDefinition for $kind."))
      jsonSchema <- Sync[F].delay(JSONSchemaReader.readSchema(infoClass))

      builder = {
        val crdBuilder = jsonSchema match {
          case Some(s) =>
            val schema = removeDefaultValues(s)
            CrdApi
              .getCrdBuilder(apiPrefix, kind, shortNames, pluralName)
              .withNewValidation
              .withNewOpenAPIV3SchemaLike(schema)
              .endOpenAPIV3Schema
              .endValidation
          case None =>
            CrdApi.getCrdBuilder(apiPrefix, kind, shortNames, pluralName)
        }

        additionalPrinterColumns match {
          case Nil => crdBuilder
          case _ :: _ =>
            additionalPrinterColumns.foldLeft(crdBuilder) {
              case (acc, c) =>
                acc
                  .addNewAdditionalPrinterColumn()
                  .withName(c.name)
                  .withType(c.columnType)
                  .withJSONPath(c.jsonPath)
                  .endAdditionalPrinterColumn
            }
        }
      }

      crd <- Sync[F].delay {
        val crd = builder.endSpec.build
        // https://github.com/fabric8io/kubernetes-client/issues/1486
        jsonSchema.foreach(_ => crd.getSpec.getValidation.getOpenAPIV3Schema.setDependencies(null))
        CrdApi.createOrReplace(client, crd)
        crd
      }.recover {
        case e: KubernetesClientException =>
          logger.error("Error when submitting CR definition", e)
          logger.warn(
            s"Consider upgrading the $re{}$xx. Probably, your version doesn't support schema validation for custom resources.",
            if (isOpenshift.contains(true)) "OpenShift"
            else "Kubernetes"
          )
          val crd = CrdApi.getCrdBuilder(apiPrefix, kind, shortNames, pluralName).endSpec.build
          CrdApi.createOrReplace(client, crd)
      }
    } yield crd

  private def removeDefaultValues(schema: JSONSchemaProps): JSONSchemaProps =
    Option(schema) match {
      case None => schema
      case Some(_) =>
        val newSchema = new JSONSchemaPropsBuilder(schema).build()
        newSchema.setDefault(null)
        Option(newSchema.getProperties).foreach { map =>
          for (prop <- map.values.asScala) {
            removeDefaultValues(prop)
          }
        }
        newSchema
    }
}
