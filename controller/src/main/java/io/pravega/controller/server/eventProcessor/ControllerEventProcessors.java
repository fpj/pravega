/**
 * Copyright (c) 2017 Dell Inc., or its subsidiaries. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.pravega.controller.server.eventProcessor;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.AbstractIdleService;
import io.pravega.client.ClientFactory;
import io.pravega.client.admin.impl.ReaderGroupManagerImpl;
import io.pravega.client.netty.impl.ConnectionFactory;
import io.pravega.common.LoggerHelpers;
import io.pravega.common.concurrent.FutureHelpers;
import io.pravega.common.util.Retry;
import io.pravega.controller.eventProcessor.EventProcessorConfig;
import io.pravega.controller.eventProcessor.EventProcessorGroup;
import io.pravega.controller.eventProcessor.EventProcessorGroupConfig;
import io.pravega.controller.eventProcessor.EventProcessorSystem;
import io.pravega.controller.eventProcessor.ExceptionHandler;
import io.pravega.controller.eventProcessor.impl.EventProcessorGroupConfigImpl;
import io.pravega.controller.eventProcessor.impl.EventProcessorSystemImpl;
import io.pravega.controller.fault.FailoverSweeper;
import io.pravega.shared.controller.event.ControllerEvent;
import io.pravega.controller.server.SegmentHelper;
import io.pravega.controller.store.checkpoint.CheckpointStore;
import io.pravega.controller.store.checkpoint.CheckpointStoreException;
import io.pravega.controller.store.host.HostControllerStore;
import io.pravega.controller.store.stream.StreamMetadataStore;
import io.pravega.controller.task.Stream.StreamMetadataTasks;
import io.pravega.controller.task.Stream.StreamTransactionMetadataTasks;
import io.pravega.controller.util.Config;
import io.pravega.client.stream.ScalingPolicy;
import io.pravega.client.stream.Serializer;
import io.pravega.client.stream.StreamConfiguration;
import io.pravega.client.stream.impl.ClientFactoryImpl;
import io.pravega.client.stream.impl.Controller;
import io.pravega.client.stream.impl.JavaSerializer;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.ImmutablePair;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Supplier;

import static io.pravega.controller.util.RetryHelper.RETRYABLE_PREDICATE;
import static io.pravega.controller.util.RetryHelper.withRetriesAsync;

@Slf4j
public class ControllerEventProcessors extends AbstractIdleService implements FailoverSweeper {

    public static final Serializer<CommitEvent> COMMIT_EVENT_SERIALIZER = new JavaSerializer<>();
    public static final Serializer<AbortEvent> ABORT_EVENT_SERIALIZER = new JavaSerializer<>();
    public static final Serializer<ControllerEvent> CONTROLLER_EVENT_SERIALIZER = new JavaSerializer<>();

    // Retry configuration
    private static final long DELAY = 100;
    private static final int MULTIPLIER = 10;
    private static final long MAX_DELAY = 10000;

    private final String objectId;
    private final ControllerEventProcessorConfig config;
    private final CheckpointStore checkpointStore;
    private final StreamMetadataStore streamMetadataStore;
    private final StreamMetadataTasks streamMetadataTasks;
    private final HostControllerStore hostControllerStore;
    private final EventProcessorSystem system;
    private final SegmentHelper segmentHelper;
    private final Controller controller;
    private final ConnectionFactory connectionFactory;
    private final ClientFactory clientFactory;
    private final ScheduledExecutorService executor;

    private EventProcessorGroup<CommitEvent> commitEventProcessors;
    private EventProcessorGroup<AbortEvent> abortEventProcessors;
    private EventProcessorGroup<ControllerEvent> requestEventProcessors;
    private final RequestHandlerMultiplexer requestHandlerMultiplexer;
    private final CommitRequestHandler commitRequestHandler;
    private final AbortRequestHandler abortRequestHandler;

    public ControllerEventProcessors(final String host,
                                     final ControllerEventProcessorConfig config,
                                     final Controller controller,
                                     final CheckpointStore checkpointStore,
                                     final StreamMetadataStore streamMetadataStore,
                                     final HostControllerStore hostControllerStore,
                                     final SegmentHelper segmentHelper,
                                     final ConnectionFactory connectionFactory,
                                     final StreamMetadataTasks streamMetadataTasks,
                                     final ScheduledExecutorService executor) {
        this(host, config, controller, checkpointStore, streamMetadataStore, hostControllerStore, segmentHelper, connectionFactory,
                streamMetadataTasks, null, executor);
    }

    @VisibleForTesting
    ControllerEventProcessors(final String host,
                                     final ControllerEventProcessorConfig config,
                                     final Controller controller,
                                     final CheckpointStore checkpointStore,
                                     final StreamMetadataStore streamMetadataStore,
                                     final HostControllerStore hostControllerStore,
                                     final SegmentHelper segmentHelper,
                                     final ConnectionFactory connectionFactory,
                                     final StreamMetadataTasks streamMetadataTasks,
                                     final EventProcessorSystem system,
                                     final ScheduledExecutorService executor) {
        this.objectId = "ControllerEventProcessors";
        this.config = config;
        this.checkpointStore = checkpointStore;
        this.streamMetadataStore = streamMetadataStore;
        this.streamMetadataTasks = streamMetadataTasks;
        this.hostControllerStore = hostControllerStore;
        this.segmentHelper = segmentHelper;
        this.controller = controller;
        this.connectionFactory = connectionFactory;
        this.clientFactory = new ClientFactoryImpl(config.getScopeName(), controller, connectionFactory);
        this.system = system == null ? new EventProcessorSystemImpl("Controller", host, config.getScopeName(), clientFactory,
                new ReaderGroupManagerImpl(config.getScopeName(), controller, clientFactory, connectionFactory)) : system;
        this.requestHandlerMultiplexer = new RequestHandlerMultiplexer(new AutoScaleRequestHandler(streamMetadataTasks, streamMetadataStore, executor),
                new ScaleOperationRequestHandler(streamMetadataTasks, streamMetadataStore, executor));
        this.commitRequestHandler = new CommitRequestHandler(streamMetadataStore, streamMetadataTasks, hostControllerStore,
                executor, segmentHelper, connectionFactory);
        this.abortRequestHandler = new AbortRequestHandler(streamMetadataStore, streamMetadataTasks, hostControllerStore,
                executor, segmentHelper, connectionFactory);
        this.executor = executor;
    }

    @Override
    protected void startUp() throws Exception {
        long traceId = LoggerHelpers.traceEnterWithContext(log, this.objectId, "startUp");
        try {
            log.info("Starting controller event processors");
            initialize();
            log.info("Controller event processors startUp complete");
        } finally {
            LoggerHelpers.traceLeave(log, this.objectId, "startUp", traceId);
        }
    }

    @Override
    protected void shutDown() {
        long traceId = LoggerHelpers.traceEnterWithContext(log, this.objectId, "shutDown");
        try {
            log.info("Stopping controller event processors");
            stopEventProcessors();
            log.info("Controller event processors shutDown complete");
        } finally {
            LoggerHelpers.traceLeave(log, this.objectId, "shutDown", traceId);
        }
    }

    @Override
    public boolean isReady() {
        return isRunning();
    }

    @Override
    public CompletableFuture<Void> sweepFailedProcesses(final Supplier<Set<String>> processes) {
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        if (this.commitEventProcessors != null) {
            futures.add(handleOrphanedReaders(this.commitEventProcessors, processes));
        }
        if (this.abortEventProcessors != null) {
            futures.add(handleOrphanedReaders(this.abortEventProcessors, processes));
        }
        if (this.requestEventProcessors != null) {
            futures.add(handleOrphanedReaders(this.requestEventProcessors, processes));
        }
        return FutureHelpers.allOf(futures);
    }

    @Override
    public CompletableFuture<Void> handleFailedProcess(String process) {
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        if (commitEventProcessors != null) {
            futures.add(withRetriesAsync(() -> CompletableFuture.runAsync(() -> {
                try {
                    commitEventProcessors.notifyProcessFailure(process);
                } catch (CheckpointStoreException e) {
                    throw new CompletionException(e);
                }
            }, executor), RETRYABLE_PREDICATE, Integer.MAX_VALUE, executor));
        }

        if (abortEventProcessors != null) {
            futures.add(withRetriesAsync(() -> CompletableFuture.runAsync(() -> {
                try {
                    abortEventProcessors.notifyProcessFailure(process);
                } catch (CheckpointStoreException e) {
                    throw new CompletionException(e);
                }
            }, executor), RETRYABLE_PREDICATE, Integer.MAX_VALUE, executor));
        }
        if (requestEventProcessors != null) {
            futures.add(withRetriesAsync(() -> CompletableFuture.runAsync(() -> {
                try {
                    requestEventProcessors.notifyProcessFailure(process);
                } catch (CheckpointStoreException e) {
                    throw new CompletionException(e);
                }
            }, executor), RETRYABLE_PREDICATE, Integer.MAX_VALUE, executor));
        }
        return FutureHelpers.allOf(futures);
    }

    private CompletableFuture<Void> createStreams() {
        StreamConfiguration commitStreamConfig =
                StreamConfiguration.builder()
                        .scope(config.getScopeName())
                        .streamName(config.getCommitStreamName())
                        .scalingPolicy(config.getCommitStreamScalingPolicy())
                        .build();

        StreamConfiguration abortStreamConfig =
                StreamConfiguration.builder()
                        .scope(config.getScopeName())
                        .streamName(config.getAbortStreamName())
                        .scalingPolicy(config.getAbortStreamScalingPolicy())
                        .build();

        StreamConfiguration requestStreamConfig =
                StreamConfiguration.builder()
                        .scope(config.getScopeName())
                        .streamName(Config.SCALE_STREAM_NAME)
                        .scalingPolicy(ScalingPolicy.fixed(1))
                        .build();

        return createScope(config.getScopeName())
                .thenCompose(ignore ->
                        CompletableFuture.allOf(createStream(commitStreamConfig),
                                createStream(abortStreamConfig),
                                createStream(requestStreamConfig)));
    }

    private CompletableFuture<Void> createScope(final String scopeName) {
        return FutureHelpers.toVoid(Retry.indefinitelyWithExpBackoff(DELAY, MULTIPLIER, MAX_DELAY,
                e -> log.warn("Error creating event processor scope " + scopeName, e))
                .runAsync(() -> controller.createScope(scopeName)
                        .thenAccept(x -> log.info("Created controller scope {}", scopeName)), executor));
    }

    private CompletableFuture<Void> createStream(final StreamConfiguration streamConfig) {
        return FutureHelpers.toVoid(Retry.indefinitelyWithExpBackoff(DELAY, MULTIPLIER, MAX_DELAY,
                e -> log.warn("Error creating event processor stream " + streamConfig.getStreamName(), e))
                .runAsync(() -> controller.createStream(streamConfig)
                                .thenAccept(x ->
                                        log.info("Created stream {}/{}", streamConfig.getScope(), streamConfig.getStreamName())),
                        executor));
    }

    public CompletableFuture<Void> bootstrap(final StreamTransactionMetadataTasks streamTransactionMetadataTasks, StreamMetadataTasks streamMetadataTasks) {
        log.info("Bootstrapping controller event processors");
        return createStreams().thenAcceptAsync(x -> {
            streamMetadataTasks.initializeStreamWriters(clientFactory, config.getRequestStreamName());
            streamTransactionMetadataTasks.initializeStreamWriters(clientFactory, config);
        }, executor);
    }

    private CompletableFuture<Void> handleOrphanedReaders(final EventProcessorGroup<? extends ControllerEvent> group,
                                       final Supplier<Set<String>> processes) {
        return withRetriesAsync(() -> CompletableFuture.supplyAsync(() -> {
            try {
                return group.getProcesses();
            } catch (CheckpointStoreException e) {
                if (e.getType().equals(CheckpointStoreException.Type.NoNode)) {
                    return Collections.<String>emptySet();
                }
                throw new CompletionException(e);
            }
        }, executor), RETRYABLE_PREDICATE, Integer.MAX_VALUE, executor)
                .thenComposeAsync(groupProcesses -> withRetriesAsync(() -> CompletableFuture.supplyAsync(() -> {
                    try {
                        return new ImmutablePair<>(processes.get(), groupProcesses);
                    } catch (Exception e) {
                        log.error(String.format("Error fetching current processes%s", group.toString()), e);
                        throw new CompletionException(e);
                    }
                }, executor), RETRYABLE_PREDICATE, Integer.MAX_VALUE, executor))
                .thenComposeAsync(pair -> {
                    Set<String> activeProcesses = pair.getLeft();
                    Set<String> registeredProcesses = pair.getRight();

                    if (registeredProcesses == null || registeredProcesses.isEmpty()) {
                        return CompletableFuture.completedFuture(null);
                    }

                    if (activeProcesses != null) {
                        registeredProcesses.removeAll(activeProcesses);
                    }

                    List<CompletableFuture<Void>> futureList = new ArrayList<>();
                    for (String process : registeredProcesses) {
                        futureList.add(withRetriesAsync(() -> CompletableFuture.runAsync(() -> {
                            try {
                                group.notifyProcessFailure(process);
                            } catch (CheckpointStoreException e) {
                                log.error(String.format("Error notifying failure of process=%s in event processor group %s", process,
                                        group.toString()), e);
                                throw new CompletionException(e);
                            }
                        }, executor), RETRYABLE_PREDICATE, Integer.MAX_VALUE, executor));
                    }

                    return FutureHelpers.allOf(futureList);
                });
    }

    private void initialize() throws Exception {

        // region Create commit event processor

        EventProcessorGroupConfig commitReadersConfig =
                EventProcessorGroupConfigImpl.builder()
                        .streamName(config.getCommitStreamName())
                        .readerGroupName(config.getCommitReaderGroupName())
                        .eventProcessorCount(config.getCommitReaderGroupSize())
                        .checkpointConfig(config.getCommitCheckpointConfig())
                        .build();

        EventProcessorConfig<CommitEvent> commitConfig =
                EventProcessorConfig.<CommitEvent>builder()
                        .config(commitReadersConfig)
                        .decider(ExceptionHandler.DEFAULT_EXCEPTION_HANDLER)
                        .serializer(COMMIT_EVENT_SERIALIZER)
                        .supplier(() -> new ConcurrentEventProcessor<>(commitRequestHandler, executor))
                        .build();

        log.info("Creating commit event processors");
        Retry.indefinitelyWithExpBackoff(DELAY, MULTIPLIER, MAX_DELAY,
                e -> log.warn("Error creating commit event processor group", e))
                .run(() -> {
                    commitEventProcessors = system.createEventProcessorGroup(commitConfig, checkpointStore);
                    return null;
                });

        // endregion

        // region Create abort event processor

        EventProcessorGroupConfig abortReadersConfig =
                EventProcessorGroupConfigImpl.builder()
                        .streamName(config.getAbortStreamName())
                        .readerGroupName(config.getAbortReaderGroupName())
                        .eventProcessorCount(config.getAbortReaderGroupSize())
                        .checkpointConfig(config.getAbortCheckpointConfig())
                        .build();

        EventProcessorConfig<AbortEvent> abortConfig =
                EventProcessorConfig.<AbortEvent>builder()
                        .config(abortReadersConfig)
                        .decider(ExceptionHandler.DEFAULT_EXCEPTION_HANDLER)
                        .serializer(ABORT_EVENT_SERIALIZER)
                        .supplier(() -> new ConcurrentEventProcessor<>(abortRequestHandler, executor))
                        .build();

        log.info("Creating abort event processors");
        Retry.indefinitelyWithExpBackoff(DELAY, MULTIPLIER, MAX_DELAY,
                e -> log.warn("Error creating commit event processor group", e))
                .run(() -> {
                    abortEventProcessors = system.createEventProcessorGroup(abortConfig, checkpointStore);
                    return null;
                });

        // endregion

        // region Create request event processor

        EventProcessorGroupConfig requestReadersConfig =
                EventProcessorGroupConfigImpl.builder()
                        .streamName(config.getRequestStreamName())
                        .readerGroupName(config.getRequestReaderGroupName())
                        .eventProcessorCount(1)
                        .checkpointConfig(config.getRequestStreamCheckpointConfig())
                        .build();

        EventProcessorConfig<ControllerEvent> requestConfig =
                EventProcessorConfig.builder()
                        .config(requestReadersConfig)
                        .decider(ExceptionHandler.DEFAULT_EXCEPTION_HANDLER)
                        .serializer(CONTROLLER_EVENT_SERIALIZER)
                        .supplier(() -> new ConcurrentEventProcessor<>(requestHandlerMultiplexer, executor))
                        .build();

        log.info("Creating request event processors");
        Retry.indefinitelyWithExpBackoff(DELAY, MULTIPLIER, MAX_DELAY,
                e -> log.warn("Error creating request event processor group", e))
                .run(() -> {
                    requestEventProcessors = system.createEventProcessorGroup(requestConfig, checkpointStore);
                    return null;
                });

        // endregion

        log.info("Awaiting start of commit event processors");
        commitEventProcessors.awaitRunning();
        log.info("Awaiting start of abort event processors");
        abortEventProcessors.awaitRunning();
        log.info("Awaiting start of request event processors");
        requestEventProcessors.awaitRunning();
    }

    private void stopEventProcessors() {
        if (commitEventProcessors != null) {
            log.info("Stopping commit event processors");
            commitEventProcessors.stopAsync();
        }
        if (abortEventProcessors != null) {
            log.info("Stopping abort event processors");
            abortEventProcessors.stopAsync();
        }
        if (requestEventProcessors != null) {
            log.info("Stopping request event processors");
            requestEventProcessors.stopAsync();
        }
        if (commitEventProcessors != null) {
            log.info("Awaiting termination of commit event processors");
            commitEventProcessors.awaitTerminated();
        }
        if (abortEventProcessors != null) {
            log.info("Awaiting termination of abort event processors");
            abortEventProcessors.awaitTerminated();
        }
        if (requestEventProcessors != null) {
            log.info("Awaiting termination of request event processors");
            requestEventProcessors.awaitTerminated();
        }
    }
}
