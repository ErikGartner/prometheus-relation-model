package com.sony.prometheus.stages

import java.util

import com.sony.prometheus.Prometheus
import com.sony.prometheus.utils.Utils.pathExists
import org.apache.log4j.LogManager
import org.apache.spark.SparkContext
import org.apache.spark.mllib.linalg.{Vector, Vectors}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.SQLContext
import org.nd4j.linalg.dataset.DataSet
import org.nd4j.linalg.factory.Nd4j

import scala.collection.JavaConversions._
import scala.collection.mutable.ListBuffer
import scala.util.Random

/**
  * Transform string features into numerical vector suitable for model training. Depends on a number of encoders to
  * encode the features.
  * @param word2VecData           for Word2Vec encoding
  * @param posEncoderStage        to encode Part-Of-Speech tags
  * @param neTypeEncoder          to encode Named Entity types
  * @param dependencyEncoderStage to encode dependency parse
  * @param featureExtractorStage  to provide the string features
  * @param configData             to provide meta information about the relations
  */
class FeatureTransformerStage(path: String, word2VecData: Word2VecData, posEncoderStage: PosEncoderStage,
                              neTypeEncoder: NeTypeEncoderStage, dependencyEncoderStage: DependencyEncoderStage,
                              featureExtractorStage: FeatureExtractorStage, configData: RelationConfigData)
                             (implicit sqlContext:SQLContext, sparkContext: SparkContext) extends Task with Data{
  /**
    * Runs the task, saving results to disk
    */
  override def run(): Unit = {

    val data = FeatureExtractor.load(featureExtractorStage.getData())
    val labelNames: util.List[String] = ListBuffer(
      data.map(d => (d.relationClass, d.relationId)).distinct().collect().sortBy(_._1).map(_._2).toList: _*)

    val numClasses = RelationConfigReader.load(configData.getData()).size + 1
    val balancedData = FeatureTransformer.balanceNegatives(data)

    val featureTransformer = sqlContext.sparkContext.broadcast(
      FeatureTransformer(word2VecData.getData(), posEncoderStage.getData(), neTypeEncoder.getData(),
                         dependencyEncoderStage.getData()))
    balancedData.map(d => {
      val vector = featureTransformer.value.toFeatureVector(
        d.wordFeatures, d.posFeatures, d.wordsBetween, d.posBetween, d.ent1PosTags, d.ent2PosTags, d.ent1Type, d.ent2Type, d.dependencyPath,
        d.ent1DepWindow, d.ent2DepWindow, d.ent1IsSubject
      ).toArray.map(_.toFloat)
      val features = Nd4j.create(vector)
      val label = Nd4j.create(featureTransformer.value.oneHotEncode(Seq(d.relationClass.toInt), numClasses).toArray)
      val dataset = new DataSet(features, label)
      dataset.setLabelNames(labelNames)
      dataset
    }).saveAsObjectFile(path)
    featureTransformer.destroy()

  }

  override def getData(): String = {
    if (!pathExists(path)) {
      run()
    }
    path
  }

}

/** Used for creating a FeatureTransformer
 */
object FeatureTransformer {
  val WORDS_BETWEEN_SIZE = 10

  val log = LogManager.getLogger(classOf[FeatureTransformer])

  def apply(pathToWord2Vec: String, pathToPosEncoder: String, pathToNeType: String, pathToDepEncoder: String)
           (implicit sqlContext: SQLContext): FeatureTransformer = {
    val posEncoder = StringIndexer.load(pathToPosEncoder, sqlContext.sparkContext)
    val word2vec = Word2VecEncoder.apply(pathToWord2Vec)
    val neType = StringIndexer.load(pathToNeType, sqlContext.sparkContext)
    val depEncoder = StringIndexer.load(pathToDepEncoder, sqlContext.sparkContext)
    new FeatureTransformer(word2vec, posEncoder, neType, depEncoder)
  }

  def load(path: String)(implicit sqlContext: SQLContext): RDD[DataSet] = {
    sqlContext.sparkContext.objectFile[DataSet](path)
  }

  def balanceNegatives(rawData: RDD[TrainingDataPoint]): RDD[TrainingDataPoint] = {

    val classCount = rawData.map(d => d.relationClass).countByValue()
    val posClasses = classCount.filter(_._1 != FeatureExtractor.NEGATIVE_CLASS_NBR)
    val sampleTo = posClasses.map(_._2).max * (classCount.keys.size - 1)

    log.info(s"Rebalancing negative examples to 50%")
    classCount.foreach(pair => log.info(s"\tClass ${pair._1}: ${pair._2}"))

    /* Resample positive classes */
    val balancedDataset = classCount.map{

      case (FeatureExtractor.NEGATIVE_CLASS_NBR, count: Long) =>
        /* Make all positive classes equally big and our two negative types ,*/
        val samplePercentage = sampleTo / count.toDouble
        val replacement = sampleTo > count
        rawData.filter(d => d.relationClass == FeatureExtractor.NEGATIVE_CLASS_NBR).sample(replacement, samplePercentage)

      case (key: Long, count: Long) =>
        rawData.filter(d => d.relationClass == key)

    }.reduce(_++_)

    log.info("Balanced result:")
    balancedDataset.map(d => d.relationClass + d.pointType.toString).countByValue().foreach(pair => log.info(s"\tClass ${pair._1}: ${pair._2}"))
    balancedDataset.repartition(Prometheus.DATA_PARTITIONS)
    balancedDataset.mapPartitions(Random.shuffle(_))
  }

}

