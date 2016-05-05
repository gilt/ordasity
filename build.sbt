name := "ordasity-scala"

organization := "com.gilt"

scalaVersion := "2.11.8"

publishTo := {
  val nexus = "https://nexus.gilt.com/nexus/"
  if (isSnapshot.value)
    Some("snapshots" at nexus + "content/repositories/gilt.snapshots")
  else
    Some("releases"  at nexus + "content/repositories/internal-releases/")
}

credentials += Credentials(Path.userHome / ".ivy2" / ".credentials")

testOptions += Tests.Argument(TestFrameworks.JUnit, "-q", "-v")

val jacksonVersion = "2.6.5"

val slf4jVersion = "1.7.7"

libraryDependencies ++= Seq(
  "com.gilt"                      %%   "overlock-scala"        % "0.10.0",
  "com.fasterxml.jackson.core"     %   "jackson-databind"      % jacksonVersion,
  "com.fasterxml.jackson.module"  %%   "jackson-module-scala"  % jacksonVersion,
  "org.slf4j"                      %   "log4j-over-slf4j"      % slf4jVersion,
  "org.slf4j"                      %   "slf4j-simple"          % slf4jVersion,
  "org.apache.zookeeper"           %   "zookeeper"             % "3.3.6" excludeAll(
    ExclusionRule(organization = "log4j"),
    ExclusionRule(organization = "jline")
  ),
  "com.twitter.common.zookeeper"  %    "client"                % "0.0.46" excludeAll(
    ExclusionRule(organization = "org.apache.zookeeper"),
    ExclusionRule(organization = "log4j"),
    ExclusionRule(organization = "jline")
  ),
  "com.twitter.common.zookeeper"  %   "map"                    % "0.0.39" excludeAll(
    ExclusionRule(organization = "org.apache.zookeeper"),
    ExclusionRule(organization = "jline")
  ),
  "junit"                         %   "junit"                  % "4.12"       %   "test",
  "com.novocode"                  %   "junit-interface"        % "0.11"       %   "test",
  "com.simple"                   %%   "simplespec"             % "0.8.4"      %   "test",
  "org.mockito"                   %   "mockito-all"            % "1.9.5"      %   "test"
)
