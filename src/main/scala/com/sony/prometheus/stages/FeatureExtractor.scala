package com.sony.prometheus.stages

import com.sony.prometheus._
import com.sony.prometheus.utils.Pruner
import com.sony.prometheus.utils.Utils.pathExists
import org.apache.log4j.LogManager
import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.SQLContext
import se.lth.cs.docforia.Document
import se.lth.cs.docforia.graph.disambig.NamedEntityDisambiguation
import se.lth.cs.docforia.graph.text.{DependencyRelation, NamedEntity, Token}
import se.lth.cs.docforia.query.QueryCollectors

import scala.collection.JavaConverters._
import scala.util.Random

/** Extracts features from the sentences provided by `trainingDataExtractor`
  * @param trainingDataExtractor  to provide the training sentences
  * @param relationConfigData     used to prune training data - check type of entities etc
  */
class FeatureExtractorStage(
   path: String,
   trainingDataExtractor: TrainingDataExtractorStage,
   relationConfigData: RelationConfigData)
   (implicit sqlContext: SQLContext, sc: SparkContext) extends Task with Data {

  override def getData(): String = {
    if (!pathExists(path)) {
      run()
    }
    path
  }

  override def run(): Unit = {
    val trainingSentences = TrainingDataExtractor.load(trainingDataExtractor.getData())
    val data = FeatureExtractor.trainingData(trainingSentences, relationConfigData.getData())
    FeatureExtractor.save(data, path)
  }
}

/** Extracts features for training/prediction
 */
object FeatureExtractor {

  val EMPTY_TOKEN = "<empty>"
  val NBR_WORDS_BEFORE = 3
  val NBR_WORDS_AFTER = 3
  val MIN_FEATURE_LENGTH = 0
  val DEPENDENCY_WINDOW = 1
  val NEGATIVE_CLASS_NAME = "neg"
  val NEGATIVE_CLASS_NBR = 0

  val DATA_TYPE_POS = "pos"
  val DATA_TYPE_NEG = "neg"
  val DATA_TYPE_NEARPOS = "nearpos"
  val log = LogManager.getLogger(FeatureExtractor.getClass)

  /** Returns an RDD of [[TrainingDataPoint]]
    *
    * Use this to collect training data for [[RelationModel]]
    *
    * @param trainingSentences  - an RDD of [[TrainingSentence]]
    */
  def trainingData(trainingSentences: RDD[TrainingSentence], relationConfigPath: String)
                  (implicit sqlContext: SQLContext): RDD[TrainingDataPoint] = {
    val trainingPoints = trainingSentences.flatMap(t => {
      val featureArrays = featureArray(t.sentenceDoc).flatMap(f => {
          val positiveExample = t.entityPair.exists(p => {
            p.source == f.subj && p.dest == f.obj
          })
          if (positiveExample && t.relationClass != NEGATIVE_CLASS_NBR) {
            Seq(TrainingDataPoint(
              t.relationId,
              t.relationName,
              t.relationClass,
              DATA_TYPE_POS,
              f.wordFeatures,
              f.posFeatures,
              f.wordsBetween,
              f.posBetween,
              f.ent1PosFeatures,
              f.ent2PosFeatures,
              f.ent1Type,
              f.ent2Type,
              f.dependencyPath,
              f.ent1DepWindow,
              f.ent2DepWindow,
              f.ent1IsSubject))
          } else {
            Seq(TrainingDataPoint(
              NEGATIVE_CLASS_NAME,
              NEGATIVE_CLASS_NAME,
              NEGATIVE_CLASS_NBR,
              if(t.positive) DATA_TYPE_NEARPOS else DATA_TYPE_NEG,
              f.wordFeatures,
              f.posFeatures,
              f.wordsBetween,
              f.posBetween,
              f.ent1PosFeatures,
              f.ent2PosFeatures,
              f.ent1Type,
              f.ent2Type,
              f.dependencyPath,
              f.ent1DepWindow,
              f.ent2DepWindow,
              f.ent1IsSubject))
          }
      })
      featureArrays
    })

    val pruner = Pruner(relationConfigPath)
    val data = pruner.value.pruneTrainingData(trainingPoints).repartition(Prometheus.DATA_PARTITIONS)
    data
  }

