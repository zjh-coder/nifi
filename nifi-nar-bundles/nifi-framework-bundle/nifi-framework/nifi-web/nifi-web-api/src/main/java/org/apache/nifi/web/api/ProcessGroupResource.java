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
package org.apache.nifi.web.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.module.jaxb.JaxbAnnotationIntrospector;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import io.swagger.annotations.Authorization;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.HttpMethod;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.UriBuilder;
import org.apache.commons.lang3.StringUtils;
import org.apache.nifi.authorization.AuthorizableLookup;
import org.apache.nifi.authorization.AuthorizeControllerServiceReference;
import org.apache.nifi.authorization.AuthorizeParameterReference;
import org.apache.nifi.authorization.ComponentAuthorizable;
import org.apache.nifi.authorization.ConnectionAuthorizable;
import org.apache.nifi.authorization.ProcessGroupAuthorizable;
import org.apache.nifi.authorization.RequestAction;
import org.apache.nifi.authorization.SnippetAuthorizable;
import org.apache.nifi.authorization.resource.Authorizable;
import org.apache.nifi.authorization.user.NiFiUser;
import org.apache.nifi.authorization.user.NiFiUserUtils;
import org.apache.nifi.cluster.manager.NodeResponse;
import org.apache.nifi.components.ConfigurableComponent;
import org.apache.nifi.connectable.ConnectableType;
import org.apache.nifi.flow.ExecutionEngine;
import org.apache.nifi.flow.VersionedFlowCoordinates;
import org.apache.nifi.flow.VersionedParameterContext;
import org.apache.nifi.flow.VersionedProcessGroup;
import org.apache.nifi.parameter.ParameterContext;
import org.apache.nifi.registry.client.NiFiRegistryException;
import org.apache.nifi.registry.flow.FlowRegistryBucket;
import org.apache.nifi.registry.flow.FlowRegistryUtils;
import org.apache.nifi.registry.flow.FlowSnapshotContainer;
import org.apache.nifi.registry.flow.RegisteredFlow;
import org.apache.nifi.registry.flow.RegisteredFlowSnapshot;
import org.apache.nifi.registry.flow.VersionedFlowState;
import org.apache.nifi.remote.util.SiteToSiteRestApiClient;
import org.apache.nifi.util.FormatUtils;
import org.apache.nifi.web.ResourceNotFoundException;
import org.apache.nifi.web.Revision;
import org.apache.nifi.web.api.concurrent.AsyncRequestManager;
import org.apache.nifi.web.api.concurrent.AsynchronousWebRequest;
import org.apache.nifi.web.api.concurrent.RequestManager;
import org.apache.nifi.web.api.concurrent.StandardAsynchronousWebRequest;
import org.apache.nifi.web.api.concurrent.StandardUpdateStep;
import org.apache.nifi.web.api.concurrent.UpdateStep;
import org.apache.nifi.web.api.dto.AnalyzeFlowRequestDTO;
import org.apache.nifi.web.api.dto.AnalyzeFlowRequestUpdateStepDTO;
import org.apache.nifi.web.api.dto.ConnectionDTO;
import org.apache.nifi.web.api.dto.ControllerServiceDTO;
import org.apache.nifi.web.api.dto.DropRequestDTO;
import org.apache.nifi.web.api.dto.ParameterContextHandlingStrategy;
import org.apache.nifi.web.api.dto.PositionDTO;
import org.apache.nifi.web.api.dto.ProcessGroupDTO;
import org.apache.nifi.web.api.dto.ProcessGroupReplaceRequestDTO;
import org.apache.nifi.web.api.dto.ProcessorConfigDTO;
import org.apache.nifi.web.api.dto.ProcessorDTO;
import org.apache.nifi.web.api.dto.RemoteProcessGroupDTO;
import org.apache.nifi.web.api.dto.RevisionDTO;
import org.apache.nifi.web.api.dto.VersionControlInformationDTO;
import org.apache.nifi.web.api.dto.flow.FlowDTO;
import org.apache.nifi.web.api.entity.AffectedComponentEntity;
import org.apache.nifi.web.api.entity.AnalyzeFlowRequestEntity;
import org.apache.nifi.web.api.entity.ConnectionEntity;
import org.apache.nifi.web.api.entity.ConnectionsEntity;
import org.apache.nifi.web.api.entity.ControllerServiceEntity;
import org.apache.nifi.web.api.entity.CopySnippetRequestEntity;
import org.apache.nifi.web.api.entity.DropRequestEntity;
import org.apache.nifi.web.api.entity.Entity;
import org.apache.nifi.web.api.entity.FlowComparisonEntity;
import org.apache.nifi.web.api.entity.FlowEntity;
import org.apache.nifi.web.api.entity.FunnelEntity;
import org.apache.nifi.web.api.entity.FunnelsEntity;
import org.apache.nifi.web.api.entity.InputPortsEntity;
import org.apache.nifi.web.api.entity.LabelEntity;
import org.apache.nifi.web.api.entity.LabelsEntity;
import org.apache.nifi.web.api.entity.OutputPortsEntity;
import org.apache.nifi.web.api.entity.ParameterContextReferenceEntity;
import org.apache.nifi.web.api.entity.PortEntity;
import org.apache.nifi.web.api.entity.ProcessGroupEntity;
import org.apache.nifi.web.api.entity.ProcessGroupImportEntity;
import org.apache.nifi.web.api.entity.ProcessGroupRecursivity;
import org.apache.nifi.web.api.entity.ProcessGroupReplaceRequestEntity;
import org.apache.nifi.web.api.entity.ProcessGroupUploadEntity;
import org.apache.nifi.web.api.entity.ProcessGroupsEntity;
import org.apache.nifi.web.api.entity.ProcessorEntity;
import org.apache.nifi.web.api.entity.ProcessorsEntity;
import org.apache.nifi.web.api.entity.RemoteProcessGroupEntity;
import org.apache.nifi.web.api.entity.RemoteProcessGroupsEntity;
import org.apache.nifi.web.api.request.ClientIdParameter;
import org.apache.nifi.web.api.request.LongParameter;
import org.apache.nifi.web.util.ParameterContextReplacer;
import org.glassfish.jersey.media.multipart.FormDataParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
* RESTful endpoint for managing a Group.
*/
@Path("/process-groups")
@Api(
       value = "/process-groups",
       description = "Endpoint for managing a Process Group."
)
public class ProcessGroupResource extends FlowUpdateResource<ProcessGroupImportEntity, ProcessGroupReplaceRequestEntity> {

   private static final Logger logger = LoggerFactory.getLogger(ProcessGroupResource.class);

   private static final String FLOW_ANALYSIS_REQUEST_TYPE = "flow-analysis-requests";

   private ProcessorResource processorResource;
   private InputPortResource inputPortResource;
   private OutputPortResource outputPortResource;
   private FunnelResource funnelResource;
   private LabelResource labelResource;
   private RemoteProcessGroupResource remoteProcessGroupResource;
   private ConnectionResource connectionResource;
   private ControllerServiceResource controllerServiceResource;
   private ParameterContextReplacer parameterContextReplacer;


   public RequestManager<String, Void> flowAnalysisAsyncRequestManager =
           new AsyncRequestManager<>(100, TimeUnit.MINUTES.toMillis(1L), "On-demand Flow Analysis");

