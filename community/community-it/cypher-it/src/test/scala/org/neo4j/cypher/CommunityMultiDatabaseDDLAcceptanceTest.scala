/*
 * Copyright (c) 2002-2019 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher

import java.io.File

import org.neo4j.configuration.Config
import org.neo4j.configuration.GraphDatabaseSettings.{DEFAULT_DATABASE_NAME, SYSTEM_DATABASE_NAME, default_database}
import org.neo4j.cypher.internal.javacompat.GraphDatabaseCypherService
import org.neo4j.dbms.database.DefaultSystemGraphInitializer
import org.neo4j.graphdb.config.Setting
import org.neo4j.kernel.database.TestDatabaseIdRepository
import org.neo4j.kernel.impl.core.ThreadToStatementContextBridge
import org.neo4j.logging.Log
import org.neo4j.server.security.auth.{InMemoryUserRepository, SecureHasher}
import org.neo4j.server.security.systemgraph.{BasicSystemGraphOperations, ContextSwitchingSystemGraphQueryExecutor, UserSecurityGraphInitializer}

import scala.collection.Map

class CommunityMultiDatabaseDDLAcceptanceTest extends CommunityDDLAcceptanceTestBase {
  private val defaultConfig = Config.defaults()
  private val databaseIdRepository = new TestDatabaseIdRepository()

  test("should show default database") {
    // GIVEN
    setup( defaultConfig )

    // WHEN
    val result = execute("SHOW DEFAULT DATABASE")

    // THEN
    result.toList should be(List(Map("name" -> "neo4j")))
  }

  test("should show custom default database using show default database command") {
    // GIVEN
    val config = Config.defaults()
    config.augment(default_database, "foo")
    setup(config)

    // WHEN
    val result = execute("SHOW DEFAULT DATABASE")

    // THEN
    result.toList should be(List(Map("name" -> "foo")))
  }

  test("should fail when showing default database when not on system database") {
    setup(defaultConfig)
    selectDatabase(DEFAULT_DATABASE_NAME)
    the [DatabaseManagementException] thrownBy {
      // WHEN
      execute("SHOW DEFAULT DATABASE")
      // THEN
    } should have message "Trying to run `CATALOG SHOW DEFAULT DATABASE` against non-system database."
  }

  test("should fail on showing database from community") {
    setup( defaultConfig )
    assertFailure("SHOW DATABASE neo4j", "Unsupported management command: SHOW DATABASE neo4j")
  }

  test("should fail on showing non-existing database with correct error message") {
    setup( defaultConfig )
    assertFailure("SHOW DATABASE foo", "Unsupported management command: SHOW DATABASE foo")
  }

  test("should fail on showing databases from community") {
    setup( defaultConfig )
    assertFailure("SHOW DATABASES", "Unsupported management command: SHOW DATABASES")
  }

  test("should fail on creating database from community") {
    setup( defaultConfig )
    assertFailure("CREATE DATABASE foo", "Unsupported management command: CREATE DATABASE foo")
  }

  test("should fail on creating already existing database with correct error message") {
    setup( defaultConfig )
    assertFailure("CREATE DATABASE neo4j", "Unsupported management command: CREATE DATABASE neo4j")
  }

  test("should fail on dropping database from community") {
    setup( defaultConfig )
    assertFailure("DROP DATABASE neo4j", "Unsupported management command: DROP DATABASE neo4j")
  }

  test("should fail on dropping non-existing database with correct error message") {
    setup( defaultConfig )
    assertFailure("DROP DATABASE foo", "Unsupported management command: DROP DATABASE foo")
  }

  test("should fail on starting database from community") {
    setup( defaultConfig )
    assertFailure("START DATABASE neo4j", "Unsupported management command: START DATABASE neo4j")
  }

  test("should fail on starting non-existing database with correct error message") {
    setup( defaultConfig )
    assertFailure("START DATABASE foo", "Unsupported management command: START DATABASE foo")
  }

  test("should fail on stopping database from community") {
    setup( defaultConfig )
    assertFailure("STOP DATABASE neo4j", "Unsupported management command: STOP DATABASE neo4j")
  }

  test("should fail on stopping non-existing database with correct error message") {
    setup( defaultConfig )
    assertFailure("STOP DATABASE foo", "Unsupported management command: STOP DATABASE foo")
  }

  // Disable normal database creation because we need different settings on each test
  override protected def initTest() {}

  protected def setup(config: Config) {
    managementService = graphDatabaseFactory(new File("test")).impermanent().setConfigRaw(config.getRaw).setInternalLogProvider(logProvider).build()
    graphOps = managementService.database(SYSTEM_DATABASE_NAME)
    graph = new GraphDatabaseCypherService(graphOps)

    val queryExecutor: ContextSwitchingSystemGraphQueryExecutor = new ContextSwitchingSystemGraphQueryExecutor(databaseManager, threadToStatementContextBridge(), databaseIdRepository)
    val secureHasher: SecureHasher = new SecureHasher
    val systemGraphOperations: BasicSystemGraphOperations = new BasicSystemGraphOperations(queryExecutor, secureHasher)

    val securityGraphInitializer = new UserSecurityGraphInitializer(
      new DefaultSystemGraphInitializer(databaseManager, databaseIdRepository, config),
      queryExecutor,
      mock[Log],
      systemGraphOperations,
      () => new InMemoryUserRepository,
      () => new InMemoryUserRepository,
      secureHasher)

    securityGraphInitializer.initializeSecurityGraph()
    selectDatabase(SYSTEM_DATABASE_NAME)
  }

  private def threadToStatementContextBridge(): ThreadToStatementContextBridge = {
    graph.getDependencyResolver.resolveDependency(classOf[ThreadToStatementContextBridge])
  }

  // Use the default value instead of the new value in CommunityDDLAcceptanceTestBase
  override def databaseConfig(): Map[Setting[_], String] = Map()
}