  /** Returns an RDD of [[TestDataPoint]]
    *
    *   Use this to collect test data for [[RelationModel]]
    *
    *   @param sentences  - a Seq of Docforia Documents
    */
  def testData(sentences: Seq[Document])(implicit sqlContext: SQLContext): Seq[TestDataPoint] = {

    val testPoints = sentences.flatMap(sentence => {
      featureArray(sentence).map(f => {
        TestDataPoint(
          sentence,
          f.subj,
          f.obj,
          f.wordFeatures,
          f.posFeatures,
          f.wordsBetween,
          f.posBetween,
          f.ent1PosFeatures,
          f.ent2PosFeatures,
          f.ent1Type,
          f.ent2Type,
          f.dependencyPath,
          f.ent1DepWindow,
          f.ent2DepWindow,
          f.ent1IsSubject)
      })
    })

    testPoints
  }

  private def featureArray(sentence: Document): Seq[FeatureArray] = {

    val NED = NamedEntityDisambiguation.`var`()
    val NE = NamedEntity.`var`()
    val T = Token.`var`()

    sentence.nodes(classOf[Token])
      .asScala
      .toSeq
      .sortBy(_.getStart)
      .zipWithIndex
      .foreach(t => t._1.putTag("idx", t._2))

    val features = sentence.select(NED, T, NE)
      .where(T)
      .coveredBy(NED)
      .where(T)
      .coveredBy(NE)
      .stream()
      .collect(QueryCollectors.groupBy(sentence, NED, NE).values(T).collector())
      .asScala
      .toSet
      .subsets(2)
      .filter(set => {
        /* Filter out relations on same person */
        val grp1 :: grp2 :: _ = set.toList
        grp1.key(NED).getIdentifier != grp2.key(NED).getIdentifier
      })
      .flatMap(set => {
        /*
        Find the positions of the entities
         */
        val grp1 :: grp2 :: _ = set.toList.sortBy(_.value(0, T).getStart)

        val start1 = grp1.value(0, T).getTag("idx"): Int
        val end1 = grp1.value(grp1.size() - 1, T).getTag("idx"): Int

        val start2 = grp2.value(0, T).getTag("idx"): Int
        val end2 = grp2.value(grp2.size() - 1, T).getTag("idx"): Int

        /* Windows of words and POS */
        val words = tokenWindow(sentence, start1, end1, start2, end2, t => t.text)
        val pos = tokenWindow(sentence, start1, end1, start2, end2, t => t.getPartOfSpeech)

        /* Sequence of words between the entities */
        val wordsBetween = wordSequenceBetween(sentence, end1, start2, t => t.text).toList
        val posBetween = wordSequenceBetween(sentence, end1, start2, t => t.getPartOfSpeech).toList

        /* Entity POS */
        val ent1TokensPos = grp1.nodes[Token](T).asScala.toSeq.map(t => t.getPartOfSpeech).toArray
        val ent2TokensPos = grp2.nodes[Token](T).asScala.toSeq.map(t => t.getPartOfSpeech).toArray

        /* Entity Mention Type */
        val ent1Type = if (grp1.key(NE).hasLabel) grp1.key(NE).getLabel else "<missing label>"
        val ent2Type = if (grp2.key(NE).hasLabel) grp2.key(NE).getLabel else "<missing label>"

        /* Dependency Path */
        val lastTokenFirstEntity = grp1.value(grp1.size() - 1, T)
        val firstTokenLastEntity =  grp2.value(0, T)
        val depRels = findDependencyPath(lastTokenFirstEntity, Set(), Seq(), firstTokenLastEntity).toList
        val dependencyPath = depRels.map(relationToPath).toList

        /* Dependency Window */
        val ent1DepWindow = Random.shuffle(dependencyWindow(lastTokenFirstEntity, depRels).map(relationToPath)).take(DEPENDENCY_WINDOW).toSeq
        val ent2DepWindow = Random.shuffle(dependencyWindow(firstTokenLastEntity, depRels).map(relationToPath)).take(DEPENDENCY_WINDOW).toSeq

        Seq(
          FeatureArray(
            sentence,
            grp1.key(NED).getIdentifier.split(":").last,
            grp2.key(NED).getIdentifier.split(":").last,
            words,
            pos,
            wordsBetween,
            posBetween,
            ent1TokensPos,
            ent2TokensPos,
            ent1Type,
            ent2Type,
            dependencyPath.slice(0, dependencyPath.size),
            ent1DepWindow,
            ent2DepWindow,
            true),
          FeatureArray(
            sentence,
            grp2.key(NED).getIdentifier.split(":").last,
            grp1.key(NED).getIdentifier.split(":").last,
            words,
            pos,
            wordsBetween,
            posBetween,
            ent1TokensPos,
            ent2TokensPos,
            ent1Type,
            ent2Type,
            dependencyPath.slice(0, dependencyPath.size),
            ent1DepWindow,
            ent2DepWindow,
            false)
        )
      }).toSeq

    features

  }




