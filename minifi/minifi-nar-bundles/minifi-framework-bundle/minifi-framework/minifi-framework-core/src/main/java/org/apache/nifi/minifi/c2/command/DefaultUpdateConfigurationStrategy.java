/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.nifi.minifi.c2.command;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.UUID.randomUUID;
import static java.util.function.Predicate.not;
import static org.apache.nifi.minifi.commons.api.MiNiFiConstants.BACKUP_EXTENSION;
import static org.apache.nifi.minifi.commons.api.MiNiFiConstants.RAW_EXTENSION;
import static org.apache.nifi.minifi.commons.util.FlowUpdateUtils.backup;
import static org.apache.nifi.minifi.commons.util.FlowUpdateUtils.persist;
import static org.apache.nifi.minifi.commons.util.FlowUpdateUtils.removeIfExists;
import static org.apache.nifi.minifi.commons.util.FlowUpdateUtils.revert;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.nifi.c2.client.service.operation.UpdateConfigurationStrategy;
import org.apache.nifi.components.ValidationResult;
import org.apache.nifi.components.validation.ValidationStatus;
import org.apache.nifi.controller.ComponentNode;
import org.apache.nifi.controller.FlowController;
import org.apache.nifi.controller.ProcessorNode;
import org.apache.nifi.controller.flow.FlowManager;
import org.apache.nifi.groups.ProcessGroup;
import org.apache.nifi.groups.RemoteProcessGroup;
import org.apache.nifi.minifi.commons.service.FlowEnrichService;
import org.apache.nifi.services.FlowService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DefaultUpdateConfigurationStrategy implements UpdateConfigurationStrategy {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultUpdateConfigurationStrategy.class);

    private static int VALIDATION_RETRY_PAUSE_DURATION_MS = 1000;
    private static int VALIDATION_MAX_RETRIES = 5;
    private static int FLOW_DRAIN_RETRY_PAUSE_DURATION_MS = 1000;
    private static int FLOW_DRAIN_MAX_RETRIES = 60;

    private final FlowController flowController;
    private final FlowService flowService;
    private final FlowEnrichService flowEnrichService;
    private final Path flowConfigurationFile;
    private final Path backupFlowConfigurationFile;
    private final Path rawFlowConfigurationFile;
    private final Path backupRawFlowConfigurationFile;

    public DefaultUpdateConfigurationStrategy(FlowController flowController, FlowService flowService, FlowEnrichService flowEnrichService, String flowConfigurationFile) {
        this.flowController = flowController;
        this.flowService = flowService;
        this.flowEnrichService = flowEnrichService;
        Path flowConfigurationFilePath = Path.of(flowConfigurationFile).toAbsolutePath();
        this.flowConfigurationFile = flowConfigurationFilePath;
        this.backupFlowConfigurationFile = Path.of(flowConfigurationFilePath + BACKUP_EXTENSION);
        String flowConfigurationFileBaseName = FilenameUtils.getBaseName(flowConfigurationFilePath.toString());
        this.rawFlowConfigurationFile = flowConfigurationFilePath.getParent().resolve(flowConfigurationFileBaseName + RAW_EXTENSION);
        this.backupRawFlowConfigurationFile = flowConfigurationFilePath.getParent().resolve(flowConfigurationFileBaseName + BACKUP_EXTENSION + RAW_EXTENSION);
    }

    @Override
    public boolean update(byte[] rawFlow) {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Attempting to update flow with content: \n{}", new String(rawFlow, UTF_8));
        }

        try {
            byte[] enrichedFlowCandidate = flowEnrichService.enrichFlow(rawFlow);
            backup(flowConfigurationFile, backupFlowConfigurationFile);
            backup(rawFlowConfigurationFile, backupRawFlowConfigurationFile);
            persist(enrichedFlowCandidate, flowConfigurationFile, true);
            persist(rawFlow, rawFlowConfigurationFile, false);
            reloadFlow();
            return true;
        } catch (IllegalStateException e) {
            LOGGER.error("Configuration update failed. Reverting and reloading previous flow", e);
            revert(backupFlowConfigurationFile, flowConfigurationFile);
            revert(backupRawFlowConfigurationFile, rawFlowConfigurationFile);
            try {
                reloadFlow();
            } catch (IOException ex) {
                LOGGER.error("Unable to reload the reverted flow", e);
            }
            return false;
        } catch (Exception e) {
            LOGGER.error("Configuration update failed. Reverting to previous flow, no reload is necessary", e);
            revert(backupFlowConfigurationFile, flowConfigurationFile);
            revert(backupRawFlowConfigurationFile, rawFlowConfigurationFile);
            return false;
        } finally {
            removeIfExists(backupFlowConfigurationFile);
            removeIfExists(backupRawFlowConfigurationFile);
        }
    }

    private void reloadFlow() throws IOException {
        LOGGER.info("Initiating flow reload");
        stopFlowGracefully(flowController.getFlowManager().getRootGroup());

        flowService.load(null);
        flowController.onFlowInitialized(true);

        List<ValidationResult> validationErrors = validate(flowController.getFlowManager());
        if (!validationErrors.isEmpty()) {
            LOGGER.error("Validation errors found when reloading the flow: {}", validationErrors);
            throw new IllegalStateException("Unable to start flow due to validation errors");
        }

        flowController.getFlowManager().getRootGroup().startProcessing();
        LOGGER.info("Flow has been reloaded successfully");
    }

    private void stopFlowGracefully(ProcessGroup rootGroup) {
        LOGGER.info("Stopping flow gracefully");
        Optional<ProcessGroup> drainResult = stopSourceProcessorsAndWaitFlowToDrain(rootGroup);

        rootGroup.stopProcessing();
        rootGroup.getRemoteProcessGroups().stream()
            .map(RemoteProcessGroup::stopTransmitting)
            .forEach(this::waitForStopOrLogTimeOut);
        drainResult.ifPresentOrElse(
            rootProcessGroup -> {
                LOGGER.warn("Flow did not stop within graceful period. Force stopping flow and emptying queues");
                rootProcessGroup.dropAllFlowFiles(randomUUID().toString(), randomUUID().toString());
            },
            () -> LOGGER.info("Flow has been stopped gracefully"));
    }

    private Optional<ProcessGroup> stopSourceProcessorsAndWaitFlowToDrain(ProcessGroup rootGroup) {
        rootGroup.getProcessors().stream().filter(this::isSourceNode).forEach(rootGroup::stopProcessor);
        return retry(() -> rootGroup, not(ProcessGroup::isDataQueued), FLOW_DRAIN_MAX_RETRIES, FLOW_DRAIN_RETRY_PAUSE_DURATION_MS);
    }

    private boolean isSourceNode(ProcessorNode processorNode) {
        boolean hasNoIncomingConnection = !processorNode.hasIncomingConnection();

        boolean allIncomingConnectionsAreLoopConnections = processorNode.getIncomingConnections()
            .stream()
            .allMatch(connection -> connection.getSource().equals(processorNode));

        return hasNoIncomingConnection || allIncomingConnectionsAreLoopConnections;
    }

    private void waitForStopOrLogTimeOut(Future<?> future) {
        try {
            future.get(10000, TimeUnit.MICROSECONDS);
        } catch (Exception e) {
            LOGGER.warn("Unable to stop remote process group within defined interval", e);
        }
    }

    private List<ValidationResult> validate(FlowManager flowManager) {
        List<? extends ComponentNode> componentNodes = extractComponentNodes(flowManager);

        retry(() -> componentIsInValidatingState(componentNodes), List::isEmpty, VALIDATION_MAX_RETRIES, VALIDATION_RETRY_PAUSE_DURATION_MS)
            .ifPresent(components -> {
                LOGGER.error("The following components are still in VALIDATING state: {}", components);
                throw new IllegalStateException("Maximum retry number exceeded while waiting for components to be validated");
            });

        return componentNodes.stream()
            .map(ComponentNode::getValidationErrors)
            .flatMap(Collection::stream)
            .toList();
    }

    private List<? extends ComponentNode> extractComponentNodes(FlowManager flowManager) {
        return Stream.of(
                flowManager.getAllControllerServices(),
                flowManager.getAllReportingTasks(),
                Set.copyOf(flowManager.getRootGroup().findAllProcessors()))
            .flatMap(Set::stream)
            .toList();
    }

    private List<ComponentNode> componentIsInValidatingState(List<? extends ComponentNode> componentNodes) {
        return componentNodes.stream()
            .map(componentNode -> Pair.of((ComponentNode) componentNode, componentNode.performValidation()))
            .filter(pair -> pair.getRight() == ValidationStatus.VALIDATING)
            .map(Pair::getLeft)
            .toList();
    }

    private <T> Optional<T> retry(Supplier<T> input, Predicate<T> predicate, int maxRetries, int pauseDurationMillis) {
        int retries = 0;
        while (true) {
            T t = input.get();
            if (predicate.test(t)) {
                return Optional.empty();
            }
            if (retries == maxRetries) {
                return Optional.ofNullable(t);
            }
            retries++;
            try {
                Thread.sleep(pauseDurationMillis);
            } catch (InterruptedException e) {
            }
        }
    }
}
