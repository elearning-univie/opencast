/*
 * Licensed to The Apereo Foundation under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 *
 * The Apereo Foundation licenses this file to you under the Educational
 * Community License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License
 * at:
 *
 *   http://opensource.org/licenses/ecl2.txt
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations under
 * the License.
 *
 */

package org.opencastproject.graphql.event;


import org.opencastproject.index.service.api.IndexService;

import graphql.annotations.annotationTypes.GraphQLDescription;
import graphql.annotations.annotationTypes.GraphQLField;
import graphql.annotations.annotationTypes.GraphQLName;

@GraphQLName(GqlDeleteEventPayload.TYPE_NAME)
@GraphQLDescription("DeleteEventPayload")
public class GqlDeleteEventPayload {
  public static final String TYPE_NAME = "DeleteEventPayload";

  public GqlDeleteEventPayload() {

  }

  public GqlDeleteEventPayload(@GraphQLName("id") String id) {
    this.id = id;
    this.status = IndexService.EventRemovalResult.SUCCESS;
  }

  public GqlDeleteEventPayload(
      @GraphQLName("id") String id,
      @GraphQLName("status") IndexService.EventRemovalResult status
  ) {
    this.id = id;
    this.status = status;
  }

  @GraphQLField
  @GraphQLDescription("A unique identifier for the client performing the mutation.")
  private String id;

  @GraphQLField
  @GraphQLDescription("The deletion status of the event.")
  private IndexService.EventRemovalResult status;

  public String getId() {
    return id;
  }

  public GqlDeleteEventState getStatus() {
    return switch (status) {
      case SUCCESS -> GqlDeleteEventState.SUCCESS;
      case NOT_FOUND -> GqlDeleteEventState.SUCCESS;
      case RETRACTING -> GqlDeleteEventState.RETRACTING;
      case GENERAL_FAILURE -> GqlDeleteEventState.FAILED;
    };
  }


}
