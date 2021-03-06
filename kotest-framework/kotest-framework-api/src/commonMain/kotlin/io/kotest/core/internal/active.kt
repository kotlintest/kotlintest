package io.kotest.core.internal

import io.kotest.core.config.configuration
import io.kotest.core.extensions.IsActiveExtension
import io.kotest.core.filter.TestFilter
import io.kotest.core.filter.TestFilterResult
import io.kotest.core.test.TestCase
import io.kotest.core.test.isBang
import io.kotest.core.test.isFocused
import io.kotest.core.spec.focusTests
import io.kotest.core.internal.tags.activeTags
import io.kotest.core.internal.tags.allTags
import io.kotest.core.internal.tags.isActive
import io.kotest.core.internal.tags.parse
import io.kotest.core.plan.toDescriptor
import io.kotest.core.test.TestCaseSeverityLevel
import io.kotest.mpp.log
import io.kotest.mpp.sysprop

/**
 * Returns true if the given [TestCase] is active based on default rules at [isActiveInternal]
 * or any registered [IsActiveExtension]s.
 */
suspend fun TestCase.isActive(): Boolean {
   val descriptor = this.descriptor ?: this.description.toDescriptor(this.source)
   return isActiveInternal() && configuration.extensions().filterIsInstance<IsActiveExtension>()
      .all { it.isActive(descriptor) }
}

/**
 * Returns true if the given [TestCase] is active by the built in rules.
 *
 * Logic can be customized via [IsActiveExtension]s.
 *
 * A test can be active or inactive.
 *
 * A test is inactive if
 *
 * - The `enabled` property is set to false in the [TestCaseConfig] associated with the test.
 * - The name of the test is prefixed with "!" and System.getProperty("kotest.bang.disable") has a null value (ie, not defined)
 * - Excluded tags have been specified and this test has a [Tag] which is one of those excluded
 * - Included tags have been specified and this test either has no tags, or does not have a tag that is one of those included
 * - The test is filtered out via a [TestFilter]
 *
 * Note: tags are defined either through [TestCaseConfig] or in the [Spec] dsl.
 */
fun TestCase.isActiveInternal(): Boolean {

   // this sys property disables the use of !
   // when it's not set, then we use ! to disable tests
   val bangEnabled = sysprop(KotestEngineProperties.disableBangPrefix) == null
   if (isBang() && bangEnabled) {
      log("${description.testPath()} is disabled by bang")
      return false
   }

   if (!config.enabled) {
      log("${description.testPath()} is disabled by enabled property in config")
      return false
   }

   if (!config.enabledIf(this)) {
      log("${description.testPath()} is disabled by enabledIf function in config")
      return false
   }

   val enabledInTags = configuration.activeTags().parse().isActive(this.allTags())
   if (!enabledInTags) {
      log("${description.testPath()} is disabled by tags")
      return false
   }

   val includedByFilters = configuration.filters().filterIsInstance<TestFilter>().all {
      it.filter(this.description) == TestFilterResult.Include
   }
   if (!includedByFilters) {
      log("${description.testPath()} is excluded by test case filters")
      return false
   }

   // if the spec has focused tests, and this test is root and *not* focused, then it's not active
   val specHasFocusedTests = spec.focusTests().isNotEmpty()
   if (description.isRootTest() && !isFocused() && specHasFocusedTests) {
      log("${description.testPath()} is disabled by another test having focus")
      return false
   }

   // if we have the severityLevel lower then in the sysprop -> we ignore this case
   val severityEnabled = config.severity?.let { TestCaseSeverityLevel.valueOf(it.name).isEnabled() }
      ?: TestCaseSeverityLevel.valueOf("NORMAL").isEnabled()
   if (!severityEnabled) {
      log("${description.testPath()} is disabled by severityLevel")
      return false
   }

   return true
}
