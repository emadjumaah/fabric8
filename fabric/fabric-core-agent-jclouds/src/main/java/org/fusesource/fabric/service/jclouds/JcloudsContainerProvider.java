/**
 * Copyright (C) FuseSource, Inc.
 * http://fusesource.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.fusesource.fabric.service.jclouds;

import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;
import com.google.inject.Module;
import org.fusesource.fabric.api.Container;
import org.fusesource.fabric.api.ContainerProvider;
import org.fusesource.fabric.api.CreateContainerMetadata;
import org.fusesource.fabric.api.CreateJCloudsContainerMetadata;
import org.fusesource.fabric.api.CreateJCloudsContainerOptions;
import org.fusesource.fabric.internal.ContainerProviderUtils;
import org.jclouds.compute.ComputeService;
import org.jclouds.compute.ComputeServiceContext;
import org.jclouds.compute.ComputeServiceContextFactory;
import org.jclouds.compute.RunNodesException;
import org.jclouds.compute.domain.ExecResponse;
import org.jclouds.compute.domain.NodeMetadata;
import org.jclouds.compute.domain.TemplateBuilder;
import org.jclouds.compute.options.RunScriptOptions;
import org.jclouds.domain.Credentials;
import org.jclouds.rest.RestContextFactory;

import static org.fusesource.fabric.internal.ContainerProviderUtils.buildInstallAndStartScript;
import static org.fusesource.fabric.internal.ContainerProviderUtils.buildStartScript;
import static org.fusesource.fabric.internal.ContainerProviderUtils.buildStopScript;

/**
 * A concrete {@link org.fusesource.fabric.api.ContainerProvider} that creates {@link org.fusesource.fabric.api.Container}s via jclouds {@link ComputeService}.
 */
public class JcloudsContainerProvider implements ContainerProvider<CreateJCloudsContainerOptions, CreateJCloudsContainerMetadata> {

    private final ConcurrentMap<String, ComputeService> computeServiceMap = new ConcurrentHashMap<String, ComputeService>();

    public void bind(ComputeService computeService) {
        if(computeService != null) {
            String providerName = computeService.getContext().getProviderSpecificContext().getId();
            if(providerName != null) {
              computeServiceMap.put(providerName,computeService);
            }
        }
    }

    public void unbind(ComputeService computeService) {
        if(computeService != null) {
            String providerName = computeService.getContext().getProviderSpecificContext().getId();
            if(providerName != null) {
               computeServiceMap.remove(providerName);
            }
        }
    }

    public ConcurrentMap<String, ComputeService> getComputeServiceMap() {
        return computeServiceMap;
    }

