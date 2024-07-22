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
package org.opencastproject.lifecyclemanagement.impl;

import static org.opencastproject.security.api.SecurityConstants.GLOBAL_ADMIN_ROLE;

import org.opencastproject.elasticsearch.api.SearchIndexException;
import org.opencastproject.elasticsearch.api.SearchResult;
import org.opencastproject.elasticsearch.api.SearchResultItem;
import org.opencastproject.elasticsearch.index.ElasticsearchIndex;
import org.opencastproject.elasticsearch.index.objects.event.Event;
import org.opencastproject.elasticsearch.index.objects.event.EventIndexSchema;
import org.opencastproject.elasticsearch.index.objects.event.EventSearchQuery;
import org.opencastproject.index.service.util.RestUtils;
import org.opencastproject.lifecyclemanagement.api.LifeCycleDatabaseException;
import org.opencastproject.lifecyclemanagement.api.LifeCycleDatabaseService;
import org.opencastproject.lifecyclemanagement.api.LifeCyclePolicy;
import org.opencastproject.lifecyclemanagement.api.LifeCyclePolicyAccessControlEntry;
import org.opencastproject.lifecyclemanagement.api.LifeCycleService;
import org.opencastproject.lifecyclemanagement.api.LifeCycleTask;
import org.opencastproject.lifecyclemanagement.api.Status;
import org.opencastproject.security.api.AccessControlEntry;
import org.opencastproject.security.api.AccessControlList;
import org.opencastproject.security.api.AuthorizationService;
import org.opencastproject.security.api.Organization;
import org.opencastproject.security.api.Permissions;
import org.opencastproject.security.api.SecurityService;
import org.opencastproject.security.api.UnauthorizedException;
import org.opencastproject.security.api.User;
import org.opencastproject.util.NotFoundException;
import org.opencastproject.util.data.Tuple;
import org.opencastproject.util.requests.SortCriterion;

import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

@Component(
    property = {
        "service.description=LifeCycle Service",
        "service.pid=org.opencastproject.lifecyclemanagement.LifeCycleService"
    },
    immediate = true,
    service = LifeCycleService.class
)
public class LifeCycleServiceImpl implements LifeCycleService {
  /** Logging facility */
  private static final Logger logger = LoggerFactory.getLogger(LifeCycleServiceImpl.class);

  /** Persistent storage */
  protected LifeCycleDatabaseService persistence;

  /** The security service */
  protected SecurityService securityService;

  /** The authorization service */
  protected AuthorizationService authorizationService = null;

  protected ElasticsearchIndex index;

  @Reference
  public void setPersistence(LifeCycleDatabaseService persistence) {
    this.persistence = persistence;
  }

  @Reference
  public void setSecurityService(SecurityService securityService) {
    this.securityService = securityService;
  }

  @Reference
  public void setAuthorizationService(AuthorizationService authorizationService) {
    this.authorizationService = authorizationService;
  }

  @Reference
  public void setElasticSearchIndex(ElasticsearchIndex index) {
    this.index = index;
  }

  @Activate
  public void activate(ComponentContext cc) throws Exception {
//    logger.info(securityService.getUserIP());
//    logger.info(securityService.getOrganization().toString());
//    logger.info(securityService.getOrganization().getId());
    logger.info("Activating LifeCycle Management Service");
  }

