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

package com.google.devtools.build.lib.rules.cpp;

import com.google.common.base.Function;
import com.google.devtools.build.lib.blaze.BlazeDirectories;
import com.google.devtools.build.lib.packages.InputFile;
import com.google.devtools.build.lib.packages.NoSuchPackageException;
import com.google.devtools.build.lib.packages.NoSuchTargetException;
import com.google.devtools.build.lib.packages.NoSuchThingException;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.syntax.Label;
import com.google.devtools.build.lib.syntax.Label.SyntaxException;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.view.RedirectChaser;
import com.google.devtools.build.lib.view.config.BuildConfiguration.Fragment;
import com.google.devtools.build.lib.view.config.BuildOptions;
import com.google.devtools.build.lib.view.config.ConfigurationEnvironment;
import com.google.devtools.build.lib.view.config.ConfigurationFragmentFactory;
import com.google.devtools.build.lib.view.config.InvalidConfigurationException;
import com.google.devtools.build.lib.view.config.crosstool.CrosstoolConfig;

/**
 * Loader for C++ configurations.
 */
public class CppConfigurationLoader implements ConfigurationFragmentFactory {
  @Override
  public Class<? extends Fragment> creates() {
    return CppConfiguration.class;
  }

  private final Function<String, String> cpuTransformer;

  /**
   * Creates a new CrosstoolConfigurationLoader instance with the given
   * configuration provider. The configuration provider is used to perform
   * caller-specific configuration file lookup.
   */
  public CppConfigurationLoader(Function<String, String> cpuTransformer) {
    this.cpuTransformer = cpuTransformer;
  }

  @Override
  public CppConfiguration create(ConfigurationEnvironment env, BlazeDirectories directories,
      BuildOptions options) throws InvalidConfigurationException {
    CppConfigurationParameters params = createParameters(env, directories, options);
    return new CppConfiguration(params);
  }

  /**
   * Value class for all the data needed to create a {@link CppConfiguration}.
   */
  public static class CppConfigurationParameters {
    protected final CrosstoolConfig.CToolchain toolchain;
    protected final String cacheKeySuffix;
    protected final BuildOptions buildOptions;
    protected final Label crosstoolTop;
    protected final Label ccToolchainLabel;
    protected final Path fdoZip;
    protected final Path execRoot;

    CppConfigurationParameters(CrosstoolConfig.CToolchain toolchain,
        String cacheKeySuffix,
        BuildOptions buildOptions,
        Path fdoZip,
        Path execRoot,
        Label crosstoolTop,
        Label ccToolchainLabel) {
      this.toolchain = toolchain;
      this.cacheKeySuffix = cacheKeySuffix;
      this.buildOptions = buildOptions;
      this.fdoZip = fdoZip;
      this.execRoot = execRoot;
      this.crosstoolTop = crosstoolTop;
      this.ccToolchainLabel = ccToolchainLabel;
    }
  }

  protected CppConfigurationParameters createParameters(
      ConfigurationEnvironment env, BlazeDirectories directories,
      BuildOptions options) throws InvalidConfigurationException {
    Label crosstoolTop = RedirectChaser.followRedirects(env,
        options.get(CppOptions.class).crosstoolTop, "crosstool_top");
    CrosstoolConfigurationLoader.CrosstoolFile file =
        CrosstoolConfigurationLoader.readCrosstool(env, crosstoolTop);
    CrosstoolConfig.CToolchain toolchain =
        CrosstoolConfigurationLoader.selectToolchain(file.getProto(), options, cpuTransformer);

    // FDO
    // TODO(bazel-team): move this to CppConfiguration.prepareHook
    CppOptions cppOptions = options.get(CppOptions.class);
    Path fdoZip;
    if (cppOptions.fdoOptimize == null) {
      fdoZip = null;
    } else if (cppOptions.fdoOptimize.startsWith("//")) {
      try {
        Target target = env.getTarget(Label.parseAbsolute(cppOptions.fdoOptimize));
        if (!(target instanceof InputFile)) {
          throw new InvalidConfigurationException(
              "--fdo_optimize cannot accept targets that do not refer to input files");
        }
        fdoZip = env.getPath(target.getPackage(), target.getName());
        if (fdoZip == null) {
          throw new InvalidConfigurationException(
              "The --fdo_optimize parameter you specified resolves to a file that does not exist");
        }
      } catch (NoSuchPackageException | NoSuchTargetException | SyntaxException e) {
        throw new InvalidConfigurationException(e);
      }
    } else {
      fdoZip = directories.getWorkspace().getRelative(cppOptions.fdoOptimize);
    }

    Label ccToolchainLabel;
    try {
      ccToolchainLabel = crosstoolTop.getRelative("cc-compiler-" + toolchain.getTargetCpu());
    } catch (Label.SyntaxException e) {
      throw new InvalidConfigurationException(String.format(
          "'%s' is not a valid CPU. It should only consist of characters valid in labels",
          toolchain.getTargetCpu()));
    }

    Target ccToolchain;
    try {
      ccToolchain = env.getTarget(ccToolchainLabel);
    } catch (NoSuchThingException e) {
      throw new InvalidConfigurationException(String.format(
          "The toolchain rule '%s' does not exist", ccToolchainLabel));
    }

    if (!(ccToolchain instanceof Rule)
        || !((Rule) ccToolchain).getRuleClass().equals("cc_toolchain")) {
      throw new InvalidConfigurationException(String.format(
          "The label '%s' is not a cc_toolchain rule", ccToolchainLabel));
    }

    return new CppConfigurationParameters(toolchain, file.getMd5(), options,
        fdoZip, directories.getExecRoot(), crosstoolTop, ccToolchainLabel);
  }
}
