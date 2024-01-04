// See README.md for license details.

name := "chisel-dma"

version := "3.6-SNAPSHOT"

scalaVersion := "2.13.10"

crossScalaVersions := Seq("2.13.10", "2.12.17")

scalacOptions ++= Seq("-encoding",
  "UTF-8",
  "-unchecked",
  "-deprecation",
  "-feature",
  "-language:reflectiveCalls",
  "-Xfatal-warnings",
  "-Ymacro-annotations")

javacOptions ++= Seq("-source", "1.8", "-target", "1.8")

resolvers ++= Seq(
  Resolver.sonatypeRepo("snapshots"),
  Resolver.sonatypeRepo("releases"),
  Resolver.mavenLocal)

// Chisel 3.6-SNAPSHOT
addCompilerPlugin("edu.berkeley.cs" % "chisel3-plugin"
  % ("3.5.6") cross CrossVersion.full)

// Provide a managed dependency on X if -DXVersion="" is supplied on the command line.
val defaultVersions = Map(
  "chisel3" -> "3.5.6",
  "chiseltest" -> "0.5-SNAPSHOT",
  "chisel-iotesters" -> "2.5.6",
  "rocketchip-macros" -> "1.6.0-fcdfff6c7-SNAPSHOT",
  "rocketchip" -> "1.6.0-fcdfff6c7-SNAPSHOT",
  "cde" -> "1.6-f11fb1aea-SNAPSHOT"
  )
libraryDependencies ++= Seq("chisel3","chiseltest","chisel-iotesters", "rocketchip-macros", "rocketchip", "cde").map {
  dep: String => "edu.berkeley.cs" %% dep % sys.props.getOrElse(dep + "Version", defaultVersions(dep)) }

libraryDependencies += "com.typesafe.play" %% "play-json" % "2.8.+"