  @Override
  public LifeCyclePolicy getLifeCyclePolicyById(String id)
          throws NotFoundException, IllegalStateException, UnauthorizedException {
    try {
      LifeCyclePolicy policy = persistence.getLifeCyclePolicy(id);
      if (!checkPermission(policy, Permissions.Action.READ)) {
        throw new UnauthorizedException("User does not have read permissions");
      }
      return policy;
    } catch (LifeCycleDatabaseException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public List<LifeCyclePolicy> getLifeCyclePolicies(int limit, int offset, SortCriterion sortCriterion)
          throws IllegalStateException {
    try {
      List<LifeCyclePolicy> policies = persistence.getLifeCyclePolicies(limit, offset, sortCriterion);
      policies.removeIf(policy -> !checkPermission(policy, Permissions.Action.READ));
      return policies;
    } catch (LifeCycleDatabaseException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public List<LifeCyclePolicy> getActiveLifeCyclePolicies() throws IllegalStateException {
    try {
      String orgId = securityService.getOrganization().getId();
      List<LifeCyclePolicy> policies = persistence.getActiveLifeCyclePolicies(orgId);
      policies.removeIf(policy -> !checkPermission(policy, Permissions.Action.READ));
      return policies;
    } catch (LifeCycleDatabaseException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public LifeCyclePolicy createLifeCyclePolicy(LifeCyclePolicy policy) throws UnauthorizedException {
    try {
      policy = persistence.createLifeCyclePolicy(policy, securityService.getOrganization().getId());
      return policy;
    } catch (LifeCycleDatabaseException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public boolean updateLifeCyclePolicy(LifeCyclePolicy policy)
          throws IllegalStateException, UnauthorizedException, IllegalArgumentException {
    try {
      LifeCyclePolicy existingPolicy = persistence.getLifeCyclePolicy(policy.getId());
      if (!checkPermission(existingPolicy, Permissions.Action.WRITE)) {
        throw new UnauthorizedException("User does not have write permissions");
      }
    } catch (NotFoundException | LifeCycleDatabaseException e) {
      throw new IllegalStateException("Could not get policy from database with id ");
    }

    if (policy.getOrganization() == null) {
      policy.setOrganization(securityService.getOrganization().getId());
    }

    try {
      return persistence.updateLifeCyclePolicy(policy, securityService.getOrganization().getId());
    } catch (LifeCycleDatabaseException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public boolean deleteLifeCyclePolicy(String id)
          throws NotFoundException, IllegalStateException, UnauthorizedException {
    try {
      LifeCyclePolicy policy = persistence.getLifeCyclePolicy(id);
      if (!checkPermission(policy, Permissions.Action.WRITE)) {
        throw new UnauthorizedException("User does not have write permissions");
      }
      return persistence.deleteLifeCyclePolicy(policy, securityService.getOrganization().getId());
    } catch (LifeCycleDatabaseException e) {
      throw new IllegalStateException("Could not delete policy from database with id " + id);
    }
  }

  @Override
  public LifeCycleTask getLifeCycleTaskById(String id)
          throws NotFoundException, IllegalStateException, UnauthorizedException {
    try {
      LifeCycleTask task = persistence.getLifeCycleTask(id);
//      if (!checkPermission(task, Permissions.Action.READ)) {
//        throw new UnauthorizedException("User does not have read permissions");
//      }
      return task;
    } catch (LifeCycleDatabaseException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public LifeCycleTask getLifeCycleTaskByTargetId(String targetId)
          throws NotFoundException, IllegalStateException, UnauthorizedException {
    try {
      LifeCycleTask task = persistence.getLifeCycleTaskByTargetId(targetId);
      //      if (!checkPermission(task, Permissions.Action.READ)) {
      //        throw new UnauthorizedException("User does not have read permissions");
      //      }
      return task;
    } catch (LifeCycleDatabaseException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public List<LifeCycleTask> getLifeCycleTasksWithStatus(Status status) throws IllegalStateException {
    try {
      String orgId = securityService.getOrganization().getId();
      List<LifeCycleTask> tasks = persistence.getLifeCycleTasksWithStatus(status, orgId);
      //      if (!checkPermission(policies, Permissions.Action.READ)) {
      //        throw new UnauthorizedException("User does not have read permissions");
      //      }
      return tasks;
    } catch (LifeCycleDatabaseException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public LifeCycleTask createLifeCycleTask(LifeCycleTask task) throws UnauthorizedException {
    try {
      task = persistence.createLifeCycleTask(task, securityService.getOrganization().getId());
      return task;
    } catch (LifeCycleDatabaseException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public boolean updateLifeCycleTask(LifeCycleTask task)
          throws IllegalStateException, UnauthorizedException, IllegalArgumentException {
    try {
      LifeCycleTask existingTask = persistence.getLifeCycleTask(task.getId());
//      if (!checkPermission(existingTask, Permissions.Action.WRITE)) {
//        throw new UnauthorizedException("User does not have write permissions");
//      }
    } catch (NotFoundException | LifeCycleDatabaseException e) {
      throw new IllegalStateException(e);
    }

    if (task.getOrganization() == null) {
      task.setOrganization(securityService.getOrganization().getId());
    }

    try {
      return persistence.updateLifeCycleTask(task, securityService.getOrganization().getId());
    } catch (LifeCycleDatabaseException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public boolean deleteLifeCycleTask(String id)
          throws NotFoundException, IllegalStateException, UnauthorizedException {
    try {
      LifeCycleTask task = persistence.getLifeCycleTask(id);
//      if (!checkPermission(task, Permissions.Action.WRITE)) {
//        throw new UnauthorizedException("User does not have write permissions");
//      }
      return persistence.deleteLifeCycleTask(task, securityService.getOrganization().getId());
    } catch (LifeCycleDatabaseException e) {
      throw new IllegalStateException("Could not delete task from database with id " + id);
    }
  }

  // TODO: Better filtering
  //   Currently we can only query
  //   - no extended metadata
  //   - if a string is contained in the metadata field ("conference" in "DACH conference").
  //     - This basically means wildcard search?, which is said to be not very performant.
  //     - Could we use existing fuzzy search mechanisms?
  //   - We also want to check the opposite (if it is NOT there).
  //     - Could add "withNot{fieldName}" functions to EventSearchQuery, but that would be a lot of writing
  //   - only event metadata, but we probably want series metadata as well
  public List<Event> filterForEntities(Map<String, String> filters) throws SearchIndexException {
    try {
      SearchResult<Event> results = null;
      List<Event> eventsList = new ArrayList<>();
      final Organization organization = securityService.getOrganization();
      final User user = securityService.getUser();
      //      if (organization == null || user == null) {
      //        return Response.status(SC_SERVICE_UNAVAILABLE).build();
      //      }
      EventSearchQuery query = new EventSearchQuery(organization.getId(), user);

      addFiltersToQuery(query, filters);

      results = index.getByQuery(query);

      for (SearchResultItem<Event> item : results.getItems()) {
        Event source = item.getSource();
        eventsList.add(source);
      }

      return eventsList;
    } catch (SearchIndexException e) {
      logger.error("The Search Index was not able to get the events list:", e);
      throw e;
    }
  }

  private void addFiltersToQuery(EventSearchQuery query, Map<String, String> filters) {
    for (String name : filters.keySet()) {
      switch (name) {
        case EventIndexSchema.UID -> query.withIdentifier(filters.get(name));
        case EventIndexSchema.TITLE -> query.withTitle(filters.get(name));
        case EventIndexSchema.DESCRIPTION -> query.withDescription(filters.get(name));
        case EventIndexSchema.PRESENTER -> query.withPresenter(filters.get(name));
        case EventIndexSchema.CONTRIBUTOR-> query.withContributor(filters.get(name));
        case EventIndexSchema.SUBJECT -> query.withSubject(filters.get(name));
        case EventIndexSchema.LOCATION -> query.withLocation(filters.get(name));
        case EventIndexSchema.SERIES_ID -> query.withSeriesId(filters.get(name));
        case EventIndexSchema.SERIES_NAME -> query.withSeriesName(filters.get(name));
        case EventIndexSchema.LANGUAGE -> query.withLanguage(filters.get(name));
        case EventIndexSchema.SOURCE -> query.withSource(filters.get(name));
        case EventIndexSchema.CREATED -> query.withCreated(filters.get(name));
        case EventIndexSchema.CREATOR -> query.withCreator(filters.get(name));
        case EventIndexSchema.PUBLISHER -> query.withPublisher(filters.get(name));
        case EventIndexSchema.LICENSE -> query.withLicense(filters.get(name));
        case EventIndexSchema.RIGHTS -> query.withRights(filters.get(name));
        case EventIndexSchema.START_DATE -> query.withStartDate(filters.get(name));

        case "start_date_range" -> {
          Tuple<Date, Date> fromAndToCreationRange = RestUtils.getFromAndToDateRange(filters.get(name));
          query.withStartFrom(fromAndToCreationRange.getA());
          query.withStartTo(fromAndToCreationRange.getB());
        }
        default -> logger.info("Filter " + name + " is not supported");
      }
    }
  }

  /**
   * Runs a permission check on the given policy for the given action
   * @param policy {@link LifeCyclePolicy} to check permission for
   * @param action Action to check permission for
   * @return True if action is permitted on the {@link LifeCyclePolicy}, else false
   */
  private boolean checkPermission(LifeCyclePolicy policy, Permissions.Action action) {
    User currentUser = securityService.getUser();
    Organization currentOrg = securityService.getOrganization();
    String currentOrgAdminRole = currentOrg.getAdminRole();
    String currentOrgId = currentOrg.getId();

    return currentUser.hasRole(GLOBAL_ADMIN_ROLE)
        || (currentUser.hasRole(currentOrgAdminRole) && currentOrgId.equals(policy.getOrganization()))
        || authorizationService.hasPermission(getAccessControlList(policy), action.toString());
  }

  /**
   * Parse the access information for a playlist from its database format into an {@link AccessControlList}
   * @param policy The {@link LifeCyclePolicy} to get the {@link AccessControlList} for
   * @return The {@link AccessControlList} for the given {@link LifeCyclePolicy}
   */
  private AccessControlList getAccessControlList(LifeCyclePolicy policy) {
    List<AccessControlEntry> accessControlEntries = new ArrayList<>();
    for (LifeCyclePolicyAccessControlEntry entry : policy.getAccessControlEntries()) {
      accessControlEntries.add(entry.toAccessControlEntry());
    }
    return new AccessControlList(accessControlEntries);
  }
}
