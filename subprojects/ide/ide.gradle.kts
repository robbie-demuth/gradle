import accessors.groovy
import org.gradle.gradlebuild.testing.integrationtests.cleanup.WhenNotEmpty
import org.gradle.gradlebuild.unittestandcompile.ModuleType

/*
 * Copyright 2010 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

dependencies {
    compile(library("groovy"))

    compile(project(":scala"))
    compile(project(":core"))
    compile(project(":plugins"))
    compile(project(":ear"))
    compile(project(":toolingApi"))
    compile(library("slf4j_api"))
    compile(library("inject"))

    testCompile(testLibrary("xmlunit"))
    testCompile("nl.jqno.equalsverifier:equalsverifier:2.1.6")
    testCompile(project(":dependencyManagement"))

    testFixturesApi(project(":internalTesting"))
    testFixturesApi(project(":internalIntegTesting"))
}

gradlebuildJava {
    moduleType = ModuleType.CORE
}

testFixtures {
    from(":core")
    from(":dependencyManagement")
}

testFilesCleanup {
    policy.set(WhenNotEmpty.REPORT)
}