  /** Finds the dependency window of an entity. I.e. Dependency relations that connected to the entity not
    * part of the dependency path.
    */
  private def dependencyWindow(entity: Token, dependencyPath: Seq[DependencyRelation]): Set[DependencyRelation] = {
    val excluded: Set[Token] = dependencyPath.flatMap(p => {Seq(p.getHead[Token], p.getTail[Token])}).toSet
    entity.connectedEdges(classOf[DependencyRelation]).toList.asScala.filter(d => {
      (!excluded.contains(d.getTail[Token]) || !excluded.contains(d.getHead[Token]))
    }).toSet
  }

  private def relationToPath(d: DependencyRelation): DependencyPath = {
    DependencyPath(d.getRelation, d.getHead[Token].text, d.getHead[Token].getStart < d.getTail[Token].getStart)
  }


  /**
    * This methods chunking causes problem with the dependency path feature.
    * Do not use before fixing those issues.
    */
  private def chunkEntities(doc: Document): Document = {
    val NED = NamedEntityDisambiguation.`var`()
    val T = Token.`var`()
    val nedGroups = doc.select(NED, T).where(T).coveredBy(NED)
      .stream()
      .collect(QueryCollectors.groupBy(doc, NED).values(T).collector())
      .asScala
      .toList

    nedGroups.foreach(pg => {
      pg.key(NED).getIdentifier
      pg.value(0, T).text
      val values = pg.nodes(T).asScala
      if (values.size > 1){
        val head = values.head
        val last = values.last
        head.setRange(head.getStart, last.getEnd)
        values.tail.foreach(doc.remove)
      }
    })
    doc
  }

  /** Extract the sequence of words between the two entities, f is the transformation of the tokens into String
    * (e.g. POS or the actual word)
    */
  private def wordSequenceBetween(sentence: Document, end1: Int, start2: Int, f: Token => String): Seq[String] = {
    sentence
      .nodes(classOf[Token]).asScala.toSeq.slice(end1 + 1, start2)
      .map(f)
      .slice(0, FeatureTransformer.WORDS_BETWEEN_SIZE)
  }

