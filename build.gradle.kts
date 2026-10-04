plugins {
    // Toolchain, not preference. Google's own table puts API 36 at AGP 8.9.1 as
    // the floor, and Play has required targeting 36 since 31 August 2026, so
    // staying on the 8.5 line meant the app could not be updated at all. These
    // are the versions documented together for AGP 9.4 (which requires Gradle
    // 9.6+), and the Compose compiler plugin rides on Kotlin, so they move
    // together rather than being pinned apart.
    id("com.android.application") version "9.4.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
    id("io.gitlab.arturbosch.detekt") version "1.23.8" apply false
}

//
// Static analysis, run over the whole module from `./gradlew detekt` and from
// .github/workflows/detekt.yml.
//
// It is here for one reason above the others: `lintDebug` already runs and says
// nothing about a function nobody calls. PrinterService.resetWasteCounters sat
// in the tree through a release with no caller, and so did a MainUiState field
// written on every failure and read by nothing but its own declaration. Both
// were found by reading, not by a tool.
//
// Worth being precise about what that means, because the obvious claim here is
// wrong. A probe was added and removed to find out: detekt catches a private
// function with no caller, and it does NOT catch a public function with no
// caller or a state field that is written and never read. Neither of the two
// above would have been found by this tool. What it does buy is the private
// case going forward, and unused imports. See the table in the README.
//
// The default ruleset is deliberately not used. It reports formatting that
// ktlint would report better, and it flags naming this codebase does not use.
// `detekt.yml` starts from the recommended rules and switches off the ones that
// would only produce noise, with the reason written next to each.
//
// There is no baseline. The analysis reached zero findings by fixing what it
// found or by switching a rule off with the reason written in detekt.yml, so
// there is nothing to tolerate. A baseline added later is a decision to accept
// debt and belongs in a commit that says so, not in the side effect of somebody
// running `--auto-baseline` to turn a red build green.
//