class FeatureTransformer(wordEncoder: Word2VecEncoder, posEncoder: StringIndexer,
                         neTypeEncoder: StringIndexer, dependencyEncoder: StringIndexer) extends Serializable {

  val DEPENDENCY_FEATURE_SIZE = 8

  lazy val emptyDependencyVector = oneHotEncode(Seq(0), dependencyEncoder.vocabSize()).toArray ++
                                  wordEncoder.emptyVector.toArray ++
                                  Array(0.0)

  def oneHotEncode(features: Seq[Int], vocabSize: Int): Vector = {
    val f = features.distinct.map(idx => (idx, 1.0))
    Vectors.sparse(vocabSize, f)
  }

  /** Creates a unified vector with all the features
    * @param  wordFeatures  the word features, a Seq of words
    * @param  posFeatures   the part-of-speech tags for the word features
    * @param  ent1TokensPos the part-of-speech tags for entity1's tokens
    * @param  ent2TokensPos the part-of-speech tags for entity2's tokens
    * @return a unified feature vector
    */
  def toFeatureVector(wordFeatures: Seq[String], posFeatures: Seq[String], wordsBetween: Seq[String],
                      posBetween: Seq[String], ent1TokensPos: Seq[String],
                      ent2TokensPos: Seq[String], ent1Type: String, ent2Type: String,
                      dependencyPath: Seq[DependencyPath], ent1DepWindow: Seq[DependencyPath],
                      ent2DepWindow: Seq[DependencyPath], ent1IsSubject: Boolean): Vector = {

    /* Word features */
    val wordVectors = wordFeatures.map(wordEncoder.index).flatMap(_.toArray).toArray

    /* Part of speech features */
    val posVectors = posFeatures.map(posEncoder.index).map(Seq(_))
      .map(oneHotEncode(_, posEncoder.vocabSize()).toArray).flatten.toArray

    val ent1Pos = oneHotEncode(    // eg Seq(ADJ, PROPER_NOUN, PROPER_NOUN) repr. (Venerable Barack Obama)
      ent1TokensPos.map(posEncoder.index),  // eg Seq(0, 2, 2) (index of the POS tags)
      posEncoder.vocabSize()
    ).toArray // one-hot encoded, eg Array(1, 0, 1, 0, 0, ... 0) with length posEncoder.vocabSize()

    val ent2Pos = oneHotEncode(
      ent2TokensPos.map(posEncoder.index),
      posEncoder.vocabSize()
    ).toArray

    /* Sequence of words between the two entities */
    val wordsBetweenVectors = wordsBetween.slice(0, FeatureTransformer.WORDS_BETWEEN_SIZE).map(wordEncoder.index).map(_.toArray)
    val wordsPadding = Seq.fill(FeatureTransformer.WORDS_BETWEEN_SIZE - wordsBetween.size)(wordEncoder.emptyVector.toArray)
    val paddedWordsBetweenVectors = (wordsBetweenVectors ++ wordsPadding).flatten.toArray
    // ... and their POS tags
    val posBetweenVectors = posBetween.slice(0, FeatureTransformer.WORDS_BETWEEN_SIZE).map(posEncoder.index).map(Seq(_))
      .map(oneHotEncode(_, posEncoder.vocabSize()).toArray)
    val posPadding = Seq.fill(FeatureTransformer.WORDS_BETWEEN_SIZE - posBetween.size)(oneHotEncode(Seq(0), posEncoder.vocabSize()).toArray)
    val paddedPosVectors = (posBetweenVectors ++ posPadding).flatten.toArray

    /* Named entity types */
    val neType1 = oneHotEncode(Seq(neTypeEncoder.index(ent1Type)), neTypeEncoder.vocabSize()).toArray
    val neType2 = oneHotEncode(Seq(neTypeEncoder.index(ent1Type)), neTypeEncoder.vocabSize()).toArray

    /* Dependency Path */
    val depPath = dependencyPath.map(d => {
      oneHotEncode(Seq(dependencyEncoder.index(d.dependency)), dependencyEncoder.vocabSize()).toArray ++
        wordEncoder.index(d.word).toArray ++
        (if (d.direction) Array(1.0) else Array(0.0))
    })

    val paddedDepPath = (depPath.slice(0, DEPENDENCY_FEATURE_SIZE) ++
      Seq.fill(DEPENDENCY_FEATURE_SIZE - depPath.size)(emptyDependencyVector)).flatten

    /* Dependency windows */
    val ent1PaddedDepWindow = (ent1DepWindow.map(d => {
      oneHotEncode(Seq(dependencyEncoder.index(d.dependency)), dependencyEncoder.vocabSize()).toArray ++
        wordEncoder.index(d.word).toArray ++
        (if (d.direction) Array(1.0) else Array(0.0))
    }) ++ Seq.fill(FeatureExtractor.DEPENDENCY_WINDOW - ent1DepWindow.size)(emptyDependencyVector)).flatten

    val ent2PaddedDepWindow = (ent2DepWindow.map(d => {
      oneHotEncode(Seq(dependencyEncoder.index(d.dependency)), dependencyEncoder.vocabSize()).toArray ++
        wordEncoder.index(d.word).toArray ++
        (if (d.direction) Array(1.0) else Array(0.0))
    }) ++ Seq.fill(FeatureExtractor.DEPENDENCY_WINDOW - ent2DepWindow.size)(emptyDependencyVector)).flatten

    /* Ordering of entities in relation to their order in the RDF triple */
    val order = if(ent1IsSubject) Array(1.0) else Array(0.0)

    Vectors.dense(wordVectors ++ posVectors ++ paddedWordsBetweenVectors ++ paddedPosVectors ++ ent1Pos ++ ent2Pos
      ++ neType1 ++ neType2 ++ paddedDepPath ++ ent1PaddedDepWindow  ++ ent2PaddedDepWindow ++ order)
  }
}
