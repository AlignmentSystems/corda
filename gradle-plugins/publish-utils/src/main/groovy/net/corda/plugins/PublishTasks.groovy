package net.corda.plugins

import org.gradle.api.*
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.javadoc.Javadoc
import org.gradle.api.Project

/**
 * A utility plugin that when applied will automatically create source and javadoc publishing tasks
 */
class PublishTasks implements Plugin<Project> {
    void apply(Project project) {
        project.task("sourceJar", type: Jar, dependsOn: project.classes) {
            classifier = 'sources'
            from project.sourceSets.main.allSource
        }

        project.task("javadocJar", type: Jar, dependsOn: project.javadoc) {
            classifier = 'javadoc'
            from project.javadoc.destinationDir
        }
    }
}
