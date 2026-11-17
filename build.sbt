ThisBuild / scalaVersion := "3.3.7"
ThisBuild / organization := "skiwi"
ThisBuild / version      := "0.1.0-SNAPSHOT"
ThisBuild / licenses     := List("MIT" -> url("https://opensource.org/licenses/MIT"))
ThisBuild / scalacOptions ++= Seq("-deprecation", "-feature", "-Wunused:imports")

val http4sV = "0.23.30"
val circeV  = "0.14.10"
val doobieV = "1.0.0-RC8"
val otel4sV = "1.2.0"
val otelV   = "1.68.0"
val otelInstV = "2.33.1-alpha"

lazy val commonTest = Seq(
  libraryDependencies ++= Seq(
    "org.scalameta" %% "munit"               % "1.0.4" % Test,
    "org.typelevel" %% "munit-cats-effect"   % "2.0.0" % Test
  ),
  Test / fork := true,
  // テストのログの設定（project/test-resources/logback-test.xml. 全モジュールで共通）
  Test / unmanagedResourceDirectories += (ThisBuild / baseDirectory).value / "project" / "test-resources"
)

// バックエンドとフロントエンド(Scala.js)で共有する API の型
lazy val shared = crossProject(JVMPlatform, JSPlatform)
  .crossType(CrossType.Full) // 日時(Timestamp)だけ JVM と JS で実装が違う
  .in(file("modules/shared"))
  .settings(
    libraryDependencies ++= Seq(
      "io.circe" %%% "circe-core"   % circeV,
      "io.circe" %%% "circe-parser" % circeV
    )
  )

// フロントエンド: Scalactive の画面（frontend/ の Vite から読み込む. 入口は skiwi.web.Main）
lazy val web = (project in file("modules/web"))
  .enablePlugins(ScalaJSPlugin, ScalactivePlugin)
  .dependsOn(shared.js)
  .settings(
    scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.ESModule)),
    scalaJSUseMainModuleInitializer := true,
    libraryDependencies ++= Seq(
      "io.github.ondeoma" %%% "scalactive" % "0.6.2"
    )
  )

lazy val core = (project in file("modules/core"))
  .settings(commonTest)

lazy val db = (project in file("modules/db"))
  .dependsOn(core, observe)
  .settings(commonTest)
  .settings(
    libraryDependencies ++= Seq(
      "org.tpolecat"  %% "doobie-core"     % doobieV,
      "org.tpolecat"  %% "doobie-hikari"   % doobieV,
      "org.tpolecat"  %% "doobie-postgres" % doobieV,
      "io.circe"      %% "circe-parser"    % circeV,
      "org.postgresql" % "postgresql"      % "42.7.5",
      "org.flywaydb"   % "flyway-core"     % "11.3.0",
      "org.flywaydb"   % "flyway-database-postgresql" % "11.3.0" % Runtime
    )
  )

lazy val auth = (project in file("modules/auth"))
  .dependsOn(core)
  .settings(commonTest)
  .settings(
    libraryDependencies ++= Seq(
      "org.http4s" %% "http4s-client"       % http4sV,
      "org.http4s" %% "http4s-dsl"          % http4sV,
      "org.http4s" %% "http4s-circe"        % http4sV,
      "io.circe"   %% "circe-core"          % circeV,
      "io.circe"   %% "circe-parser"        % circeV
    )
  )

lazy val storage = (project in file("modules/storage"))
  .settings(commonTest)
  .settings(
    libraryDependencies ++= Seq(
      ("software.amazon.awssdk" % "s3"            % "2.30.38").exclude("software.amazon.awssdk", "netty-nio-client"),
      "software.amazon.awssdk" % "apache-client" % "2.30.38",
      "co.fs2"                %% "fs2-io"        % "3.11.0",
      "org.typelevel"         %% "cats-effect"   % "3.5.7"
    )
    // netty-nio-client は依存ごとの exclude で外す（プロジェクト全体の excludeDependencies だと
    // 下流モジュール(app/collab)の解決でAWS SDK自体が落ちる現象があったため）
  )

