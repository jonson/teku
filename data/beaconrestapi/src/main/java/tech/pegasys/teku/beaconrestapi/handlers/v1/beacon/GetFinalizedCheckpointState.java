/*
 * Copyright ConsenSys Software Inc., 2022
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

package tech.pegasys.teku.beaconrestapi.handlers.v1.beacon;

import static tech.pegasys.teku.beaconrestapi.EthereumTypes.sszResponseType;
import static tech.pegasys.teku.infrastructure.http.ContentTypes.OCTET_STREAM;
import static tech.pegasys.teku.infrastructure.http.HttpStatusCodes.SC_OK;
import static tech.pegasys.teku.infrastructure.http.RestApiConstants.HEADER_CONSENSUS_VERSION;
import static tech.pegasys.teku.infrastructure.http.RestApiConstants.RES_BAD_REQUEST;
import static tech.pegasys.teku.infrastructure.http.RestApiConstants.RES_INTERNAL_ERROR;
import static tech.pegasys.teku.infrastructure.http.RestApiConstants.RES_NOT_FOUND;
import static tech.pegasys.teku.infrastructure.http.RestApiConstants.RES_OK;
import static tech.pegasys.teku.infrastructure.http.RestApiConstants.RES_SERVICE_UNAVAILABLE;
import static tech.pegasys.teku.infrastructure.http.RestApiConstants.SERVICE_UNAVAILABLE;
import static tech.pegasys.teku.infrastructure.http.RestApiConstants.TAG_EXPERIMENTAL;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.javalin.http.Context;
import io.javalin.plugin.openapi.annotations.HttpMethod;
import io.javalin.plugin.openapi.annotations.OpenApi;
import io.javalin.plugin.openapi.annotations.OpenApiContent;
import io.javalin.plugin.openapi.annotations.OpenApiResponse;
import java.util.Optional;
import org.jetbrains.annotations.NotNull;
import tech.pegasys.teku.api.ChainDataProvider;
import tech.pegasys.teku.api.DataProvider;
import tech.pegasys.teku.api.schema.Version;
import tech.pegasys.teku.beaconrestapi.MigratingEndpointAdapter;
import tech.pegasys.teku.infrastructure.async.SafeFuture;
import tech.pegasys.teku.infrastructure.restapi.endpoints.AsyncApiResponse;
import tech.pegasys.teku.infrastructure.restapi.endpoints.EndpointMetadata;
import tech.pegasys.teku.infrastructure.restapi.endpoints.RestApiRequest;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.datastructures.metadata.StateAndMetaData;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.BeaconState;

public class GetFinalizedCheckpointState extends MigratingEndpointAdapter {

  public static final String ROUTE = "/eth/v1/checkpoint/finalized_state";
  private final ChainDataProvider chainDataProvider;

  public GetFinalizedCheckpointState(final DataProvider dataProvider, Spec spec) {
    this(dataProvider.getChainDataProvider(), spec);
  }

  public GetFinalizedCheckpointState(final ChainDataProvider chainDataProvider, Spec spec) {
    super(
        EndpointMetadata.get(ROUTE)
            .operationId("getFinalizedCheckpointState")
            .summary("Get full BeaconState object for finalized checkpoint state")
            .description(
                "Returns full BeaconState object for a finalized checkpoint state from the Weak Subjectivity period.")
            .tags(TAG_EXPERIMENTAL)
            .defaultResponseType(OCTET_STREAM)
            .response(
                SC_OK,
                "Request successful",
                sszResponseType(
                    beaconState ->
                        spec.getForkSchedule()
                            .getSpecMilestoneAtSlot(((BeaconState) beaconState).getSlot())))
            .withNotFoundResponse()
            .build());
    this.chainDataProvider = chainDataProvider;
  }

  @OpenApi(
      path = ROUTE,
      method = HttpMethod.GET,
      summary = "Get finalized checkpoint state",
      tags = {TAG_EXPERIMENTAL},
      description = "Returns the latest finalized BeaconState.",
      responses = {
        @OpenApiResponse(
            status = RES_OK,
            content = {@OpenApiContent(type = OCTET_STREAM)}),
        @OpenApiResponse(status = RES_BAD_REQUEST),
        @OpenApiResponse(status = RES_NOT_FOUND),
        @OpenApiResponse(status = RES_INTERNAL_ERROR),
        @OpenApiResponse(status = RES_SERVICE_UNAVAILABLE, description = SERVICE_UNAVAILABLE)
      })
  @Override
  public void handle(@NotNull Context ctx) throws Exception {
    adapt(ctx);
  }

  @Override
  public void handleRequest(RestApiRequest request) throws JsonProcessingException {
    final SafeFuture<Optional<StateAndMetaData>> future =
        chainDataProvider.getBeaconStateAndMetadata("finalized");

    request.respondAsync(
        future.thenApply(
            maybeStateAndMetaData ->
                maybeStateAndMetaData
                    .map(
                        stateAndMetaData -> {
                          request.header(
                              HEADER_CONSENSUS_VERSION,
                              Version.fromMilestone(stateAndMetaData.getMilestone()).name());
                          return AsyncApiResponse.respondOk(stateAndMetaData.getData());
                        })
                    .orElseGet(AsyncApiResponse::respondNotFound)));
  }
}
