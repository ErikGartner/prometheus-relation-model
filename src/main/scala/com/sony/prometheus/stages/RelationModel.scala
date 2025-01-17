package com.sony.prometheus.stages

import org.apache.log4j.LogManager
import org.apache.spark.mllib.classification.LogisticRegressionModel
import org.apache.spark.mllib.linalg.Vector
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.SQLContext
import org.deeplearning4j.nn.multilayer.MultiLayerNetwork
import org.nd4j.linalg.factory.Nd4j

/** Provides the RelationModel classifier
 */
object RelationModel {

  val THRESHOLD = 0.75

  val log = LogManager.getLogger(classOf[RelationModel])

  def splitToTestTrain[T](data: RDD[T], testPercentage: Double = 0.1): (RDD[T], RDD[T]) = {
    log.info(s"Splitting data into ${1 - testPercentage}:$testPercentage")
    val splits = data.randomSplit(Array(1 - testPercentage, testPercentage))
    (splits(0), splits(1))
  }

  def balanceData[T](rawData: RDD[T], underSample: Boolean = false, getClass: (T) => Long): RDD[T] = {

    val classCount = rawData.map(getClass(_)).countByValue()
    val posClasses = classCount.filter(_._1 != FeatureExtractor.NEGATIVE_CLASS_NBR)
    val sampleTo = if (underSample) posClasses.map(_._2).min else posClasses.map(_._2).max

    log.info(s"Rebalancing dataset (${if (underSample) "undersample" else "oversample"})")
    classCount.foreach(pair => log.info(s"\tClass ${pair._1}: ${pair._2}"))

    /* Resample postive classes */
    val balancedDataset = classCount.map{
      case (key: Long, count: Long) =>
        val samplePercentage = sampleTo / count.toDouble
        val replacement = sampleTo > count
        rawData.filter(getClass(_) == key).sample(replacement, samplePercentage)

    }.reduce(_++_)

    log.info("Balanced result:")
    balancedDataset.map(getClass(_)).countByValue().foreach(pair => log.info(s"\tClass ${pair._1}: ${pair._2}"))
    balancedDataset //.repartition(Prometheus.DATA_PARTITIONS)
  }

  def apply(filterModelStage: FilterModelStage, classificationModelStage: ClassificationModelStage,
            threshold: Double = RelationModel.THRESHOLD) (implicit sqlContext: SQLContext): RelationModel = {


    val filterModel = FilterModel.load(filterModelStage.getData())
    val classificationModel = ClassificationModel.load(classificationModelStage.getData())
    new RelationModel(filterModel, classificationModel, threshold)
  }

}

/**
  * Combines the filter model and the classification model to make predictions
  */
class RelationModel(val filterModel: LogisticRegressionModel, val classModel: MultiLayerNetwork,
                    val threshold: Double) extends Serializable {

  filterModel.clearThreshold()
  classModel.conf().setUseDropConnect(false)
  classModel.conf().setUseRegularization(false)

  def predict(vector: Vector, threshold: Double): Prediction = {
    val filterProb = filterModel.predict(vector)
    classify(vector, filterProb, threshold)
  }

  def predict(vectors: RDD[Vector], threshold: Double): RDD[Prediction] = {
    vectors.zip(filterModel.predict(vectors)).map(t => classify(t._1, t._2, threshold))
  }

  private def classify(vector: Vector, filterProb: Double, threshold: Double) = {
    if(filterProb < threshold) {
      Prediction(FeatureExtractor.NEGATIVE_CLASS_NBR, 1 - filterProb, filterProb, 0.0)
    } else {
      val vec = Nd4j.create(vector.toArray)

      val output = classModel.output(vec, false)
      val cls = Nd4j.argMax(output).getInt(0)
      val prob = output.getDouble(cls)

      val combinedProb = filterProb * prob

      if(combinedProb < threshold){
        Prediction(FeatureExtractor.NEGATIVE_CLASS_NBR, (1 - combinedProb), filterProb, prob)
      } else {
        Prediction(cls, combinedProb, filterProb, prob)
      }
    }
  }

}

case class Prediction(clsIdx: Int, probability: Double, filterProb: Double, classProb: Double)
