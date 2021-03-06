/**
 * Copyright (c) 2017 Dell Inc., or its subsidiaries. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.pravega.client.stream.impl;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import io.pravega.client.ClientFactory;
import io.pravega.client.batch.BatchClient;
import io.pravega.client.batch.impl.BatchClientImpl;
import io.pravega.client.netty.impl.ConnectionFactory;
import io.pravega.client.netty.impl.ConnectionFactoryImpl;
import io.pravega.client.segment.impl.Segment;
import io.pravega.client.segment.impl.SegmentInputStream;
import io.pravega.client.segment.impl.SegmentInputStreamFactory;
import io.pravega.client.segment.impl.SegmentInputStreamFactoryImpl;
import io.pravega.client.segment.impl.SegmentMetadataClient;
import io.pravega.client.segment.impl.SegmentMetadataClientFactory;
import io.pravega.client.segment.impl.SegmentMetadataClientFactoryImpl;
import io.pravega.client.segment.impl.SegmentOutputStream;
import io.pravega.client.segment.impl.SegmentOutputStreamFactory;
import io.pravega.client.segment.impl.SegmentOutputStreamFactoryImpl;
import io.pravega.client.state.InitialUpdate;
import io.pravega.client.state.Revisioned;
import io.pravega.client.state.RevisionedStreamClient;
import io.pravega.client.state.StateSynchronizer;
import io.pravega.client.state.SynchronizerConfig;
import io.pravega.client.state.Update;
import io.pravega.client.state.impl.RevisionedStreamClientImpl;
import io.pravega.client.state.impl.StateSynchronizerImpl;
import io.pravega.client.state.impl.UpdateOrInitSerializer;
import io.pravega.client.stream.EventStreamReader;
import io.pravega.client.stream.EventStreamWriter;
import io.pravega.client.stream.EventWriterConfig;
import io.pravega.client.stream.InvalidStreamException;
import io.pravega.client.stream.ReaderConfig;
import io.pravega.client.stream.Serializer;
import io.pravega.client.stream.Stream;
import io.pravega.common.concurrent.ExecutorServiceHelpers;
import io.pravega.common.concurrent.Futures;
import io.pravega.shared.NameUtils;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.Consumer;
import java.util.function.Supplier;

@Slf4j
public class ClientFactoryImpl implements ClientFactory {

    private final String scope;
    private final Controller controller;
    private final SegmentInputStreamFactory inFactory;
    private final SegmentOutputStreamFactory outFactory;
    private final SegmentMetadataClientFactory metaFactory;
    private final ConnectionFactory connectionFactory;

    /**
     * Creates a new instance of ClientFactory class.
     *
     * @param scope             The scope string.
     * @param controller        The reference to Controller.
     */
    public ClientFactoryImpl(String scope, Controller controller) {
        Preconditions.checkNotNull(scope);
        Preconditions.checkNotNull(controller);
        this.scope = scope;
        this.controller = controller;
        this.connectionFactory = new ConnectionFactoryImpl(false);
        this.inFactory = new SegmentInputStreamFactoryImpl(controller, connectionFactory);
        this.outFactory = new SegmentOutputStreamFactoryImpl(controller, connectionFactory);
        this.metaFactory = new SegmentMetadataClientFactoryImpl(controller, connectionFactory);
    }

    /**
     * Creates a new instance of the ClientFactory class.
     *
     * @param scope             The scope string.
     * @param controller        The reference to Controller.
     * @param connectionFactory The reference to Connection Factory impl.
     */
    @VisibleForTesting
    public ClientFactoryImpl(String scope, Controller controller, ConnectionFactory connectionFactory) {
        this(scope, controller, connectionFactory, new SegmentInputStreamFactoryImpl(controller, connectionFactory),
             new SegmentOutputStreamFactoryImpl(controller, connectionFactory),
             new SegmentMetadataClientFactoryImpl(controller, connectionFactory));
    }

    @VisibleForTesting
    public ClientFactoryImpl(String scope, Controller controller, ConnectionFactory connectionFactory,
            SegmentInputStreamFactory inFactory, SegmentOutputStreamFactory outFactory, SegmentMetadataClientFactory metaFactory) {
        Preconditions.checkNotNull(scope);
        Preconditions.checkNotNull(controller);
        Preconditions.checkNotNull(inFactory);
        Preconditions.checkNotNull(outFactory);
        Preconditions.checkNotNull(metaFactory);
        this.scope = scope;
        this.controller = controller;
        this.connectionFactory = connectionFactory;
        this.inFactory = inFactory;
        this.outFactory = outFactory;
        this.metaFactory = metaFactory;
    }

    @Override
    public <T> EventStreamWriter<T> createEventWriter(String streamName, Serializer<T> s, EventWriterConfig config) {
        log.info("Creating writer for stream: {} with configuration: {}", streamName, config);
        Stream stream = new StreamImpl(scope, streamName);
        ThreadPoolExecutor executor = ExecutorServiceHelpers.getShrinkingExecutor(1, 100, "ScalingRetransmition-"
                + stream.getScopedName());
        return new EventStreamWriterImpl<T>(stream, controller, outFactory, s, config, executor);
    }

    @Override
    public <T> EventStreamReader<T> createReader(String readerId, String readerGroup, Serializer<T> s,
                                                 ReaderConfig config) {
        log.info("Creating reader: {} under readerGroup: {} with configuration: {}", readerId, readerGroup, config);
        return createReader(readerId, readerGroup, s, config, System::nanoTime, System::currentTimeMillis);
    }

    @VisibleForTesting
    public <T> EventStreamReader<T> createReader(String readerId, String readerGroup, Serializer<T> s, ReaderConfig config,
                                          Supplier<Long> nanoTime, Supplier<Long> milliTime) {
        log.info("Creating reader: {} under readerGroup: {} with configuration: {}", readerId, readerGroup, config);
        SynchronizerConfig synchronizerConfig = SynchronizerConfig.builder().build();
        StateSynchronizer<ReaderGroupState> sync = createStateSynchronizer(
                NameUtils.getStreamForReaderGroup(readerGroup),
                new JavaSerializer<>(),
                new JavaSerializer<>(),
                synchronizerConfig);
        ReaderGroupStateManager stateManager = new ReaderGroupStateManager(readerId, sync, controller, nanoTime);
        stateManager.initializeReader(config.getInitialAllocationDelay());
        return new EventStreamReaderImpl<T>(inFactory, metaFactory, s, stateManager, new Orderer(), milliTime, config);
    }
    
    @Override
    public <T> RevisionedStreamClient<T> createRevisionedStreamClient(String streamName, Serializer<T> serializer,
                                                                      SynchronizerConfig config) {
        log.info("Creating revisioned stream client for stream: {} with synchronizer configuration: {}", streamName, config);
        Segment segment = new Segment(scope, streamName, 0);
        SegmentInputStream in = inFactory.createInputStreamForSegment(segment);
        // Segment sealed is not expected for Revisioned Stream Client.
        Consumer<Segment> segmentSealedCallBack = s -> {
            throw new IllegalStateException("RevisionedClient: Segmentsealed exception observed for segment:" + s);
        };
        SegmentOutputStream out = outFactory.createOutputStreamForSegment(segment, segmentSealedCallBack, config.getEventWriterConfig());
        SegmentMetadataClient meta = metaFactory.createSegmentMetadataClient(segment);
        return new RevisionedStreamClientImpl<>(segment, in, out, meta, serializer);
    }

    @Override
    public <StateT extends Revisioned, UpdateT extends Update<StateT>, InitT extends InitialUpdate<StateT>> StateSynchronizer<StateT>
        createStateSynchronizer(String streamName,
                                Serializer<UpdateT> updateSerializer,
                                Serializer<InitT> initialSerializer,
                                SynchronizerConfig config) {
        log.info("Creating state synchronizer with stream: {} and configuration: {}", streamName, config);
        Segment segment = new Segment(scope, streamName, 0);
        if (!Futures.getAndHandleExceptions(controller.isSegmentOpen(segment), InvalidStreamException::new)) {
            throw new InvalidStreamException("Segment does not exist: " + segment);
        }
        val serializer = new UpdateOrInitSerializer<>(updateSerializer, initialSerializer);
        return new StateSynchronizerImpl<StateT>(segment, createRevisionedStreamClient(streamName, serializer, config));
    }
    
    @Override
    public BatchClient createBatchClient() {
        return new BatchClientImpl(controller, connectionFactory);
    }

    @Override
    public void close() {
        connectionFactory.close();
    }

}
