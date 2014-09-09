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
package com.google.devtools.build.lib.bazel;

import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.actions.ActionContextProvider;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ActionExecutionException;
import com.google.devtools.build.lib.actions.ActionGraph;
import com.google.devtools.build.lib.actions.ActionInputFileCache;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ArtifactFactory;
import com.google.devtools.build.lib.actions.ArtifactOwner;
import com.google.devtools.build.lib.actions.ExecutionStrategy;
import com.google.devtools.build.lib.actions.Executor;
import com.google.devtools.build.lib.actions.Executor.ActionContext;
import com.google.devtools.build.lib.actions.ExecutorInitException;
import com.google.devtools.build.lib.actions.ResourceSet;
import com.google.devtools.build.lib.actions.Root;
import com.google.devtools.build.lib.blaze.BlazeModule;
import com.google.devtools.build.lib.blaze.BlazeRuntime;
import com.google.devtools.build.lib.blaze.Command;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.view.BuildInfoHelper;
import com.google.devtools.build.lib.view.WorkspaceStatusAction;
import com.google.devtools.build.lib.view.WorkspaceStatusAction.Key;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Workspace status information for Bazel.
 *
 * <p>Currently only a stub.
 */
public class BazelWorkspaceStatusModule extends BlazeModule {
  private static class BazelWorkspaceStatusAction extends WorkspaceStatusAction {
    private final Artifact stableStatus;
    private final Artifact volatileStatus;

    private BazelWorkspaceStatusAction(
        Artifact stableStatus, Artifact volatileStatus) {
      super(BuildInfoHelper.BUILD_INFO_ACTION_OWNER, Artifact.NO_ARTIFACTS,
          ImmutableList.of(stableStatus, volatileStatus));
      this.stableStatus = stableStatus;
      this.volatileStatus = volatileStatus;
    }

    @Override
    public String describeStrategy(Executor executor) {
      return "";
    }

    @Override
    public void execute(ActionExecutionContext actionExecutionContext)
        throws ActionExecutionException {
      try {
        FileSystemUtils.writeContent(stableStatus.getPath(), new byte[] {});
        FileSystemUtils.writeContent(volatileStatus.getPath(), new byte[] {});
      } catch (IOException e) {
        throw new ActionExecutionException(e, this, true);
      }
    }

    // TODO(bazel-team): Add test for equals, add hashCode.
    @Override
    public boolean equals(Object o) {
      if (!(o instanceof BazelWorkspaceStatusAction)) {
        return false;
      }

      BazelWorkspaceStatusAction that = (BazelWorkspaceStatusAction) o;
      return this.stableStatus.equals(that.stableStatus)
          && this.volatileStatus.equals(that.volatileStatus);
    }

    @Override
    public int hashCode() {
      return Objects.hash(stableStatus, volatileStatus);
    }

    @Override
    public String getMnemonic() {
      return "BazelWorkspaceStatusAction";
    }

    @Override
    public ResourceSet estimateResourceConsumption(Executor executor) {
      return ResourceSet.ZERO;
    }

    @Override
    protected String computeKey() {
      return "";
    }

    @Override
    public Artifact getVolatileStatus() {
      return volatileStatus;
    }

    @Override
    public Artifact getStableStatus() {
      return stableStatus;
    }
  }

  private class BazelStatusActionFactory implements WorkspaceStatusAction.Factory {
    @Override
    public Map<String, String> createDummyWorkspaceStatus() {
      return ImmutableMap.of();
    }

    @Override
    public WorkspaceStatusAction createWorkspaceStatusAction(
        ArtifactFactory factory, ArtifactOwner artifactOwner, Supplier<UUID> buildId) {
      Root root = runtime.getDirectories().getBuildDataDirectory();

      Artifact stableArtifact =
          factory.getSpecialMetadataHandlingArtifact(new PathFragment("stable-status.txt"),
              root, artifactOwner,
              /*forceConstantMetadata=*/false, /*forceDigestMetadata=*/true);
      Artifact volatileArtifact =
          factory.getSpecialMetadataHandlingArtifact(new PathFragment("volatile-status.txt"),
              root, artifactOwner,
            /*forceConstantMetadata=*/true, /*forceDigestMetadata=*/false);

      return new BazelWorkspaceStatusAction(stableArtifact, volatileArtifact);
    }
  }

  @ExecutionStrategy(contextType = WorkspaceStatusAction.Context.class)
  private class BazelWorkspaceStatusActionContext implements WorkspaceStatusAction.Context {
    @Override
    public ImmutableMap<String, Key> getStableKeys() {
      return ImmutableMap.of();
    }

    @Override
    public ImmutableMap<String, Key> getVolatileKeys() {
      return ImmutableMap.of();
    }
  }


  private class WorkspaceActionContextProvider implements ActionContextProvider {
    @Override
    public Iterable<ActionContext> getActionContexts() {
      return ImmutableList.<ActionContext>of(new BazelWorkspaceStatusActionContext());
    }

    @Override
    public void executorCreated(Iterable<ActionContext> usedContexts)
        throws ExecutorInitException {
    }

    @Override
    public void executionPhaseEnding() {
    }

    @Override
    public void executionPhaseStarting(ActionInputFileCache actionInputFileCache,
        ActionGraph actionGraph, Iterable<Artifact> topLevelArtifacts) throws ExecutorInitException,
        InterruptedException {
    }
  }

  private BlazeRuntime runtime;

  @Override
  public void beforeCommand(BlazeRuntime runtime, Command command) {
    this.runtime = runtime;
  }

  @Override
  public ActionContextProvider getActionContextProvider() {
    return new WorkspaceActionContextProvider();
  }

  @Override
  public WorkspaceStatusAction.Factory getWorkspaceStatusActionFactory() {
    return new BazelStatusActionFactory();
  }
}
