ThisBuild / scalaVersion := "3.3.7"
ThisBuild / organization := "io.github.ondeoma"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / licenses := List("MIT" -> url("https://opensource.org/licenses/MIT"))
ThisBuild / homepage := Some(url("https://github.com/Ondeoma/skiwi"))
ThisBuild / scalacOptions ++= Seq("-deprecation", "-feature", "-Wunused:imports", "-no-indent")

val http4sV = "1.0.0-M49"
val circeV = "0.14.16"
val doobieV = "1.0.0-RC12"
val ceV = "3.7.1"
val munitV = "1.3.6"
val flywayV = "11.3.0"

// sbirds シリーズの共通ライブラリ（GitHub のリポジトリを直接参照する. 手元の sbirds-lib で試すときは SBIRDS_LIB=../sbirds-lib）
lazy val sbirdsLib = sys.env
  .get("SBIRDS_LIB")
  .map(p => file(p).getCanonicalFile.toURI)
  .getOrElse(uri("https://github.com/Ondeoma/sbirds-lib.git#v0.1.0"))
lazy val libSharedJVM = ProjectRef(sbirdsLib, "sharedJVM")
lazy val libSharedJS = ProjectRef(sbirdsLib, "sharedJS")
lazy val libObserve = ProjectRef(sbirdsLib, "observe")
lazy val libAuth = ProjectRef(sbirdsLib, "auth")
lazy val libHttp = ProjectRef(sbirdsLib, "http")
lazy val libPush = ProjectRef(sbirdsLib, "push")
lazy val libWeb = ProjectRef(sbirdsLib, "web")
lazy val libMail = ProjectRef(sbirdsLib, "mail")
lazy val libStorage = ProjectRef(sbirdsLib, "storage")

lazy val commonTest = Seq(
  libraryDependencies ++= Seq(
    "org.scalameta" %% "munit" % munitV % Test,
    "org.typelevel" %% "munit-cats-effect" % "2.2.1" % Test
  ),
  Test / fork := true,
  // テストのログの設定（project/test-resources/logback-test.xml. 全モジュールで共通）
  Test / unmanagedResourceDirectories += (ThisBuild / baseDirectory).value / "project" / "test-resources"
)

// バックエンドとフロントエンド(Scala.js)で共有する API の型
lazy val shared = crossProject(JVMPlatform, JSPlatform)
  .crossType(CrossType.Full)
  .in(file("modules/shared"))
  .jvmConfigure(_.dependsOn(libSharedJVM))
  .jsConfigure(_.dependsOn(libSharedJS))
  .settings(
    libraryDependencies ++= Seq(
      "io.circe" %%% "circe-core" % circeV,
      "io.circe" %%% "circe-parser" % circeV
    )
  )

// フロントエンド: Scalactive の画面（frontend/ の Vite から読み込む. 入口は skiwi.web.Main）
lazy val web = (project in file("modules/web"))
  .enablePlugins(ScalaJSPlugin, ScalactivePlugin)
  .dependsOn(shared.js, libWeb)
  .settings(
    scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.ESModule)),
    scalaJSUseMainModuleInitializer := true,
    libraryDependencies ++= Seq(
      "io.github.ondeoma" %%% "scalactive" % "0.6.2"
    )
  )

lazy val core = (project in file("modules/core"))
  .dependsOn(shared.jvm)
  .settings(commonTest)

lazy val db = (project in file("modules/db"))
  .dependsOn(core, libObserve)
  .settings(commonTest)
  .settings(
    libraryDependencies ++= Seq(
      "org.tpolecat" %% "doobie-core" % doobieV,
      "org.tpolecat" %% "doobie-hikari" % doobieV,
      "org.tpolecat" %% "doobie-postgres" % doobieV,
      "org.typelevel" %% "cats-effect" % ceV,
      "io.circe" %% "circe-parser" % circeV,
      "org.postgresql" % "postgresql" % "42.7.14",
      "org.flywaydb" % "flyway-core" % flywayV,
      "org.flywaydb" % "flyway-database-postgresql" % flywayV % Runtime
    )
  )

// サービス固有の認証の部品（SecretBox. OIDC・Crypto は sbirds-lib の auth）
lazy val auth = (project in file("modules/auth"))
  .dependsOn(core, libAuth)
  .settings(commonTest)

lazy val notifier = (project in file("modules/notify"))
  // circe-parser は db から。テストは db の DbSuite を使う
  // observe は db 経由でも入るが、"compile;test->test" の指定では observe のライブラリ（otel4s など）がクラスパスに入らないため直接も指定する
  .dependsOn(core, libObserve, libMail, shared.jvm, db % "compile;test->test")
  .settings(commonTest)

lazy val api = (project in file("modules/api"))
  .dependsOn(
    core,
    db % "compile;test->test",
    auth,
    shared.jvm,
    libObserve,
    libHttp,
    libPush,
    libStorage
  ) // テストは db の DbSuite を使う
  .settings(commonTest)
  .settings(
    libraryDependencies ++= Seq(
      "org.http4s" %% "http4s-dsl" % http4sV,
      "org.http4s" %% "http4s-circe" % http4sV,
      "org.http4s" %% "http4s-ember-client" % http4sV, // 外への接続（リンクのカード・Webhook）。接続先のアドレスを確かめる（NetGuard）
      "org.apache.pdfbox" % "pdfbox" % "3.0.4" // 添付の PDF から文字を取り出して検索に使う
    )
  )

lazy val collab = (project in file("modules/collab"))
  .dependsOn(api) // core / db、http4s-dsl・circe-parser は api から
  .settings(commonTest)
  .settings(
    libraryDependencies ++= Seq(
      "org.http4s" %% "http4s-server" % http4sV,
      "org.http4s" %% "http4s-client" % http4sV, // 担当ノードへのWebSocket転送（WSClient. 実装は JDK 標準の WebSocket）
      "org.http4s" %% "http4s-ember-server" % http4sV % Test // JdkWebSocket のテストで相手のサーバーにする
    )
  )

lazy val app = (project in file("modules/app"))
  .dependsOn(api, collab, notifier)
  .settings(
    libraryDependencies ++= Seq(
      "org.http4s" %% "http4s-ember-server" % http4sV,
      "org.http4s" %% "http4s-ember-client" % http4sV,
      "ch.qos.logback" % "logback-classic" % "1.5.18",
      "org.typelevel" %% "log4cats-slf4j" % "2.8.0"
    ),
    Compile / mainClass := Some("skiwi.app.Main"),
    fork := true
  )

lazy val root = (project in file("."))
  .aggregate(shared.jvm, shared.js, web, core, db, auth, notifier, api, collab, app)
  .settings(name := "skiwi", publish / skip := true)
