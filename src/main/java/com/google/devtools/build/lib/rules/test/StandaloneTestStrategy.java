// Copyright 2014 Google Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.rules.test;

import com.google.common.collect.Lists;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.BaseSpawn;
import com.google.devtools.build.lib.actions.EnvironmentalExecException;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.actions.ExecutionStrategy;
import com.google.devtools.build.lib.actions.Executor;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.actions.TestExecException;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.Reporter;
import com.google.devtools.build.lib.util.io.FileOutErr;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.view.config.BinTools;
import com.google.devtools.build.lib.view.config.BuildConfiguration;
import com.google.devtools.build.lib.view.test.TestStatus.BlazeTestStatus;
import com.google.devtools.build.lib.view.test.TestStatus.FailedTestCaseDetails;
import com.google.devtools.build.lib.view.test.TestStatus.TestResultData;
import com.google.devtools.common.options.OptionsClassProvider;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Runs TestRunnerAction actions.
 */
@ExecutionStrategy(contextType = TestActionContext.class,
          name = { "standalone" })
public class StandaloneTestStrategy extends TestStrategy {

  /*
    TODO(bazel-team):

    * tests
    * It would be nice to get rid of (cd $TEST_SRCDIR) in the test-setup script.
    * test timeouts.
    * parsing XML output.

    */
  protected final PathFragment runfilesPrefix;

  public StandaloneTestStrategy(OptionsClassProvider requestOptions,
      OptionsClassProvider startupOptions, BinTools binTools, PathFragment runfilesPrefix) {
    super(requestOptions, startupOptions, binTools);

    this.runfilesPrefix = runfilesPrefix;
  }

  private static final String TEST_SETUP = "tools/test-setup.sh";

  @Override
  public void exec(TestRunnerAction action, ActionExecutionContext actionExecutionContext)
      throws ExecException, InterruptedException {
    Path runfilesDir = null;
    try {
      runfilesDir = TestStrategy.getLocalRunfilesDirectory(
          action, actionExecutionContext, binTools);
    } catch (ExecException e) {
      throw new TestExecException(e.getMessage());
    }

    Path workingDirectory = runfilesDir.getRelative(runfilesPrefix);
    Map<String, String> env = getEnv(action, runfilesDir);
    Spawn spawn = new BaseSpawn(getArgs(action), env,
        action.getTestProperties().getExecutionInfo(),
        action,
        action.getTestProperties().getLocalResourceUsage());

    Executor executor = actionExecutionContext.getExecutor();
    try {
      FileSystemUtils.createDirectoryAndParents(workingDirectory);
      FileOutErr fileOutErr = new FileOutErr(action.getTestLog().getPath(), action.getTestStderr());
      TestResultData data = execute(
          actionExecutionContext.withFileOutErr(fileOutErr), spawn, action);
      appendStderr(fileOutErr.getOutputFile(), fileOutErr.getErrorFile());
      finalizeTest(executor, action, data, fileOutErr);
    } catch (IOException e) {
      executor.getEventHandler().handle(Event.error("Caught I/O exception: " + e));
      throw new EnvironmentalExecException("unexpected I/O exception", e);
    }
  }

  private Map<String, String> getEnv(TestRunnerAction action, Path runfilesDir) {
    Map<String, String> vars = getDefaultTestEnvironment(action);
    BuildConfiguration config = action.getConfiguration();

    vars.putAll(config.getDefaultShellEnvironment());
    vars.putAll(config.getTestEnv());
    vars.put("TEST_SRCDIR", runfilesDir.getRelative(runfilesPrefix).getPathString());

    // TODO(bazel-team): set TEST_TMPDIR.

    return vars;
  }

  private TestResultData execute(
      ActionExecutionContext actionExecutionContext, Spawn spawn, TestRunnerAction action)
      throws TestExecException, InterruptedException {
    Executor executor = actionExecutionContext.getExecutor();
    Closeable streamed = null;
    Path testLogPath = action.getTestLog().getPath();
    TestResultData.Builder builder = TestResultData.newBuilder();

    try {
      try {
        if (executionOptions.testOutput.equals(TestOutputFormat.STREAMED)) {
          streamed = new StreamedTestOutput(
              Reporter.outErrForReporter(
                  actionExecutionContext.getExecutor().getEventHandler()), testLogPath);
        }
        executor.getSpawnActionContext(action.getMnemonic()).exec(spawn, actionExecutionContext);

        builder.setTestPassed(true)
            .setStatus(BlazeTestStatus.PASSED)
            .setCachable(true);
      } catch (ExecException e) {
        // Execution failed, which we consider a test failure.

        // TODO(bazel-team): set cachable==true for relevant statuses (failure, but not for
        // timeout, etc.)
        builder.setTestPassed(false)
            .setStatus(BlazeTestStatus.FAILED);
      } finally {
        if (streamed != null) {
          streamed.close();
        }
      }

      HierarchicalTestResult details = parseTestResult(action.getXmlOutputPath());
      if (details != null) {
        FailedTestCaseDetails failedDetails = details.collectFailedTestCases();
        if (failedDetails != null) {
          builder.setFailedTestCaseDetails(failedDetails);
        }
      }

      return builder.build();
    } catch (IOException e) {
      throw new TestExecException(e.getMessage());
    }
  }

  protected final void finalizeTest(Executor executor, TestRunnerAction action,
      TestResultData data,  FileOutErr outErr) throws IOException, ExecException {
    TestResult result = new TestResult(action, data, false);
    postTestResult(executor, result);

    // TODO(bazel-team): handle --test_output=errors, --test_output=all.

    if (!executionOptions.testKeepGoing && data.getStatus() != BlazeTestStatus.PASSED) {
      throw new TestExecException("Test failed: aborting");
    }
  }

  private List<String> getArgs(TestRunnerAction action) {
    List<String> args = Lists.newArrayList(TEST_SETUP);
    TestTargetExecutionSettings execSettings = action.getExecutionSettings();

    // Execute the test using the alias in the runfiles tree.
    args.add(execSettings.getExecutable().getRootRelativePath().getPathString());
    args.addAll(execSettings.getArgs());

    return args;
  }

  @Override
  public String strategyLocality(TestRunnerAction action) { return "standalone"; }

  @Override
  public TestResult newCachedTestResult(TestRunnerAction action, TestResultData data) {
    return new TestResult(action, data, /*cached*/ true);
  }
}