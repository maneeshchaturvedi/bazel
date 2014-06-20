// Copyright 2014 Google Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.skyframe;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe;
import com.google.devtools.build.skyframe.Node;
import com.google.devtools.build.skyframe.NodeKey;

import java.util.Map;

import javax.annotation.Nullable;

/**
 * A node representing an executed action.
 */
@Immutable
@ThreadSafe
public class ActionExecutionNode implements Node {
  private final ImmutableMap<Artifact, FileNode> artifactData;
  private final ImmutableMap<Artifact, FileArtifactNode> additionalOutputData;

  /**
   * @param artifactData Map from Artifacts to corresponding FileNodes.
   * @param additionalOutputData Map from Artifacts to nodes if the FileArtifactNode for this
   *     artifact cannot be derived from the corresponding FileNode (see {@link
   *     FileAndMetadataCache#getAdditionalOutputData} for when this is necessary).
   */
  ActionExecutionNode(Map<Artifact, FileNode> artifactData,
      Map<Artifact, FileArtifactNode> additionalOutputData) {
    this.artifactData = ImmutableMap.copyOf(artifactData);
    this.additionalOutputData = ImmutableMap.copyOf(additionalOutputData);
  }

  /**
   * Returns metadata for a given artifact, if that metadata cannot be inferred from the
   * corresponding {@link #getData} call for that Artifact. See {@link
   * FileAndMetadataCache#getAdditionalOutputData} for when that can happen.
   */
  @Nullable
  FileArtifactNode getArtifactNode(Artifact artifact) {
    return additionalOutputData.get(artifact);
  }

  /**
   * @return The data for each non-middleman output of this action, in the form of the {@link
   * FileNode} that would be created for the file if it were to be read from disk.
   */
  FileNode getData(Artifact artifact) {
    Preconditions.checkState(!additionalOutputData.containsKey(artifact),
        "Should not be requesting data for already-constructed FileArtifactNode: %s", artifact);
    return artifactData.get(artifact);
  }

  /**
   * @return The map from {@link Artifact} to the corresponding {@link FileNode} that would be
   * returned by {@link #getData}. Should only be needed by {@link FilesystemNodeChecker}.
   */
  ImmutableMap<Artifact, FileNode> getAllOutputArtifactData() {
    return artifactData;
  }

  @ThreadSafe
  @VisibleForTesting
  public static NodeKey key(Action action) {
    return new NodeKey(NodeTypes.ACTION_EXECUTION, action);
  }

  /**
   * Returns whether the {@code key} corresponds to a ActionExecutionNode of a "real" action.
   *
   * <p>We define "real" as meaningful for the user, by virtue of having a progress message.
   * Actions lacking that, such as middleman actions and build info actions, typically don't
   * interest the user and they tend to be instantaneous, so counting them in progress reports
   * leads to more confusion than good.
   */
  public static boolean isRealActionKey(NodeKey key) {
    return key.getNodeType() == NodeTypes.ACTION_EXECUTION
        && ((Action) key.getNodeName()).getProgressMessage() != null;
  }
}