  /** Extract string features from a Token window around two entities.
    */
  private def tokenWindow(sentence: Document, start1: Int, end1: Int, start2: Int, end2: Int, f: Token => String): Seq[String] = {
    /*
      Extract words before and after entity 1
     */
    val wordsBefore1 = sentence.nodes(classOf[Token]).asScala.toSeq.slice(start1 - NBR_WORDS_BEFORE, start1)
    val wordsAfter1 = sentence.nodes(classOf[Token]).asScala.toSeq.slice(end1 + 1, end1 + NBR_WORDS_AFTER + 1)

    /*
      Extract words before and after entity 2
     */
    val wordsBefore2 = sentence.nodes(classOf[Token]).asScala.toSeq.slice(start2 - NBR_WORDS_BEFORE, start2)
    val wordsAfter2 = sentence.nodes(classOf[Token]).asScala.toSeq.slice(end2 + 1, end2 + NBR_WORDS_AFTER + 1)

    /*
      Create string feature vector for the pair
     */
    val features = Seq(
      Seq.fill(NBR_WORDS_BEFORE - wordsBefore1.length)(EMPTY_TOKEN) ++ wordsBefore1.map(f),
      /* We use word sequence between instead */
      wordsAfter1.map(f) ++ Seq.fill(NBR_WORDS_AFTER - wordsAfter1.length)(EMPTY_TOKEN),
      Seq.fill(NBR_WORDS_BEFORE - wordsBefore2.length)(EMPTY_TOKEN) ++ wordsBefore2.map(f),
      wordsAfter2.map(f) ++ Seq.fill(NBR_WORDS_AFTER - wordsAfter2.length)(EMPTY_TOKEN)
    ).flatten
    features
  }

  /** Find the Dependency Path between two tokens using DFS.
    */
  def findDependencyPath(current: Token, visited: Set[Token], chain: Seq[DependencyRelation], target: Token): Seq[DependencyRelation] = {
    if(current == target) {
      chain
    }else if(visited.contains(current)){
      Seq()
    }else{
      val deps = current.connectedEdges(classOf[DependencyRelation]).toList.asScala
      val newVisited = visited + current
      deps.flatMap(d => {
        findDependencyPath(d.getHead[Token], newVisited, chain :+ d, target) ++
          findDependencyPath(d.getTail[Token], newVisited, chain :+ d, target)
      })
    }
  }

  /** Saves the training data to the path
   */
  def save(data: RDD[TrainingDataPoint], path: String)(implicit sqlContext: SQLContext): Unit = {
    import sqlContext.implicits._
    data.toDF().write.json(path + "_json")
    data.toDF().write.parquet(path)
  }

  /** Loads the data from path
   */
  def load(path: String)(implicit sqlContext: SQLContext): RDD[TrainingDataPoint]  = {
    import sqlContext.implicits._
    sqlContext.read.parquet(path).as[TrainingDataPoint].rdd
  }

}

/**
  * Contains a single dependency. Uses java.lang.Boolean for serializing
  */
case class DependencyPath(dependency: String, word: String, direction: java.lang.Boolean)

case class TrainingDataPoint(
  relationId: String,
  relationName: String,
  relationClass: Long,
  pointType: String,
  wordFeatures: Seq[String],
  posFeatures: Seq[String],
  wordsBetween: Seq[String],
  posBetween: Seq[String],
  ent1PosTags: Seq[String],
  ent2PosTags: Seq[String],
  ent1Type: String,
  ent2Type: String,
  dependencyPath: Seq[DependencyPath],
  ent1DepWindow: Seq[DependencyPath],
  ent2DepWindow: Seq[DependencyPath],
  ent1IsSubject: Boolean)

case class TestDataPoint(
  sentence: Document,
  qidSource: String,
  qidDest: String,
  wordFeatures: Seq[String],
  posFeatures: Seq[String],
  wordsBetween: Seq[String],
  posBetween: Seq[String],
  ent1PosFeatures: Seq[String],
  ent2PosFeatures: Seq[String],
  ent1Type: String,
  ent2Type: String,
  dependencyPath: Seq[DependencyPath],
  ent1DepWindow: Seq[DependencyPath],
  ent2DepWindow: Seq[DependencyPath],
  ent1IsSubject: Boolean)

case class FeatureArray(
  sentence: Document,
  subj: String,
  obj: String,
  wordFeatures: Seq[String],
  posFeatures: Seq[String],
  wordsBetween: Seq[String],
  posBetween: Seq[String],
  ent1PosFeatures: Seq[String],
  ent2PosFeatures: Seq[String],
  ent1Type: String,
  ent2Type: String,
  dependencyPath: Seq[DependencyPath],
  ent1DepWindow: Seq[DependencyPath],
  ent2DepWindow: Seq[DependencyPath],
  ent1IsSubject: Boolean)

