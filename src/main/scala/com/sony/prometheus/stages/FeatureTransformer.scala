package com.sony.prometheus.stages

import java.util

import com.sony.prometheus.Prometheus
import org.apache.spark.SparkContext
import org.apache.spark.broadcast.Broadcast
import scala.collection.JavaConversions._
import org.apache.spark.mllib.linalg.{Vector, Vectors}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.SQLContext
import se.lth.cs.docforia.Document
import com.sony.prometheus.utils.Utils.pathExists
import org.apache.log4j.LogManager
import org.nd4j.linalg.factory.Nd4j
import org.nd4j.linalg.dataset.DataSet

import scala.collection.mutable.ListBuffer

class FeatureTransfomerStage(path: String, word2VecData: Word2VecData, posEncoderStage: PosEncoderStage,
                             neTypeEncoder: NeTypeEncoderStage, featureExtractorStage: FeatureExtractorStage)
                            (implicit sqlContext:SQLContext, sparkContext: SparkContext) extends Task with Data{
  /**
    * Runs the task, saving results to disk
    */
  override def run(): Unit = {

    val data = FeatureExtractor.load(featureExtractorStage.getData())
    val labelNames:util.List[String] = ListBuffer(
      data.map(d => (d.relationClass, d.relationId)).distinct().collect().sortBy(_._1).map(_._2).toList: _*)
    val numClasses = data.map(d => d.relationClass).distinct().count().toInt
    val balancedData = FeatureTransformer.balanceData(data, false)

    val featureTransformer = sqlContext.sparkContext.broadcast(
      FeatureTransformer(word2VecData.getData(), posEncoderStage.getData(), neTypeEncoder.getData()))
    balancedData.map(d => {
      val vector = featureTransformer.value.toFeatureVector(
        d.wordFeatures, d.posFeatures, d.ent1PosTags, d.ent2PosTags, d.ent1Type, d.ent2Type
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

  val log = LogManager.getLogger(classOf[FeatureTransformer])

  def apply(pathToWord2Vec: String, pathToPosEncoder: String, pathToNeType: String)
           (implicit sqlContext: SQLContext): FeatureTransformer = {
    val posEncoder = StringIndexer.load(pathToPosEncoder, sqlContext.sparkContext)
    val word2vec = Word2VecEncoder.apply(pathToWord2Vec)
    val neType = StringIndexer.load(pathToNeType, sqlContext.sparkContext)
    new FeatureTransformer(word2vec, posEncoder, neType)
  }

  def load(path: String)(implicit sqlContext: SQLContext): RDD[DataSet] = {
    sqlContext.sparkContext.objectFile[DataSet](path)
  }

  /**
    * Rebalances an imbalanced dataset. Either undersample or oversample.
    * Balances to match biggest or smallest class, excluding class 0, i.e. the negative class.
    */
  def balanceData(rawData: RDD[TrainingDataPoint], underSample: Boolean = false): RDD[TrainingDataPoint] = {

    log.info(s"Rebalancing dataset (${if (underSample) "undersample" else "oversample"})")
    val classCount = rawData.map(d => d.relationClass).countByValue()
    val realClasses = classCount.filter(_._1 != 0)
    val sampleTo = if (underSample) realClasses.map(_._2).min else realClasses.map(_._2).max
    classCount.foreach(pair => log.info(s"\tClass ${pair._1}: ${pair._2} => ${sampleTo}"))

    val balancedDataset = classCount.map{
      case (key:Long, count: Long) =>
        val samplePercentage = sampleTo / count.toDouble
        val replacement = sampleTo > count
        rawData.filter(d => d.relationClass == key).sample(replacement, samplePercentage)
    }.reduce(_.union(_))

    balancedDataset.repartition(Prometheus.DATA_PARTITIONS)
  }

}

/**
 */
class FeatureTransformer(val wordEncoder: Word2VecEncoder, val posEncoder: StringIndexer,
                         val neTypeEncoder: StringIndexer) extends Serializable {

  def transformWords(tokens: Seq[String]): Seq[Vector] = {
    tokens.map(wordEncoder.index)
  }

  def transformPos(pos: Seq[String]): Seq[Int] = {
    pos.map(posEncoder.index)
  }

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
  def toFeatureVector(wordFeatures: Seq[String], posFeatures: Seq[String], ent1TokensPos: Seq[String],
                      ent2TokensPos: Seq[String], ent1Type: String, ent2Type: String): Vector = {
    val wordVectors = wordFeatures.map(wordEncoder.index).map(_.toArray).flatten.toArray
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

    val neType1 = oneHotEncode(Seq(neTypeEncoder.index(ent1Type)), neTypeEncoder.vocabSize()).toArray
    val neType2 = oneHotEncode(Seq(neTypeEncoder.index(ent1Type)), neTypeEncoder.vocabSize()).toArray

    Vectors.dense(wordVectors ++ posVectors ++ ent1Pos ++ ent2Pos ++ neType1 ++ neType2)
  }
}