/**
 * Copyright (C) 2016 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.redhat.ipaas.rest.v1.handler.integration;

import java.io.IOException;
import java.util.HashMap;
import java.math.BigInteger;
import java.util.Date;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.ws.rs.Path;

import com.redhat.ipaas.core.IPaasServerException;
import com.redhat.ipaas.core.Names;
import com.redhat.ipaas.dao.manager.DataManager;
import com.redhat.ipaas.github.GitHubService;
import com.redhat.ipaas.model.Kind;
import com.redhat.ipaas.model.connection.Connector;
import com.redhat.ipaas.model.integration.Integration;
import com.redhat.ipaas.model.integration.Step;
import com.redhat.ipaas.model.integration.Integration.Status;
import com.redhat.ipaas.openshift.ImmutableCreateResourcesRequest;
import com.redhat.ipaas.openshift.OpenShiftService;
import com.redhat.ipaas.project.converter.GenerateProjectRequest;
import com.redhat.ipaas.project.converter.ImmutableGenerateProjectRequest;
import com.redhat.ipaas.project.converter.ProjectGenerator;
import com.redhat.ipaas.rest.v1.handler.BaseHandler;
import com.redhat.ipaas.rest.v1.operations.*;

import io.fabric8.funktion.model.StepKinds;
import io.swagger.annotations.Api;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Path("/integrations")
@Api(value = "integrations")
@Component
public class IntegrationHandler extends BaseHandler implements Lister<Integration>, Getter<Integration>, Creator<Integration>, Deleter<Integration>, Updater<Integration> {

    private final GitHubService gitHubService;

    private final ProjectGenerator projectConverter;

    @Value("${openshift.apiBaseUrl}")
    private String openshiftApiBaseUrl;

    @Value("${openshift.namespace}")
    private String namespace;

    private final OpenShiftService openShiftService;

    public IntegrationHandler(DataManager dataMgr, GitHubService gitHubService, ProjectGenerator projectConverter, OpenShiftService openShiftService) {
        super(dataMgr);
        this.gitHubService = gitHubService;
        this.projectConverter = projectConverter;
        this.openShiftService = openShiftService;
    }

    @Override
    public Kind resourceKind() {
        return Kind.Integration;
    }

    @Override
    public Integration get(String id) {

        Integration integration = Getter.super.get(id);

        //fudging the timesUsed for now
        if (integration.getCurrentStatus().equals(Integration.Status.Activated)) {
            Integration updatedIntegration = integration = new Integration.Builder()
                    .createFrom(integration)
                    .timesUsed(BigInteger.valueOf(new Date().getTime()/1000000))
                    .build();
            return updatedIntegration;
        } else {
            return integration;
        }
    }

    @Override
    public Integration create(Integration integration) {

        String secret = createSecret();
        String gitCloneUrl = ensureGitHubSetup(integration, secret);

        Integration updatedIntegration = new Integration.Builder()
                .createFrom(integration).gitRepo(gitCloneUrl)
                .lastUpdated(new Date()).currentStatus(Status.Draft)
                .build();
        ensureOpenShiftResources(updatedIntegration, secret);
        return Creator.super.create(updatedIntegration);
    }

    @Override
    public void update(String id, Integration integration) {
        Integration updatedIntegration = new Integration.Builder()
                .createFrom(integration)
                .lastUpdated(new Date())
                .build();
        Updater.super.update(id, updatedIntegration);
    }

    // ==========================================================================

    private String ensureGitHubSetup(Integration integration, String secret) {
        try {
            Integration integrationWithGitRepoName = ensureGitRepoName(integration);
            String repoName = integrationWithGitRepoName.getGitRepo().orElseThrow(() -> new IllegalArgumentException("Missing git repo in integration"));

            GenerateProjectRequest request = ImmutableGenerateProjectRequest
                .builder()
                .integration(integrationWithGitRepoName)
                .connectors(connectorsMap())
                .build();

            Map<String, byte[]> fileContents = projectConverter.generate(request);

            // Secret to be used in the build trigger
            String webHookUrl = createWebHookUrl(repoName, secret);

            // Do all github stuff at once
            return gitHubService.createOrUpdateProjectFiles(repoName, generateCommitMessage(), fileContents, webHookUrl);

        } catch (IOException e) {
            throw IPaasServerException.launderThrowable(e);
        }
    }

    private String createWebHookUrl(String bcName, String secret) {
        return String.format(
            "%s/namespaces/%s/buildconfigs/%s/webhooks/%s/github", openshiftApiBaseUrl, namespace, bcName, secret);
    }

    private Integration ensureGitRepoName(Integration integration) {
        Optional<String> repoNameOptional = integration.getGitRepo();
        if (!repoNameOptional.isPresent()) {
            String generatedRepoName = Names.sanitize(integration.getName());
            integration = new Integration.Builder()
                    .createFrom(integration).gitRepo(generatedRepoName)
                    .build();
        }
        return integration;
    }

    private String generateCommitMessage() {
        // TODO Let's generate some nice message...
        return "Updated";
    }

    private String createSecret() {
        return UUID.randomUUID().toString();
    }

    private Map<String, Connector> connectorsMap() {
        return getDataManager().fetchAll(Connector.class).getItems().stream().collect(Collectors.toMap(o -> o.getId().get(), o -> o));
    }

    private void ensureOpenShiftResources(Integration integration, String webHookSecret) {
        integration.getGitRepo().ifPresent(gitRepo -> openShiftService.createOpenShiftResources(ImmutableCreateResourcesRequest.builder()
            .name(integration.getName())
            .gitRepository(gitRepo)
            .webhookSecret(webHookSecret)
            .secretData(extractSecretsFrom(integration))
            .build()));
    }

    private Map<String, String> extractSecretsFrom(Integration integration) {
        Map<String, String> secrets = new HashMap<>();
        Map<String, Connector> connectorMap = connectorsMap();

        integration.getSteps().ifPresent(steps -> {
            for (Step step : steps) {
                if (step.getStepKind().equals(StepKinds.ENDPOINT)) {
                    step.getAction().ifPresent(action -> {
                        step.getConnection().ifPresent(connection -> {
                                String connectorId = step.getConnection().get().getConnectorId().orElse(action.getConnectorId());
                                if (!connectorMap.containsKey(connectorId)) {
                                    throw new IllegalStateException("Connector:[" + connectorId + "] not found.");
                                }
                                Connector connector = connectorMap.get(connectorId);
                                secrets.putAll(connector.filterSecrets(connection.getConfiguredProperties()));
                                secrets.putAll(connector.filterSecrets(step.getConfiguredProperties()));
                        });
                    });
                    continue;
                }
            }
        });
        return secrets;
    }
}
