/*
 * Copyright 2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
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
package org.gradle.internal.service.scopes;

import org.gradle.api.Action;
import org.gradle.api.internal.DependencyInjectingInstantiator;
import org.gradle.api.internal.GradleInternal;
import org.gradle.api.internal.artifacts.dsl.dependencies.ProjectFinder;
import org.gradle.api.internal.plugins.*;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.api.internal.tasks.TaskExecuter;
import org.gradle.api.internal.tasks.options.OptionReader;
import org.gradle.api.invocation.Gradle;
import org.gradle.execution.*;
import org.gradle.execution.commandline.CommandLineTaskConfigurer;
import org.gradle.execution.commandline.CommandLineTaskParser;
import org.gradle.execution.taskgraph.DefaultTaskGraphExecuter;
import org.gradle.execution.taskgraph.TaskPlanExecutor;
import org.gradle.initialization.BuildCancellationToken;
import org.gradle.internal.Factory;
import org.gradle.internal.TimeProvider;
import org.gradle.internal.concurrent.CompositeStoppable;
import org.gradle.internal.event.ListenerManager;
import org.gradle.internal.progress.BuildOperationExecutor;
import org.gradle.internal.reflect.Instantiator;
import org.gradle.internal.service.DefaultServiceRegistry;
import org.gradle.internal.service.ServiceRegistration;
import org.gradle.internal.service.ServiceRegistry;

import java.util.LinkedList;
import java.util.List;

import static java.util.Arrays.asList;

/**
 * Contains the services for a given {@link GradleInternal} instance.
 */
public class GradleScopeServices extends DefaultServiceRegistry {

    private final CompositeStoppable registries = new CompositeStoppable();

    public GradleScopeServices(final ServiceRegistry parent, GradleInternal gradle) {
        super(parent);
        add(GradleInternal.class, gradle);
        addProvider(new TaskExecutionServices());
        register(new Action<ServiceRegistration>() {
            public void execute(ServiceRegistration registration) {
                for (PluginServiceRegistry pluginServiceRegistry : parent.getAll(PluginServiceRegistry.class)) {
                    pluginServiceRegistry.registerGradleServices(registration);
                }
            }
        });
    }

    TaskSelector createTaskSelector(GradleInternal gradle, ProjectConfigurer projectConfigurer) {
        return new TaskSelector(gradle, projectConfigurer);
    }

    OptionReader createOptionReader() {
        return new OptionReader();
    }

    CommandLineTaskParser createCommandLineTaskParser(OptionReader optionReader, TaskSelector taskSelector) {
        return new CommandLineTaskParser(new CommandLineTaskConfigurer(optionReader), taskSelector);
    }

    BuildExecuter createBuildExecuter(CommandLineTaskParser commandLineTaskParser, TaskSelector taskSelector, ProjectConfigurer projectConfigurer) {
        List<BuildConfigurationAction> configs = new LinkedList<BuildConfigurationAction>();
        configs.add(new DefaultTasksBuildExecutionAction(projectConfigurer));
        configs.add(new ExcludedTaskFilteringBuildConfigurationAction(taskSelector));
        configs.add(new TaskNameResolvingBuildConfigurationAction(commandLineTaskParser));

        return new DefaultBuildExecuter(
                configs,
                asList(new DryRunBuildExecutionAction(),
                        new SelectedTaskExecutionAction()));
    }

    ProjectFinder createProjectFinder(final GradleInternal gradle) {
        return new ProjectFinder() {
            public ProjectInternal getProject(String path) {
                return gradle.getRootProject().project(path);
            }
        };
    }

    TaskGraphExecuter createTaskGraphExecuter(ListenerManager listenerManager, TaskPlanExecutor taskPlanExecutor, BuildCancellationToken cancellationToken, TimeProvider timeProvider, BuildOperationExecutor buildOperationExecutor) {
        Factory<TaskExecuter> taskExecuterFactory = new Factory<TaskExecuter>() {
            @Override
            public TaskExecuter create() {
                return get(TaskExecuter.class);
            }
        };
        return new DefaultTaskGraphExecuter(listenerManager, taskPlanExecutor, taskExecuterFactory, cancellationToken, timeProvider, buildOperationExecutor);
    }

    ServiceRegistryFactory createServiceRegistryFactory(final ServiceRegistry services) {
        return new ServiceRegistryFactory() {
            public ServiceRegistry createFor(Object domainObject) {
                if (domainObject instanceof ProjectInternal) {
                    ProjectScopeServices projectScopeServices = new ProjectScopeServices(services, (ProjectInternal) domainObject);
                    registries.add(projectScopeServices);
                    return projectScopeServices;
                }
                throw new UnsupportedOperationException();
            }
        };
    }

    PluginRegistry createPluginRegistry(PluginRegistry parentRegistry) {
        return parentRegistry.createChild(get(GradleInternal.class).getClassLoaderScope());
    }

    PluginManagerInternal createPluginManager(Instantiator instantiator, GradleInternal gradleInternal, PluginRegistry pluginRegistry) {
        PluginApplicator applicator = new ImperativeOnlyPluginApplicator<Gradle>(gradleInternal);
        return instantiator.newInstance(DefaultPluginManager.class, pluginRegistry, new DependencyInjectingInstantiator(this), applicator);
    }

    @Override
    public void close() {
        registries.stop();
        super.close();
    }
}
