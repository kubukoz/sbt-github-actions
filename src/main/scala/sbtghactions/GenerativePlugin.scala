/*
 * Copyright 2020 Daniel Spiewak
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

package sbtghactions

import sbt._, Keys._

import scala.io.Source

import java.io.{BufferedWriter, FileWriter}

object GenerativePlugin extends AutoPlugin {

  override def requires = plugins.JvmPlugin
  override def trigger = allRequirements

  object autoImport extends GenerativeKeys

  import autoImport._

  private def indent(output: String, level: Int): String = {
    val space = (0 until level * 2).map(_ => ' ').mkString
    (space + output.replace("\n", s"\n$space")).replaceAll("""\n[ ]+\n""", "\n\n")
  }

  private def isSafeString(str: String): Boolean =
    !(str.indexOf(':') >= 0 ||    // pretend colon is illegal everywhere for simplicity
      str.indexOf('#') >= 0 ||    // same for comment
      str.indexOf('!') == 0 ||
      str.indexOf('*') == 0 ||
      str.indexOf('-') == 0 ||
      str.indexOf('?') == 0 ||
      str.indexOf('{') == 0 ||
      str.indexOf('}') == 0 ||
      str.indexOf('[') == 0 ||
      str.indexOf(']') == 0 ||
      str.indexOf(',') == 0 ||
      str.indexOf('|') == 0 ||
      str.indexOf('>') == 0 ||
      str.indexOf('@') == 0 ||
      str.indexOf('`') == 0 ||
      str.indexOf('"') == 0 ||
      str.indexOf('\'') == 0 ||
      str.indexOf('&') == 0)

  private def wrap(str: String): String =
    if (str.indexOf('\n') >= 0)
      "|\n" + indent(str, 1)
    else if (isSafeString(str))
      str
    else
      s"'${str.replace("'", "''")}'"

  def compileList(items: List[String]): String =
    items.map(wrap).map("- " + _).mkString("\n")

  def compileEnv(env: Map[String, String], prefix: String = "env"): String =
    if (env.isEmpty) {
      ""
    } else {
      val rendered = env map {
        case (key, value) =>
          if (!isSafeString(key) || key.indexOf(' ') >= 0)
            sys.error(s"'$key' is not a valid environment variable name")

          s"""$key: ${wrap(value)}"""
      }
s"""$prefix:
${indent(rendered.mkString("\n"), 1)}"""
    }

  def compileStep(step: WorkflowStep, sbt: String, declareShell: Boolean = false): String = {
    import WorkflowStep._

    val renderedName = step.name.map(wrap).map("name: " + _ + "\n").getOrElse("")
    val renderedCond = step.cond.map(wrap).map("if: " + _ + "\n").getOrElse("")
    val renderedShell = if (declareShell) "shell: bash\n" else ""

    val renderedEnvPre = compileEnv(step.env)
    val renderedEnv = if (renderedEnvPre.isEmpty)
      ""
    else
      renderedEnvPre + "\n"

    val preamblePre = renderedName + renderedCond + renderedShell + renderedEnv

    val preamble = if (preamblePre.isEmpty)
      ""
    else
      preamblePre

    val body = step match {
      case Run(commands, _, _, _) =>
        "run: " + wrap(commands.mkString("\n"))

      case Sbt(commands, _, _, _) =>
        val safeCommands = commands map { c =>
          if (c.indexOf(' ') >= 0)
            s"'$c'"
          else
            c
        }

        "run: " + wrap(s"$sbt ++$${{ matrix.scala }} ${safeCommands.mkString(" ")}")

      case Use(owner, repo, version, params, _, _, _) =>
        val renderedParamsPre = compileEnv(params, prefix = "with")
        val renderedParams = if (renderedParamsPre.isEmpty)
          ""
        else
          "\n" + renderedParamsPre

        s"uses: $owner/$repo@v$version" + renderedParams
    }

    indent(preamble + body, 1).updated(0, '-')
  }

  def compileJob(job: WorkflowJob, sbt: String): String = {
    val renderedNeeds = if (job.needs.isEmpty)
      ""
    else
      s"\nneeds: [${job.needs.mkString(", ")}]"

    val renderedCond = job.cond.map(wrap).map("\nif: " + _).getOrElse("")

    val renderedEnvPre = compileEnv(job.env)
    val renderedEnv = if (renderedEnvPre.isEmpty)
      ""
    else
      "\n" + renderedEnvPre

    val declareShell = job.oses.exists(_.contains("windows"))

    val body = s"""name: ${wrap(job.name)}${renderedNeeds}${renderedCond}
strategy:
  matrix:
    os: [${job.oses.mkString(", ")}]
    scala: [${job.scalas.mkString(", ")}]
    java: [${job.javas.mkString(", ")}]
runs-on: $${{ matrix.os }}${renderedEnv}
steps:
${indent(job.steps.map(compileStep(_, sbt, declareShell = declareShell)).mkString("\n\n"), 1)}"""

    s"${job.id}:\n${indent(body, 1)}"
  }

  def compileWorkflow(name: String, branches: List[String], env: Map[String, String], jobs: List[WorkflowJob], sbt: String): String = {
    val renderedEnvPre = compileEnv(env)
    val renderedEnv = if (renderedEnvPre.isEmpty)
      ""
    else
      renderedEnvPre + "\n\n"

    s"""# This file was automatically generated by sbt-github-actions using the
# githubWorkflowGenerate task. You should add and commit this file to
# your git repository. It goes without saying that you shouldn't edit
# this file by hand! Instead, if you wish to make changes, you should
# change your sbt build configuration to revise the workflow description
# to meet your needs, then regenerate this file.

name: ${wrap(name)}

on:
  pull_request:
    branches: [${branches.map(wrap).mkString(", ")}]
  push:
    branches: [${branches.map(wrap).mkString(", ")}]

${renderedEnv}jobs:
${indent(jobs.map(compileJob(_, sbt)).mkString("\n\n"), 1)}"""
}

  val settingDefaults = Seq(
    githubWorkflowSbtCommand := "sbt",

    githubWorkflowBuildPreamble := Seq(),
    githubWorkflowBuild := WorkflowStep.Sbt(List("test"), name = Some("Build project")),

    githubWorkflowPublishPreamble := Seq(),
    githubWorkflowPublish := WorkflowStep.Sbt(List("+publish"), name = Some("Publish project")),
    githubWorkflowPublishBranchPatterns := Seq("master"),
    githubWorkflowPublishCond := None,

    githubWorkflowJavaVersions := Seq("adopt@1.8"),
    githubWorkflowScalaVersions := crossScalaVersions.value,
    githubWorkflowOSes := Seq("ubuntu-latest"),
    githubWorkflowDependencyPatterns := Seq("**/*.sbt", "project/build.properties"),
    githubWorkflowTargetBranches := Seq("*"),

    githubWorkflowEnv := Map("GITHUB_TOKEN" -> s"$${{ secrets.GITHUB_TOKEN }}"),
    githubWorkflowAddedJobs := Seq())

  private lazy val internalTargetAggregation = settingKey[Seq[File]]("Aggregates target directories from all subprojects")

  override def projectSettings = Seq(Global / internalTargetAggregation += target.value)

  override def globalSettings = settingDefaults ++ Seq(
    internalTargetAggregation := Seq(),

    githubWorkflowGeneratedCI := {
      val hashes = githubWorkflowDependencyPatterns.value map { glob =>
        s"$${{ hashFiles('$glob') }}"
      }

      val hashesStr = hashes.mkString("-")

      val base = (ThisBuild / baseDirectory).value.toPath

      val pathStrs = (internalTargetAggregation.value :+ file("project/target")) map { file =>
        val path = file.toPath

        if (path.isAbsolute)
          base.relativize(path).toString
        else
          path.toString
      }

      val uploadSteps = pathStrs map { target =>
        WorkflowStep.Use(
          "actions",
          "upload-artifact",
          1,
          name = Some(s"Upload target directory '$target'"),
          params = Map(
            "name" -> s"target-$${{ runner.os }}-$target",
            "path" -> target))
      }

      val downloadSteps = pathStrs map { target =>
        WorkflowStep.Use(
          "actions",
          "download-artifact",
          1,
          name = Some(s"Download target directory '$target'"),
          params = Map("name" -> s"target-$${{ runner.os }}-$target"))
      }

      val preamble = List(
        WorkflowStep.Checkout,
        WorkflowStep.SetupScala,

        WorkflowStep.Use(
          "actions",
          "cache",
          1,
          name = Some("Cache ivy2"),
          params = Map(
            "path" -> "~/.ivy2/cache",
            "key" -> s"$${{ runner.os }}-sbt-ivy-cache-$hashesStr")),

        WorkflowStep.Use(
          "actions",
          "cache",
          1,
          name = Some("Cache coursier"),
          params = Map(
            "path" -> "~/.cache/coursier/v1",
            "key" -> s"$${{ runner.os }}-sbt-coursier-cache-$hashesStr")),

        WorkflowStep.Use(
          "actions",
          "cache",
          1,
          name = Some("Cache sbt"),
          params = Map(
            "path" -> "~/.sbt",
            "key" -> s"$${{ runner.os }}-sbt-cache-$hashesStr")))

      val publicationCondPre =
        githubWorkflowPublishBranchPatterns.value.map(g => s"contains(github.ref, $g)").mkString("(", " || ", ")")

      val publicationCond = githubWorkflowPublishCond.value match {
        case Some(cond) => publicationCondPre + " && (" + cond + ")"
        case None => publicationCondPre
      }

      val publishJobOpt = Seq(
        WorkflowJob(
          "publish",
          "Publish Artifacts",
          preamble :::
            downloadSteps.toList :::
            githubWorkflowPublishPreamble.value.toList :::
            List(githubWorkflowPublish.value),   // TODO more steps
          cond = Some(s"github.event_name != 'pull_request' && $publicationCond"),
          scalas = List(scalaVersion.value),
          needs = List("build"))).filter(_ => !githubWorkflowPublishBranchPatterns.value.isEmpty)

      Seq(
        WorkflowJob(
          "build",
          "Build and Test",
          preamble :::
            githubWorkflowBuildPreamble.value.toList :::
            List(
              WorkflowStep.Sbt(
                List("githubWorkflowCheck"),
                name = Some("Check that workflows are up to date")),
              githubWorkflowBuild.value) :::
            uploadSteps.toList,
          oses = githubWorkflowOSes.value.toList,
          scalas = crossScalaVersions.value.toList)) ++ publishJobOpt ++ githubWorkflowAddedJobs.value
    })

  private val generateCiContents = Def task {
    compileWorkflow(
      "Continuous Integration",
      githubWorkflowTargetBranches.value.toList,
      githubWorkflowEnv.value,
      githubWorkflowGeneratedCI.value.toList,
      githubWorkflowSbtCommand.value)
  }

  private val readCleanContents = Def task {
    val src = Source.fromURL(getClass.getResource("/clean.yml"))
    try {
      src.getLines().mkString("\n")
    } finally {
      src.close()
    }
  }

  private val workflowsDirTask = Def task {
    val githubDir = baseDirectory.value / ".github"
    val workflowsDir = githubDir / "workflows"

    if (!githubDir.exists()) {
      githubDir.mkdir()
    }

    if (!workflowsDir.exists()) {
      workflowsDir.mkdir()
    }

    workflowsDir
  }

  private val ciYmlFile = Def task {
    workflowsDirTask.value / "ci.yml"
  }

  private val cleanYmlFile = Def task {
    workflowsDirTask.value / "clean.yml"
  }

  override def buildSettings = Seq(
    githubWorkflowGenerate / aggregate := false,
    githubWorkflowCheck / aggregate := false,

    githubWorkflowGenerate := {
      val ciContents = generateCiContents.value
      val cleanContents = readCleanContents.value

      val ciYml = ciYmlFile.value
      val cleanYml = cleanYmlFile.value

      val ciWriter = new BufferedWriter(new FileWriter(ciYml))
      try {
        ciWriter.write(ciContents)
      } finally {
        ciWriter.close()
      }

      val cleanWriter = new BufferedWriter(new FileWriter(cleanYml))
      try {
        cleanWriter.write(cleanContents)
      } finally {
        cleanWriter.close()
      }
    },

    githubWorkflowCheck := {
      val expectedCiContents = generateCiContents.value
      val expectedCleanContents = readCleanContents.value

      val ciYml = ciYmlFile.value
      val cleanYml = cleanYmlFile.value

      val ciSource = Source.fromFile(ciYml)
      val actualCiContents = try {
        ciSource.getLines().mkString("\n")
      } finally {
        ciSource.close()
      }

      if (expectedCiContents != actualCiContents) {
        sys.error("ci.yml does not contain contents that would have been generated by sbt-github-actions; try running githubWorkflowGenerate")
      }

      val cleanSource = Source.fromFile(cleanYml)
      val actualCleanContents = try {
        cleanSource.getLines().mkString("\n")
      } finally {
        cleanSource.close()
      }

      if (expectedCleanContents != actualCleanContents) {
        sys.error("clean.yml does not contain contents that would have been generated by sbt-github-actions; try running githubWorkflowGenerate")
      }
    })
}
