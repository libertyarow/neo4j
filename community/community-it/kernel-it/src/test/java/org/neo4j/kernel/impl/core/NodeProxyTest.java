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
package org.neo4j.kernel.impl.core;

import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.RandomUtils;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import org.neo4j.exceptions.KernelException;
import org.neo4j.graphdb.ConstraintViolationException;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.NotFoundException;
import org.neo4j.graphdb.PropertyContainer;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.graphdb.Transaction;
import org.neo4j.internal.helpers.collection.Iterables;
import org.neo4j.internal.helpers.collection.Iterators;

import static java.util.Collections.singletonList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.neo4j.internal.helpers.NamedThreadFactory.named;
import static org.neo4j.test.DoubleLatch.awaitLatch;

public class NodeProxyTest extends PropertyContainerProxyTest
{
    private final String PROPERTY_KEY = "PROPERTY_KEY";

    @Override
    protected long createPropertyContainer()
    {
        return db.createNode().getId();
    }

    @Override
    protected PropertyContainer lookupPropertyContainer( long id )
    {
        return db.getNodeById( id );
    }

    @Test
    void shouldThrowHumaneExceptionsWhenPropertyDoesNotExistOnNode()
    {
        // Given a database with PROPERTY_KEY in it
        createNodeWith( PROPERTY_KEY );

        // When trying to get property from node without it
        NotFoundException exception = assertThrows( NotFoundException.class, () ->
        {
            try ( Transaction ignored = db.beginTx() )
            {
                Node node = db.createNode();
                node.getProperty( PROPERTY_KEY );
            }
        } );
        assertThat( exception.getMessage(), containsString( PROPERTY_KEY ) );
    }

    @Test
    void createDropNodeLongStringProperty()
    {
        Label markerLabel = Label.label( "marker" );
        String testPropertyKey = "testProperty";
        String propertyValue = RandomStringUtils.randomAscii( 255 );

        try ( Transaction tx = db.beginTx() )
        {
            Node node = db.createNode( markerLabel );
            node.setProperty( testPropertyKey, propertyValue );
            tx.success();
        }

        try ( Transaction tx = db.beginTx() )
        {
            Node node = Iterators.single( db.findNodes( markerLabel ) );
            assertEquals( propertyValue, node.getProperty( testPropertyKey ) );
            tx.success();
        }

        try ( Transaction tx = db.beginTx() )
        {
            Node node = Iterators.single( db.findNodes( markerLabel ) );
            node.removeProperty( testPropertyKey );
            tx.success();
        }

        try ( Transaction tx = db.beginTx() )
        {
            Node node = Iterators.single( db.findNodes( markerLabel ) );
            assertFalse( node.hasProperty( testPropertyKey ) );
            tx.success();
        }
    }

    @Test
    void createDropNodeLongArrayProperty()
    {
        Label markerLabel = Label.label( "marker" );
        String testPropertyKey = "testProperty";
        byte[] propertyValue = RandomUtils.nextBytes( 1024 );

        try ( Transaction tx = db.beginTx() )
        {
            Node node = db.createNode( markerLabel );
            node.setProperty( testPropertyKey, propertyValue );
            tx.success();
        }

        try ( Transaction tx = db.beginTx() )
        {
            Node node = Iterators.single( db.findNodes( markerLabel ) );
            assertArrayEquals( propertyValue, (byte[]) node.getProperty( testPropertyKey ) );
            tx.success();
        }

        try ( Transaction tx = db.beginTx() )
        {
            Node node = Iterators.single( db.findNodes( markerLabel ) );
            node.removeProperty( testPropertyKey );
            tx.success();
        }

        try ( Transaction tx = db.beginTx() )
        {
            Node node = Iterators.single( db.findNodes( markerLabel ) );
            assertFalse( node.hasProperty( testPropertyKey ) );
            tx.success();
        }
    }

    @Test
    void shouldThrowHumaneExceptionsWhenPropertyDoesNotExist()
    {
        // Given a database without PROPERTY_KEY in it

        // When
        try ( Transaction ignored = db.beginTx() )
        {
            Node node = db.createNode();
            node.getProperty( PROPERTY_KEY );
        }
        // Then
        catch ( NotFoundException exception )
        {
            assertThat( exception.getMessage(), containsString( PROPERTY_KEY ) );
        }
    }

    @Test
    void deletionOfSameNodeTwiceInOneTransactionShouldNotRollbackIt()
    {
        // Given
        Node node;
        try ( Transaction tx = db.beginTx() )
        {
            node = db.createNode();
            tx.success();
        }

        // When
        Exception exceptionThrownBySecondDelete = null;

        try ( Transaction tx = db.beginTx() )
        {
            node.delete();
            try
            {
                node.delete();
            }
            catch ( Exception e )
            {
                exceptionThrownBySecondDelete = e;
            }
            tx.success();
        }

        // Then
        assertThat( exceptionThrownBySecondDelete, instanceOf( NotFoundException.class ) );

        assertThrows( NotFoundException.class, () ->
        {
            try ( Transaction tx = db.beginTx() )
            {
                db.getNodeById( node.getId() ); // should throw NotFoundException
                tx.success();
            }
        } );
    }

