/*
 * Copyright (c) 2017, Salesforce.com, Inc.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * * Redistributions of source code must retain the above copyright notice, this
 *   list of conditions and the following disclaimer.
 *
 * * Redistributions in binary form must reproduce the above copyright notice,
 *   this list of conditions and the following disclaimer in the documentation
 *   and/or other materials provided with the distribution.
 *
 * * Neither the name of the copyright holder nor the names of its
 *   contributors may be used to endorse or promote products derived from
 *   this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.salesforce.op.features

import com.salesforce.op.UID
import com.salesforce.op.features.types.FeatureType
import com.salesforce.op.stages.{OPStage, OpPipelineStage, OpPipelineStageBase, OpPipelineStageEither}

import scala.reflect.runtime.universe.WeakTypeTag

/**
 * Feature instance
 *
 * Note: can only be created using [[FeatureBuilder]].
 *
 * @param name        name of feature, represents the name of the column in the dataframe.
 * @param isResponse  whether or not this feature is a response feature, ie dependent variable
 * @param originStage reference to OpPipelineStage responsible for generating the feature.
 * @param parents     references to the features that are transformed by the originStage that produces this feature
 * @param uid         unique identifier of the feature instance
 * @param wtt         feature's value type tag
 * @tparam O feature value type
 */
case class Feature[O <: FeatureType] private[op]
(
  name: String,
  isResponse: Boolean,
  originStage: OpPipelineStageBase,
  parents: Seq[OPFeature],
  uid: String,
  distributions: Seq[FeatureDistributionLike] = Seq.empty,
  isRight: Boolean = false,
  isLeft: Boolean = false
)(implicit val wtt: WeakTypeTag[O]) extends FeatureLike[O] {

  def this(
    name: String,
    isResponse: Boolean,
    originStage: OpPipelineStageBase,
    parents: Seq[OPFeature]
  )(implicit wtt: WeakTypeTag[O]) = this(
    name = name,
    isResponse = isResponse,
    originStage = originStage,
    parents = parents,
    uid = FeatureUID(originStage.uid),
    distributions = Seq.empty,
    isRight = false,
    isLeft = false
  )(wtt)

  def copy(
    name: String = this.name, isResponse: Boolean = this.isResponse,
    originStage: OpPipelineStageBase = this.originStage,
    parents: Seq[OPFeature] = this.parents, uid: String = this.uid,
    distributions: Seq[FeatureDistributionLike] = this.distributions,isRight: Boolean = false,
    isLeft: Boolean = false
  ): Feature[O] = Feature[O](name = name, isResponse = isResponse, originStage = originStage, parents = parents,
    uid = uid, distributions = distributions, isRight = isRight, isLeft = isLeft)


  /**
   * Takes an array of stages and will try to replace all origin stages of features with
   * stage from the new stages with the same uid. This is used to make a copy of the feature
   * with the origin stage pointing at the fitted model resulting from an estimator rather
   * than the estimator.
   *
   * @param stages Array of all parent stages for the features
   * @return A feature with the origin stage (and the origin stages or all parent stages replaced
   *         with the stages in the map passed in)
   */
  private[op] final def copyWithNewStages(stages: Array[OPStage]): FeatureLike[O] = {
    val stagesMap: Map[String, OpPipelineStageBase] = stages.map(s => s.uid -> s).toMap

    def copy[T <: FeatureType](f: FeatureLike[T]): Feature[T] = {
      // try to get replacement stage, if no replacement provided use original
      val stage = stagesMap.getOrElse(f.originStage.uid, f.originStage).asInstanceOf[OpPipelineStage[T]]
      val newParents = f.parents.map(p => copy[T](p.asInstanceOf[FeatureLike[T]]))
      Feature[T](
        name = f.name, isResponse = f.isResponse, originStage = stage, parents = newParents, uid = f.uid,
        distributions = f.distributions, isRight = f.isRight, isLeft = f.isLeft
      )(f.wtt)
    }

    copy(this)
  }

  /**
   * Takes an a sequence of feature distributions assocaited with the feature
   *
   * @param distributions Seq of the feature distributions for the feature
   * @return A feature with the distributions assocated
   */
  override private[op] def withDistributions(distributions: Seq[FeatureDistributionLike]) =
    this.copy(distributions = distributions)
}


