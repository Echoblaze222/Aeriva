plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Pure Kotlin domain models, zero dependencies. Deferred from Phase 1
// because nothing needed a domain model yet -- see Phase 1 README. Phase 2
// (Network Detection) is the first real consumer: architecture doc
// Section 6's NetworkState tree.
dependencies {
    testImplementation(libs.junit4)
}
