// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.gradle.importing

import org.jetbrains.plugins.gradle.frameworkSupport.script.*
import org.jetbrains.plugins.gradle.frameworkSupport.script.ScriptElement.Statement.Expression
import org.jetbrains.plugins.gradle.frameworkSupport.script.ScriptElement.Statement.Expression.BlockElement

interface GradleSettingScriptBuilder : ScriptElementBuilder {

  fun setProjectName(projectName: String): GradleSettingScriptBuilder

  fun include(name: String): GradleSettingScriptBuilder

  fun includeFlat(name: String): GradleSettingScriptBuilder

  fun includeBuild(name: String): GradleSettingScriptBuilder

  fun enableFeaturePreview(featureName: String): GradleSettingScriptBuilder

  fun addCode(text: String): GradleSettingScriptBuilder

  fun addCode(expression: Expression): GradleSettingScriptBuilder

  fun addCode(configure: ScriptTreeBuilder.() -> Unit): GradleSettingScriptBuilder

  fun generateTree(): BlockElement

  fun generate(useKotlinDsl: Boolean): String
}