lazy val notifier = (project in file("modules/notify"))
  // circe-parser は db から。テストは db の DbSuite を使う
  // observe は db 経由でも入るが、"compile;test->test" の指定では observe のライブラリ（otel4s など）がクラスパスに入らないため直接も指定する
  .dependsOn(core, observe, db % "compile;test->test")
  .settings(commonTest)
  .settings(
    libraryDependencies ++= Seq(
      "org.eclipse.angus" % "angus-mail" % "2.0.3"
    )
  )

// 観測（OpenTelemetry: otel4s-oteljava）. ログ・メトリクス・トレースを OTLP で skorimako に送る
lazy val observe = (project in file("modules/observe"))
  .settings(commonTest)
  .settings(
    libraryDependencies ++= Seq(
      "org.typelevel"   %% "otel4s-oteljava"                 % otel4sV,
      "org.typelevel"   %% "otel4s-oteljava-context-storage" % otel4sV,
      "org.typelevel"   %% "otel4s-instrumentation-metrics"  % otel4sV,
      "io.opentelemetry" % "opentelemetry-exporter-otlp"     % otelV,
      "io.opentelemetry.instrumentation" % "opentelemetry-logback-appender-1.0" % otelInstV,
      "io.opentelemetry.instrumentation" % "opentelemetry-logback-mdc-1.0"      % otelInstV,
      "io.opentelemetry.instrumentation" % "opentelemetry-runtime-telemetry"    % otelInstV,
      "org.http4s"      %% "http4s-server"                   % http4sV,
      "org.http4s"      %% "http4s-client"                   % http4sV,
      "org.http4s"      %% "http4s-circe"                    % http4sV,
      "org.http4s"      %% "http4s-dsl"                      % http4sV,
      "ch.qos.logback"   % "logback-classic"                 % "1.5.18",
      "org.tpolecat"    %% "doobie-core"                     % doobieV
    )
  )

// プッシュ通知（PWA. Web Push の暗号化と VAPID は JDK の暗号の機能だけで実装）
lazy val push = (project in file("modules/push"))
  .dependsOn(db, observe)
  .settings(commonTest)
  .settings(
    libraryDependencies ++= Seq(
      "org.http4s" %% "http4s-client" % http4sV,
      "io.circe"   %% "circe-core"    % circeV
    )
  )

lazy val api = (project in file("modules/api"))
  .dependsOn(core, db % "compile;test->test", auth, storage, shared.jvm, observe, push) // テストは db の DbSuite を使う
  .settings(commonTest)
  .settings(
    libraryDependencies ++= Seq(
      "org.http4s" %% "http4s-dsl"   % http4sV,
      "org.http4s" %% "http4s-circe" % http4sV,
      "org.apache.pdfbox" % "pdfbox" % "3.0.4" // 添付の PDF から文字を取り出して検索に使う
    )
  )

lazy val collab = (project in file("modules/collab"))
  .dependsOn(api) // core / db、http4s-dsl・circe-parser は api から
  .settings(commonTest)
  .settings(
    libraryDependencies ++= Seq(
      "org.http4s" %% "http4s-server" % http4sV,
      "org.http4s" %% "http4s-jdk-http-client" % "0.10.0" // 担当ノードへのWebSocket転送
    )
  )

lazy val app = (project in file("modules/app"))
  .dependsOn(api, collab, notifier)
  .settings(
    libraryDependencies ++= Seq(
      "org.http4s"     %% "http4s-ember-server" % http4sV,
      "org.http4s"     %% "http4s-ember-client" % http4sV,
      "ch.qos.logback"  % "logback-classic"     % "1.5.18"
    ),
    Compile / mainClass := Some("skiwi.app.Main"),
    fork := true
  )

lazy val root = (project in file("."))
  .aggregate(shared.jvm, shared.js, web, core, db, auth, storage, notifier, observe, push, api, collab, app)
  .settings(name := "skiwi", publish / skip := true)
