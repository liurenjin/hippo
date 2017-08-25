/*
 *  Copyright 2008-2017 Hippo B.V. (http://www.onehippo.com)
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.hippoecm.repository;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;

import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.SimpleCredentials;

import org.apache.commons.lang.StringUtils;
import org.apache.jackrabbit.core.config.RepositoryConfig;
import org.apache.jackrabbit.core.fs.FileSystem;
import org.hippoecm.repository.jackrabbit.HippoNodeTypeRegistry;
import org.hippoecm.repository.nodetypes.NodeTypesChangeTracker;
import org.onehippo.cm.ConfigurationService;
import org.onehippo.cm.engine.ConfigurationServiceImpl;
import org.onehippo.cm.engine.InternalConfigurationService;
import org.onehippo.cms7.services.HippoServiceRegistry;
import org.onehippo.repository.bootstrap.InitializationProcessor;
import org.hippoecm.repository.api.ReferenceWorkspace;
import org.hippoecm.repository.impl.DecoratorFactoryImpl;
import org.onehippo.repository.bootstrap.InitializationProcessorImpl;
import org.hippoecm.repository.impl.ReferenceWorkspaceImpl;
import org.hippoecm.repository.jackrabbit.RepositoryImpl;
import org.hippoecm.repository.security.HippoSecurityManager;
import org.hippoecm.repository.util.RepoUtils;
import org.onehippo.repository.modules.ModuleManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LocalHippoRepository extends HippoRepositoryImpl {


    /** System property for overriding the repository path */
    public static final String SYSTEM_PATH_PROPERTY = "repo.path";

    /** System property for defining the base path for a non-absolute repo.path property */
    public static final String SYSTEM_BASE_PATH_PROPERTY = "repo.base.path";

    /** System property for overriding the repository config file */
    public static final String SYSTEM_CONFIG_PROPERTY = "repo.config";

    /** System property for enabling bootstrap */
    public static final String SYSTEM_BOOTSTRAP_PROPERTY = "repo.bootstrap";

    /** System property for overriding the servlet config file */
    public static final String SYSTEM_SERVLETCONFIG_PROPERTY = "repo.servletconfig";

    /** Default config file */
    public static final String DEFAULT_REPOSITORY_CONFIG = "repository.xml";

    /** The advised threshold on the number of modified nodes to hold in transient session state */
    public static int batchThreshold = 96;

    protected static final Logger log = LoggerFactory.getLogger(LocalHippoRepository.class);

    private LocalRepositoryImpl jackrabbitRepository = null;

    private String repoPath;
    private String repoConfig;

    private ConfigurationServiceImpl configurationService;

    private ModuleManager moduleManager;

    private NodeTypesChangeTracker nodeTypesChangeTracker;

    protected LocalHippoRepository() {
        super();
    }

    protected LocalHippoRepository(String repositoryConfig) throws RepositoryException {
        super();
        this.repoConfig = repositoryConfig;
    }

    protected LocalHippoRepository(String repositoryDirectory, String repositoryConfig) throws RepositoryException {
        super(repositoryDirectory);
        this.repoConfig = repositoryConfig;
    }

    public static HippoRepository create(String repositoryDirectory) throws RepositoryException {
        return create(repositoryDirectory, null);
    }

    public static HippoRepository create(String repositoryDirectory, String repositoryConfig) throws RepositoryException {
        LocalHippoRepository localHippoRepository;
        if (repositoryDirectory == null) {
            localHippoRepository = new LocalHippoRepository(repositoryConfig);
        } else {
            localHippoRepository = new LocalHippoRepository(repositoryDirectory, repositoryConfig);
        }
        localHippoRepository.initialize();
        VMHippoRepository.register(repositoryDirectory, localHippoRepository);
        return localHippoRepository;
    }

    @Override
    public String getLocation() {
        return super.getLocation();
    }

    @Override
    protected Object clone() throws CloneNotSupportedException {
        return super.clone();
    }

    /**
     * Construct the repository path, default getWorkingDirectory() is used.
     * <p>
     * The system property repo.path can be used to override the default.
     * </p>
     * If repo.path has an absolute path, or system property repo.base.path is undefined/empty, the repo.path
     * is assumed to be an absolute path and returned as such
     * </p>
     * <p>
     * If repo.path starts with '~/' the '~' is expanded to the user.home location, thereby becoming an absolute
     * path and returns as repository path.
     * </p>
     * <p>
     * Else, when repo.path is not an absolute path and system property repo.base.path also is defined,
     * the repo.path is taken relative to the repo.base.path.
     *
     * @return The absolute path to the file repository
     */
    protected String getRepositoryPath() {
        if (repoPath != null) {
            return repoPath;
        }

        String path = System.getProperty(SYSTEM_PATH_PROPERTY);
        if (path != null) {
            if (path.isEmpty()) {
                path = null;
            }
            else {
                path = RepoUtils.stripFileProtocol(path);
                if (path.startsWith("~" + File.separator)) {
                    path = System.getProperty("user.home") + path.substring(1);
                }
            }
        }

        String basePath = path != null ? System.getProperty(SYSTEM_BASE_PATH_PROPERTY) : null;

        if (basePath != null ) {
            if (basePath.isEmpty()) {
                basePath = null;
            }
            else {
                basePath = RepoUtils.stripFileProtocol(basePath);
            }
        }

        if (path == null) {
            repoPath = getWorkingDirectory();
        }
        else if (new File(path).isAbsolute() || basePath == null) {
            repoPath = path;
        }
        else {
            repoPath = basePath + System.getProperty("file.separator") + path;
        }

        log.info("Using repository path: " + repoPath);
        return repoPath;
    }

    /**
     * If the "file://" protocol is used, the path MUST be absolute.
     * In all other cases the config file is used as a class resource.
     * @return InputStream to the repository config
     * @throws RepositoryException
     */
    private InputStream getRepositoryConfigAsStream() throws RepositoryException {

        String configPath = repoConfig;

        if (StringUtils.isEmpty(configPath)) {
            configPath = System.getProperty(SYSTEM_CONFIG_PROPERTY);
        }

        if (StringUtils.isEmpty(configPath)) {
            configPath = System.getProperty(SYSTEM_SERVLETCONFIG_PROPERTY);
        }

        if (StringUtils.isEmpty(configPath)) {
            configPath = DEFAULT_REPOSITORY_CONFIG;
        }

        if (!configPath.startsWith("file:")) {
            final URL configResource = LocalHippoRepository.class.getResource(configPath);
            log.info("Using resource repository config: " + configResource);
            try {
                return configResource.openStream();
            } catch (IOException e) {
                throw new RepositoryException("Failed to open repository configuration", e);
            }
        }

        configPath = RepoUtils.stripFileProtocol(configPath);

        log.info("Using file repository config: file:/" + configPath);

        File configFile = new File(configPath);
        try {
            return new BufferedInputStream(new FileInputStream(configFile));
        } catch (FileNotFoundException e) {
            throw new RepositoryException("Repository config not found: file:/" + configPath);
        }
    }

    private class LocalRepositoryImpl extends RepositoryImpl {
        LocalRepositoryImpl(RepositoryConfig repConfig) throws RepositoryException {
            super(repConfig);
        }
        @Override
        public Session getRootSession(String workspaceName) throws RepositoryException {
            return super.getRootSession(workspaceName);
        }
        void enableVirtualLayer(boolean enabled) throws RepositoryException {
            isStarted = enabled;
        }

        protected FileSystem getFileSystem() {
            return super.getFileSystem();
        }

    }

    protected void initialize() throws RepositoryException {
        log.info("Initializing Hippo Repository");

        Modules.setModules(new Modules(Thread.currentThread().getContextClassLoader()));

        jackrabbitRepository = new LocalRepositoryImpl(createRepositoryConfig());
        repository = new DecoratorFactoryImpl().getRepositoryDecorator(jackrabbitRepository);
        final Session rootSession =  jackrabbitRepository.getRootSession(null);

        configurationService = initializeConfiguration(rootSession);
        if (configurationService != null) {
            HippoServiceRegistry.registerService(configurationService, new Class[]{ConfigurationService.class, InternalConfigurationService.class});
        }
    }

    protected RepositoryConfig createRepositoryConfig() throws RepositoryException {
        return RepositoryConfig.create(getRepositoryConfigAsStream(), getRepositoryPath());
    }

    protected ConfigurationServiceImpl initializeConfiguration(final Session rootSession) throws RepositoryException {
        log.info("Initializing LocalHippoRepository");
        final SimpleCredentials credentials = new SimpleCredentials("system", new char[]{});
        final Session configurationServiceSession = DecoratorFactoryImpl.getSessionDecorator(rootSession.impersonate(credentials), credentials);
        migrateToV12IfNeeded(configurationServiceSession, false);

        return new ConfigurationServiceImpl().start(configurationServiceSession,() -> start(rootSession));
    }

    protected void start(final Session rootSession) throws RepositoryException {
        jackrabbitRepository.enableVirtualLayer(true);

        moduleManager = new ModuleManager(rootSession.impersonate(new SimpleCredentials("system", new char[]{})));
        moduleManager.start();

        nodeTypesChangeTracker = new NodeTypesChangeTracker(rootSession.impersonate(new SimpleCredentials("system", new char[]{})));
        nodeTypesChangeTracker.start();

        ((HippoSecurityManager) jackrabbitRepository.getSecurityManager()).configure();
    }

    protected void migrateToV12IfNeeded(final Session rootSession, final boolean dryRun) throws RepositoryException {
        new MigrateToV12(rootSession, (HippoNodeTypeRegistry)jackrabbitRepository.getNodeTypeRegistry(), dryRun)
                .migrateIfNeeded();
    }

    @Override
    public synchronized void close() {
        if (moduleManager != null) {
            moduleManager.stop();
            moduleManager = null;
        }
        if (nodeTypesChangeTracker != null) {
            nodeTypesChangeTracker.stop();
            nodeTypesChangeTracker = null;
        }
        if (configurationService != null) {
            HippoServiceRegistry.unregisterService(configurationService, ConfigurationService.class);
            configurationService.stop();
        }
        if (jackrabbitRepository != null) {
            try {
                jackrabbitRepository.shutdown();
                jackrabbitRepository = null;
            } catch (Exception ex) {
                log.error("Error while shutting down Jackrabbit", ex);
            }
        }
        repository = null;
        super.close();
    }

    @Override
    public InitializationProcessor getInitializationProcessor() {
        return new InitializationProcessorImpl();
    }

    @Override
    public ReferenceWorkspace getOrCreateReferenceWorkspace() throws RepositoryException {
        return new ReferenceWorkspaceImpl(jackrabbitRepository);
    }
}
