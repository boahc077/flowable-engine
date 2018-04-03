/* Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.flowable.spring.boot.cmmn;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.flowable.cmmn.engine.CmmnEngineConfiguration;
import org.flowable.cmmn.engine.configurator.CmmnEngineConfigurator;
import org.flowable.cmmn.spring.SpringCmmnEngineConfiguration;
import org.flowable.cmmn.spring.configurator.SpringCmmnEngineConfigurator;
import org.flowable.job.service.impl.asyncexecutor.AsyncExecutor;
import org.flowable.spring.SpringProcessEngineConfiguration;
import org.flowable.spring.boot.AbstractSpringEngineAutoConfiguration;
import org.flowable.spring.boot.EngineConfigurationConfigurer;
import org.flowable.spring.boot.FlowableJobConfiguration;
import org.flowable.spring.boot.FlowableProperties;
import org.flowable.spring.boot.FlowableTransactionAutoConfiguration;
import org.flowable.spring.boot.ProcessEngineAutoConfiguration;
import org.flowable.spring.boot.condition.ConditionalOnCmmnEngine;
import org.flowable.spring.boot.condition.ConditionalOnProcessEngine;
import org.flowable.spring.boot.idm.FlowableIdmProperties;
import org.flowable.spring.job.service.SpringAsyncExecutor;
import org.flowable.spring.job.service.SpringRejectedJobsHandler;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.Resource;
import org.springframework.core.task.TaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * {@link org.springframework.boot.autoconfigure.EnableAutoConfiguration} for the CMMN engine
 *
 * @author Filip Hrisafov
 */
@Configuration
@ConditionalOnCmmnEngine
@EnableConfigurationProperties({
    FlowableProperties.class,
    FlowableIdmProperties.class,
    FlowableCmmnProperties.class
})
@AutoConfigureAfter({
    FlowableTransactionAutoConfiguration.class,
})
@AutoConfigureBefore({
    ProcessEngineAutoConfiguration.class
})
@Import({
    FlowableJobConfiguration.class
})
public class CmmnEngineAutoConfiguration extends AbstractSpringEngineAutoConfiguration {

    protected final FlowableCmmnProperties cmmnProperties;
    protected final FlowableIdmProperties idmProperties;
    protected List<EngineConfigurationConfigurer<SpringCmmnEngineConfiguration>> engineConfigurers = new ArrayList<>();

    public CmmnEngineAutoConfiguration(FlowableProperties flowableProperties, FlowableCmmnProperties cmmnProperties, FlowableIdmProperties idmProperties) {
        super(flowableProperties);
        this.cmmnProperties = cmmnProperties;
        this.idmProperties = idmProperties;
    }

    /**
     * The Async Executor must not be shared between the engines.
     * Therefore a dedicated one is always created.
     */
    @Bean
    @Cmmn
    @ConfigurationProperties(prefix = "flowable.cmmn.async.executor")
    @ConditionalOnMissingBean(name = "cmmnAsyncExecutor")
    public SpringAsyncExecutor cmmnAsyncExecutor(
        ObjectProvider<TaskExecutor> taskExecutor,
        @Cmmn ObjectProvider<TaskExecutor> cmmnTaskExecutor,
        ObjectProvider<SpringRejectedJobsHandler> rejectedJobsHandler,
        @Cmmn ObjectProvider<SpringRejectedJobsHandler> cmmnRejectedJobsHandler
    ) {
        return new SpringAsyncExecutor(
            getIfAvailable(cmmnTaskExecutor, taskExecutor),
            getIfAvailable(cmmnRejectedJobsHandler, rejectedJobsHandler)
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public SpringCmmnEngineConfiguration cmmnEngineConfiguration(DataSource dataSource, PlatformTransactionManager platformTransactionManager,
        @Cmmn ObjectProvider<AsyncExecutor> asyncExecutorProvider)
        throws IOException {
        SpringCmmnEngineConfiguration configuration = new SpringCmmnEngineConfiguration();

        List<Resource> resources = this.discoverDeploymentResources(
            cmmnProperties.getResourceLocation(),
            cmmnProperties.getResourceSuffixes(),
            cmmnProperties.isDeployResources()
        );

        if (resources != null && !resources.isEmpty()) {
            configuration.setDeploymentResources(resources.toArray(new Resource[0]));
            configuration.setDeploymentName(cmmnProperties.getDeploymentName());
        }

        AsyncExecutor asyncExecutor = asyncExecutorProvider.getIfUnique();
        if (asyncExecutor != null) {
            configuration.setAsyncExecutor(asyncExecutor);
        }

        configureSpringEngine(configuration, platformTransactionManager);
        configureEngine(configuration, dataSource);

        configuration.setDeploymentName(defaultText(cmmnProperties.getDeploymentName(), configuration.getDeploymentName()));

        configuration.setDisableIdmEngine(!idmProperties.isEnabled());

        configuration.setAsyncExecutorActivate(flowableProperties.isAsyncExecutorActivate());

        //TODO Can it have different then the Process engine?
        configuration.setHistoryLevel(flowableProperties.getHistoryLevel());

        configuration.setEnableSafeCmmnXml(cmmnProperties.isEnableSafeXml());

        engineConfigurers.forEach(configurer -> configurer.configure(configuration));

        return configuration;
    }

    @Configuration
    @ConditionalOnProcessEngine
    public static class CmmnEngineProcessConfiguration {

        @Bean
        @ConditionalOnMissingBean(name = "cmmnProcessEngineConfigurationConfigurer")
        public EngineConfigurationConfigurer<SpringProcessEngineConfiguration> cmmnProcessEngineConfigurationConfigurer(
            CmmnEngineConfigurator cmmnEngineConfigurator) {
            return processEngineConfiguration -> processEngineConfiguration.addConfigurator(cmmnEngineConfigurator);
        }

        @Bean
        @ConditionalOnMissingBean
        public CmmnEngineConfigurator cmmnEngineConfigurator(CmmnEngineConfiguration cmmnEngineConfiguration) {
            SpringCmmnEngineConfigurator cmmnEngineConfigurator = new SpringCmmnEngineConfigurator();
            cmmnEngineConfigurator.setCmmnEngineConfiguration(cmmnEngineConfiguration);
            return cmmnEngineConfigurator;
        }
    }

    @Autowired(required = false)
    public void setEngineConfigurers(List<EngineConfigurationConfigurer<SpringCmmnEngineConfiguration>> engineConfigurers) {
        this.engineConfigurers = engineConfigurers;
    }
}
