/*
 * Copyright 2018 Google, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.halyard.controllers.v1;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.netflix.spinnaker.halyard.config.config.v1.HalconfigDirectoryStructure;
import com.netflix.spinnaker.halyard.config.config.v1.HalconfigParser;
import com.netflix.spinnaker.halyard.config.model.v1.canary.AbstractCanaryAccount;
import com.netflix.spinnaker.halyard.config.model.v1.canary.Canary;
import com.netflix.spinnaker.halyard.config.model.v1.node.Halconfig;
import com.netflix.spinnaker.halyard.config.services.v1.CanaryAccountService;
import com.netflix.spinnaker.halyard.config.services.v1.CanaryService;
import com.netflix.spinnaker.halyard.core.DaemonResponse.UpdateRequestBuilder;
import com.netflix.spinnaker.halyard.core.problem.v1.ProblemSet;
import com.netflix.spinnaker.halyard.core.tasks.v1.DaemonTask;
import com.netflix.spinnaker.halyard.core.tasks.v1.DaemonTaskHandler;
import com.netflix.spinnaker.halyard.models.v1.ValidationSettings;
import com.netflix.spinnaker.halyard.util.v1.GenericGetRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.util.function.Supplier;

@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/config/deployments/{deploymentName:.+}/canary")
public class CanaryController {
  private final HalconfigParser halconfigParser;
  private final CanaryService canaryService;
  private final CanaryAccountService canaryAccountService;
  private final HalconfigDirectoryStructure halconfigDirectoryStructure;
  private final ObjectMapper objectMapper;

  @RequestMapping(value = "/", method = RequestMethod.GET)
  DaemonTask<Halconfig, Canary> getCanary(@PathVariable String deploymentName,
      @ModelAttribute ValidationSettings validationSettings) {
    return GenericGetRequest.<Canary>builder()
        .getter(() -> canaryService.getCanary(deploymentName))
        .validator(() -> canaryService.validateCanary(deploymentName))
        .description("Get all canary settings")
        .build()
        .execute(validationSettings);
  }

  @RequestMapping(value = "/", method = RequestMethod.PUT)
  DaemonTask<Halconfig, Void> setCanary(@PathVariable String deploymentName,
      @ModelAttribute ValidationSettings validationSettings,
      @RequestBody Canary canary) {
    UpdateRequestBuilder builder = new UpdateRequestBuilder();

    Path configPath = halconfigDirectoryStructure.getConfigPath(deploymentName);
    builder.setStage(() -> canary.stageLocalFiles(configPath));
    builder.setSeverity(validationSettings.getSeverity());
    builder.setUpdate(() -> canaryService.setCanary(deploymentName, canary));

    builder.setValidate(ProblemSet::new);

    if (validationSettings.isValidate()) {
      builder.setValidate(() -> canaryService.validateCanary(deploymentName));
    }

    builder.setRevert(() -> halconfigParser.undoChanges());
    builder.setSave(() -> halconfigParser.saveConfig());
    builder.setClean(() -> halconfigParser.cleanLocalFiles(configPath));

    return DaemonTaskHandler.submitTask(builder::build, "Edit canary analysis settings");
  }

  @RequestMapping(value = "/enabled/", method = RequestMethod.PUT)
  DaemonTask<Halconfig, Void> setEnabled(@PathVariable String deploymentName,
      @ModelAttribute ValidationSettings validationSettings,
      @RequestBody boolean enabled) {
    UpdateRequestBuilder builder = new UpdateRequestBuilder();

    builder.setUpdate(() -> canaryService.setCanaryEnabled(deploymentName, enabled));
    builder.setSeverity(validationSettings.getSeverity());

    builder.setValidate(ProblemSet::new);

    if (validationSettings.isValidate()) {
      builder.setValidate(() -> canaryService.validateCanary(deploymentName));
    }

    builder.setRevert(() -> halconfigParser.undoChanges());
    builder.setSave(() -> halconfigParser.saveConfig());

    return DaemonTaskHandler.submitTask(builder::build, "Edit canary settings");
  }

  @RequestMapping(value = "/{serviceIntegrationName:.+}/accounts/account/{accountName:.+}", method = RequestMethod.GET)
  DaemonTask<Halconfig, AbstractCanaryAccount> getCanaryAccount(@PathVariable String deploymentName,
      @PathVariable String serviceIntegrationName,
      @PathVariable String accountName,
      @ModelAttribute ValidationSettings validationSettings) {
    return GenericGetRequest.<AbstractCanaryAccount>builder()
        .getter(() -> canaryAccountService.getCanaryAccount(deploymentName, serviceIntegrationName, accountName))
        .validator(() -> canaryService.validateCanary(deploymentName))
        .description("Get " + accountName + " canary account")
        .build()
        .execute(validationSettings);
  }

  @RequestMapping(value = "/{serviceIntegrationName:.+}/accounts/account/{accountName:.+}", method = RequestMethod.PUT)
  DaemonTask<Halconfig, Void> setCanaryAccount(@PathVariable String deploymentName,
      @PathVariable String serviceIntegrationName,
      @PathVariable String accountName,
      @ModelAttribute ValidationSettings validationSettings,
      @RequestBody Object rawCanaryAccount) {
    AbstractCanaryAccount canaryAccount = objectMapper.convertValue(
        rawCanaryAccount,
        Canary.translateCanaryAccountType(serviceIntegrationName)
    );

    UpdateRequestBuilder builder = new UpdateRequestBuilder();

    Path configPath = halconfigDirectoryStructure.getConfigPath(deploymentName);
    builder.setStage(() -> canaryAccount.stageLocalFiles(configPath));
    builder.setUpdate(() -> canaryAccountService.setAccount(deploymentName, serviceIntegrationName, accountName, canaryAccount));
    builder.setSeverity(validationSettings.getSeverity());

    Supplier<ProblemSet> doValidate = ProblemSet::new;

    if (validationSettings.isValidate()) {
      doValidate = () -> canaryService.validateCanary(deploymentName);
    }

    builder.setValidate(doValidate);
    builder.setRevert(() -> halconfigParser.undoChanges());
    builder.setSave(() -> halconfigParser.saveConfig());
    builder.setClean(() -> halconfigParser.cleanLocalFiles(configPath));

    return DaemonTaskHandler.submitTask(builder::build, "Edit the " + accountName + " canary account");
  }

  @RequestMapping(value = "/{serviceIntegrationName:.+}/accounts/", method = RequestMethod.POST)
  DaemonTask<Halconfig, Void> addCanaryAccount(@PathVariable String deploymentName,
      @PathVariable String serviceIntegrationName,
      @ModelAttribute ValidationSettings validationSettings,
      @RequestBody Object rawCanaryAccount) {
    AbstractCanaryAccount canaryAccount = objectMapper.convertValue(
        rawCanaryAccount,
        Canary.translateCanaryAccountType(serviceIntegrationName)
    );

    UpdateRequestBuilder builder = new UpdateRequestBuilder();

    Path configPath = halconfigDirectoryStructure.getConfigPath(deploymentName);
    builder.setStage(() -> canaryAccount.stageLocalFiles(configPath));
    builder.setSeverity(validationSettings.getSeverity());
    builder.setUpdate(() -> canaryAccountService.addAccount(deploymentName, serviceIntegrationName, canaryAccount));

    Supplier<ProblemSet> doValidate = ProblemSet::new;

    if (validationSettings.isValidate()) {
      doValidate = () -> canaryService.validateCanary(deploymentName);
    }

    builder.setValidate(doValidate);
    builder.setRevert(() -> halconfigParser.undoChanges());
    builder.setSave(() -> halconfigParser.saveConfig());
    builder.setClean(() -> halconfigParser.cleanLocalFiles(configPath));

    return DaemonTaskHandler.submitTask(builder::build, "Add the " + canaryAccount.getName() + " canary account to " + serviceIntegrationName + " service integration");
  }

  @RequestMapping(value = "/{serviceIntegrationName:.+}/accounts/account/{accountName:.+}", method = RequestMethod.DELETE)
  DaemonTask<Halconfig, Void> deleteCanaryAccount(@PathVariable String deploymentName,
      @PathVariable String serviceIntegrationName,
      @PathVariable String accountName,
      @ModelAttribute ValidationSettings validationSettings) {
    UpdateRequestBuilder builder = new UpdateRequestBuilder();

    builder.setUpdate(() -> canaryAccountService.deleteAccount(deploymentName, serviceIntegrationName, accountName));
    builder.setSeverity(validationSettings.getSeverity());

    Supplier<ProblemSet> doValidate = ProblemSet::new;

    if (validationSettings.isValidate()) {
      doValidate = () -> canaryService.validateCanary(deploymentName);
    }

    builder.setValidate(doValidate);
    builder.setRevert(() -> halconfigParser.undoChanges());
    builder.setSave(() -> halconfigParser.saveConfig());
    Path configPath = halconfigDirectoryStructure.getConfigPath(deploymentName);
    builder.setClean(() -> halconfigParser.cleanLocalFiles(configPath));

    return DaemonTaskHandler.submitTask(builder::build, "Delete the " + accountName + " canary account");
  }
}
