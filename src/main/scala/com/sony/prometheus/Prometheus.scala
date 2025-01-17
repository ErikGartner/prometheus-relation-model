package com.sony.prometheus

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import com.sony.prometheus.evaluation._
import com.sony.prometheus.interfaces._
import com.sony.prometheus.stages._
import com.sony.prometheus.utils.Utils.Colours._
import org.apache.log4j.{Level, LogManager, Logger}
import org.apache.spark.sql.SQLContext
import org.apache.spark.{SparkConf, SparkContext}
import org.http4s.server.blaze._
import org.rogach.scallop._
import org.rogach.scallop.exceptions._

import scala.util.Properties.envOrNone

/** Main class, sets up and runs the pipeline
 */
object Prometheus {

  val DATA_PARTITIONS = 432
  val PORT = 8080
  val DEFAULT_EPOCHS = 5
  var conf: Conf = null

  /** Provides argument parsing
   */
  class Conf(arguments: Seq[String]) extends ScallopConf(arguments) {
    version("Prometheus Model Trainer")
    banner("""Usage: Prometheus [options] corpus-path config-file wikidata-path temp-data-path word2vecPath
           |Prometheus model trainer trains a relation extractor
           |Options:
           |""".stripMargin)
    /* Trailing args */
    val corpusPath = trailArg[String](
      descr = "path to the corpus to train on",
      validate = pathPrefixValidation)
    val relationConfig = trailArg[String](
      descr = "path to a TSV listing the desired relations to train for",
      validate = pathPrefixValidation)
    val wikiData = trailArg[String](
      descr = "path to the wikidata dump in parquet",
      validate = pathPrefixValidation)
    val tempDataPath = trailArg[String](
      descr= "path to a directory that will contain intermediate results",
      validate = pathPrefixValidation)
    val word2vecPath = trailArg[String](
      descr = "path to a word2vec model in the C binary format",
      validate = pathPrefixValidation)

    /* Options */
    val sampleSize = opt[Double](
      descr = "use this to sample a fraction of the corpus",
      validate = x => (x > 0 && x <= 1),
      default = Some(1.0))
    val probabilityCutoff = opt[Double](
      descr = "use this to set the cutoff probability for extractions",
      validate = x => (x >= 0 && x <= 1),
      default = Some(RelationModel.THRESHOLD))
    val corefs = opt[Boolean](
      descr = "enable co-reference resolutions for annotation",
      default = Some(false))
    val demoServer = opt[Boolean](
      descr = "start an HTTP server to receive text to extract relations from")
    val modelEvaluationFiles = opt[List[String]](descr = "path to model evaluation files")
    val dataEvaluation = opt[Boolean](descr = "flag to evaluate extractions against Wikidata")
    val epochs = opt[Int](
      descr = "number of epochs for neural network",
      validate = x => x >= 0,
      default = Some(DEFAULT_EPOCHS))
    val language = opt[String](
      default = Some("en"),
      validate = l => l == "sv" || l == "en",
      descr = "the language to use for the pipeline (defaults to en)")
    val stage = opt[String](
      descr = """how far to run the program, [preprocess|train|full]
                |train implies preprocess
                |full implies train
                |""".stripMargin,
      validate = s => s == "preprocess" || s == "train" || s == "full",
      default = Some("full"))
    val name = opt[String](
      descr = "Custom Spark application name",
      default = Some("")
    )
    val modelSuffix = opt[String](
      descr = "Custom prefix for the model directories",
      default = Some("")
    )

    verify()

    override def onError(e: Throwable): Unit = e match {
      case ScallopException(message) =>
        println(message)
        printHelp
        sys.exit(1)
      case ex => super.onError(ex)
    }

    private def pathPrefixValidation(path: String): Boolean = {
      path.split(":") match {
        case Array("hdfs", _) => true
        case Array("file", _) => true
        case Array("s3", _) => true
        case _ => {
          System.err.println(s"""$path must be prefixed with either "hdfs:" or "file: or s3:"""")
          false
        }
      }
    }

  }

