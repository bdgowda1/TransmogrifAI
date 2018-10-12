package com.salesforce.op.stages.impl.feature

import com.salesforce.op._
import com.salesforce.op.dsl.RichTextFeature
import com.salesforce.op.utils.spark.RichDataset._
import com.salesforce.op.OpWorkflow
import com.salesforce.op.features.FeatureLike
import com.salesforce.op.features.types.{Text, _}
import com.salesforce.op.test.{TestFeatureBuilder, TestSparkContext}
import com.salesforce.op.utils.spark.{OpVectorColumnMetadata, OpVectorMetadata}
import org.apache.spark.ml.linalg.Vectors
import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class YoEstimatorTest extends FlatSpec with TestSparkContext with AttributeAsserts {

  lazy val (inputData, f1, f2) = TestFeatureBuilder("text1", "text2",
    Seq[(Text, Text)](
      ("hello world".toText, "Hello world!".toText),
      ("hello world".toText, "What's up".toText),
      ("good evening".toText, "How are you doing, my friend?".toText),
      ("hello world".toText, "Not bad, my friend.".toText),
      (Text.empty, Text.empty)
    )
  )
  val estimator = new YoEstimator().setInput(f1)

  val expectedResult = Seq(
    Vectors.sparse(9, Array(0, 4, 6), Array(1.0, 1.0, 1.0)),
    Vectors.sparse(9, Array(0, 8), Array(1.0, 1.0)),
    Vectors.sparse(9, Array(1, 6), Array(1.0, 1.0)),
    Vectors.sparse(9, Array(0, 6), Array(1.0, 2.0)),
    Vectors.sparse(9, Array(3, 8), Array(1.0, 1.0))
  ).map(_.toOPVector)

  Spec[YoEstimator] should "detect one categorical and one non-categorical text feature" in {
    val output = new YoEstimator().setInput(f1).getOutput()

    val text = output.left
    val pickList = output.right
    val v1 = text.tokenize()
    val v2 = Seq(pickList).transmogrify()
    println(pickList.isRight)

    val wf = new OpWorkflow().setResultFeatures(v1, v2)

    println(s"Stages are : ${wf.stages.map(f => (f.stageName, f.branching))
      .toSeq}")
    val transformed2 = wf.transform(inputData)
    transformed2.show()

  }

  it should "detect two categorical text features" in {
    val smartVectorized = new SmartTextVectorizer()
      .setMaxCardinality(10).setNumFeatures(4).setMinSupport(1).setTopK(2).setPrependFeatureName(false)
      .setInput(f1, f2).getOutput()

    val categoricalVectorized =
      new OpTextPivotVectorizer[Text]().setMinSupport(1).setTopK(2).setInput(f1, f2).getOutput()

    val transformed = new OpWorkflow().setResultFeatures(smartVectorized, categoricalVectorized).transform(inputData)
    val result = transformed.collect(smartVectorized, categoricalVectorized)
    val field = transformed.schema(smartVectorized.name)
    val smartRes = transformed.collect(smartVectorized)
    assertNominal(field, Array.fill(smartRes.head.value.size)(true), smartRes)
    val fieldCategorical = transformed.schema(categoricalVectorized.name)
    val catRes = transformed.collect(categoricalVectorized)
    assertNominal(fieldCategorical, Array.fill(catRes.head.value.size)(true), catRes)
    val (smart, expected) = result.unzip

    smart shouldBe expected
  }

  it should "detect two non categorical text features" in {
    val smartVectorized = new SmartTextVectorizer()
      .setMaxCardinality(1).setNumFeatures(4).setMinSupport(1).setTopK(2).setPrependFeatureName(false)
      .setInput(f1, f2).getOutput()

    val f1Tokenized = new TextTokenizer[Text]().setInput(f1).getOutput()
    val f2Tokenized = new TextTokenizer[Text]().setInput(f2).getOutput()
    val textVectorized = new OPCollectionHashingVectorizer[TextList]()
      .setNumFeatures(4).setPrependFeatureName(false).setInput(f1Tokenized, f2Tokenized).getOutput()
    val nullIndicator = new TextListNullTransformer[TextList]().setInput(f1Tokenized, f2Tokenized).getOutput()

    val transformed = new OpWorkflow()
      .setResultFeatures(smartVectorized, textVectorized, nullIndicator).transform(inputData)
    val result = transformed.collect(smartVectorized, textVectorized, nullIndicator)
    val field = transformed.schema(smartVectorized.name)
    assertNominal(field, Array.fill(8)(false) ++ Array(true, true), transformed.collect(smartVectorized))
    val fieldText = transformed.schema(textVectorized.name)
    val textRes = transformed.collect(textVectorized)
    assertNominal(fieldText, Array.fill(textRes.head.value.size)(false), textRes)
    val (smart, expected) = result.map { case (smartVector, textVector, nullVector) =>
      val combined = VectorsCombiner.combineOP(Seq(textVector, nullVector))
      smartVector -> combined
    }.unzip

    smart shouldBe expected
  }

  it should "work for shortcut" in {
    val smartVectorized = new SmartTextVectorizer()
      .setMaxCardinality(2).setNumFeatures(4).setMinSupport(1).setTopK(2)
      .setAutoDetectLanguage(false).setMinTokenLength(1).setToLowercase(false)
      .setInput(f1, f2).getOutput()

    val shortcutVectorized = f1.smartVectorize(
      maxCategoricalCardinality = 2, numHashes = 4, minSupport = 1, topK = 2,
      autoDetectLanguage = false, minTokenLength = 1, toLowercase = false,
      hashSpaceStrategy = HashSpaceStrategy.Shared, others = Array(f2)
    )

    val transformed = new OpWorkflow().setResultFeatures(smartVectorized, shortcutVectorized).transform(inputData)
    val result = transformed.collect(smartVectorized, shortcutVectorized)
    val field = transformed.schema(smartVectorized.name)
    assertNominal(field, Array.fill(4)(true) ++ Array.fill(4)(false) :+ true, transformed.collect(smartVectorized))
    val fieldShortcut = transformed.schema(shortcutVectorized.name)
    assertNominal(fieldShortcut, Array.fill(4)(true) ++ Array.fill(4)(false) :+ true,
      transformed.collect(shortcutVectorized))
    val (regular, shortcut) = result.unzip

    regular shouldBe shortcut
  }

  it should "fail with an assertion error" in {
    val emptyDF = inputData.filter(inputData("text1") === "").toDF()

    val smartVectorized = new SmartTextVectorizer()
      .setMaxCardinality(2).setNumFeatures(4).setMinSupport(1).setTopK(2).setPrependFeatureName(false)
      .setInput(f1, f2).getOutput()

    val thrown = intercept[AssertionError] {
      new OpWorkflow().setResultFeatures(smartVectorized).transform(emptyDF)
    }
    assert(thrown.getMessage.contains("assertion failed"))
  }

  it should "generate metadata correctly" in {
    val smartVectorized = new SmartTextVectorizer()
      .setMaxCardinality(2).setNumFeatures(4).setMinSupport(1).setTopK(2).setPrependFeatureName(false)
      .setInput(f1, f2).getOutput()

    val transformed = new OpWorkflow().setResultFeatures(smartVectorized).transform(inputData)

    val meta = OpVectorMetadata(transformed.schema(smartVectorized.name))
    meta.history.keys shouldBe Set(f1.name, f2.name)
    meta.columns.length shouldBe 9
    meta.columns.foreach { col =>
      if (col.index < 2) {
        col.parentFeatureName shouldBe Seq(f1.name)
        col.grouping shouldBe Option(f1.name)
      } else if (col.index == 2) {
        col.parentFeatureName shouldBe Seq(f1.name)
        col.grouping shouldBe Option(f1.name)
        col.indicatorValue shouldBe Option(TransmogrifierDefaults.OtherString)
      } else if (col.index == 3) {
        col.parentFeatureName shouldBe Seq(f1.name)
        col.grouping shouldBe Option(f1.name)
        col.indicatorValue shouldBe Option(OpVectorColumnMetadata.NullString)
      } else if (col.index < 8) {
        col.parentFeatureName shouldBe Seq(f2.name)
        col.grouping shouldBe None
      } else {
        col.parentFeatureName shouldBe Seq(f2.name)
        col.grouping shouldBe Option(f2.name)
        col.indicatorValue shouldBe Option(OpVectorColumnMetadata.NullString)
      }
    }
  }

  it should "generate categorical metadata correctly" in {
    val smartVectorized = new SmartTextVectorizer()
      .setMaxCardinality(4).setNumFeatures(4).setMinSupport(1).setTopK(2).setPrependFeatureName(false)
      .setInput(f1, f2).getOutput()

    val transformed = new OpWorkflow().setResultFeatures(smartVectorized).transform(inputData)

    val meta = OpVectorMetadata(transformed.schema(smartVectorized.name))
    meta.history.keys shouldBe Set(f1.name, f2.name)
    meta.columns.length shouldBe 8
    meta.columns.foreach { col =>
      if (col.index < 2) {
        col.parentFeatureName shouldBe Seq(f1.name)
        col.grouping shouldBe Option(f1.name)
      } else if (col.index == 2) {
        col.parentFeatureName shouldBe Seq(f1.name)
        col.grouping shouldBe Option(f1.name)
        col.indicatorValue shouldBe Option(TransmogrifierDefaults.OtherString)
      } else if (col.index == 3) {
        col.parentFeatureName shouldBe Seq(f1.name)
        col.grouping shouldBe Option(f1.name)
        col.indicatorValue shouldBe Option(OpVectorColumnMetadata.NullString)
      } else if (col.index < 6) {
        col.parentFeatureName shouldBe Seq(f2.name)
        col.grouping shouldBe Option(f2.name)
      } else if (col.index == 6) {
        col.parentFeatureName shouldBe Seq(f2.name)
        col.grouping shouldBe Option(f2.name)
        col.indicatorValue shouldBe Option(TransmogrifierDefaults.OtherString)
      } else {
        col.parentFeatureName shouldBe Seq(f2.name)
        col.grouping shouldBe Option(f2.name)
        col.indicatorValue shouldBe Option(OpVectorColumnMetadata.NullString)
      }
    }
  }

  it should "generate non categorical metadata correctly" in {
    val smartVectorized = new SmartTextVectorizer()
      .setMaxCardinality(1).setNumFeatures(4).setMinSupport(1).setTopK(2).setPrependFeatureName(false)
      .setInput(f1, f2).getOutput()

    val transformed = new OpWorkflow().setResultFeatures(smartVectorized).transform(inputData)

    val meta = OpVectorMetadata(transformed.schema(smartVectorized.name))
    meta.history.keys shouldBe Set(f1.name, f2.name)
    meta.columns.length shouldBe 10
    meta.columns.foreach { col =>
      if (col.index < 4) {
        col.parentFeatureName shouldBe Seq(f1.name)
        col.grouping shouldBe None
      } else if (col.index < 8) {
        col.parentFeatureName shouldBe Seq(f2.name)
        col.grouping shouldBe None
      } else if (col.index == 8) {
        col.parentFeatureName shouldBe Seq(f1.name)
        col.grouping shouldBe Option(f1.name)
        col.indicatorValue shouldBe Option(OpVectorColumnMetadata.NullString)
      } else {
        col.parentFeatureName shouldBe Seq(f2.name)
        col.grouping shouldBe Option(f2.name)
        col.indicatorValue shouldBe Option(OpVectorColumnMetadata.NullString)
      }
    }
  }

  Spec[TextStats] should "aggregate correctly" in {
    val l1 = TextStats(Map("hello" -> 1, "world" -> 2))
    val r1 = TextStats(Map("hello" -> 1, "world" -> 1))
    val expected1 = TextStats(Map("hello" -> 2, "world" -> 3))

    val l2 = TextStats(Map("hello" -> 1, "world" -> 2, "ocean" -> 3))
    val r2 = TextStats(Map("hello" -> 1))
    val expected2 = TextStats(Map("hello" -> 1, "world" -> 2, "ocean" -> 3))

    TextStats.semiGroup(2).plus(l1, r1) shouldBe expected1
    TextStats.semiGroup(2).plus(l2, r2) shouldBe expected2
  }

}