    public Set<CreateJCloudsContainerMetadata> create(CreateJCloudsContainerOptions options) throws MalformedURLException, RunNodesException, URISyntaxException, InterruptedException {
       final Set<CreateJCloudsContainerMetadata> result = new LinkedHashSet<CreateJCloudsContainerMetadata>();

        ComputeService computeService = getOrCreateComputeService(options);

        TemplateBuilder builder = computeService.templateBuilder();
        builder.any();
        switch (options.getInstanceType()) {
            case Smallest:
                builder.smallest();
                break;
            case Biggest:
                builder.biggest();
                break;
            case Fastest:
                builder.fastest();
        }

        if (options.getLocationId() != null) {
            builder.locationId(options.getLocationId());
        }
        if (options.getImageId() != null) {
            builder.imageId(options.getImageId());
        }
        if (options.getHardwareId() != null) {
            builder.hardwareId(options.getHardwareId());
        }

        Set<? extends NodeMetadata> metadatas = null;

        metadatas = computeService.createNodesInGroup(options.getGroup(), options.getNumber(), builder.build());

        Thread.sleep(5000);

        int suffix = 1;
        StringBuilder buffer = new StringBuilder();
        boolean first = true;
        if (metadatas != null) {
            String originalName = new String(options.getName());
            for (NodeMetadata nodeMetadata : metadatas) {
                Credentials credentials = null;
                //For some cloud providers return do not allow shell access to root, so the user needs to be overrided.
                if (options.getUser() != null) {
                    credentials = new Credentials(options.getUser(), nodeMetadata.getCredentials().credential);
                } else {
                    credentials = nodeMetadata.getCredentials();
                }
                String id = nodeMetadata.getId();
                Set<String> publicAddresses = nodeMetadata.getPublicAddresses();

                String containerName;
                if (options.getNumber() > 1) {
                    containerName = originalName + (suffix++);
                } else {
                    containerName = originalName;
                }

                CreateJCloudsContainerMetadata jCloudsContainerMetadata = new CreateJCloudsContainerMetadata();
                jCloudsContainerMetadata.setCreateOptions(options);
                jCloudsContainerMetadata.setNodeId(nodeMetadata.getId());
                jCloudsContainerMetadata.setContainerName(containerName);
                jCloudsContainerMetadata.setPublicAddresses(publicAddresses);
                jCloudsContainerMetadata.setHostname(nodeMetadata.getHostname());
                jCloudsContainerMetadata.setIdentity(credentials.identity);
                jCloudsContainerMetadata.setCredential(credentials.credential);

                Properties addresses = new Properties();
                if (publicAddresses != null && !publicAddresses.isEmpty()) {
                    String publicAddress = publicAddresses.toArray(new String[publicAddresses.size()])[0];
                    addresses.put("publicip", publicAddress);
                }

                options.getSystemProperties().put(ContainerProviderUtils.ADDRESSES_PROPERTY_KEY,addresses);

                try {
                    String script = buildInstallAndStartScript(options.name(containerName));
                    ExecResponse response = null;
                    if (credentials != null) {
                       response =  computeService.runScriptOnNode(id, script, RunScriptOptions.Builder.overrideCredentialsWith(credentials).runAsRoot(false));
                    } else {
                        response = computeService.runScriptOnNode(id, script);
                    }
                    if (response == null) {
                        jCloudsContainerMetadata.setFailure(new Exception("No response received for fabric install script."));
                    } else if (response.getOutput() != null && response.getOutput().contains("Command failed")) {
                        jCloudsContainerMetadata.setFailure(new Exception(response.getError()));
                    }
                } catch (Throwable t) {
                    jCloudsContainerMetadata.setFailure(t);
                }
                //Cleanup addresses.
                options.getSystemProperties().clear();
                result.add(jCloudsContainerMetadata);
            }
        }
        return result;
    }

    @Override
    public void start(Container container) {
        CreateContainerMetadata metadata = container.getMetadata();
        if (!(metadata instanceof CreateJCloudsContainerMetadata)) {
            throw new IllegalStateException("Container doesn't have valid create container metadata type");
        } else {
            CreateJCloudsContainerMetadata jCloudsContainerMetadata = (CreateJCloudsContainerMetadata) metadata;
            CreateJCloudsContainerOptions options = jCloudsContainerMetadata.getCreateOptions();
            try {
                Credentials credentials = new Credentials(jCloudsContainerMetadata.getIdentity(), jCloudsContainerMetadata.getCredential());
                String nodeId = jCloudsContainerMetadata.getNodeId();
                ComputeService computeService = getOrCreateComputeService(options);
                String script = buildStartScript(options.name(container.getId()));
                ExecResponse response = null;
                if (credentials != null) {
                    response = computeService.runScriptOnNode(nodeId, script, RunScriptOptions.Builder.overrideCredentialsWith(credentials).runAsRoot(false));
                } else {
                    response = computeService.runScriptOnNode(nodeId, script);
                }
                if (response == null) {
                    jCloudsContainerMetadata.setFailure(new Exception("No response received for fabric install script."));
                } else if (response.getOutput() != null && response.getOutput().contains("Command failed")) {
                    jCloudsContainerMetadata.setFailure(new Exception(response.getError()));
                }
            } catch (Throwable t) {
                jCloudsContainerMetadata.setFailure(t);
            }
        }
    }