   private static final ObjectMapper MAPPER = new ObjectMapper();
   static {
       MAPPER.setSerializationInclusion(JsonInclude.Include.NON_NULL);
       MAPPER.setDefaultPropertyInclusion(JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL));
       MAPPER.setAnnotationIntrospector(new JaxbAnnotationIntrospector(MAPPER.getTypeFactory()));
       MAPPER.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
   }

   /**
    * Populates the remaining fields in the specified process groups.
    *
    * @param processGroupEntities groups
    * @return group dto
    */
   public Set<ProcessGroupEntity> populateRemainingProcessGroupEntitiesContent(Set<ProcessGroupEntity> processGroupEntities) {
       for (ProcessGroupEntity processGroupEntity : processGroupEntities) {
           populateRemainingProcessGroupEntityContent(processGroupEntity);
       }
       return processGroupEntities;
   }

   /**
    * Populates the remaining fields in the specified process group.
    *
    * @param processGroupEntity group
    * @return group dto
    */
   public ProcessGroupEntity populateRemainingProcessGroupEntityContent(ProcessGroupEntity processGroupEntity) {
       processGroupEntity.setUri(generateResourceUri("process-groups", processGroupEntity.getId()));
       return processGroupEntity;
   }


   /**
    * Populates the remaining content of the specified snippet.
    */
   private FlowDTO populateRemainingSnippetContent(FlowDTO flow) {
       processorResource.populateRemainingProcessorEntitiesContent(flow.getProcessors());
       connectionResource.populateRemainingConnectionEntitiesContent(flow.getConnections());
       inputPortResource.populateRemainingInputPortEntitiesContent(flow.getInputPorts());
       outputPortResource.populateRemainingOutputPortEntitiesContent(flow.getOutputPorts());
       remoteProcessGroupResource.populateRemainingRemoteProcessGroupEntitiesContent(flow.getRemoteProcessGroups());
       funnelResource.populateRemainingFunnelEntitiesContent(flow.getFunnels());
       labelResource.populateRemainingLabelEntitiesContent(flow.getLabels());

       // go through each process group child and populate its uri
       if (flow.getProcessGroups() != null) {
           populateRemainingProcessGroupEntitiesContent(flow.getProcessGroups());
       }

       return flow;
   }

   /**
    * Retrieves the contents of the specified group.
    *
    * @param groupId The id of the process group.
    * @return A processGroupEntity.
    */
   @GET
   @Consumes(MediaType.WILDCARD)
   @Produces(MediaType.APPLICATION_JSON)
   @Path("{id}")
   @ApiOperation(
           value = "Gets a process group",
           response = ProcessGroupEntity.class,
           authorizations = {
                   @Authorization(value = "Read - /process-groups/{uuid}")
           }
   )
   @ApiResponses(
           value = {
                   @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
                   @ApiResponse(code = 401, message = "Client could not be authenticated."),
                   @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
                   @ApiResponse(code = 404, message = "The specified resource could not be found."),
                   @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
           }
   )
   public Response getProcessGroup(
           @ApiParam(
                   value = "The process group id.",
                   required = false
           )
           @PathParam("id") final String groupId) {

       if (isReplicateRequest()) {
           return replicate(HttpMethod.GET);
       }

       // authorize access
       serviceFacade.authorizeAccess(lookup -> {
           final Authorizable processGroup = lookup.getProcessGroup(groupId).getAuthorizable();
           processGroup.authorize(authorizer, RequestAction.READ, NiFiUserUtils.getNiFiUser());
       });

       // get this process group contents
       final ProcessGroupEntity entity = serviceFacade.getProcessGroup(groupId);
       populateRemainingProcessGroupEntityContent(entity);

       if (entity.getComponent() != null) {
           entity.getComponent().setContents(null);
       }

       return generateOkResponse(entity).build();
   }

   /**
    * Retrieves the specified group as a versioned flow snapshot for download.
    *
    * @param groupId The id of the process group
    * @return A processGroupEntity.
    */
   @GET
   @Consumes(MediaType.WILDCARD)
   @Produces(MediaType.APPLICATION_JSON)
   @Path("{id}/download")
   @ApiOperation(
       value = "Gets a process group for download",
       response = String.class,
       authorizations = {
           @Authorization(value = "Read - /process-groups/{uuid}")
       }
   )
   @ApiResponses(value = {
       @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
       @ApiResponse(code = 401, message = "Client could not be authenticated."),
       @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
       @ApiResponse(code = 404, message = "The specified resource could not be found."),
       @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
   })
   public Response exportProcessGroup(
           @ApiParam(
                   value = "The process group id.",
                   required = true
           )
           @PathParam("id") final String groupId,
           @ApiParam(
                   value = "If referenced services from outside the target group should be included",
                   required = false
           )
           @QueryParam("includeReferencedServices")
           @DefaultValue("false") boolean includeReferencedServices) {
       // authorize access
       serviceFacade.authorizeAccess(lookup -> {
           // ensure access to process groups (nested), encapsulated controller services and referenced parameter contexts
           final ProcessGroupAuthorizable groupAuthorizable = lookup.getProcessGroup(groupId);
           authorizeProcessGroup(groupAuthorizable, authorizer, lookup, RequestAction.READ, true,
                   false, false, true);
       });

       // get the versioned flow
       final RegisteredFlowSnapshot currentVersionedFlowSnapshot = includeReferencedServices
           ? serviceFacade.getCurrentFlowSnapshotByGroupIdWithReferencedControllerServices(groupId)
           : serviceFacade.getCurrentFlowSnapshotByGroupId(groupId);

       // determine the name of the attachment - possible issues with spaces in file names
       final VersionedProcessGroup currentVersionedProcessGroup = currentVersionedFlowSnapshot.getFlowContents();
       final String flowName = currentVersionedProcessGroup.getName();
       final String filename = flowName.replaceAll("\\s", "_") + ".json";

       return generateOkResponse(currentVersionedFlowSnapshot).header(HttpHeaders.CONTENT_DISPOSITION, String.format("attachment; filename=\"%s\"", filename)).build();
   }

   /**
    * Retrieves a list of local modifications to the Process Group since it was last synchronized with the Flow Registry
    *
    * @param groupId The id of the process group.
    * @return A processGroupEntity.
    */
   @GET
   @Consumes(MediaType.WILDCARD)
   @Produces(MediaType.APPLICATION_JSON)
   @Path("{id}/local-modifications")
   @ApiOperation(
           value = "Gets a list of local modifications to the Process Group since it was last synchronized with the Flow Registry",
           response = FlowComparisonEntity.class,
           authorizations = {
           @Authorization(value = "Read - /process-groups/{uuid}"),
           @Authorization(value = "Read - /{component-type}/{uuid} - For all encapsulated components")
           }
   )
   @ApiResponses(
           value = {
                   @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
                   @ApiResponse(code = 401, message = "Client could not be authenticated."),
                   @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
                   @ApiResponse(code = 404, message = "The specified resource could not be found."),
                   @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
           }
   )
   public Response getLocalModifications(
           @ApiParam(
                   value = "The process group id.",
                   required = false
           )
           @PathParam("id") final String groupId) throws IOException, NiFiRegistryException {

       // authorize access
       serviceFacade.authorizeAccess(lookup -> {
           final ProcessGroupAuthorizable groupAuthorizable = lookup.getProcessGroup(groupId);
           authorizeProcessGroup(groupAuthorizable, authorizer, lookup, RequestAction.READ, false, false, false, false);
       });

       final FlowComparisonEntity entity = serviceFacade.getLocalModifications(groupId);
       return generateOkResponse(entity).build();
   }

   /**
    * Updates the specified process group.
    *
    * @param httpServletRequest request
    * @param id                 The id of the process group.
    * @param requestProcessGroupEntity A processGroupEntity.
    * @return A processGroupEntity or the parent processGroupEntity for recursive requests.
    */
   @PUT
   @Consumes(MediaType.APPLICATION_JSON)
   @Produces(MediaType.APPLICATION_JSON)
   @Path("{id}")
   @ApiOperation(
           value = "Updates a process group",
           response = ProcessGroupEntity.class,
           authorizations = {
                   @Authorization(value = "Write - /process-groups/{uuid}")
           }
   )
   @ApiResponses(
           value = {
                   @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
                   @ApiResponse(code = 401, message = "Client could not be authenticated."),
                   @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
                   @ApiResponse(code = 404, message = "The specified resource could not be found."),
                   @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
           }
   )
   public Response updateProcessGroup(
           @Context final HttpServletRequest httpServletRequest,
           @ApiParam(
                   value = "The process group id.",
                   required = true
           )
           @PathParam("id") final String id,
           @ApiParam(
                   value = "The process group configuration details.",
                   required = true
           ) final ProcessGroupEntity requestProcessGroupEntity) {

       if (requestProcessGroupEntity == null || requestProcessGroupEntity.getComponent() == null) {
           throw new IllegalArgumentException("Process group details must be specified.");
       }

       if (requestProcessGroupEntity.getRevision() == null) {
           throw new IllegalArgumentException("Revision must be specified.");
       }

       // ensure the same id is being used
       final ProcessGroupDTO requestProcessGroupDTO = requestProcessGroupEntity.getComponent();
       if (!id.equals(requestProcessGroupDTO.getId())) {
           throw new IllegalArgumentException(String.format("The process group id (%s) in the request body does "
                   + "not equal the process group id of the requested resource (%s).", requestProcessGroupDTO.getId(), id));
       }

       final PositionDTO proposedPosition = requestProcessGroupDTO.getPosition();
       if (proposedPosition != null) {
           if (proposedPosition.getX() == null || proposedPosition.getY() == null) {
               throw new IllegalArgumentException("The x and y coordinate of the proposed position must be specified.");
           }
       }

       final String processGroupUpdateStrategy = requestProcessGroupEntity.getProcessGroupUpdateStrategy();
       final ProcessGroupRecursivity updateStrategy;
       if (processGroupUpdateStrategy == null) {
           updateStrategy = ProcessGroupRecursivity.DIRECT_CHILDREN;
       } else {
           updateStrategy = ProcessGroupRecursivity.valueOf(processGroupUpdateStrategy);
       }

       final String executionEngine = requestProcessGroupDTO.getExecutionEngine();
       if (executionEngine != null) {
           try {
               ExecutionEngine.valueOf(executionEngine);
           } catch (final IllegalArgumentException iae) {
               throw new IllegalArgumentException("Illegal value proposed for Execution Engine: " + executionEngine);
           }
       }

       final String statelessTimeout = requestProcessGroupDTO.getStatelessFlowTimeout();
       if (statelessTimeout != null) {
           try {
               FormatUtils.getPreciseTimeDuration(statelessTimeout, TimeUnit.MILLISECONDS);
           } catch (final Exception e) {
               throw new IllegalArgumentException("Illegal value proposed for Stateless Flow Timeout: " + statelessTimeout);
           }
       }

       final Integer maxConcurrentTasks = requestProcessGroupDTO.getMaxConcurrentTasks();
       if (maxConcurrentTasks != null && maxConcurrentTasks < 1) {
           throw new IllegalArgumentException("Illegal value proposed for Max Concurrent Tasks: " + maxConcurrentTasks);
       }

       if (isReplicateRequest()) {
           return replicate(HttpMethod.PUT, requestProcessGroupEntity);
       } else if (isDisconnectedFromCluster()) {
           verifyDisconnectedNodeModification(requestProcessGroupEntity.isDisconnectedNodeAcknowledged());
       }

       // handle expects request (usually from the cluster manager)
       final ParameterContextReferenceEntity requestParamContext = requestProcessGroupDTO.getParameterContext();
       final String requestGroupId = requestProcessGroupDTO.getId();
       final Map<ProcessGroupEntity, Revision> updatableProcessGroups = new HashMap<>();

       updatableProcessGroups.put(requestProcessGroupEntity, getRevision(requestProcessGroupEntity, requestGroupId));

       if (updateStrategy == ProcessGroupRecursivity.ALL_DESCENDANTS) {
           for (ProcessGroupEntity processGroupEntity : serviceFacade.getProcessGroups(requestGroupId, updateStrategy)) {
               final ProcessGroupDTO processGroupDTO = processGroupEntity.getComponent();
               final String processGroupId = processGroupDTO == null ? processGroupEntity.getId() : processGroupDTO.getId();
               if (processGroupDTO != null) {
                   processGroupDTO.setParameterContext(requestParamContext);
               }
               updatableProcessGroups.put(processGroupEntity, getRevision(processGroupEntity, processGroupId));
           }
       }

       return withWriteLock(
               serviceFacade,
               requestProcessGroupEntity,
               new HashSet<>(updatableProcessGroups.values()),
               lookup -> {
                   final NiFiUser user = NiFiUserUtils.getNiFiUser();

                   for (final ProcessGroupEntity updatableGroupEntity : updatableProcessGroups.keySet()) {
                       final ProcessGroupDTO updatableGroupDto = updatableGroupEntity.getComponent();
                       final String groupId = updatableGroupDto == null ? updatableGroupEntity.getId() : updatableGroupDto.getId();

                       Authorizable authorizable = lookup.getProcessGroup(groupId).getAuthorizable();
                       authorizable.authorize(authorizer, RequestAction.WRITE, user);

                       // Ensure that user has READ permission on current Parameter Context (if any) because user is un-binding.
                       final ParameterContextReferenceEntity referencedParamContext = updatableGroupDto.getParameterContext();
                       if (referencedParamContext != null) {
                           // Lookup the current Parameter Context and determine whether or not the Parameter Context is changing
                           final ProcessGroupEntity currentGroupEntity = serviceFacade.getProcessGroup(groupId);
                           final ProcessGroupDTO groupDto = currentGroupEntity.getComponent();
                           final ParameterContextReferenceEntity currentParamContext = groupDto.getParameterContext();
                           final String currentParamContextId = currentParamContext == null ? null : currentParamContext.getId();
                           final boolean parameterContextChanging = !Objects.equals(referencedParamContext.getId(), currentParamContextId);

                           // If Parameter Context is changing...
                           if (parameterContextChanging) {
                               // In order to bind to a Parameter Context, the user must have the READ policy to that Parameter Context.
                               if (referencedParamContext.getId() != null) {
                                   lookup.getParameterContext(referencedParamContext.getId()).authorize(authorizer, RequestAction.READ, user);
                               }

                               // If currently referencing a Parameter Context, we must authorize that the user has READ permissions on the Parameter Context in order to un-bind to it.
                               if (currentParamContextId != null) {
                                   lookup.getParameterContext(currentParamContextId).authorize(authorizer, RequestAction.READ, user);
                               }

                               // Because the user will be changing the behavior of any component in this group that is currently referencing any Parameter, we must ensure that the user has
                               // both READ and WRITE policies for each of those components.
                               for (final AffectedComponentEntity affectedComponentEntity : serviceFacade.getProcessorsReferencingParameter(groupId)) {
                                   final Authorizable processorAuthorizable = lookup.getProcessor(affectedComponentEntity.getId()).getAuthorizable();
                                   processorAuthorizable.authorize(authorizer, RequestAction.READ, user);
                                   processorAuthorizable.authorize(authorizer, RequestAction.WRITE, user);
                               }

                               for (final AffectedComponentEntity affectedComponentEntity : serviceFacade.getControllerServicesReferencingParameter(groupId)) {
                                   final Authorizable serviceAuthorizable = lookup.getControllerService(affectedComponentEntity.getId()).getAuthorizable();
                                   serviceAuthorizable.authorize(authorizer, RequestAction.READ, user);
                                   serviceAuthorizable.authorize(authorizer, RequestAction.WRITE, user);
                               }
                           }
                       }
                   }
               },
               () -> {
                   for (final ProcessGroupEntity entity : updatableProcessGroups.keySet()) {
                       serviceFacade.verifyUpdateProcessGroup(entity.getComponent());
                   }
               },
               (revisions, entities) -> {
                   ProcessGroupEntity responseEntity = null;
                   for (Map.Entry<ProcessGroupEntity, Revision> entry : updatableProcessGroups.entrySet()) {
                       // update the process group
                       final Revision revision = entry.getValue();
                       final ProcessGroupDTO groupDTO = entry.getKey().getComponent();
                       final ProcessGroupEntity entity = serviceFacade.updateProcessGroup(revision, groupDTO);

                       if (requestGroupId.equals(entity.getId())) {
                           responseEntity = entity;
                           populateRemainingProcessGroupEntityContent(responseEntity);

                           // prune response as necessary
                           if (responseEntity.getComponent() != null) {
                               responseEntity.getComponent().setContents(null);
                           }
                       }
                   }
                   return generateOkResponse(responseEntity).build();
               }
       );
   }

   /**
    * Extracts the response entity from the specified node response.
    *
    * @param nodeResponse node response
    * @param clazz class
    * @param <T> type of class
    * @return the response entity
    */
   @Override
   @SuppressWarnings("unchecked")
   protected <T> T getResponseEntity(final NodeResponse nodeResponse, final Class<T> clazz) {
       T entity = (T) nodeResponse.getUpdatedEntity();
       if (entity == null) {
           entity = nodeResponse.getClientResponse().readEntity(clazz);
       }
       return entity;
   }

   /**
    * Creates a request to drop the flowfiles from all connection queues within a process group (recursively).
    *
    * @param httpServletRequest request
    * @param processGroupId     The id of the process group to be removed.
    * @return A dropRequestEntity.
    */
   @POST
   @Consumes(MediaType.WILDCARD)
   @Produces(MediaType.APPLICATION_JSON)
   @Path("{id}/empty-all-connections-requests")
   @ApiOperation(
       value = "Creates a request to drop all flowfiles of all connection queues in this process group.",
       response = DropRequestEntity.class,
       authorizations = {
           @Authorization(value = "Read - /process-groups/{uuid} - For this and all encapsulated process groups"),
           @Authorization(value = "Write Source Data - /data/{component-type}/{uuid} - For all encapsulated connections")
       }
   )
   @ApiResponses(
       value = {
           @ApiResponse(code = 202, message = "The request has been accepted. An HTTP response header will contain the URI where the status can be polled."),
           @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
           @ApiResponse(code = 401, message = "Client could not be authenticated."),
           @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
           @ApiResponse(code = 404, message = "The specified resource could not be found."),
           @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
       }
   )
   public Response createEmptyAllConnectionsRequest(
       @Context final HttpServletRequest httpServletRequest,
       @ApiParam(
           value = "The process group id.",
           required = true
       )
       @PathParam("id") final String processGroupId
   ) {
       if (isReplicateRequest()) {
           return replicate(HttpMethod.POST);
       }

       final ProcessGroupEntity requestProcessGroupEntity = new ProcessGroupEntity();
       requestProcessGroupEntity.setId(processGroupId);

       return withWriteLock(
           serviceFacade,
           requestProcessGroupEntity,
           lookup -> authorizeHandleDropAllFlowFilesRequest(processGroupId, lookup),
           null,
           (processGroupEntity) -> {
               // ensure the id is the same across the cluster
               final String dropRequestId = generateUuid();

               // submit the drop request
               final DropRequestDTO dropRequest = serviceFacade.createDropAllFlowFilesInProcessGroup(processGroupEntity.getId(), dropRequestId);
               dropRequest.setUri(generateResourceUri("process-groups", processGroupEntity.getId(), "empty-all-connections-requests", dropRequest.getId()));

               // create the response entity
               final DropRequestEntity entity = new DropRequestEntity();
               entity.setDropRequest(dropRequest);

               // generate the URI where the response will be
               final URI location = URI.create(dropRequest.getUri());
               return Response.status(Status.ACCEPTED).location(location).entity(entity).build();
           }
       );
   }

   /**
    * Checks the status of an outstanding request for dropping all flowfiles within a process group.
    *
    * @param processGroupId  The id of the process group
    * @param dropRequestId   The id of the drop request
    * @return A dropRequestEntity
    */
   @GET
   @Consumes(MediaType.WILDCARD)
   @Produces(MediaType.APPLICATION_JSON)
   @Path("{id}/empty-all-connections-requests/{drop-request-id}")
   @ApiOperation(
       value = "Gets the current status of a drop all flowfiles request.",
       response = DropRequestEntity.class,
       authorizations = {
           @Authorization(value = "Read - /process-groups/{uuid} - For this and all encapsulated process groups"),
           @Authorization(value = "Write Source Data - /data/{component-type}/{uuid} - For all encapsulated connections")
       }
   )
   @ApiResponses(
       value = {
           @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
           @ApiResponse(code = 401, message = "Client could not be authenticated."),
           @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
           @ApiResponse(code = 404, message = "The specified resource could not be found."),
           @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
       }
   )
   public Response getDropAllFlowfilesRequest(
       @ApiParam(
           value = "The process group id.",
           required = true
       )
       @PathParam("id") final String processGroupId,
       @ApiParam(
           value = "The drop request id.",
           required = true
       )
       @PathParam("drop-request-id") final String dropRequestId
   ) {
       if (isReplicateRequest()) {
           return replicate(HttpMethod.GET);
       }

       // authorize access
       serviceFacade.authorizeAccess(lookup -> authorizeHandleDropAllFlowFilesRequest(processGroupId, lookup));

       // get the drop request
       final DropRequestDTO dropRequest = serviceFacade.getDropAllFlowFilesRequest(processGroupId, dropRequestId);
       dropRequest.setUri(generateResourceUri("process-groups", processGroupId, "empty-all-connections-requests", dropRequest.getId()));

       // create the response entity
       final DropRequestEntity entity = new DropRequestEntity();
       entity.setDropRequest(dropRequest);

       return generateOkResponse(entity).build();
   }

   /**
    * Cancels the specified request for dropping all flowfiles within a process group.
    *
    * @param httpServletRequest request
    * @param processGroupId     The process group id
    * @param dropRequestId      The drop request id
    * @return A dropRequestEntity
    */
   @DELETE
   @Consumes(MediaType.WILDCARD)
   @Produces(MediaType.APPLICATION_JSON)
   @Path("{id}/empty-all-connections-requests/{drop-request-id}")
   @ApiOperation(
       value = "Cancels and/or removes a request to drop all flowfiles.",
       response = DropRequestEntity.class,
       authorizations = {
           @Authorization(value = "Read - /process-groups/{uuid} - For this and all encapsulated process groups"),
           @Authorization(value = "Write Source Data - /data/{component-type}/{uuid} - For all encapsulated connections")
       }
   )
   @ApiResponses(
       value = {
           @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
           @ApiResponse(code = 401, message = "Client could not be authenticated."),
           @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
           @ApiResponse(code = 404, message = "The specified resource could not be found."),
           @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
       }
   )
   public Response removeDropRequest(
       @Context final HttpServletRequest httpServletRequest,
       @ApiParam(
           value = "The process group id.",
           required = true
       )
       @PathParam("id") final String processGroupId,
       @ApiParam(
           value = "The drop request id.",
           required = true
       )
       @PathParam("drop-request-id") final String dropRequestId
   ) {
       if (isReplicateRequest()) {
           return replicate(HttpMethod.DELETE);
       }

       return withWriteLock(
           serviceFacade,
           new DropEntity(processGroupId, dropRequestId),
           lookup -> authorizeHandleDropAllFlowFilesRequest(processGroupId, lookup),
           null,
           (dropEntity) -> {
               // delete the drop request
               final DropRequestDTO dropRequest = serviceFacade.deleteDropAllFlowFilesRequest(dropEntity.getEntityId(), dropEntity.getDropRequestId());
               dropRequest.setUri(generateResourceUri("process-groups", dropEntity.getEntityId(), "empty-all-connections-requests", dropRequest.getId()));

               // create the response entity
               final DropRequestEntity entity = new DropRequestEntity();
               entity.setDropRequest(dropRequest);

               return generateOkResponse(entity).build();
           }
       );
   }

   private void authorizeHandleDropAllFlowFilesRequest(String processGroupId, AuthorizableLookup lookup) {
       final ProcessGroupAuthorizable processGroup = lookup.getProcessGroup(processGroupId);

       authorizeProcessGroup(processGroup, authorizer, lookup, RequestAction.READ, false, false, false, false);

       processGroup.getEncapsulatedProcessGroups()
           .forEach(encapsulatedProcessGroup -> authorizeProcessGroup(encapsulatedProcessGroup, authorizer, lookup, RequestAction.READ, false, false, false, false));

       processGroup.getEncapsulatedConnections().stream()
           .map(ConnectionAuthorizable::getSourceData)
           .forEach(connectionSourceData -> connectionSourceData.authorize(authorizer, RequestAction.WRITE, NiFiUserUtils.getNiFiUser()));
   }

   /**
    * Removes the specified process group reference.
    *
    * @param httpServletRequest request
    * @param version            The revision is used to verify the client is working with the latest version of the flow.
    * @param clientId           Optional client id. If the client id is not specified, a new one will be generated. This value (whether specified or generated) is included in the response.
    * @param id                 The id of the process group to be removed.
    * @return A processGroupEntity.
    */
   @DELETE
   @Consumes(MediaType.WILDCARD)
   @Produces(MediaType.APPLICATION_JSON)
   @Path("{id}")
   @ApiOperation(
           value = "Deletes a process group",
           response = ProcessGroupEntity.class,
           authorizations = {
                   @Authorization(value = "Write - /process-groups/{uuid}"),
                   @Authorization(value = "Write - Parent Process Group - /process-groups/{uuid}"),
                   @Authorization(value = "Read - any referenced Controller Services by any encapsulated components - /controller-services/{uuid}"),
                   @Authorization(value = "Write - /{component-type}/{uuid} - For all encapsulated components")
           }
   )
   @ApiResponses(
           value = {
                   @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
                   @ApiResponse(code = 401, message = "Client could not be authenticated."),
                   @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
                   @ApiResponse(code = 404, message = "The specified resource could not be found."),
                   @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
           }
   )
   public Response removeProcessGroup(
           @Context final HttpServletRequest httpServletRequest,
           @ApiParam(
                   value = "The revision is used to verify the client is working with the latest version of the flow.",
                   required = false
           )
           @QueryParam(VERSION) final LongParameter version,
           @ApiParam(
                   value = "If the client id is not specified, new one will be generated. This value (whether specified or generated) is included in the response.",
                   required = false
           )
           @QueryParam(CLIENT_ID) @DefaultValue(StringUtils.EMPTY) final ClientIdParameter clientId,
           @ApiParam(
                   value = "Acknowledges that this node is disconnected to allow for mutable requests to proceed.",
                   required = false
           )
           @QueryParam(DISCONNECTED_NODE_ACKNOWLEDGED) @DefaultValue("false") final Boolean disconnectedNodeAcknowledged,
           @ApiParam(
                   value = "The process group id.",
                   required = true
           )
           @PathParam("id") final String id) {

       // replicate if cluster manager
       if (isReplicateRequest()) {
           return replicate(HttpMethod.DELETE);
       } else if (isDisconnectedFromCluster()) {
           verifyDisconnectedNodeModification(disconnectedNodeAcknowledged);
       }

       final ProcessGroupEntity requestProcessGroupEntity = new ProcessGroupEntity();
       requestProcessGroupEntity.setId(id);

       // handle expects request (usually from the cluster manager)
       final Revision requestRevision = new Revision(version == null ? null : version.getLong(), clientId.getClientId(), id);
       return withWriteLock(
               serviceFacade,
               requestProcessGroupEntity,
               requestRevision,
               lookup -> {
                   final ProcessGroupAuthorizable processGroupAuthorizable = lookup.getProcessGroup(id);

                   // ensure write to this process group and all encapsulated components including controller services. additionally, ensure
                   // read to any referenced services by encapsulated components
                   authorizeProcessGroup(processGroupAuthorizable, authorizer, lookup, RequestAction.WRITE, true, true, false, false);

                   // ensure write permission to the parent process group, if applicable... if this is the root group the
                   // request will fail later but still need to handle authorization here
                   final Authorizable parentAuthorizable = processGroupAuthorizable.getAuthorizable().getParentAuthorizable();
                   if (parentAuthorizable != null) {
                       parentAuthorizable.authorize(authorizer, RequestAction.WRITE, NiFiUserUtils.getNiFiUser());
                   }
               },
               () -> serviceFacade.verifyDeleteProcessGroup(id),
               (revision, processGroupEntity) -> {
                   // delete the process group
                   final ProcessGroupEntity entity = serviceFacade.deleteProcessGroup(revision, processGroupEntity.getId());

                   // prune response as necessary
                   if (entity.getComponent() != null) {
                       entity.getComponent().setContents(null);
                   }

                   // create the response
                   return generateOkResponse(entity).build();
               }
       );
   }

   /**
    * Adds the specified process group.
    *
    * @param httpServletRequest request
    * @param groupId The group id
    * @param requestProcessGroupEntity A processGroupEntity
    * @return A processGroupEntity
    * @throws IOException if the request indicates that the Process Group should be imported from a Flow Registry and NiFi is unable to communicate with the Flow Registry
    */
   @POST
   @Consumes(MediaType.APPLICATION_JSON)
   @Produces(MediaType.APPLICATION_JSON)
   @Path("{id}/process-groups")
   @ApiOperation(
           value = "Creates a process group",
           response = ProcessGroupEntity.class,
           authorizations = {
                   @Authorization(value = "Write - /process-groups/{uuid}")
           }
   )
   @ApiResponses(
           value = {
                   @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
                   @ApiResponse(code = 401, message = "Client could not be authenticated."),
                   @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
                   @ApiResponse(code = 404, message = "The specified resource could not be found."),
                   @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
           }
   )
   public Response createProcessGroup(
           @Context final HttpServletRequest httpServletRequest,
           @ApiParam(
                   value = "The process group id.",
                   required = true
           )
           @PathParam("id") final String groupId,
           @ApiParam(
                   value = "The process group configuration details.",
                   required = true
           )
           final ProcessGroupEntity requestProcessGroupEntity,
           @ApiParam(
                   value = "Handling Strategy controls whether to keep or replace Parameter Contexts",
                   defaultValue = "KEEP_EXISTING"
           )
           @QueryParam("parameterContextHandlingStrategy")
           @DefaultValue("KEEP_EXISTING")
           final ParameterContextHandlingStrategy parameterContextHandlingStrategy
   ) {
       if (requestProcessGroupEntity == null || requestProcessGroupEntity.getComponent() == null) {
           throw new IllegalArgumentException("Process group details must be specified.");
       }

       if (requestProcessGroupEntity.getRevision() == null || (requestProcessGroupEntity.getRevision().getVersion() == null || requestProcessGroupEntity.getRevision().getVersion() != 0)) {
           throw new IllegalArgumentException("A revision of 0 must be specified when creating a new Process group.");
       }

       if (requestProcessGroupEntity.getComponent().getId() != null) {
           throw new IllegalArgumentException("Process group ID cannot be specified.");
       }

       final PositionDTO proposedPosition = requestProcessGroupEntity.getComponent().getPosition();
       if (proposedPosition != null) {
           if (proposedPosition.getX() == null || proposedPosition.getY() == null) {
               throw new IllegalArgumentException("The x and y coordinate of the proposed position must be specified.");
           }
       }

       // if the group name isn't specified, ensure the group is being imported from version control
       if (StringUtils.isBlank(requestProcessGroupEntity.getComponent().getName()) && requestProcessGroupEntity.getComponent().getVersionControlInformation() == null) {
           throw new IllegalArgumentException("The group name is required when the group is not imported from version control.");
       }

       if (requestProcessGroupEntity.getComponent().getParentGroupId() != null && !groupId.equals(requestProcessGroupEntity.getComponent().getParentGroupId())) {
           throw new IllegalArgumentException(String.format("If specified, the parent process group id %s must be the same as specified in the URI %s",
                   requestProcessGroupEntity.getComponent().getParentGroupId(), groupId));
       }
       requestProcessGroupEntity.getComponent().setParentGroupId(groupId);

       // Step 1: Ensure that user has write permissions to the Process Group. If not, then immediately fail.
       // Step 2: Retrieve flow from Flow Registry
       // Step 3: Resolve Bundle info
       // Step 4: Update contents of the ProcessGroupDTO passed in to include the components that need to be added.
       // Step 5: If any of the components is a Restricted Component, then we must authorize the user
       //         for write access to the RestrictedComponents resource
       // Step 6: Replicate the request or call serviceFacade.updateProcessGroup

       final VersionControlInformationDTO versionControlInfo = requestProcessGroupEntity.getComponent().getVersionControlInformation();
       if (versionControlInfo != null && requestProcessGroupEntity.getVersionedFlowSnapshot() == null) {
           // Step 1: Ensure that user has write permissions to the Process Group. If not, then immediately fail.
           // Step 2: Retrieve flow from Flow Registry
           final FlowSnapshotContainer flowSnapshotContainer = getFlowFromRegistry(versionControlInfo);
           final RegisteredFlowSnapshot flowSnapshot = flowSnapshotContainer.getFlowSnapshot();

           // Step 3: Enrich version control info came from UI
           if (flowSnapshot.getFlowContents() != null) {
               final VersionedFlowCoordinates versionedFlowCoordinates = flowSnapshot.getFlowContents().getVersionedFlowCoordinates();
               if (versionedFlowCoordinates != null) {
                   versionControlInfo.setStorageLocation(versionedFlowCoordinates.getStorageLocation());
               }
           }

           // Step 4: Replace parameter contexts if necessary
           if (ParameterContextHandlingStrategy.REPLACE.equals(parameterContextHandlingStrategy)) {
               parameterContextReplacer.replaceParameterContexts(flowSnapshot, serviceFacade.getParameterContexts());
           }

           // Step 5: Resolve Bundle info
           serviceFacade.discoverCompatibleBundles(flowSnapshot.getFlowContents());

           // If there are any Controller Services referenced that are inherited from the parent group, resolve those to point to the appropriate Controller Service, if we are able to.
           serviceFacade.resolveInheritedControllerServices(flowSnapshotContainer, groupId, NiFiUserUtils.getNiFiUser());

           // If there are any Parameter Providers referenced by Parameter Contexts, resolve these to point to the appropriate Parameter Provider, if we are able to.
           serviceFacade.resolveParameterProviders(flowSnapshot, NiFiUserUtils.getNiFiUser());

           // Step 6: Update contents of the ProcessGroupDTO passed in to include the components that need to be added.
           requestProcessGroupEntity.setVersionedFlowSnapshot(flowSnapshot);
       }

       if (versionControlInfo != null) {
           final RegisteredFlowSnapshot flowSnapshot = requestProcessGroupEntity.getVersionedFlowSnapshot();
           serviceFacade.verifyImportProcessGroup(versionControlInfo, flowSnapshot.getFlowContents(), groupId);
       }

       // Step 7: Replicate the request or call serviceFacade.updateProcessGroup
       if (isReplicateRequest()) {
           return replicate(HttpMethod.POST, requestProcessGroupEntity);
       } else if (isDisconnectedFromCluster()) {
           verifyDisconnectedNodeModification(requestProcessGroupEntity.isDisconnectedNodeAcknowledged());
       }

       return withWriteLock(
               serviceFacade,
               requestProcessGroupEntity,
               lookup -> authorizeAccess(groupId, requestProcessGroupEntity, lookup),
               () -> {
                   final RegisteredFlowSnapshot versionedFlowSnapshot = requestProcessGroupEntity.getVersionedFlowSnapshot();
                   if (versionedFlowSnapshot != null) {
                       serviceFacade.verifyComponentTypes(versionedFlowSnapshot.getFlowContents());
                   }
               },
               processGroupEntity -> {
                   final ProcessGroupDTO processGroup = processGroupEntity.getComponent();

                   // set the processor id as appropriate
                   processGroup.setId(generateUuid());

                   // ensure the group name comes from the versioned flow
                   final RegisteredFlowSnapshot flowSnapshot = processGroupEntity.getVersionedFlowSnapshot();
                   if (flowSnapshot != null && StringUtils.isNotBlank(flowSnapshot.getFlowContents().getName()) && StringUtils.isBlank(processGroup.getName())) {
                       processGroup.setName(flowSnapshot.getFlowContents().getName());
                   }

                   // create the process group contents
                   final Revision revision = getRevision(processGroupEntity, processGroup.getId());
                   ProcessGroupEntity entity = serviceFacade.createProcessGroup(revision, groupId, processGroup);

                   if (flowSnapshot != null) {
                       final RevisionDTO revisionDto = entity.getRevision();
                       final String newGroupId = entity.getComponent().getId();
                       final Revision newGroupRevision = new Revision(revisionDto.getVersion(), revisionDto.getClientId(), newGroupId);

                       // We don't want the Process Group's position to be updated because we want to keep the position where the user
                       // placed the Process Group. However, we do want to use the name of the Process Group that is in the Flow Contents.
                       // To accomplish this, we call updateProcessGroupContents() passing 'true' for the updateSettings flag but null out the position.
                       flowSnapshot.getFlowContents().setPosition(null);
                       entity = serviceFacade.updateProcessGroupContents(newGroupRevision, newGroupId, versionControlInfo, flowSnapshot,
                               getIdGenerationSeed().orElse(null), false, true, true);
                   }

                   populateRemainingProcessGroupEntityContent(entity);

                   // generate a 201 created response
                   String uri = entity.getUri();
                   return generateCreatedResponse(URI.create(uri), entity).build();
               }
       );
   }

   private FlowSnapshotContainer getFlowFromRegistry(final VersionControlInformationDTO versionControlInfo) {
       final FlowSnapshotContainer flowSnapshotContainer = serviceFacade.getVersionedFlowSnapshot(versionControlInfo, true);
       final RegisteredFlowSnapshot flowSnapshot = flowSnapshotContainer.getFlowSnapshot();
       final FlowRegistryBucket bucket = flowSnapshot.getBucket();
       final RegisteredFlow flow = flowSnapshot.getFlow();

       versionControlInfo.setBucketName(bucket.getName());
       versionControlInfo.setFlowName(flow.getName());
       versionControlInfo.setFlowDescription(flow.getDescription());

       versionControlInfo.setRegistryName(serviceFacade.getFlowRegistryName(versionControlInfo.getRegistryId()));
       final VersionedFlowState flowState = flowSnapshot.isLatest() ? VersionedFlowState.UP_TO_DATE : VersionedFlowState.STALE;
       versionControlInfo.setState(flowState.name());

       return flowSnapshotContainer;
   }


   /**
    * Retrieves all the child process groups of the process group with the given id.
    *
    * @param groupId the parent process group id
    * @return An entity containing all the child process group entities.
    */
   @GET
   @Consumes(MediaType.WILDCARD)
   @Produces(MediaType.APPLICATION_JSON)
   @Path("{id}/process-groups")
   @ApiOperation(
           value = "Gets all process groups",
           response = ProcessGroupsEntity.class,
           authorizations = {
                   @Authorization(value = "Read - /process-groups/{uuid}")
           }
   )
   @ApiResponses(
           value = {
                   @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
                   @ApiResponse(code = 401, message = "Client could not be authenticated."),
                   @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
                   @ApiResponse(code = 404, message = "The specified resource could not be found."),
                   @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
           }
   )
   public Response getProcessGroups(
           @ApiParam(
                   value = "The process group id.",
                   required = true
           )
           @PathParam("id") final String groupId) {

       if (isReplicateRequest()) {
           return replicate(HttpMethod.GET);
       }

       // authorize access
       serviceFacade.authorizeAccess(lookup -> {
           final Authorizable processGroup = lookup.getProcessGroup(groupId).getAuthorizable();
           processGroup.authorize(authorizer, RequestAction.READ, NiFiUserUtils.getNiFiUser());
       });

       // get the process groups
       final Set<ProcessGroupEntity> entities = serviceFacade.getProcessGroups(groupId, ProcessGroupRecursivity.DIRECT_CHILDREN);

       // always prune the contents
       for (final ProcessGroupEntity entity : entities) {
           if (entity.getComponent() != null) {
               entity.getComponent().setContents(null);
           }
       }

       // create the response entity
       final ProcessGroupsEntity entity = new ProcessGroupsEntity();
       entity.setProcessGroups(populateRemainingProcessGroupEntitiesContent(entities));

       // generate the response
       return generateOkResponse(entity).build();
   }

   // ----------
   // processors
   // ----------

   /**
    * Creates a new processor.
    *
    * @param httpServletRequest request
    * @param groupId            The group id
    * @param requestProcessorEntity    A processorEntity.
    * @return A processorEntity.
    */
   @POST
   @Consumes(MediaType.APPLICATION_JSON)
   @Produces(MediaType.APPLICATION_JSON)
   @Path("{id}/processors")
   @ApiOperation(
           value = "Creates a new processor",
           response = ProcessorEntity.class,
           authorizations = {
                   @Authorization(value = "Write - /process-groups/{uuid}"),
                   @Authorization(value = "Read - any referenced Controller Services - /controller-services/{uuid}"),
                   @Authorization(value = "Write - if the Processor is restricted - /restricted-components")
           }
   )
   @ApiResponses(
           value = {
                   @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
                   @ApiResponse(code = 401, message = "Client could not be authenticated."),
                   @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
                   @ApiResponse(code = 404, message = "The specified resource could not be found."),
                   @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
           }
   )
   public Response createProcessor(
           @Context final HttpServletRequest httpServletRequest,
           @ApiParam(
                   value = "The process group id.",
                   required = true
           )
           @PathParam("id") final String groupId,
           @ApiParam(
                   value = "The processor configuration details.",
                   required = true
           ) final ProcessorEntity requestProcessorEntity) {

       if (requestProcessorEntity == null || requestProcessorEntity.getComponent() == null) {
           throw new IllegalArgumentException("Processor details must be specified.");
       }

       if (requestProcessorEntity.getRevision() == null || (requestProcessorEntity.getRevision().getVersion() == null || requestProcessorEntity.getRevision().getVersion() != 0)) {
           throw new IllegalArgumentException("A revision of 0 must be specified when creating a new Processor.");
       }

       final ProcessorDTO requestProcessor = requestProcessorEntity.getComponent();
       if (requestProcessor.getId() != null) {
           throw new IllegalArgumentException("Processor ID cannot be specified.");
       }

       if (StringUtils.isBlank(requestProcessor.getType())) {
           throw new IllegalArgumentException("The type of processor to create must be specified.");
       }

       final PositionDTO proposedPosition = requestProcessor.getPosition();
       if (proposedPosition != null) {
           if (proposedPosition.getX() == null || proposedPosition.getY() == null) {
               throw new IllegalArgumentException("The x and y coordinate of the proposed position must be specified.");
           }
       }

       if (requestProcessor.getParentGroupId() != null && !groupId.equals(requestProcessor.getParentGroupId())) {
           throw new IllegalArgumentException(String.format("If specified, the parent process group id %s must be the same as specified in the URI %s",
                   requestProcessor.getParentGroupId(), groupId));
       }
       requestProcessor.setParentGroupId(groupId);

       if (isReplicateRequest()) {
           return replicate(HttpMethod.POST, requestProcessorEntity);
       } else if (isDisconnectedFromCluster()) {
           verifyDisconnectedNodeModification(requestProcessorEntity.isDisconnectedNodeAcknowledged());
       }

       return withWriteLock(
               serviceFacade,
               requestProcessorEntity,
               lookup -> {
                   final NiFiUser user = NiFiUserUtils.getNiFiUser();

                   final ProcessGroupAuthorizable groupAuthorizable = lookup.getProcessGroup(groupId);
                   final Authorizable processGroup = groupAuthorizable.getAuthorizable();
                   processGroup.authorize(authorizer, RequestAction.WRITE, user);

                   final Authorizable parameterContext = groupAuthorizable.getProcessGroup().getParameterContext();
                   final ProcessorConfigDTO configDto = requestProcessor.getConfig();
                   if (parameterContext != null && configDto != null) {
                       AuthorizeParameterReference.authorizeParameterReferences(configDto.getProperties(), authorizer, parameterContext, user);
                   }

                   ComponentAuthorizable authorizable = null;
                   try {
                       authorizable = lookup.getConfigurableComponent(requestProcessor.getType(), requestProcessor.getBundle());

                       if (authorizable.isRestricted()) {
                           authorizeRestrictions(authorizer, authorizable);
                       }

                       final ProcessorConfigDTO config = requestProcessor.getConfig();
                       if (config != null && config.getProperties() != null) {
                           AuthorizeControllerServiceReference.authorizeControllerServiceReferences(config.getProperties(), authorizable, authorizer, lookup);
                       }
                   } finally {
                       if (authorizable != null) {
                           authorizable.cleanUpResources();
                       }
                   }
               },
               () -> serviceFacade.verifyCreateProcessor(requestProcessor),
               processorEntity -> {
                   final ProcessorDTO processor = processorEntity.getComponent();

                   // set the processor id as appropriate
                   processor.setId(generateUuid());

                   // create the new processor
                   final Revision revision = getRevision(processorEntity, processor.getId());
                   final ProcessorEntity entity = serviceFacade.createProcessor(revision, groupId, processor);
                   processorResource.populateRemainingProcessorEntityContent(entity);

                   // generate a 201 created response
                   String uri = entity.getUri();
                   return generateCreatedResponse(URI.create(uri), entity).build();
               }
       );
   }

   /**
    * Retrieves all the processors in this NiFi.
    *
    * @param groupId group id
    * @return A processorsEntity.
    */
   @GET
   @Consumes(MediaType.WILDCARD)
   @Produces(MediaType.APPLICATION_JSON)
   @Path("{id}/processors")
   @ApiOperation(
           value = "Gets all processors",
           response = ProcessorsEntity.class,
           authorizations = {
                   @Authorization(value = "Read - /process-groups/{uuid}")
           }
   )
   @ApiResponses(
           value = {
                   @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
                   @ApiResponse(code = 401, message = "Client could not be authenticated."),
                   @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
                   @ApiResponse(code = 404, message = "The specified resource could not be found."),
                   @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
           }
   )
   public Response getProcessors(
           @ApiParam(
                   value = "The process group id.",
                   required = true
           )
           @PathParam("id") final String groupId,
           @ApiParam("Whether or not to include processors from descendant process groups") @QueryParam("includeDescendantGroups") @DefaultValue("false") boolean includeDescendantGroups) {

       if (isReplicateRequest()) {
           return replicate(HttpMethod.GET);
       }

       // authorize access
       serviceFacade.authorizeAccess(lookup -> {
           final Authorizable processGroup = lookup.getProcessGroup(groupId).getAuthorizable();
           processGroup.authorize(authorizer, RequestAction.READ, NiFiUserUtils.getNiFiUser());
       });

       // get the processors
       final Set<ProcessorEntity> processors = serviceFacade.getProcessors(groupId, includeDescendantGroups);

       // create the response entity
       final ProcessorsEntity entity = new ProcessorsEntity();
       entity.setProcessors(processorResource.populateRemainingProcessorEntitiesContent(processors));

       // generate the response
       return generateOkResponse(entity).build();
   }

   // -----------
   // input ports
   // -----------

   /**
    * Creates a new input port.
    *
    * @param httpServletRequest request
    * @param groupId            The group id
    * @param requestPortEntity         A inputPortEntity.
    * @return A inputPortEntity.
    */
   @POST
   @Consumes(MediaType.APPLICATION_JSON)
   @Produces(MediaType.APPLICATION_JSON)
   @Path("{id}/input-ports")
   @ApiOperation(
           value = "Creates an input port",
           response = PortEntity.class,
           authorizations = {
                   @Authorization(value = "Write - /process-groups/{uuid}")
           }
   )
   @ApiResponses(
           value = {
                   @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
                   @ApiResponse(code = 401, message = "Client could not be authenticated."),
                   @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
                   @ApiResponse(code = 404, message = "The specified resource could not be found."),
                   @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
           }
   )
   public Response createInputPort(
           @Context final HttpServletRequest httpServletRequest,
           @ApiParam(
                   value = "The process group id.",
                   required = true
           )
           @PathParam("id") final String groupId,
           @ApiParam(
                   value = "The input port configuration details.",
                   required = true
           ) final PortEntity requestPortEntity) {

       if (requestPortEntity == null || requestPortEntity.getComponent() == null) {
           throw new IllegalArgumentException("Port details must be specified.");
       }

       if (requestPortEntity.getRevision() == null || (requestPortEntity.getRevision().getVersion() == null || requestPortEntity.getRevision().getVersion() != 0)) {
           throw new IllegalArgumentException("A revision of 0 must be specified when creating a new Input port.");
       }

       if (requestPortEntity.getComponent().getId() != null) {
           throw new IllegalArgumentException("Input port ID cannot be specified.");
       }

       final PositionDTO proposedPosition = requestPortEntity.getComponent().getPosition();
       if (proposedPosition != null) {
           if (proposedPosition.getX() == null || proposedPosition.getY() == null) {
               throw new IllegalArgumentException("The x and y coordinate of the proposed position must be specified.");
           }
       }

       if (requestPortEntity.getComponent().getParentGroupId() != null && !groupId.equals(requestPortEntity.getComponent().getParentGroupId())) {
           throw new IllegalArgumentException(String.format("If specified, the parent process group id %s must be the same as specified in the URI %s",
                   requestPortEntity.getComponent().getParentGroupId(), groupId));
       }
       requestPortEntity.getComponent().setParentGroupId(groupId);

       if (isReplicateRequest()) {
           return replicate(HttpMethod.POST, requestPortEntity);
       } else if (isDisconnectedFromCluster()) {
           verifyDisconnectedNodeModification(requestPortEntity.isDisconnectedNodeAcknowledged());
       }

       return withWriteLock(
               serviceFacade,
               requestPortEntity,
               lookup -> {
                   final Authorizable processGroup = lookup.getProcessGroup(groupId).getAuthorizable();
                   processGroup.authorize(authorizer, RequestAction.WRITE, NiFiUserUtils.getNiFiUser());
               },
               null,
               portEntity -> {
                   // set the processor id as appropriate
                   portEntity.getComponent().setId(generateUuid());

                   // create the input port and generate the json
                   final Revision revision = getRevision(portEntity, portEntity.getComponent().getId());
                   final PortEntity entity = serviceFacade.createInputPort(revision, groupId, portEntity.getComponent());
                   inputPortResource.populateRemainingInputPortEntityContent(entity);

                   // build the response
                   return generateCreatedResponse(URI.create(entity.getUri()), entity).build();
               }
       );
   }

   /**
    * Retrieves all the of input ports in this NiFi.
    *
    * @return A inputPortsEntity.
    */
   @GET
   @Consumes(MediaType.WILDCARD)
   @Produces(MediaType.APPLICATION_JSON)
   @Path("{id}/input-ports")
   @ApiOperation(
           value = "Gets all input ports",
           response = InputPortsEntity.class,
           authorizations = {
                   @Authorization(value = "Read - /process-groups/{uuid}")
           }
   )
   @ApiResponses(
           value = {
                   @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
                   @ApiResponse(code = 401, message = "Client could not be authenticated."),
                   @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
                   @ApiResponse(code = 404, message = "The specified resource could not be found."),
                   @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
           }
   )
   public Response getInputPorts(
           @ApiParam(
                   value = "The process group id.",
                   required = true
           )
           @PathParam("id") final String groupId) {

       if (isReplicateRequest()) {
           return replicate(HttpMethod.GET);
       }

       // authorize access
       serviceFacade.authorizeAccess(lookup -> {
           final Authorizable processGroup = lookup.getProcessGroup(groupId).getAuthorizable();
           processGroup.authorize(authorizer, RequestAction.READ, NiFiUserUtils.getNiFiUser());
       });

       // get all the input ports
       final Set<PortEntity> inputPorts = serviceFacade.getInputPorts(groupId);

       final InputPortsEntity entity = new InputPortsEntity();
       entity.setInputPorts(inputPortResource.populateRemainingInputPortEntitiesContent(inputPorts));

       // generate the response
       return generateOkResponse(entity).build();
   }

   // ------------
   // output ports
   // ------------

   /**
    * Creates a new output port.
    *
    * @param httpServletRequest request
    * @param groupId            The group id
    * @param requestPortEntity         A outputPortEntity.
    * @return A outputPortEntity.
    */
   @POST
   @Consumes(MediaType.APPLICATION_JSON)
   @Produces(MediaType.APPLICATION_JSON)
   @Path("{id}/output-ports")
   @ApiOperation(
           value = "Creates an output port",
           response = PortEntity.class,
           authorizations = {
                   @Authorization(value = "Write - /process-groups/{uuid}")
           }
   )
   @ApiResponses(
           value = {
                   @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
                   @ApiResponse(code = 401, message = "Client could not be authenticated."),
                   @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
                   @ApiResponse(code = 404, message = "The specified resource could not be found."),
                   @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
           }
   )
   public Response createOutputPort(
           @Context final HttpServletRequest httpServletRequest,
           @ApiParam(
                   value = "The process group id.",
                   required = true
           )
           @PathParam("id") final String groupId,
           @ApiParam(
                   value = "The output port configuration.",
                   required = true
           ) final PortEntity requestPortEntity) {

       if (requestPortEntity == null || requestPortEntity.getComponent() == null) {
           throw new IllegalArgumentException("Port details must be specified.");
       }

       if (requestPortEntity.getRevision() == null || (requestPortEntity.getRevision().getVersion() == null || requestPortEntity.getRevision().getVersion() != 0)) {
           throw new IllegalArgumentException("A revision of 0 must be specified when creating a new Output port.");
       }

       if (requestPortEntity.getComponent().getId() != null) {
           throw new IllegalArgumentException("Output port ID cannot be specified.");
       }

       final PositionDTO proposedPosition = requestPortEntity.getComponent().getPosition();
       if (proposedPosition != null) {
           if (proposedPosition.getX() == null || proposedPosition.getY() == null) {
               throw new IllegalArgumentException("The x and y coordinate of the proposed position must be specified.");
           }
       }

       if (requestPortEntity.getComponent().getParentGroupId() != null && !groupId.equals(requestPortEntity.getComponent().getParentGroupId())) {
           throw new IllegalArgumentException(String.format("If specified, the parent process group id %s must be the same as specified in the URI %s",
                   requestPortEntity.getComponent().getParentGroupId(), groupId));
       }
       requestPortEntity.getComponent().setParentGroupId(groupId);

       if (isReplicateRequest()) {
           return replicate(HttpMethod.POST, requestPortEntity);
       } else if (isDisconnectedFromCluster()) {
           verifyDisconnectedNodeModification(requestPortEntity.isDisconnectedNodeAcknowledged());
       }

       return withWriteLock(
               serviceFacade,
               requestPortEntity,
               lookup -> {
                   final Authorizable processGroup = lookup.getProcessGroup(groupId).getAuthorizable();
                   processGroup.authorize(authorizer, RequestAction.WRITE, NiFiUserUtils.getNiFiUser());
               },
               null,
               portEntity -> {
                   // set the processor id as appropriate
                   portEntity.getComponent().setId(generateUuid());

                   // create the output port and generate the json
                   final Revision revision = getRevision(portEntity, portEntity.getComponent().getId());
                   final PortEntity entity = serviceFacade.createOutputPort(revision, groupId, portEntity.getComponent());
                   outputPortResource.populateRemainingOutputPortEntityContent(entity);

                   // build the response
                   return generateCreatedResponse(URI.create(entity.getUri()), entity).build();
               }
       );
   }

   /**
    * Retrieves all the of output ports in this NiFi.
    *
    * @return A outputPortsEntity.
    */
   @GET
   @Consumes(MediaType.WILDCARD)
   @Produces(MediaType.APPLICATION_JSON)
   @Path("{id}/output-ports")
   @ApiOperation(
           value = "Gets all output ports",
           response = OutputPortsEntity.class,
           authorizations = {
                   @Authorization(value = "Read - /process-groups/{uuid}")
           }
   )
   @ApiResponses(
           value = {
                   @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
                   @ApiResponse(code = 401, message = "Client could not be authenticated."),
                   @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
                   @ApiResponse(code = 404, message = "The specified resource could not be found."),
                   @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
           }
   )
   public Response getOutputPorts(
           @ApiParam(
                   value = "The process group id.",
                   required = true
           )
           @PathParam("id") final String groupId) {

       if (isReplicateRequest()) {
           return replicate(HttpMethod.GET);
       }

       // authorize access
       serviceFacade.authorizeAccess(lookup -> {
           final Authorizable processGroup = lookup.getProcessGroup(groupId).getAuthorizable();
           processGroup.authorize(authorizer, RequestAction.READ, NiFiUserUtils.getNiFiUser());
       });

       // get all the output ports
       final Set<PortEntity> outputPorts = serviceFacade.getOutputPorts(groupId);

       // create the response entity
       final OutputPortsEntity entity = new OutputPortsEntity();
       entity.setOutputPorts(outputPortResource.populateRemainingOutputPortEntitiesContent(outputPorts));

       // generate the response
       return generateOkResponse(entity).build();
   }

   // -------
   // funnels
   // -------

   /**
    * Creates a new Funnel.
    *
    * @param httpServletRequest request
    * @param groupId            The group id
    * @param requestFunnelEntity       A funnelEntity.
    * @return A funnelEntity.
    */
   @POST
   @Consumes(MediaType.APPLICATION_JSON)
   @Produces(MediaType.APPLICATION_JSON)
   @Path("{id}/funnels")
   @ApiOperation(
           value = "Creates a funnel",
           response = FunnelEntity.class,
           authorizations = {
                   @Authorization(value = "Write - /process-groups/{uuid}")
           }
   )
   @ApiResponses(
           value = {
                   @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
                   @ApiResponse(code = 401, message = "Client could not be authenticated."),
                   @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
                   @ApiResponse(code = 404, message = "The specified resource could not be found."),
                   @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
           }
   )
   public Response createFunnel(
           @Context final HttpServletRequest httpServletRequest,
           @ApiParam(
                   value = "The process group id.",
                   required = true
           )
           @PathParam("id") final String groupId,
           @ApiParam(
                   value = "The funnel configuration details.",
                   required = true
           ) final FunnelEntity requestFunnelEntity) {

       if (requestFunnelEntity == null || requestFunnelEntity.getComponent() == null) {
           throw new IllegalArgumentException("Funnel details must be specified.");
       }

       if (requestFunnelEntity.getRevision() == null || (requestFunnelEntity.getRevision().getVersion() == null || requestFunnelEntity.getRevision().getVersion() != 0)) {
           throw new IllegalArgumentException("A revision of 0 must be specified when creating a new Funnel.");
       }

       if (requestFunnelEntity.getComponent().getId() != null) {
           throw new IllegalArgumentException("Funnel ID cannot be specified.");
       }

       final PositionDTO proposedPosition = requestFunnelEntity.getComponent().getPosition();
       if (proposedPosition != null) {
           if (proposedPosition.getX() == null || proposedPosition.getY() == null) {
               throw new IllegalArgumentException("The x and y coordinate of the proposed position must be specified.");
           }
       }

       if (requestFunnelEntity.getComponent().getParentGroupId() != null && !groupId.equals(requestFunnelEntity.getComponent().getParentGroupId())) {
           throw new IllegalArgumentException(String.format("If specified, the parent process group id %s must be the same as specified in the URI %s",
                   requestFunnelEntity.getComponent().getParentGroupId(), groupId));
       }
       requestFunnelEntity.getComponent().setParentGroupId(groupId);

       if (isReplicateRequest()) {
           return replicate(HttpMethod.POST, requestFunnelEntity);
       } else if (isDisconnectedFromCluster()) {
           verifyDisconnectedNodeModification(requestFunnelEntity.isDisconnectedNodeAcknowledged());
       }

       return withWriteLock(
               serviceFacade,
               requestFunnelEntity,
               lookup -> {
                   final Authorizable processGroup = lookup.getProcessGroup(groupId).getAuthorizable();
                   processGroup.authorize(authorizer, RequestAction.WRITE, NiFiUserUtils.getNiFiUser());
               },
               null,
               funnelEntity -> {
                   // set the processor id as appropriate
                   funnelEntity.getComponent().setId(generateUuid());

                   // create the funnel and generate the json
                   final Revision revision = getRevision(funnelEntity, funnelEntity.getComponent().getId());
                   final FunnelEntity entity = serviceFacade.createFunnel(revision, groupId, funnelEntity.getComponent());
                   funnelResource.populateRemainingFunnelEntityContent(entity);

                   // build the response
                   return generateCreatedResponse(URI.create(entity.getUri()), entity).build();
               }
       );
   }

   /**
    * Retrieves all the of funnels in this NiFi.
    *
    * @return A funnelsEntity.
    */
   @GET
   @Consumes(MediaType.WILDCARD)
   @Produces(MediaType.APPLICATION_JSON)
   @Path("{id}/funnels")
   @ApiOperation(
           value = "Gets all funnels",
           response = FunnelsEntity.class,
           authorizations = {
                   @Authorization(value = "Read - /process-groups/{uuid}")
           }
   )
   @ApiResponses(
           value = {
                   @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
                   @ApiResponse(code = 401, message = "Client could not be authenticated."),
                   @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
                   @ApiResponse(code = 404, message = "The specified resource could not be found."),
                   @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
           }
   )
   public Response getFunnels(
           @ApiParam(
                   value = "The process group id.",
                   required = true
           )
           @PathParam("id") final String groupId) {

       if (isReplicateRequest()) {
           return replicate(HttpMethod.GET);
       }

       // authorize access
       serviceFacade.authorizeAccess(lookup -> {
           final Authorizable processGroup = lookup.getProcessGroup(groupId).getAuthorizable();
           processGroup.authorize(authorizer, RequestAction.READ, NiFiUserUtils.getNiFiUser());
       });

       // get all the funnels
       final Set<FunnelEntity> funnels = serviceFacade.getFunnels(groupId);

       // create the response entity
       final FunnelsEntity entity = new FunnelsEntity();
       entity.setFunnels(funnelResource.populateRemainingFunnelEntitiesContent(funnels));

       // generate the response
       return generateOkResponse(entity).build();
   }

   // ------
   // labels
   // ------

   /**
    * Creates a new Label.
    *
    * @param httpServletRequest request
    * @param groupId            The group id
    * @param requestLabelEntity        A labelEntity.
    * @return A labelEntity.
    */
   @POST
   @Consumes(MediaType.APPLICATION_JSON)
   @Produces(MediaType.APPLICATION_JSON)
   @Path("{id}/labels")
   @ApiOperation(
           value = "Creates a label",
           response = LabelEntity.class,
           authorizations = {
                   @Authorization(value = "Write - /process-groups/{uuid}")
           }
   )
   @ApiResponses(
           value = {
                   @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
                   @ApiResponse(code = 401, message = "Client could not be authenticated."),
                   @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
                   @ApiResponse(code = 404, message = "The specified resource could not be found."),
                   @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
           }
   )
   public Response createLabel(
           @Context final HttpServletRequest httpServletRequest,
           @ApiParam(
                   value = "The process group id.",
                   required = true
           )
           @PathParam("id") final String groupId,
           @ApiParam(
                   value = "The label configuration details.",
                   required = true
           ) final LabelEntity requestLabelEntity) {

       if (requestLabelEntity == null || requestLabelEntity.getComponent() == null) {
           throw new IllegalArgumentException("Label details must be specified.");
       }

       if (requestLabelEntity.getRevision() == null || (requestLabelEntity.getRevision().getVersion() == null || requestLabelEntity.getRevision().getVersion() != 0)) {
           throw new IllegalArgumentException("A revision of 0 must be specified when creating a new Label.");
       }

       if (requestLabelEntity.getComponent().getId() != null) {
           throw new IllegalArgumentException("Label ID cannot be specified.");
       }

       final PositionDTO proposedPosition = requestLabelEntity.getComponent().getPosition();
       if (proposedPosition != null) {
           if (proposedPosition.getX() == null || proposedPosition.getY() == null) {
               throw new IllegalArgumentException("The x and y coordinate of the proposed position must be specified.");
           }
       }

       if (requestLabelEntity.getComponent().getParentGroupId() != null && !groupId.equals(requestLabelEntity.getComponent().getParentGroupId())) {
           throw new IllegalArgumentException(String.format("If specified, the parent process group id %s must be the same as specified in the URI %s",
                   requestLabelEntity.getComponent().getParentGroupId(), groupId));
       }
       requestLabelEntity.getComponent().setParentGroupId(groupId);

       if (isReplicateRequest()) {
           return replicate(HttpMethod.POST, requestLabelEntity);
       } else if (isDisconnectedFromCluster()) {
           verifyDisconnectedNodeModification(requestLabelEntity.isDisconnectedNodeAcknowledged());
       }

       return withWriteLock(
               serviceFacade,
               requestLabelEntity,
               lookup -> {
                   final Authorizable processGroup = lookup.getProcessGroup(groupId).getAuthorizable();
                   processGroup.authorize(authorizer, RequestAction.WRITE, NiFiUserUtils.getNiFiUser());
               },
               null,
               labelEntity -> {
                   // set the processor id as appropriate
                   labelEntity.getComponent().setId(generateUuid());

                   // create the label and generate the json
                   final Revision revision = getRevision(labelEntity, labelEntity.getComponent().getId());
                   final LabelEntity entity = serviceFacade.createLabel(revision, groupId, labelEntity.getComponent());
                   labelResource.populateRemainingLabelEntityContent(entity);

                   // build the response
                   return generateCreatedResponse(URI.create(entity.getUri()), entity).build();
               }
       );
   }

   /**
    * Retrieves all the of labels in this NiFi.
    *
    * @return A labelsEntity.
    */
   @GET
   @Consumes(MediaType.WILDCARD)
   @Produces(MediaType.APPLICATION_JSON)
   @Path("{id}/labels")
   @ApiOperation(
           value = "Gets all labels",
           response = LabelsEntity.class,
           authorizations = {
                   @Authorization(value = "Read - /process-groups/{uuid}")
           }
   )
   @ApiResponses(
           value = {
                   @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
                   @ApiResponse(code = 401, message = "Client could not be authenticated."),
                   @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
                   @ApiResponse(code = 404, message = "The specified resource could not be found."),
                   @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
           }
   )
   public Response getLabels(
           @ApiParam(
                   value = "The process group id.",
                   required = true
           )
           @PathParam("id") final String groupId) {

       if (isReplicateRequest()) {
           return replicate(HttpMethod.GET);
       }

       // authorize access
       serviceFacade.authorizeAccess(lookup -> {
           final Authorizable processGroup = lookup.getProcessGroup(groupId).getAuthorizable();
           processGroup.authorize(authorizer, RequestAction.READ, NiFiUserUtils.getNiFiUser());
       });

       // get all the labels
       final Set<LabelEntity> labels = serviceFacade.getLabels(groupId);

       // create the response entity
       final LabelsEntity entity = new LabelsEntity();
       entity.setLabels(labelResource.populateRemainingLabelEntitiesContent(labels));

       // generate the response
       return generateOkResponse(entity).build();
   }

   // ---------------------
   // remote process groups
   // ---------------------

   /**
    * Creates a new remote process group.
    *
    * @param httpServletRequest       request
    * @param groupId                  The group id
    * @param requestRemoteProcessGroupEntity A remoteProcessGroupEntity.
    * @return A remoteProcessGroupEntity.
    */
   @POST
   @Consumes(MediaType.APPLICATION_JSON)
   @Produces(MediaType.APPLICATION_JSON)
   @Path("{id}/remote-process-groups")
   @ApiOperation(
           value = "Creates a new process group",
           response = RemoteProcessGroupEntity.class,
           authorizations = {
                   @Authorization(value = "Write - /process-groups/{uuid}")
           }
   )
   @ApiResponses(
           value = {
                   @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
                   @ApiResponse(code = 401, message = "Client could not be authenticated."),
                   @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
                   @ApiResponse(code = 404, message = "The specified resource could not be found."),
                   @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
           }
   )
   public Response createRemoteProcessGroup(
           @Context final HttpServletRequest httpServletRequest,
           @ApiParam(
                   value = "The process group id.",
                   required = true
           )
           @PathParam("id") final String groupId,
           @ApiParam(
                   value = "The remote process group configuration details.",
                   required = true
           ) final RemoteProcessGroupEntity requestRemoteProcessGroupEntity) {

       if (requestRemoteProcessGroupEntity == null || requestRemoteProcessGroupEntity.getComponent() == null) {
           throw new IllegalArgumentException("Remote process group details must be specified.");
       }

       if (requestRemoteProcessGroupEntity.getRevision() == null
               || (requestRemoteProcessGroupEntity.getRevision().getVersion() == null || requestRemoteProcessGroupEntity.getRevision().getVersion() != 0)) {
           throw new IllegalArgumentException("A revision of 0 must be specified when creating a new Remote process group.");
       }

       final RemoteProcessGroupDTO requestRemoteProcessGroupDTO = requestRemoteProcessGroupEntity.getComponent();

       if (requestRemoteProcessGroupDTO.getId() != null) {
           throw new IllegalArgumentException("Remote process group ID cannot be specified.");
       }

       if (requestRemoteProcessGroupDTO.getTargetUri() == null) {
           throw new IllegalArgumentException("The URI of the process group must be specified.");
       }

       final PositionDTO proposedPosition = requestRemoteProcessGroupDTO.getPosition();
       if (proposedPosition != null) {
           if (proposedPosition.getX() == null || proposedPosition.getY() == null) {
               throw new IllegalArgumentException("The x and y coordinate of the proposed position must be specified.");
           }
       }

       if (requestRemoteProcessGroupDTO.getParentGroupId() != null && !groupId.equals(requestRemoteProcessGroupDTO.getParentGroupId())) {
           throw new IllegalArgumentException(String.format("If specified, the parent process group id %s must be the same as specified in the URI %s",
                   requestRemoteProcessGroupDTO.getParentGroupId(), groupId));
       }
       requestRemoteProcessGroupDTO.setParentGroupId(groupId);

       if (isReplicateRequest()) {
           return replicate(HttpMethod.POST, requestRemoteProcessGroupEntity);
       } else if (isDisconnectedFromCluster()) {
           verifyDisconnectedNodeModification(requestRemoteProcessGroupEntity.isDisconnectedNodeAcknowledged());
       }

       return withWriteLock(
               serviceFacade,
               requestRemoteProcessGroupEntity,
               lookup -> {
                   final Authorizable processGroup = lookup.getProcessGroup(groupId).getAuthorizable();
                   processGroup.authorize(authorizer, RequestAction.WRITE, NiFiUserUtils.getNiFiUser());
               },
               null,
               remoteProcessGroupEntity -> {
                   final RemoteProcessGroupDTO remoteProcessGroupDTO = remoteProcessGroupEntity.getComponent();

                   // set the processor id as appropriate
                   remoteProcessGroupDTO.setId(generateUuid());

                   // parse the uri to check if the uri is valid
                   final String targetUris = remoteProcessGroupDTO.getTargetUris();
                   SiteToSiteRestApiClient.parseClusterUrls(targetUris);

                   // since the uri is valid, use it
                   remoteProcessGroupDTO.setTargetUris(targetUris);

                   // create the remote process group
                   final Revision revision = getRevision(remoteProcessGroupEntity, remoteProcessGroupDTO.getId());
                   final RemoteProcessGroupEntity entity = serviceFacade.createRemoteProcessGroup(revision, groupId, remoteProcessGroupDTO);
                   remoteProcessGroupResource.populateRemainingRemoteProcessGroupEntityContent(entity);

                   return generateCreatedResponse(URI.create(entity.getUri()), entity).build();
               }
       );
   }

   /**
    * Retrieves all the of remote process groups in this NiFi.
    *
    * @return A remoteProcessGroupEntity.
    */
   @GET
   @Consumes(MediaType.WILDCARD)
   @Produces(MediaType.APPLICATION_JSON)
   @Path("{id}/remote-process-groups")
   @ApiOperation(
           value = "Gets all remote process groups",
           response = RemoteProcessGroupsEntity.class,
           authorizations = {
                   @Authorization(value = "Read - /process-groups/{uuid}")
           }
   )
   @ApiResponses(
           value = {
                   @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
                   @ApiResponse(code = 401, message = "Client could not be authenticated."),
                   @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
                   @ApiResponse(code = 404, message = "The specified resource could not be found."),
                   @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
           }
   )
   public Response getRemoteProcessGroups(
           @ApiParam(
                   value = "The process group id.",
                   required = true
           )
           @PathParam("id") final String groupId) {

       if (isReplicateRequest()) {
           return replicate(HttpMethod.GET);
       }

       // authorize access
       serviceFacade.authorizeAccess(lookup -> {
           final Authorizable processGroup = lookup.getProcessGroup(groupId).getAuthorizable();
           processGroup.authorize(authorizer, RequestAction.READ, NiFiUserUtils.getNiFiUser());
       });

       // get all the remote process groups
       final Set<RemoteProcessGroupEntity> remoteProcessGroups = serviceFacade.getRemoteProcessGroups(groupId);

       // prune response as necessary
       for (RemoteProcessGroupEntity remoteProcessGroupEntity : remoteProcessGroups) {
           if (remoteProcessGroupEntity.getComponent() != null) {
               remoteProcessGroupEntity.getComponent().setContents(null);
           }
       }

       // create the response entity
       final RemoteProcessGroupsEntity entity = new RemoteProcessGroupsEntity();
       entity.setRemoteProcessGroups(remoteProcessGroupResource.populateRemainingRemoteProcessGroupEntitiesContent(remoteProcessGroups));

       // generate the response
       return generateOkResponse(entity).build();
   }

   // -----------
   // connections
   // -----------

   /**
    * Creates a new connection.
    *
    * @param httpServletRequest request
    * @param groupId            The group id
    * @param requestConnectionEntity   A connectionEntity.
    * @return A connectionEntity.
    */
   @POST
   @Consumes(MediaType.APPLICATION_JSON)
   @Produces(MediaType.APPLICATION_JSON)
   @Path("{id}/connections")
   @ApiOperation(
           value = "Creates a connection",
           response = ConnectionEntity.class,
           authorizations = {
                   @Authorization(value = "Write - /process-groups/{uuid}"),
                   @Authorization(value = "Write Source - /{component-type}/{uuid}"),
                   @Authorization(value = "Write Destination - /{component-type}/{uuid}")
           }
   )
   @ApiResponses(
           value = {
                   @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
                   @ApiResponse(code = 401, message = "Client could not be authenticated."),
                   @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
                   @ApiResponse(code = 404, message = "The specified resource could not be found."),
                   @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
           }
   )
   public Response createConnection(
           @Context final HttpServletRequest httpServletRequest,
           @ApiParam(
                   value = "The process group id.",
                   required = true
           )
           @PathParam("id") final String groupId,
           @ApiParam(
                   value = "The connection configuration details.",
                   required = true
           ) final ConnectionEntity requestConnectionEntity) {

       if (requestConnectionEntity == null || requestConnectionEntity.getComponent() == null) {
           throw new IllegalArgumentException("Connection details must be specified.");
       }

       if (requestConnectionEntity.getRevision() == null || (requestConnectionEntity.getRevision().getVersion() == null || requestConnectionEntity.getRevision().getVersion() != 0)) {
           throw new IllegalArgumentException("A revision of 0 must be specified when creating a new Connection.");
       }

       if (requestConnectionEntity.getComponent().getId() != null) {
           throw new IllegalArgumentException("Connection ID cannot be specified.");
       }

       final List<PositionDTO> proposedBends = requestConnectionEntity.getComponent().getBends();
       if (proposedBends != null) {
           for (final PositionDTO proposedBend : proposedBends) {
               if (proposedBend.getX() == null || proposedBend.getY() == null) {
                   throw new IllegalArgumentException("The x and y coordinate of the each bend must be specified.");
               }
           }
       }

       if (requestConnectionEntity.getComponent().getParentGroupId() != null && !groupId.equals(requestConnectionEntity.getComponent().getParentGroupId())) {
           throw new IllegalArgumentException(String.format("If specified, the parent process group id %s must be the same as specified in the URI %s",
                   requestConnectionEntity.getComponent().getParentGroupId(), groupId));
       }
       requestConnectionEntity.getComponent().setParentGroupId(groupId);

       // get the connection
       final ConnectionDTO requestConnection = requestConnectionEntity.getComponent();

       if (requestConnection.getSource() == null || requestConnection.getSource().getId() == null) {
           throw new IllegalArgumentException("The source of the connection must be specified.");
       }

       if (requestConnection.getSource().getType() == null) {
           throw new IllegalArgumentException("The type of the source of the connection must be specified.");
       }

       final ConnectableType sourceConnectableType;
       try {
           sourceConnectableType = ConnectableType.valueOf(requestConnection.getSource().getType());
       } catch (final IllegalArgumentException e) {
           throw new IllegalArgumentException(String.format("Unrecognized source type %s. Expected values are [%s]",
                   requestConnection.getSource().getType(), StringUtils.join(ConnectableType.values(), ", ")));
       }

       if (requestConnection.getDestination() == null || requestConnection.getDestination().getId() == null) {
           throw new IllegalArgumentException("The destination of the connection must be specified.");
       }

       if (requestConnection.getDestination().getType() == null) {
           throw new IllegalArgumentException("The type of the destination of the connection must be specified.");
       }

       final ConnectableType destinationConnectableType;
       try {
           destinationConnectableType = ConnectableType.valueOf(requestConnection.getDestination().getType());
       } catch (final IllegalArgumentException e) {
           throw new IllegalArgumentException(String.format("Unrecognized destination type %s. Expected values are [%s]",
                   requestConnection.getDestination().getType(), StringUtils.join(ConnectableType.values(), ", ")));
       }

       if (isReplicateRequest()) {
           return replicate(HttpMethod.POST, requestConnectionEntity);
       } else if (isDisconnectedFromCluster()) {
           verifyDisconnectedNodeModification(requestConnectionEntity.isDisconnectedNodeAcknowledged());
       }

       return withWriteLock(
               serviceFacade,
               requestConnectionEntity,
               lookup -> {
                   // ensure write access to the group
                   final Authorizable processGroup = lookup.getProcessGroup(groupId).getAuthorizable();
                   processGroup.authorize(authorizer, RequestAction.WRITE, NiFiUserUtils.getNiFiUser());

                   // explicitly handle RPGs differently as the connectable id can be ambiguous if self referencing
                   final Authorizable source;
                   if (ConnectableType.REMOTE_OUTPUT_PORT.equals(sourceConnectableType)) {
                       source = lookup.getRemoteProcessGroup(requestConnection.getSource().getGroupId());
                   } else {
                       source = lookup.getLocalConnectable(requestConnection.getSource().getId());
                   }

                   // ensure write access to the source
                   if (source == null) {
                       throw new ResourceNotFoundException("Cannot find source component with ID [" + requestConnection.getSource().getId() + "]");
                   }
                   source.authorize(authorizer, RequestAction.WRITE, NiFiUserUtils.getNiFiUser());

                   // explicitly handle RPGs differently as the connectable id can be ambiguous if self referencing
                   final Authorizable destination;
                   if (ConnectableType.REMOTE_INPUT_PORT.equals(destinationConnectableType)) {
                       destination = lookup.getRemoteProcessGroup(requestConnection.getDestination().getGroupId());
                   } else {
                       destination = lookup.getLocalConnectable(requestConnection.getDestination().getId());
                   }

                   // ensure write access to the destination
                   if (destination == null) {
                       throw new ResourceNotFoundException("Cannot find destination component with ID [" + requestConnection.getDestination().getId() + "]");
                   }

                   destination.authorize(authorizer, RequestAction.WRITE, NiFiUserUtils.getNiFiUser());
               },
               () -> serviceFacade.verifyCreateConnection(groupId, requestConnection),
               connectionEntity -> {
                   final ConnectionDTO connection = connectionEntity.getComponent();

                   // set the connection id as appropriate
                   connection.setId(generateUuid());

                   // create the new relationship target
                   final Revision revision = getRevision(connectionEntity, connection.getId());
                   final ConnectionEntity entity = serviceFacade.createConnection(revision, groupId, connection);
                   connectionResource.populateRemainingConnectionEntityContent(entity);

                   // extract the href and build the response
                   String uri = entity.getUri();
                   return generateCreatedResponse(URI.create(uri), entity).build();
               }
       );
   }

   /**
    * Gets all the connections.
    *
    * @return A connectionsEntity.
    */
   @GET
   @Consumes(MediaType.WILDCARD)
   @Produces(MediaType.APPLICATION_JSON)
   @Path("{id}/connections")
   @ApiOperation(
           value = "Gets all connections",
           response = ConnectionsEntity.class,
           authorizations = {
                   @Authorization(value = "Read - /process-groups/{uuid}")
           }
   )
   @ApiResponses(
           value = {
                   @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
                   @ApiResponse(code = 401, message = "Client could not be authenticated."),
                   @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
                   @ApiResponse(code = 404, message = "The specified resource could not be found."),
                   @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
           }
   )
   public Response getConnections(
           @ApiParam(
                   value = "The process group id.",
                   required = true
           )
           @PathParam("id") String groupId) {

       if (isReplicateRequest()) {
           return replicate(HttpMethod.GET);
       }

       // authorize access
       serviceFacade.authorizeAccess(lookup -> {
           final Authorizable processGroup = lookup.getProcessGroup(groupId).getAuthorizable();
           processGroup.authorize(authorizer, RequestAction.READ, NiFiUserUtils.getNiFiUser());
       });

       // all of the relationships for the specified source processor
       Set<ConnectionEntity> connections = serviceFacade.getConnections(groupId);

       // create the client response entity
       ConnectionsEntity entity = new ConnectionsEntity();
       entity.setConnections(connectionResource.populateRemainingConnectionEntitiesContent(connections));

       // generate the response
       return generateOkResponse(entity).build();
   }

   // ----------------
   // snippet instance
   // ----------------

   /**
    * Copies the specified snippet within this ProcessGroup. The snippet instance that is instantiated cannot be referenced at a later time, therefore there is no
    * corresponding URI. Instead the request URI is returned.
    * <p>
    * Alternatively, we could have performed a PUT request. However, PUT requests are supposed to be idempotent and this endpoint is certainly not.
    *
    * @param httpServletRequest request
    * @param groupId            The group id
    * @param requestCopySnippetEntity  The copy snippet request
    * @return A flowSnippetEntity.
    */
   @POST
   @Consumes(MediaType.APPLICATION_JSON)
   @Produces(MediaType.APPLICATION_JSON)
   @Path("{id}/snippet-instance")
   @ApiOperation(
           value = "Copies a snippet and discards it.",
           response = FlowEntity.class,
           authorizations = {
                   @Authorization(value = "Write - /process-groups/{uuid}"),
                   @Authorization(value = "Read - /{component-type}/{uuid} - For each component in the snippet and their descendant components"),
                   @Authorization(value = "Write - if the snippet contains any restricted Processors - /restricted-components")
           }
   )
   @ApiResponses(
           value = {
                   @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
                   @ApiResponse(code = 401, message = "Client could not be authenticated."),
                   @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
                   @ApiResponse(code = 404, message = "The specified resource could not be found."),
                   @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
           }
   )
   public Response copySnippet(
           @Context HttpServletRequest httpServletRequest,
           @ApiParam(
                   value = "The process group id.",
                   required = true
           )
           @PathParam("id") String groupId,
           @ApiParam(
                   value = "The copy snippet request.",
                   required = true
           ) CopySnippetRequestEntity requestCopySnippetEntity) {

       // ensure the position has been specified
       if (requestCopySnippetEntity == null || requestCopySnippetEntity.getOriginX() == null || requestCopySnippetEntity.getOriginY() == null) {
           throw new IllegalArgumentException("The  origin position (x, y) must be specified");
       }

       if (requestCopySnippetEntity.getSnippetId() == null) {
           throw new IllegalArgumentException("The snippet id must be specified.");
       }

       if (isReplicateRequest()) {
           return replicate(HttpMethod.POST, requestCopySnippetEntity);
       } else if (isDisconnectedFromCluster()) {
           verifyDisconnectedNodeModification(requestCopySnippetEntity.isDisconnectedNodeAcknowledged());
       }

       return withWriteLock(
               serviceFacade,
               requestCopySnippetEntity,
               lookup -> {
                   final NiFiUser user = NiFiUserUtils.getNiFiUser();
                   final SnippetAuthorizable snippet = authorizeSnippetUsage(lookup, groupId, requestCopySnippetEntity.getSnippetId(), false, true);

                   final Consumer<ComponentAuthorizable> authorizeRestricted = authorizable -> {
                       if (authorizable.isRestricted()) {
                           authorizeRestrictions(authorizer, authorizable);
                       }
                   };

                   // consider each processor. note - this request will not create new controller services so we do not need to check
                   // for if there are not restricted controller services. it will however, need to authorize the user has access
                   // to any referenced services and this is done within authorizeSnippetUsage above.
                   // Also ensure that user has READ permissions to the Parameter Contexts in order to copy them.
                   snippet.getSelectedProcessors().forEach(authorizeRestricted);
                   for (final ProcessGroupAuthorizable groupAuthorizable : snippet.getSelectedProcessGroups()) {
                       groupAuthorizable.getEncapsulatedProcessors().forEach(authorizeRestricted);

                       final ParameterContext parameterContext = groupAuthorizable.getProcessGroup().getParameterContext();
                       if (parameterContext != null) {
                           parameterContext.authorize(authorizer, RequestAction.READ, user);
                       }

                       for (final ProcessGroupAuthorizable encapsulatedGroupAuth : groupAuthorizable.getEncapsulatedProcessGroups()) {
                           final ParameterContext encapsulatedGroupParameterContext = encapsulatedGroupAuth.getProcessGroup().getParameterContext();
                           if (encapsulatedGroupParameterContext != null) {
                               encapsulatedGroupParameterContext.authorize(authorizer, RequestAction.READ, user);
                           }

                       }
                   }
               },
               null,
               copySnippetRequestEntity -> {
                   // copy the specified snippet
                   final FlowEntity flowEntity = serviceFacade.copySnippet(
                           groupId, copySnippetRequestEntity.getSnippetId(), copySnippetRequestEntity.getOriginX(), copySnippetRequestEntity.getOriginY(), getIdGenerationSeed().orElse(null));

                   // get the snippet
                   final FlowDTO flow = flowEntity.getFlow();

                   // prune response as necessary
                   for (ProcessGroupEntity childGroupEntity : flow.getProcessGroups()) {
                       childGroupEntity.getComponent().setContents(null);
                   }

                   // create the response entity
                   populateRemainingSnippetContent(flow);

                   // generate the response
                   return generateCreatedResponse(getAbsolutePath(), flowEntity).build();
               }
       );
   }

   private SnippetAuthorizable authorizeSnippetUsage(final AuthorizableLookup lookup, final String groupId, final String snippetId,
                                                     final boolean authorizeTransitiveServices, final boolean authorizeParameterReferences) {

       final NiFiUser user = NiFiUserUtils.getNiFiUser();

       // ensure write access to the target process group
       lookup.getProcessGroup(groupId).getAuthorizable().authorize(authorizer, RequestAction.WRITE, user);

       // ensure read permission to every component in the snippet including referenced services
       final SnippetAuthorizable snippet = lookup.getSnippet(snippetId);
       authorizeSnippet(snippet, authorizer, lookup, RequestAction.READ, true, authorizeTransitiveServices, authorizeParameterReferences);
       return snippet;
   }

   // -------------------
   // controller services
   // -------------------

   /**
    * Creates a new Controller Service.
    *
    * @param httpServletRequest      request
    * @param requestControllerServiceEntity A controllerServiceEntity.
    * @return A controllerServiceEntity.
    */
   @POST
   @Consumes(MediaType.APPLICATION_JSON)
   @Produces(MediaType.APPLICATION_JSON)
   @Path("{id}/controller-services")
   @ApiOperation(
           value = "Creates a new controller service",
           response = ControllerServiceEntity.class,
           authorizations = {
                   @Authorization(value = "Write - /process-groups/{uuid}"),
                   @Authorization(value = "Read - any referenced Controller Services - /controller-services/{uuid}"),
                   @Authorization(value = "Write - if the Controller Service is restricted - /restricted-components")
           }
   )
   @ApiResponses(
           value = {
                   @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
                   @ApiResponse(code = 401, message = "Client could not be authenticated."),
                   @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
                   @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
           }
   )
   public Response createControllerService(
           @Context final HttpServletRequest httpServletRequest,
           @ApiParam(
                   value = "The process group id.",
                   required = true
           )
           @PathParam("id") final String groupId,
           @ApiParam(
                   value = "The controller service configuration details.",
                   required = true
           ) final ControllerServiceEntity requestControllerServiceEntity) {

       if (requestControllerServiceEntity == null || requestControllerServiceEntity.getComponent() == null) {
           throw new IllegalArgumentException("Controller service details must be specified.");
       }

       if (requestControllerServiceEntity.getRevision() == null
               || (requestControllerServiceEntity.getRevision().getVersion() == null || requestControllerServiceEntity.getRevision().getVersion() != 0)) {
           throw new IllegalArgumentException("A revision of 0 must be specified when creating a new Controller service.");
       }

       final ControllerServiceDTO requestControllerService = requestControllerServiceEntity.getComponent();
       if (requestControllerService.getId() != null) {
           throw new IllegalArgumentException("Controller service ID cannot be specified.");
       }

       if (StringUtils.isBlank(requestControllerService.getType())) {
           throw new IllegalArgumentException("The type of controller service to create must be specified.");
       }

       if (requestControllerService.getParentGroupId() != null && !groupId.equals(requestControllerService.getParentGroupId())) {
           throw new IllegalArgumentException(String.format("If specified, the parent process group id %s must be the same as specified in the URI %s",
                   requestControllerService.getParentGroupId(), groupId));
       }
       requestControllerService.setParentGroupId(groupId);

       if (isReplicateRequest()) {
           return replicate(HttpMethod.POST, requestControllerServiceEntity);
       } else if (isDisconnectedFromCluster()) {
           verifyDisconnectedNodeModification(requestControllerServiceEntity.isDisconnectedNodeAcknowledged());
       }

       return withWriteLock(
               serviceFacade,
               requestControllerServiceEntity,
               lookup -> {
                   final NiFiUser user = NiFiUserUtils.getNiFiUser();

                   final ProcessGroupAuthorizable groupAuthorizable = lookup.getProcessGroup(groupId);
                   final Authorizable processGroup = groupAuthorizable.getAuthorizable();
                   processGroup.authorize(authorizer, RequestAction.WRITE, user);

                   final Authorizable parameterContext = groupAuthorizable.getProcessGroup().getParameterContext();
                   if (parameterContext != null) {
                       AuthorizeParameterReference.authorizeParameterReferences(requestControllerService.getProperties(), authorizer, parameterContext, user);
                   }

                   ComponentAuthorizable authorizable = null;
                   try {
                       authorizable = lookup.getConfigurableComponent(requestControllerService.getType(), requestControllerService.getBundle());

                       if (authorizable.isRestricted()) {
                           authorizeRestrictions(authorizer, authorizable);
                       }

                       if (requestControllerService.getProperties() != null) {
                           AuthorizeControllerServiceReference.authorizeControllerServiceReferences(requestControllerService.getProperties(), authorizable, authorizer, lookup);
                       }
                   } finally {
                       if (authorizable != null) {
                           authorizable.cleanUpResources();
                       }
                   }
               },
               () -> serviceFacade.verifyCreateControllerService(requestControllerService),
               controllerServiceEntity -> {
                   final ControllerServiceDTO controllerService = controllerServiceEntity.getComponent();

                   // set the processor id as appropriate
                   controllerService.setId(generateUuid());

                   // create the controller service and generate the json
                   final Revision revision = getRevision(controllerServiceEntity, controllerService.getId());
                   final ControllerServiceEntity entity = serviceFacade.createControllerService(revision, groupId, controllerService);
                   controllerServiceResource.populateRemainingControllerServiceEntityContent(entity);

                   // build the response
                   return generateCreatedResponse(URI.create(entity.getUri()), entity).build();
               }
       );
   }

   /**
    * Initiates the request to replace the Process Group with the given ID with the Process Group in the given import entity
    *
    * @param groupId          The id of the process group to replace
    * @param importEntity     A request entity containing revision info and the process group to replace with
    * @return A ProcessGroupReplaceRequestEntity.
    */
   @POST
   @Consumes(MediaType.APPLICATION_JSON)
   @Produces(MediaType.APPLICATION_JSON)
   @Path("{id}/replace-requests")
   @ApiOperation(
           value = "Initiate the Replace Request of a Process Group with the given ID",
           response = ProcessGroupReplaceRequestEntity.class,
           notes = "This will initiate the action of replacing a process group with the given process group. This can be a lengthy "
                   + "process, as it will stop any Processors and disable any Controller Services necessary to perform the action and then restart them. As a result, "
                   + "the endpoint will immediately return a ProcessGroupReplaceRequestEntity, and the process of replacing the flow will occur "
                   + "asynchronously in the background. The client may then periodically poll the status of the request by issuing a GET request to "
                   + "/process-groups/replace-requests/{requestId}. Once the request is completed, the client is expected to issue a DELETE request to "
                   + "/process-groups/replace-requests/{requestId}. " + NON_GUARANTEED_ENDPOINT,
           authorizations = {
                   @Authorization(value = "Read - /process-groups/{uuid}"),
                   @Authorization(value = "Write - /process-groups/{uuid}"),
                   @Authorization(value = "Read - /{component-type}/{uuid} - For all encapsulated components"),
                   @Authorization(value = "Write - /{component-type}/{uuid} - For all encapsulated components"),
                   @Authorization(value = "Write - if the snapshot contains any restricted components - /restricted-components"),
                   @Authorization(value = "Read - /parameter-contexts/{uuid} - For any Parameter Context that is referenced by a Property that is changed, added, or removed")
           }
   )
   @ApiResponses(value = {
           @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
           @ApiResponse(code = 401, message = "Client could not be authenticated."),
           @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
           @ApiResponse(code = 404, message = "The specified resource could not be found."),
           @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
   })
   public Response initiateReplaceProcessGroup(@ApiParam(value = "The process group id.", required = true) @PathParam("id") final String groupId,
                                               @ApiParam(value = "The process group replace request entity", required = true) final ProcessGroupImportEntity importEntity) {
       if (importEntity == null) {
           throw new IllegalArgumentException("Process Group Import Entity is required");
       }

       // replacing a flow under version control is not permitted via import. Versioned flows have additional requirements to allow
       // them only to be replaced by a different version of the same flow.
       if (serviceFacade.isAnyProcessGroupUnderVersionControl(groupId)) {
           throw new IllegalStateException("Cannot replace a Process Group via import while it or its descendants are under Version Control.");
       }

       final RegisteredFlowSnapshot versionedFlowSnapshot = importEntity.getVersionedFlowSnapshot();
       if (versionedFlowSnapshot == null) {
           throw new IllegalArgumentException("Versioned Flow Snapshot must be supplied");
       }

       // remove any registry-specific versioning content which could be present if the flow was exported from registry
       versionedFlowSnapshot.setFlow(null);
       versionedFlowSnapshot.setBucket(null);
       versionedFlowSnapshot.setSnapshotMetadata(null);
       sanitizeRegistryInfo(versionedFlowSnapshot.getFlowContents());

       final FlowSnapshotContainer flowSnapshotContainer = new FlowSnapshotContainer(versionedFlowSnapshot);
       return initiateFlowUpdate(groupId, importEntity, true, "replace-requests",
               "/nifi-api/process-groups/" + groupId + "/flow-contents", () -> flowSnapshotContainer);
   }

   /**
    * Recursively clear the registry info in the given versioned process group and all nested versioned process groups
    *
    * @param versionedProcessGroup the process group to sanitize
    */
   private void sanitizeRegistryInfo(final VersionedProcessGroup versionedProcessGroup) {
       versionedProcessGroup.setVersionedFlowCoordinates(null);

       for (final VersionedProcessGroup innerVersionedProcessGroup : versionedProcessGroup.getProcessGroups()) {
           sanitizeRegistryInfo(innerVersionedProcessGroup);
       }
   }

   /**
    * Uploads the specified versioned flow definition and adds it to a new process group.
    *
    * @param in The flow definition stream
    * @return A processGroupEntity
    * @throws IOException if there is an error during deserialization of the InputStream
    */
   @POST
   @Consumes(MediaType.MULTIPART_FORM_DATA)
   @Produces(MediaType.APPLICATION_JSON)
   @Path("{id}/process-groups/upload")
   @ApiOperation(
           value = "Uploads a versioned flow definition and creates a process group",
           response = ProcessGroupEntity.class,
           authorizations = {
                   @Authorization(value = "Write - /process-groups/{uuid}")
           }
   )
   @ApiResponses(
           value = {
                   @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
                   @ApiResponse(code = 401, message = "Client could not be authenticated."),
                   @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
                   @ApiResponse(code = 404, message = "The specified resource could not be found."),
                   @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
           }
   )
   public Response uploadProcessGroup(
           @ApiParam(
                   value = "The process group id.",
                   required = true
           )
           @PathParam("id") final String groupId,
           @ApiParam(
                   value = "The process group name.",
                   required = true
           )
           @FormDataParam("groupName") final String groupName,
           @ApiParam(
                   value = "The process group X position.",
                   required = true
           )
           @FormDataParam("positionX") final Double positionX,
           @ApiParam(
                   value = "The process group Y position.",
                   required = true
           )
           @FormDataParam("positionY") final Double positionY,
           @ApiParam(
                   value = "The client id.",
                   required = true
           )
           @FormDataParam("clientId") final String clientId,
           @ApiParam(
                   value = "Acknowledges that this node is disconnected to allow for mutable requests to proceed.",
                   required = false
           )
           @FormDataParam(DISCONNECTED_NODE_ACKNOWLEDGED) @DefaultValue("false") final Boolean disconnectedNodeAcknowledged,
           @FormDataParam("file") final InputStream in) throws InterruptedException {

       // ensure the group name is specified
       if (StringUtils.isBlank(groupName)) {
           throw new IllegalArgumentException("The process group name is required.");
       }

       if (StringUtils.isBlank(groupId)) {
           throw new IllegalArgumentException("The parent process group id is required");
       }

       if (positionX == null) {
           throw new IllegalArgumentException("The x coordinate of the proposed position must be specified.");
       }

       if (positionY == null) {
           throw new IllegalArgumentException("The y coordinate of the proposed position must be specified.");
       }

       if (StringUtils.isBlank(clientId)) {
           throw new IllegalArgumentException("The client id must be specified");
       }

       // deserialize InputStream to a VersionedFlowSnapshot
       RegisteredFlowSnapshot deserializedSnapshot;

       try {
           deserializedSnapshot = MAPPER.readValue(in, RegisteredFlowSnapshot.class);
       } catch (IOException e) {
           logger.warn("Deserialization of uploaded JSON failed", e);
           throw new IllegalArgumentException("Deserialization of uploaded JSON failed", e);
       }

       // clear Registry info
       sanitizeRegistryInfo(deserializedSnapshot.getFlowContents());

       // resolve Bundle info
       serviceFacade.discoverCompatibleBundles(deserializedSnapshot.getFlowContents());

       // if there are any Controller Services referenced that are inherited from the parent group,
       // resolve those to point to the appropriate Controller Service, if we are able to.
       final FlowSnapshotContainer flowSnapshotContainer = new FlowSnapshotContainer(deserializedSnapshot);
       serviceFacade.resolveInheritedControllerServices(flowSnapshotContainer, groupId, NiFiUserUtils.getNiFiUser());

       // If there are any Parameter Providers referenced by Parameter Contexts, resolve these to point to the appropriate Parameter Provider, if we are able to.
       serviceFacade.resolveParameterProviders(deserializedSnapshot, NiFiUserUtils.getNiFiUser());

       if (isDisconnectedFromCluster()) {
           verifyDisconnectedNodeModification(disconnectedNodeAcknowledged);
       }

       // create a PositionDTO
       final PositionDTO positionDTO = new PositionDTO();
       positionDTO.setX(positionX);
       positionDTO.setY(positionY);

       // create a RevisionDTO
       RevisionDTO revisionDTO = new RevisionDTO();
       revisionDTO.setClientId(clientId);
       revisionDTO.setVersion((long) 0);

       // build the response entity for a replicate request
       ProcessGroupUploadEntity pgUploadEntity = new ProcessGroupUploadEntity();
       pgUploadEntity.setGroupId(groupId);
       pgUploadEntity.setGroupName(groupName);
       pgUploadEntity.setDisconnectedNodeAcknowledged(disconnectedNodeAcknowledged);
       pgUploadEntity.setFlowSnapshot(deserializedSnapshot);
       pgUploadEntity.setPositionDTO(positionDTO);
       pgUploadEntity.setRevisionDTO(revisionDTO);

       // replicate the request
       if (isReplicateRequest()) {
           // convert request accordingly
           final UriBuilder uriBuilder = uriInfo.getBaseUriBuilder();
           uriBuilder.segment("process-groups", groupId, "process-groups", "import");
           final URI importUri = uriBuilder.build();

           final Map<String, String> headersToOverride = new HashMap<>();
           headersToOverride.put("content-type", MediaType.APPLICATION_JSON);

           // Determine whether we should replicate only to the cluster coordinator, or if we should replicate directly
           // to the cluster nodes themselves.
           if (getReplicationTarget() == ReplicationTarget.CLUSTER_NODES) {
               return getRequestReplicator().replicate(HttpMethod.POST, importUri, pgUploadEntity, getHeaders(headersToOverride)).awaitMergedResponse().getResponse();
           } else {
               return getRequestReplicator().forwardToCoordinator(
                       getClusterCoordinatorNode(), HttpMethod.POST, importUri, pgUploadEntity, getHeaders(headersToOverride)).awaitMergedResponse().getResponse();
           }
       }

       // otherwise import the process group locally
       return importProcessGroup(groupId, pgUploadEntity);
   }

   /**
    * Imports the specified process group.
    *
    * @param processGroupUploadEntity     A ProcessGroupUploadEntity.
    * @return A processGroupEntity.
    */
   @POST
   @Consumes(MediaType.APPLICATION_JSON)
   @Produces(MediaType.APPLICATION_JSON)
   @Path("{id}/process-groups/import")
   @ApiOperation(
           value = "Imports a specified process group",
           response = ProcessGroupEntity.class,
           authorizations = {
                   @Authorization(value = "Write - /process-groups/{uuid}")
           }
   )
   @ApiResponses(
           value = {
                   @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
                   @ApiResponse(code = 401, message = "Client could not be authenticated."),
                   @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
                   @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
           }
   )
   public Response importProcessGroup(
           @ApiParam(
                   value = "The process group id.",
                   required = true
           )
           @PathParam("id") final String groupId,
           final ProcessGroupUploadEntity processGroupUploadEntity) {

       // verify the process group was specified
       if (processGroupUploadEntity == null || processGroupUploadEntity.getFlowSnapshot() == null) {
           throw new IllegalArgumentException("Process group details must be specified.");
       }

       final RegisteredFlowSnapshot versionedFlowSnapshot = processGroupUploadEntity.getFlowSnapshot();

       // clear Registry info
       sanitizeRegistryInfo(versionedFlowSnapshot.getFlowContents());

       // resolve Bundle info
       serviceFacade.discoverCompatibleBundles(versionedFlowSnapshot.getFlowContents());

       // if there are any Controller Services referenced that are inherited from the parent group,
       // resolve those to point to the appropriate Controller Service, if we are able to.
       final FlowSnapshotContainer flowSnapshotContainer = new FlowSnapshotContainer(versionedFlowSnapshot);
       serviceFacade.resolveInheritedControllerServices(flowSnapshotContainer, groupId, NiFiUserUtils.getNiFiUser());

       // If there are any Parameter Providers referenced by Parameter Contexts, resolve these to point to the appropriate Parameter Provider, if we are able to.
       serviceFacade.resolveParameterProviders(versionedFlowSnapshot, NiFiUserUtils.getNiFiUser());

       if (isReplicateRequest()) {
           return replicate(HttpMethod.POST, processGroupUploadEntity);
       } else if (isDisconnectedFromCluster()) {
           verifyDisconnectedNodeModification(processGroupUploadEntity.getDisconnectedNodeAcknowledged());
       }

       // create a new ProcessGroupEntity
       final ProcessGroupEntity newProcessGroupEntity = createProcessGroupEntity(groupId, processGroupUploadEntity.getGroupName(), processGroupUploadEntity.getPositionDTO(), versionedFlowSnapshot);

       return withWriteLock(
               serviceFacade,
               newProcessGroupEntity,
               lookup -> authorizeAccess(groupId, newProcessGroupEntity, lookup),
               () -> {
                   final RegisteredFlowSnapshot newVersionedFlowSnapshot = newProcessGroupEntity.getVersionedFlowSnapshot();
                   if (newVersionedFlowSnapshot != null) {
                       serviceFacade.verifyComponentTypes(newVersionedFlowSnapshot.getFlowContents());
                   }
               },
               processGroupEntity -> {
                   final ProcessGroupDTO processGroup = processGroupEntity.getComponent();

                   // set the processor id as appropriate
                   processGroup.setId(generateUuid());

                   // get the versioned flow
                   final RegisteredFlowSnapshot flowSnapshot = processGroupEntity.getVersionedFlowSnapshot();

                   // create the process group contents
                   final Revision revision = new Revision((long) 0, processGroupUploadEntity.getRevisionDTO().getClientId(), processGroup.getId());

                   ProcessGroupEntity entity = serviceFacade.createProcessGroup(revision, groupId, processGroup);

                   if (flowSnapshot != null) {
                       final RevisionDTO revisionDto = entity.getRevision();
                       final String newGroupId = entity.getComponent().getId();
                       final Revision newGroupRevision = new Revision(revisionDto.getVersion(), revisionDto.getClientId(), newGroupId);

                       // We don't want the Process Group's position to be updated because we want to keep the position where the user
                       // placed the Process Group. We do not want to use the name of the Process Group that is in the Flow Contents.
                       // To accomplish this, we call updateProcessGroupContents() passing 'false' for the updateSettings flag, set
                       // the Process Group name, and null out the position.
                       flowSnapshot.getFlowContents().setPosition(null);
                       flowSnapshot.getFlowContents().setName(processGroupUploadEntity.getGroupName());

                       entity = serviceFacade.updateProcessGroupContents(newGroupRevision, newGroupId, null, flowSnapshot,
                               getIdGenerationSeed().orElse(null), false, false, true);
                   }

                   populateRemainingProcessGroupEntityContent(entity);

                   // generate a 201 created response
                   String uri = entity.getUri();
                   return generateCreatedResponse(URI.create(uri), entity).build();
               }
       );

   }


   /**
    * Replace the Process Group contents with the given ID with the specified Process Group contents.
    *
    * This is the endpoint used in a cluster update replication scenario.
    *
    * @param groupId          The id of the process group to replace
    * @param importEntity     A request entity containing revision info and the process group to replace with
    * @return A ProcessGroupImportEntity.
    */
   @PUT
   @Consumes(MediaType.APPLICATION_JSON)
   @Produces(MediaType.APPLICATION_JSON)
   @Path("{id}/flow-contents")
   @ApiOperation(
           value = "Replace Process Group contents with the given ID with the specified Process Group contents",
           response = ProcessGroupImportEntity.class,
           notes = "This endpoint is used for replication within a cluster, when replacing a flow with a new flow. It expects that the flow being"
                   + "replaced is not under version control and that the given snapshot will not modify any Processor that is currently running "
                   + "or any Controller Service that is enabled. "
                   + NON_GUARANTEED_ENDPOINT,
           authorizations = {
                   @Authorization(value = "Read - /process-groups/{uuid}"),
                   @Authorization(value = "Write - /process-groups/{uuid}")
           })
   @ApiResponses(value = {
           @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
           @ApiResponse(code = 401, message = "Client could not be authenticated."),
           @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
           @ApiResponse(code = 404, message = "The specified resource could not be found."),
           @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
   })
   public Response replaceProcessGroup(@ApiParam(value = "The process group id.", required = true) @PathParam("id") final String groupId,
                                       @ApiParam(value = "The process group replace request entity.", required = true) final ProcessGroupImportEntity importEntity) {
       // Verify the request
       if (importEntity == null) {
           throw new IllegalArgumentException("Process Group Import Entity is required");
       }

       final RevisionDTO revisionDto = importEntity.getProcessGroupRevision();
       if (revisionDto == null) {
           throw new IllegalArgumentException("Process Group Revision must be specified.");
       }

       final RegisteredFlowSnapshot requestFlowSnapshot = importEntity.getVersionedFlowSnapshot();
       if (requestFlowSnapshot == null) {
           throw new IllegalArgumentException("Versioned Flow Snapshot must be supplied.");
       }

       // Perform the request
       if (isReplicateRequest()) {
           return replicate(HttpMethod.PUT, importEntity);
       } else if (isDisconnectedFromCluster()) {
           verifyDisconnectedNodeModification(importEntity.isDisconnectedNodeAcknowledged());
       }

       final Revision requestRevision = getRevision(importEntity.getProcessGroupRevision(), groupId);
       return withWriteLock(
               serviceFacade,
               importEntity,
               requestRevision,
               lookup -> {
                   final ProcessGroupAuthorizable groupAuthorizable = lookup.getProcessGroup(groupId);
                   final Authorizable processGroup = groupAuthorizable.getAuthorizable();
                   processGroup.authorize(authorizer, RequestAction.READ, NiFiUserUtils.getNiFiUser());
                   processGroup.authorize(authorizer, RequestAction.WRITE, NiFiUserUtils.getNiFiUser());
               },
               () -> {
                   // We do not enforce that the Process Group is 'not dirty' because at this point,
                   // the client has explicitly indicated the dataflow that the Process Group should
                   // provide and provided the Revision to ensure that they have the most up-to-date
                   // view of the Process Group.
                   serviceFacade.verifyCanUpdate(groupId, requestFlowSnapshot, true, false);
               },
               (revision, entity) -> {
                   final ProcessGroupEntity updatedGroup =
                           performUpdateFlow(groupId, revision, importEntity, entity.getVersionedFlowSnapshot(),
                                   getIdGenerationSeed().orElse(null), false, true);

                   // response to replication request is an entity with revision info but no versioned flow snapshot
                   final ProcessGroupImportEntity responseEntity = new ProcessGroupImportEntity();
                   responseEntity.setProcessGroupRevision(updatedGroup.getRevision());

                   return generateOkResponse(responseEntity).build();
               });
   }

   /**
    * Retrieve a request to replace a Process Group by request ID.
    *
    * @param replaceRequestId  The ID of the replace request
    * @return A ProcessGroupReplaceRequestEntity.
    */
   @GET
   @Consumes(MediaType.WILDCARD)
   @Produces(MediaType.APPLICATION_JSON)
   @Path("replace-requests/{id}")
   @ApiOperation(
           value = "Returns the Replace Request with the given ID",
           response = ProcessGroupReplaceRequestEntity.class,
           notes = "Returns the Replace Request with the given ID. Once a Replace Request has been created by performing a POST to /process-groups/{id}/replace-requests, "
                   + "that request can subsequently be retrieved via this endpoint, and the request that is fetched will contain the updated state, such as percent complete, the "
                   + "current state of the request, and any failures. "
                   + NON_GUARANTEED_ENDPOINT,
           authorizations = {
                   @Authorization(value = "Only the user that submitted the request can get it")
           })
   @ApiResponses(value = {
           @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
           @ApiResponse(code = 401, message = "Client could not be authenticated."),
           @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
           @ApiResponse(code = 404, message = "The specified resource could not be found."),
           @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
   })
   public Response getReplaceProcessGroupRequest(
           @ApiParam("The ID of the Replace Request") @PathParam("id") final String replaceRequestId) {
       return retrieveFlowUpdateRequest("replace-requests", replaceRequestId);
   }

   @DELETE
   @Consumes(MediaType.WILDCARD)
   @Produces(MediaType.APPLICATION_JSON)
   @Path("replace-requests/{id}")
   @ApiOperation(
           value = "Deletes the Replace Request with the given ID",
           response = ProcessGroupReplaceRequestEntity.class,
           notes = "Deletes the Replace Request with the given ID. After a request is created via a POST to /process-groups/{id}/replace-requests, it is expected "
                   + "that the client will properly clean up the request by DELETE'ing it, once the Replace process has completed. If the request is deleted before the request "
                   + "completes, then the Replace request will finish the step that it is currently performing and then will cancel any subsequent steps. "
                   + NON_GUARANTEED_ENDPOINT,
           authorizations = {
                   @Authorization(value = "Only the user that submitted the request can remove it")
           })
   @ApiResponses(value = {
           @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
           @ApiResponse(code = 401, message = "Client could not be authenticated."),
           @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
           @ApiResponse(code = 404, message = "The specified resource could not be found."),
           @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
   })
   public Response deleteReplaceProcessGroupRequest(
           @ApiParam(value = "Acknowledges that this node is disconnected to allow for mutable requests to proceed.", required = false)
               @QueryParam(DISCONNECTED_NODE_ACKNOWLEDGED) @DefaultValue("false") final Boolean disconnectedNodeAcknowledged,
           @ApiParam("The ID of the Update Request") @PathParam("id") final String replaceRequestId) {
       return deleteFlowUpdateRequest("replace-requests", replaceRequestId, disconnectedNodeAcknowledged.booleanValue());
   }

   // -------------
   // flow-analysis
   // -------------

   /**
    * Submits a request to run a flow analysis.
    *
    * @param processGroupId The id of the process group representing (a part of) the flow to be analyzed
    * @return An AnalyzeFlowRequestEntity
    */
   @POST
   @Consumes(MediaType.WILDCARD)
   @Produces(MediaType.APPLICATION_JSON)
   @Path("{id}/flow-analysis-requests")
   @ApiOperation(
       value = "Executes a flow analysis for components within a given process group",
       response = AnalyzeFlowRequestEntity.class,
       authorizations = {
           @Authorization(value = "Read - /process-groups/{uuid} - For this and all encapsulated process groups")
       })
   @ApiResponses(value = {
       @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
       @ApiResponse(code = 401, message = "Client could not be authenticated."),
       @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
       @ApiResponse(code = 404, message = "The specified resource could not be found."),
       @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
   })
   public Response submitAnalyzeFlowRequest(
       @ApiParam(
           value = "The id of the process group representing (a part of) the flow to be analyzed.",
           required = true
       )
       @PathParam("id")
       final String processGroupId
   ) {
       if (isReplicateRequest()) {
           return replicate(HttpMethod.POST);
       }

       NiFiUser user = NiFiUserUtils.getNiFiUser();

       ProcessGroupEntity requestProcessGroupEntity = new ProcessGroupEntity();
       requestProcessGroupEntity.setId(processGroupId);

       return withWriteLock(
           serviceFacade,
           requestProcessGroupEntity,
           lookup -> {
               final ProcessGroupAuthorizable processGroup = lookup.getProcessGroup(processGroupId);
               processGroup.getAuthorizable().authorize(authorizer, RequestAction.READ, user);
           },
           null,
           (processGroupEntity) -> {
               String analyzedGroupId = processGroupEntity.getId();

               final String requestId = generateUuid();
               final AsynchronousWebRequest<String, Void> analyzeFlowAsyncWebRequest = new StandardAsynchronousWebRequest<>(
                       requestId,
                       analyzedGroupId,
                       analyzedGroupId,
                       user,
                       Collections.singletonList(new StandardUpdateStep("Analyze Process Group"))
               );

               // Submit the request to be performed in the background
               final Consumer<AsynchronousWebRequest<String, Void>> analyzeFlowTask = asyncRequest -> {
                   try {
                       serviceFacade.analyzeProcessGroup(analyzedGroupId);
                       asyncRequest.markStepComplete();
                   } catch (final Exception e) {
                       logger.error("Failed to run flow analysis on process group {}", processGroupId, e);
                       asyncRequest.fail("Failed to run flow analysis on process group " + processGroupId + " due to " + e);
                   }
               };
               flowAnalysisAsyncRequestManager.submitRequest(
                       FLOW_ANALYSIS_REQUEST_TYPE,
                       requestId,
                       analyzeFlowAsyncWebRequest,
                       analyzeFlowTask
               );

               return generateOkResponse(createAnalyzeFlowRequestEntity(analyzeFlowAsyncWebRequest, requestId)).build();
           }
       );
   }

   /**
    * Checks the status of an outstanding request for a flow analysis.
    *
    * @param requestId The id of flow analysis request
    * @return An analyzeFlowRequestEntity
    */
   @GET
   @Consumes(MediaType.WILDCARD)
   @Produces(MediaType.APPLICATION_JSON)
   @Path("{id}/flow-analysis-requests/{requestId}")
   @ApiOperation(
       value = "Gets the current status of a flow analysis request.",
       response = AnalyzeFlowRequestEntity.class,
       authorizations = {
           @Authorization(value = "Read - /process-groups/{uuid} - For this and all encapsulated process groups")
       }
   )
   @ApiResponses(
       value = {
           @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
           @ApiResponse(code = 401, message = "Client could not be authenticated."),
           @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
           @ApiResponse(code = 404, message = "The specified resource could not be found."),
           @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
       }
   )
   public Response getAnalyzeFlowRequest(
       @ApiParam(
           value = "The id of the process group representing (a part of) the flow being analyzed.",
           required = true
       )
       @PathParam("id")
       final String processGroupId,
       @ApiParam(
           value = "The id of the process group representing (a part of) the flow to be analyzed.",
           required = true
       )
       @PathParam("requestId")
       final String requestId
   ) {
       if (isReplicateRequest()) {
           return replicate(HttpMethod.GET);
       }

       final NiFiUser user = NiFiUserUtils.getNiFiUser();

       // request manager will ensure that the current is the user that submitted this request
       final AsynchronousWebRequest<String, Void> asyncRequest =
               flowAnalysisAsyncRequestManager.getRequest(FLOW_ANALYSIS_REQUEST_TYPE, requestId, user);

       return generateOkResponse(createAnalyzeFlowRequestEntity(asyncRequest, requestId)).build();
   }

   /**
    * Cancels the specified flow analysis request.
    *
    * @param httpServletRequest request
    * @param requestId     The id of the flow analysis request
    * @return An analyzeFlowRequestEntity
    */
   @DELETE
   @Consumes(MediaType.WILDCARD)
   @Produces(MediaType.APPLICATION_JSON)
   @Path("{id}/flow-analysis-requests/{requestId}")
   @ApiOperation(
       value = "Cancels a flow analysis request.",
       response = AnalyzeFlowRequestEntity.class,
       authorizations = {
           @Authorization(value = "Read - /process-groups/{uuid} - For this and all encapsulated process groups")
       }
   )
   @ApiResponses(
       value = {
           @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
           @ApiResponse(code = 401, message = "Client could not be authenticated."),
           @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
           @ApiResponse(code = 404, message = "The specified resource could not be found."),
           @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
       }
   )
   public Response removeAnalyzeFlowRequest(
       @ApiParam(
           value = "The id of the process group representing (a part of) the flow being analyzed.",
           required = true
       )
       @PathParam("id")
       final String processGroupId,
       @Context final HttpServletRequest httpServletRequest,
       @ApiParam(
           value = "The id of the flow analysis request",
           required = true
       )
       @PathParam("requestId")
       final String requestId
   ) {
       if (isReplicateRequest()) {
           return replicate(HttpMethod.DELETE);
       }

       final NiFiUser user = NiFiUserUtils.getNiFiUser();
       final boolean twoPhaseRequest = isTwoPhaseRequest(httpServletRequest);
       final boolean executionPhase = isExecutionPhase(httpServletRequest);

       // If this is a standalone node, or if this is the execution phase of the request, perform the actual request.
       if (!twoPhaseRequest || executionPhase) {
           // request manager will ensure that the current is the user that submitted this request
           final AsynchronousWebRequest<String, Void> asyncRequest =
                   flowAnalysisAsyncRequestManager.removeRequest(FLOW_ANALYSIS_REQUEST_TYPE, requestId, user);

           if (asyncRequest == null) {
               throw new ResourceNotFoundException("Could not find request of type " + FLOW_ANALYSIS_REQUEST_TYPE + " with ID " + requestId);
           }

           if (!asyncRequest.isComplete()) {
               asyncRequest.cancel();
           }

           AnalyzeFlowRequestEntity analyzeFlowRequestEntity = createAnalyzeFlowRequestEntity(asyncRequest, requestId);
           return generateOkResponse(analyzeFlowRequestEntity).build();
       }

       if (isValidationPhase(httpServletRequest)) {
           // Perform authorization by attempting to get the request
           flowAnalysisAsyncRequestManager.getRequest(FLOW_ANALYSIS_REQUEST_TYPE, requestId, user);
           return generateContinueResponse().build();
       } else if (isCancellationPhase(httpServletRequest)) {
           return generateOkResponse().build();
       } else {
           throw new IllegalStateException("This request does not appear to be part of the two phase commit.");
       }
   }

   private AnalyzeFlowRequestEntity createAnalyzeFlowRequestEntity(
           final AsynchronousWebRequest<String, Void> asyncRequest,
           final String requestId
   ) {
       String analyzedGroupId = asyncRequest.getRequest();

       AnalyzeFlowRequestDTO responseDto = new AnalyzeFlowRequestDTO();
       responseDto.setProcessGroupId(analyzedGroupId);

       responseDto.setRequestId(requestId);
       responseDto.setComplete(asyncRequest.isComplete());
       responseDto.setFailureReason(asyncRequest.getFailureReason());
       responseDto.setLastUpdated(asyncRequest.getLastUpdated());
       responseDto.setPercentCompleted(asyncRequest.getPercentComplete());
       responseDto.setState(asyncRequest.getState());
       responseDto.setUri(generateResourceUri("process-groups", "flow-analysis", analyzedGroupId));

       final List<AnalyzeFlowRequestUpdateStepDTO> updateSteps = new ArrayList<>();
       for (final UpdateStep updateStep : asyncRequest.getUpdateSteps()) {
           final AnalyzeFlowRequestUpdateStepDTO updateStepDTO = new AnalyzeFlowRequestUpdateStepDTO();
           updateStepDTO.setDescription(updateStep.getDescription());
           updateStepDTO.setComplete(updateStep.isComplete());
           updateStepDTO.setFailureReason(updateStep.getFailureReason());
           updateSteps.add(updateStepDTO);
       }
       responseDto.setUpdateSteps(updateSteps);

       AnalyzeFlowRequestEntity analyzeFlowRequestEntity = new AnalyzeFlowRequestEntity();
       analyzeFlowRequestEntity.setAnalyzeFlowRequest(responseDto);

       return analyzeFlowRequestEntity;
   }
   //--

   /**
    * Perform actual flow update of the specified flow. This is used for the initial flow update and replication updates.
    */
   @Override
   protected ProcessGroupEntity performUpdateFlow(final String groupId, final Revision revision, final ProcessGroupImportEntity requestEntity,
                                                  final RegisteredFlowSnapshot flowSnapshot, final String idGenerationSeed,
                                                  final boolean verifyNotModified, final boolean updateDescendantVersionedFlows) {
       logger.info("Replacing Process Group with ID {} with imported Process Group with ID {}", groupId, flowSnapshot.getFlowContents().getIdentifier());

       // Update Process Group to the new flow (including name)
       return serviceFacade.updateProcessGroupContents(revision, groupId, null, flowSnapshot, idGenerationSeed, verifyNotModified,
               true, updateDescendantVersionedFlows);
   }

   /**
    * Create the entity that is used for update flow replication. The initial replace request entity can be re-used for the replication request.
    */
   @Override
   protected Entity createReplicateUpdateFlowEntity(final Revision revision, final ProcessGroupImportEntity requestEntity,
                                                    final RegisteredFlowSnapshot flowSnapshot) {
       return requestEntity;
   }

   /**
    * Create the entity that captures the status and result of a replace request
    *
    * @return a new instance of a ProcessGroupReplaceRequestEntity
    */
   @Override
   protected ProcessGroupReplaceRequestEntity createUpdateRequestEntity() {
       return new ProcessGroupReplaceRequestEntity();
   }

   /**
    * Finalize a completed update request for an existing replace request. This is used when retrieving and deleting a replace request.
    *
    * A completed request will contain the updated VersionedFlowSnapshot
    *
    * @param requestEntity     the request entity to finalize
    */
   @Override
   protected void finalizeCompletedUpdateRequest(final ProcessGroupReplaceRequestEntity requestEntity) {
       final ProcessGroupReplaceRequestDTO updateRequestDto = requestEntity.getRequest();
       if (updateRequestDto.isComplete()) {
           final RegisteredFlowSnapshot versionedFlowSnapshot =
                   serviceFacade.getCurrentFlowSnapshotByGroupId(updateRequestDto.getProcessGroupId());
           requestEntity.setVersionedFlowSnapshot(versionedFlowSnapshot);
       }
   }

   /**
    * Creates a new ProcessGroupEntity with the specified VersionedFlowSnapshot.
    *
    * @param groupId               the group id string
    * @param groupName             the process group name string
    * @param positionDTO           the process group PositionDTO
    * @param deserializedSnapshot  the deserialized snapshot
    *
    * @return a new ProcessGroupEntity
    */
   private ProcessGroupEntity createProcessGroupEntity(
           String groupId, String groupName, PositionDTO positionDTO, RegisteredFlowSnapshot deserializedSnapshot) {

       final ProcessGroupEntity processGroupEntity = new ProcessGroupEntity();

       // create a ProcessGroupDTO
       final ProcessGroupDTO processGroupDTO = new ProcessGroupDTO();
       processGroupDTO.setParentGroupId(groupId);
       processGroupDTO.setName(groupName);

       processGroupEntity.setComponent(processGroupDTO);
       processGroupEntity.setVersionedFlowSnapshot(deserializedSnapshot);

       // set the ProcessGroupEntity position
       processGroupEntity.getComponent().setPosition(positionDTO);

       return processGroupEntity;
   }

   /**
    * Authorizes access to a Parameter Context and RestrictedComponents resource.
    *
    * @param groupId               the group id string
    * @param processGroupEntity    the ProcessGroupEntity
    * @param lookup                the lookup
    */
   private void authorizeAccess(String groupId, ProcessGroupEntity processGroupEntity, AuthorizableLookup lookup) {
       final NiFiUser user = NiFiUserUtils.getNiFiUser();
       final Authorizable processGroup = lookup.getProcessGroup(groupId).getAuthorizable();
       processGroup.authorize(authorizer, RequestAction.WRITE, user);

       // if request specifies a Parameter Context, need to authorize that user has READ policy for the Parameter Context.
       final ParameterContextReferenceEntity referencedParamContext = processGroupEntity.getComponent().getParameterContext();
       if (referencedParamContext != null && referencedParamContext.getId() != null) {
           lookup.getParameterContext(referencedParamContext.getId()).authorize(authorizer, RequestAction.READ, user);
       }

       // if any of the components is a Restricted Component, then we must authorize the user
       // for write access to the RestrictedComponents resource
       final RegisteredFlowSnapshot versionedFlowSnapshot = processGroupEntity.getVersionedFlowSnapshot();
       if (versionedFlowSnapshot != null) {
           final Set<ConfigurableComponent> restrictedComponents = FlowRegistryUtils.getRestrictedComponents(versionedFlowSnapshot.getFlowContents(), serviceFacade);
           restrictedComponents.forEach(restrictedComponent -> {
               final ComponentAuthorizable restrictedComponentAuthorizable = lookup.getConfigurableComponent(restrictedComponent);
               authorizeRestrictions(authorizer, restrictedComponentAuthorizable);
           });

           final Map<String, VersionedParameterContext> parameterContexts = versionedFlowSnapshot.getParameterContexts();
           if (parameterContexts != null) {
               parameterContexts.values().forEach(context -> AuthorizeParameterReference.authorizeParameterContextAddition(context, serviceFacade, authorizer, lookup, user));
           }
       }
   }

   // setters

   public void setProcessorResource(ProcessorResource processorResource) {
       this.processorResource = processorResource;
   }

   public void setInputPortResource(InputPortResource inputPortResource) {
       this.inputPortResource = inputPortResource;
   }

   public void setOutputPortResource(OutputPortResource outputPortResource) {
       this.outputPortResource = outputPortResource;
   }

   public void setFunnelResource(FunnelResource funnelResource) {
       this.funnelResource = funnelResource;
   }

   public void setLabelResource(LabelResource labelResource) {
       this.labelResource = labelResource;
   }

   public void setRemoteProcessGroupResource(RemoteProcessGroupResource remoteProcessGroupResource) {
       this.remoteProcessGroupResource = remoteProcessGroupResource;
   }

   public void setConnectionResource(ConnectionResource connectionResource) {
       this.connectionResource = connectionResource;
   }

   public void setControllerServiceResource(ControllerServiceResource controllerServiceResource) {
       this.controllerServiceResource = controllerServiceResource;
   }

   public void setParameterContextReplacer(ParameterContextReplacer parameterContextReplacer) {
       this.parameterContextReplacer = parameterContextReplacer;
   }

   private static class DropEntity extends Entity {
       final String entityId;
       final String dropRequestId;

       public DropEntity(String entityId, String dropRequestId) {
           this.entityId = entityId;
           this.dropRequestId = dropRequestId;
       }

       public String getEntityId() {
           return entityId;
       }

       public String getDropRequestId() {
           return dropRequestId;
       }
   }
}