  def main(args: Array[String]): Unit = {
    conf = new Conf(args)
    Logger.getLogger("org").setLevel(Level.WARN)
    Logger.getLogger("akka").setLevel(Level.WARN)
    val log = LogManager.getLogger(Prometheus.getClass)
    val appName = s"Prometheus Relation Model ${conf.language()} ${conf.stage()} ${conf.name()}"
    val sparkConf = new SparkConf().setAppName(appName)
    envOrNone("SPARK_MASTER").foreach(m => sparkConf.setMaster(m))

    implicit val sc = new SparkContext(sparkConf)
    implicit val sqlContext = new SQLContext(sc)

    val tempDataPath = conf.tempDataPath() + "/" + conf.language()
    println("Program arguments:")
    println(conf.summary.split("\n").tail.mkString("\n"))

    try {
      val corpusData = new CorpusData(conf.corpusPath(), conf.sampleSize())
      val word2VecData = new Word2VecData(conf.word2vecPath())
      val configData = new RelationConfigData(conf.relationConfig())
      val wikidata = new WikidataData(conf.wikiData())

      // Extract entity pairs that participate in the given relations
      val entityPairs = new EntityPairExtractorStage(
        tempDataPath + "/relation_entity_pairs",
        configData,
        wikidata
      )

      // Extract training sentences
      val trainingExtractorTask = new TrainingDataExtractorStage(
        tempDataPath + "/training_sentences",
        corpusData,
        entityPairs)

      val posEncoderStage = new PosEncoderStage(
        tempDataPath + "/pos_encoder",
        corpusData)

      val neTypeEncoderStage = new NeTypeEncoderStage(
        tempDataPath + "/netype_encoder",
        corpusData)

      val depEncoder = new DependencyEncoderStage(
        tempDataPath + "/dep_encoder",
        corpusData)

      // Extract features from the training data (Strings)
      val featureExtractionTask = new FeatureExtractorStage(
        tempDataPath + "/features",
        trainingExtractorTask,
        configData)

      // Transform features from Strings into vectors of numbers
      val featureTransformerStage = new FeatureTransformerStage(
        tempDataPath + "/vector_features",
        word2VecData,
        posEncoderStage,
        neTypeEncoderStage,
        depEncoder,
        featureExtractionTask,
        configData
      )

      if (conf.stage() == "preprocess") {
        val featuresPath = featureTransformerStage.getData()
        log.info(s"Entity pairs saved to ${featuresPath}")
      } else {

        // Train models
        val classificationModelStage = new ClassificationModelStage(
          tempDataPath + "/classification_model" + conf.modelSuffix(),
          featureTransformerStage,
          conf.epochs()
        )

        val filterModelStage = new FilterModelStage(
          tempDataPath + "/filter_model" + conf.modelSuffix(),
          featureTransformerStage
        )

        if (conf.stage() == "train") {
          filterModelStage.getData()
          classificationModelStage.getData()
          log.info(s"Saved model to ${classificationModelStage.getData()} and ${filterModelStage.getData()}")
        } else {

          val relationModel = RelationModel(filterModelStage, classificationModelStage, conf.probabilityCutoff())

          // Evaluate
          conf.modelEvaluationFiles.foreach(evalFiles => {
            log.info("Performing evaluation")
            val predictor = Predictor(relationModel, posEncoderStage, word2VecData, neTypeEncoderStage,
              depEncoder, configData)
            performModelEvaluation(evalFiles, predictor, conf.language(), log, tempDataPath)
          })

          // Serve HTTP API
          if (conf.demoServer()) {
            val predictor = Predictor(relationModel,  posEncoderStage, word2VecData, neTypeEncoderStage, depEncoder, configData)
            try {
              val task = BlazeBuilder
                .bindHttp(PORT, "0.0.0.0")
                .mountService(REST.api(predictor), "/")
                .run
                println(s"${GREEN}REST interface ready to accept connections on $PORT ${RESET}")
                task.awaitShutdown()
              } catch  {
                case e: java.net.BindException => {
                  println(s"${BOLD}${RED}Error:${RESET} ${e.getMessage}")
                  sc.stop()
                  sys.exit(1)
                }
              }
          }

          // Configure data extraction
          val predictorStage = new PredictorStage(
            tempDataPath + "/extractions",
            corpusData,
            relationModel,
            posEncoderStage,
            word2VecData,
            neTypeEncoderStage,
            depEncoder,
            configData)

          // Evaluate extractions against Wikidata
          if (conf.dataEvaluation()) {
            val f = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss")
            val t = LocalDateTime.now()

            val N_BEST = 100
            val dataEvaluation = new DataEvaluationStage(
              tempDataPath + s"/evaluation/${t.format(f)}-wd",
              entityPairs,
              conf.language(),
              predictorStage,
              Some(N_BEST)
            )
            val data = dataEvaluation.getData()
            log.info(s"Saved Wikidata evaluation to $data")
          } else {
            // Force running data extraction
            predictorStage.run()
          }
        }

      }
      log.info("Successfully completed all requested stages!")
    } finally {
      sc.stop()
    }
  }

  private def performModelEvaluation(
    evalFiles: List[String],
    predictor: Predictor,
    lang: String,
    log: Logger,
    tempDataPath: String)
    (implicit sqlContext: SQLContext, sc: SparkContext): Unit = {

    val N_MOST_PROBABLE = 100

    val f = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss")
    val t = LocalDateTime.now()

    evalFiles.foreach(evalFile => {
      log.info(s"Evaluating $evalFile")
      val evaluationData = new ModelEvaluationData(evalFile)
      val evalSavePath = tempDataPath +
        s"/evaluation/${t.format(f)}-${evalFile.split("/").last.split(".json")(0)}"
      val evaluationTask = new ModelEvaluatorStage(
        evalSavePath,
        evaluationData,
        lang,
        predictor,
        Some(N_MOST_PROBABLE))
      val _ = evaluationTask.getData()
      log.info(s"Saved evaluation to $evalSavePath.tsv")
    })
  }
}