    @Test
    void deletionOfAlreadyDeletedNodeShouldThrow()
    {
        // Given
        Node node;
        try ( Transaction tx = db.beginTx() )
        {
            node = db.createNode();
            tx.success();
        }
        try ( Transaction tx = db.beginTx() )
        {
            node.delete();
            tx.success();
        }

        // When
        assertThrows( NotFoundException.class, () ->
        {
            try ( Transaction tx = db.beginTx() )
            {
                node.delete(); // should throw NotFoundException as this node is already deleted
                tx.success();
            }
        } );
    }

    @Test
    void getAllPropertiesShouldWorkFineWithConcurrentPropertyModifications() throws Exception
    {
        // Given
        ExecutorService executor = Executors.newFixedThreadPool( 2, named( "Test-executor-thread" ) );
        try
        {
            final int propertiesCount = 100;

            final long nodeId;
            try ( Transaction tx = db.beginTx() )
            {
                Node node = db.createNode();
                nodeId = node.getId();
                for ( int i = 0; i < propertiesCount; i++ )
                {
                    node.setProperty( "property-" + i, i );
                }
                tx.success();
            }

            final CountDownLatch start = new CountDownLatch( 1 );
            final AtomicBoolean writerDone = new AtomicBoolean();

            Runnable writer = () ->
            {
                try
                {
                    awaitLatch( start );
                    int propertyKey = 0;
                    while ( propertyKey < propertiesCount )
                    {
                        try ( Transaction tx = db.beginTx() )
                        {
                            Node node = db.getNodeById( nodeId );
                            for ( int i = 0; i < 10 && propertyKey < propertiesCount; i++, propertyKey++ )
                            {
                                node.setProperty( "property-" + propertyKey, UUID.randomUUID().toString() );
                            }
                            tx.success();
                        }
                    }
                }
                finally
                {
                    writerDone.set( true );
                }
            };
            Runnable reader = () ->
            {
                try ( Transaction tx = db.beginTx() )
                {
                    Node node = db.getNodeById( nodeId );
                    awaitLatch( start );
                    while ( !writerDone.get() )
                    {
                        int size = node.getAllProperties().size();
                        assertThat( size, greaterThan( 0 ) );
                    }
                    tx.success();
                }
            };

            Future<?> readerFuture = executor.submit( reader );
            Future<?> writerFuture = executor.submit( writer );

            start.countDown();

            // When
            writerFuture.get();
            readerFuture.get();

            // Then
            try ( Transaction tx = db.beginTx() )
            {
                assertEquals( propertiesCount, db.getNodeById( nodeId ).getAllProperties().size() );
                tx.success();
            }
        }
        finally
        {
            executor.shutdown();
        }
    }

    @Test
    void shouldBeAbleToForceTypeChangeOfProperty()
    {
        // Given
        Node node;
        try ( Transaction tx = db.beginTx() )
        {
            node = db.createNode();
            node.setProperty( "prop", 1337 );
            tx.success();
        }

        // When
        try ( Transaction tx = db.beginTx() )
        {
            node.setProperty( "prop", 1337.0 );
            tx.success();
        }

        // Then
        try ( Transaction ignore = db.beginTx() )
        {
            assertThat( node.getProperty( "prop" ), instanceOf( Double.class ) );
        }
    }

    @Test
    void shouldOnlyReturnTypeOnce()
    {
        // Given
        Node node;
        try ( Transaction tx = db.beginTx() )
        {
            node = db.createNode();
            node.createRelationshipTo( db.createNode(), RelationshipType.withName( "R" ) );
            node.createRelationshipTo( db.createNode(), RelationshipType.withName( "R" ) );
            node.createRelationshipTo( db.createNode(), RelationshipType.withName( "R" ) );
            tx.success();
        }

        // Then
        try ( Transaction tx = db.beginTx() )
        {
            assertThat( Iterables.asList( node.getRelationshipTypes() ),
                    equalTo( singletonList( RelationshipType.withName( "R" ) ) ) );
        }
    }

    @Test
    void shouldThrowCorrectExceptionOnLabelTokensExceeded() throws KernelException
    {
        // given
        EmbeddedProxySPI spi = mockedProxySPIWithDepletedTokens();
        NodeProxy nodeProxy = new NodeProxy( spi, 5 );

        // when
        assertThrows( ConstraintViolationException.class, () -> nodeProxy.addLabel( Label.label( "Label" ) ) );
    }

    @Test
    void shouldThrowCorrectExceptionOnPropertyKeyTokensExceeded() throws KernelException
    {
        // given
        EmbeddedProxySPI spi = mockedProxySPIWithDepletedTokens();
        NodeProxy nodeProxy = new NodeProxy( spi, 5 );

        // when
        assertThrows( ConstraintViolationException.class, () -> nodeProxy.setProperty( "key", "value" ) );
    }

    @Test
    void shouldThrowCorrectExceptionOnRelationshipTypeTokensExceeded() throws KernelException
    {
        // given
        EmbeddedProxySPI spi = mockedProxySPIWithDepletedTokens();
        NodeProxy nodeProxy = new NodeProxy( spi, 5 );

        // when
        assertThrows( ConstraintViolationException.class, () -> nodeProxy.setProperty( "key", "value" ) );
    }

    private void createNodeWith( String key )
    {
        try ( Transaction tx = db.beginTx() )
        {
            Node node = db.createNode();
            node.setProperty( key, 1 );
            tx.success();
        }
    }
}
