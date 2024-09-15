/*
 * Copyright 2015 Ben Manes. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.benmanes.caffeine.cache.node;

import static com.github.benmanes.caffeine.cache.Specifications.NODE;
import static com.github.benmanes.caffeine.cache.Specifications.NODE_FACTORY;
import static com.github.benmanes.caffeine.cache.Specifications.kTypeVar;
import static com.github.benmanes.caffeine.cache.Specifications.vTypeVar;

import javax.lang.model.element.Modifier;

import com.github.benmanes.caffeine.cache.Feature;
import com.google.common.base.CaseFormat;

/**
 * Adds the node inheritance hierarchy.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class AddSubtype implements NodeRule {

  @Override
  public boolean applies(NodeContext context) {
    return true;
  }

  @Override
  public void execute(NodeContext context) {
    context.nodeSubtype
        .addJavadoc(getJavaDoc(context))
        .addTypeVariable(kTypeVar)
        .addTypeVariable(vTypeVar);
    if (context.isFinal) {
      context.nodeSubtype.addModifiers(Modifier.FINAL);
    }
    if (context.isBaseClass()) {
      context.nodeSubtype
          .superclass(NODE)
          .addSuperinterface(NODE_FACTORY);
    } else {
      context.nodeSubtype.superclass(context.superClass);
    }
  }

  private static String getJavaDoc(NodeContext context) {
    var doc = new StringBuilder(200);
    doc.append("<em>WARNING: GENERATED CODE</em>\n\n"
        + "A cache entry that provides the following features:\n<ul>");
    for (Feature feature : context.generateFeatures) {
      String name = CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.UPPER_CAMEL, feature.name());
      doc.append("\n  <li>").append(name);
    }
    for (Feature feature : context.parentFeatures) {
      String name = CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.UPPER_CAMEL, feature.name());
      doc.append("\n  <li>").append(name).append(" (inherited)");
    }
    doc.append("\n</ul>\n\n@author ben.manes@gmail.com (Ben Manes)\n");
    return doc.toString();
  }
}