    @Override
    public void stop(Container container) {
        CreateContainerMetadata metadata = container.getMetadata();
        if (!(metadata instanceof CreateJCloudsContainerMetadata)) {
            throw new IllegalStateException("Container doesn't have valid create container metadata type");
        } else {
            CreateJCloudsContainerMetadata jCloudsContainerMetadata = (CreateJCloudsContainerMetadata) metadata;
            CreateJCloudsContainerOptions options =  jCloudsContainerMetadata.getCreateOptions();
            try {
                Credentials credentials = new Credentials(jCloudsContainerMetadata.getIdentity(),jCloudsContainerMetadata.getCredential());
                String nodeId = jCloudsContainerMetadata.getNodeId();
                ComputeService computeService = getOrCreateComputeService(options);
                String script = buildStopScript(options.name(container.getId()));
                ExecResponse response = null;
                if (credentials != null) {
                    response = computeService.runScriptOnNode(nodeId, script, RunScriptOptions.Builder.overrideCredentialsWith(credentials).runAsRoot(false));
                } else {
                    response = computeService.runScriptOnNode(nodeId, script);
                }

                if (response == null) {
                    jCloudsContainerMetadata.setFailure(new Exception("No response received for fabric install script."));
                } else if (response.getOutput() != null && response.getOutput().contains("Command failed")) {
                    jCloudsContainerMetadata.setFailure(new Exception(response.getError()));
                }
            } catch (Throwable t) {
                jCloudsContainerMetadata.setFailure(t);
            }
        }
    }

    @Override
    public void destroy(Container container) {
        CreateContainerMetadata metadata = container.getMetadata();
        if (!(metadata instanceof CreateJCloudsContainerMetadata)) {
            throw new IllegalStateException("Container doesn't have valid create container metadata type");
        } else {
            CreateJCloudsContainerMetadata jCloudsContainerMetadata = (CreateJCloudsContainerMetadata)metadata;
            CreateJCloudsContainerOptions options =  jCloudsContainerMetadata.getCreateOptions();
            String nodeId = jCloudsContainerMetadata.getNodeId();
            ComputeService computeService = getOrCreateComputeService(options);
            computeService.destroyNode(nodeId);
        }
    }

    public Map<String, String> parseQuery(String uri) throws URISyntaxException {
        //TODO: This is copied form URISupport. We should move URISupport to core so that we don't have to copy stuff arround.
        try {
            Map<String, String> rc = new HashMap<String, String>();
            if (uri != null) {
                String[] parameters = uri.split("&");
                for (int i = 0; i < parameters.length; i++) {
                    int p = parameters[i].indexOf("=");
                    if (p >= 0) {
                        String name = URLDecoder.decode(parameters[i].substring(0, p), "UTF-8");
                        String value = URLDecoder.decode(parameters[i].substring(p + 1), "UTF-8");
                        rc.put(name, value);
                    } else {
                        rc.put(parameters[i], null);
                    }
                }
            }
            return rc;
        } catch (UnsupportedEncodingException e) {
            throw (URISyntaxException) new URISyntaxException(e.toString(), "Invalid encoding").initCause(e);
        }
    }

    /**
     * Gets an existing {@link ComputeService} that matches configuration or creates a new one.
     * @param options
     * @return
     */
    private ComputeService getOrCreateComputeService(CreateJCloudsContainerOptions options) {
        ComputeService computeService = null;
        if (options != null) {
            computeService = computeServiceMap.get(options.getProviderName());
            if (computeService == null) {

                if (options.getIdentity() == null || options.getCredential() == null) {
                    throw new RuntimeException("Compute service not found, please specify provider, identity & credential in order to create one");
                }
                Iterable<? extends Module> modules = ImmutableSet.of();

                Properties props = new Properties();
                props.put("provider", options.getProviderName());
                props.put("identity", options.getIdentity());
                props.put("credential", options.getCredential());
                if (!Strings.isNullOrEmpty(options.getOwner()) && options.getProviderName().equals("aws-ec2")) {
                    props.put("jclouds.ec2.ami-owners", options.getOwner());
                }

                RestContextFactory restFactory = new RestContextFactory();
                ComputeServiceContext context = new ComputeServiceContextFactory(restFactory).createContext(options.getProviderName(), options.getIdentity(), options.getCredential(), modules, props);
                computeService = context.getComputeService();
                computeServiceMap.put(options.getProviderName(),computeService);
            }

        }
        return computeService;
    }
}