case class FeatureEither[O1 <: FeatureType, O2 <: FeatureType] private[op]
(
  name: String,
  isResponse: Boolean,
  originStage: OpPipelineStageBase,
  parents: Seq[OPFeature],
  uid: String,
  distributions: Seq[FeatureDistributionLike] = Seq.empty,
  isRight: Boolean = false,
  isLeft: Boolean = false
)(implicit val wtt1: WeakTypeTag[O1], implicit val wtt2: WeakTypeTag[O2]) extends FeatureEitherLike[O1, O2] {
  override def right: FeatureLike[O2] = new Feature[O2](
    uid = uid,
    name = name,
    originStage = originStage,
    isResponse = isResponse,
    parents = parents,
    isRight = true,
    isLeft = false
  )(wtt2)

  override def left: FeatureLike[O1] = new Feature[O1](
    uid = uid,
    name = name,
    originStage = originStage,
    isResponse = isResponse,
    parents = parents,
    isLeft = true,
    isRight = false
  )(wtt1)

  def this(
    name: String,
    isResponse: Boolean,
    originStage: OpPipelineStageBase,
    parents: Seq[OPFeature]
  )(implicit wtt1: WeakTypeTag[O1], wtt2: WeakTypeTag[O2]) = this(
    name = name,
    isResponse = isResponse,
    originStage = originStage,
    parents = parents,
    uid = FeatureUIDEither(originStage.uid),
    distributions = Seq.empty
  )(wtt1, wtt2)


  override private[op] def withDistributions(distributions: Seq[FeatureDistributionLike]) =
    this.copy(distributions = distributions)

  private[op] final def copyWithNewStages(stages: Array[OPStage]): FeatureEitherLike[O1, O2] = {
    val stagesMap: Map[String, OpPipelineStageBase] = stages.map(s => s.uid -> s).toMap

    def copy[T1 <: FeatureType, T2 <: FeatureType](f: FeatureEitherLike[T1, T2]): FeatureEither[T1, T2] = {
      // try to get replacement stage, if no replacement provided use original
      val stage = stagesMap.getOrElse(f.originStage.uid, f.originStage).asInstanceOf[OpPipelineStageEither[T1, T2]]
      val newParents = f.parents.map(p => copy[T1, T2](p.asInstanceOf[FeatureEitherLike[T1, T2]]))
      FeatureEither[T1, T2](
        name = f.name, isResponse = f.isResponse, originStage = stage, parents = newParents, uid = f.uid,
        distributions = f.distributions
      )(f.wtt1, f.wtt2)
    }

    copy(this)
  }

}

/**
 * Feature UID factory
 */
case object FeatureUID {

  /**
   * Returns a UID for features that is built of: feature type name + "_" + 12 hex chars of stage uid.
   *
   * @tparam T feature type T with a type tag
   * @param stageUid stage uid
   * @return UID
   */
  def apply[T <: FeatureType : WeakTypeTag](stageUid: String): String = {
    val (_, stageUidSuffix) = UID.fromString(stageUid)
    val shortTypeName = FeatureType.shortTypeName[T]
    s"${shortTypeName}_$stageUidSuffix"
  }

}

/**
 * Feature UID factory Either
 */
case object FeatureUIDEither {

  /**
   * Returns a UID for features that is built of: feature type name + "_" + 12 hex chars of stage uid.
   *
   * @tparam T1 feature type T with a type tag
   * @param stageUid stage uid
   * @return UID
   */
  def apply[T1 <: FeatureType : WeakTypeTag, T2 <: FeatureType : WeakTypeTag](stageUid: String): String = {
    val (_, stageUidSuffix) = UID.fromString(stageUid)
    val shortTypeName1 = FeatureType.shortTypeName[T1]
    val shortTypeName2 = FeatureType.shortTypeName[T2]
    s"${shortTypeName1}_or_${shortTypeName2}_$stageUidSuffix"
  }

}
