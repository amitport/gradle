/*
 * Copyright 2015 the original author or authors.
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

package org.gradle.tooling.internal.consumer.connection;

import com.google.common.collect.Lists;
import org.gradle.api.Action;
import org.gradle.tooling.events.OperationDescriptor;
import org.gradle.tooling.events.task.TaskOperationDescriptor;
import org.gradle.tooling.events.test.JvmTestOperationDescriptor;
import org.gradle.tooling.internal.adapter.ProtocolToModelAdapter;
import org.gradle.tooling.internal.adapter.SourceObjectMapping;
import org.gradle.tooling.internal.consumer.converters.TaskPropertyHandlerFactory;
import org.gradle.tooling.internal.consumer.parameters.BuildCancellationTokenAdapter;
import org.gradle.tooling.internal.consumer.parameters.ConsumerOperationParameters;
import org.gradle.tooling.internal.consumer.versioning.ModelMapping;
import org.gradle.tooling.internal.protocol.BuildResult;
import org.gradle.tooling.internal.protocol.ConnectionVersion4;
import org.gradle.tooling.internal.protocol.test.InternalJvmTestExecutionDescriptor;
import org.gradle.tooling.internal.protocol.test.InternalTestExecutionConnection;
import org.gradle.tooling.internal.protocol.test.InternalTestExecutionRequest;
import org.gradle.tooling.internal.provider.TestExecutionRequest;

import java.util.Collection;
import java.util.List;

/*
 * <p>Used for providers >= 2.5.</p>
 */
public class TestExecutionConsumerConnection extends ShutdownAwareConsumerConnection {
    private final ProtocolToModelAdapter adapter;

    public TestExecutionConsumerConnection(ConnectionVersion4 delegate, ModelMapping modelMapping, ProtocolToModelAdapter adapter) {
        super(delegate, modelMapping, adapter);
        this.adapter = adapter;
    }

    public Void runTests(final TestExecutionRequest testExecutionRequest, ConsumerOperationParameters operationParameters) {
        final BuildCancellationTokenAdapter cancellationTokenAdapter = new BuildCancellationTokenAdapter(operationParameters.getCancellationToken());
        final BuildResult<Object> result = ((InternalTestExecutionConnection) getDelegate()).runTests(toInternalTestExecutionRequest(testExecutionRequest), cancellationTokenAdapter, operationParameters);
        Action<SourceObjectMapping> mapper = new TaskPropertyHandlerFactory().forVersion(getVersionDetails());
        return adapter.adapt(Void.class, result.getModel(), mapper);
    }

    InternalTestExecutionRequest toInternalTestExecutionRequest(TestExecutionRequest testExecutionRequest) {
        final Collection<JvmTestOperationDescriptor> testTaskPaths = testExecutionRequest.getTestOperationDescriptors();
        final List<InternalJvmTestExecutionDescriptor> internalJvmTestDescriptors = Lists.newArrayList();

        for (final JvmTestOperationDescriptor descriptor : testTaskPaths) {
            internalJvmTestDescriptors.add(new InternalJvmTestExecutionDescriptor() {
                @Override
                public String getClassName() {
                    return descriptor.getClassName();
                }

                @Override
                public String getMethodName() {
                    return descriptor.getMethodName();
                }

                @Override
                public String getTaskPath() {
                    return findTaskPath(descriptor);
                }
            });
        }

        InternalTestExecutionRequest request = new InternalTestExecutionRequest() {
            @Override
            public Collection<InternalJvmTestExecutionDescriptor> getTestExecutionDescriptors() {
                return internalJvmTestDescriptors;
            }
        };
        return request;
    }

    private String findTaskPath(JvmTestOperationDescriptor descriptor) {
        OperationDescriptor parent = descriptor.getParent();
        while (parent != null && parent.getParent() != null) {
            parent = parent.getParent();
        }
        if (parent instanceof TaskOperationDescriptor) {
            return ((TaskOperationDescriptor) parent).getTaskPath();
        } else {
            return null;
        }
    }
}
