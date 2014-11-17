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

import com.google.devtools.build.lib.syntax.BuildFileAST;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;

import javax.annotation.Nullable;

/**
 * A value that represents an AST file lookup result.
 */
public class ASTFileLookupValue implements SkyValue {

  static final ASTFileLookupValue NO_FILE = new ASTFileLookupValue(null);

  @Nullable private final BuildFileAST ast;

  public ASTFileLookupValue(@Nullable BuildFileAST ast) {
    this.ast = ast;
  }

  /**
   * Returns the original AST file.
   */
  @Nullable public BuildFileAST getAST() {
    return ast;
  }

  static SkyKey key(PathFragment astFilePathFragment) {
    return new SkyKey(SkyFunctions.AST_FILE_LOOKUP, astFilePathFragment);
  }
}