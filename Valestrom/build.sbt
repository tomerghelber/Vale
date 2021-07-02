name := "Valestrom"

version := "1.0"

scalaVersion := "2.12.6"

logLevel := Level.Debug

(unmanagedSourceDirectories) in Compile := Seq(
    (baseDirectory.value / "Von" / "src"),
    (baseDirectory.value / "Utils" / "src"),
    (baseDirectory.value / "Parser" / "src"),
    (baseDirectory.value / "Astronomer" / "src"),
    (baseDirectory.value / "Driver" / "src"),
    (baseDirectory.value / "Hammer" / "src"),
    (baseDirectory.value / "Highlighter" / "src"),
    (baseDirectory.value / "Hinputs" / "src"),
    (baseDirectory.value / "Builtins" / "src"),
    (baseDirectory.value / "Metal" / "src"),
    (baseDirectory.value / "Samples" / "src"),
    (baseDirectory.value / "Scout" / "src"),
    (baseDirectory.value / "Templar" / "src"),
    (baseDirectory.value / "Templata" / "src"),
    (baseDirectory.value / "Vivem" / "src"))

(unmanagedSourceDirectories) in Test := Seq(
    (baseDirectory.value / "Tests" / "src"),
    (baseDirectory.value / "Tests" / "test"),
    (baseDirectory.value / "IntegrationTests" / "test"),
    (baseDirectory.value / "Von" / "test"),
    (baseDirectory.value / "Utils" / "test"),
    (baseDirectory.value / "Parser" / "test"),
    (baseDirectory.value / "Astronomer" / "test"),
    (baseDirectory.value / "Driver" / "test"),
    (baseDirectory.value / "Hammer" / "test"),
    (baseDirectory.value / "Highlighter" / "test"),
    (baseDirectory.value / "Hinputs" / "test"),
    (baseDirectory.value / "Builtins" / "test"),
    (baseDirectory.value / "Metal" / "test"),
    (baseDirectory.value / "Samples" / "test"),
    (baseDirectory.value / "Scout" / "test"),
    (baseDirectory.value / "Templar" / "test"),
    (baseDirectory.value / "Templata" / "test"),
    (baseDirectory.value / "Vivem" / "test"))

unmanagedJars in Compile += (baseDirectory.value / "lib" / "scala-parser-combinators_2.12-1.1.1.jar")

(unmanagedResourceDirectories) in Compile := Seq(
    baseDirectory.value / "Builtins" / "src" / "net" / "verdagon" / "vale" / "resources")

unmanagedJars in Test += (baseDirectory.value / "lib" / "scalatest_2.12-3.0.8.jar")
unmanagedJars in Test += (baseDirectory.value / "lib" / "scalactic_2.12-3.0.8.jar")

(unmanagedResourceDirectories) in Test := Seq(
    baseDirectory.value / "Tests" / "test" / "main" / "resources")

test in assembly := {}
assemblyJarName in assembly := "Valestrom.jar"
assemblyOutputPath in assembly := (baseDirectory.value / "Valestrom.jar")
