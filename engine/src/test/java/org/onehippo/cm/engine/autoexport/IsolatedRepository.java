/*
 *  Copyright 2017 Hippo B.V. (http://www.onehippo.com)
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
package org.onehippo.cm.engine.autoexport;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;

import javax.jcr.Credentials;
import javax.jcr.Session;
import javax.jcr.SimpleCredentials;

import org.apache.commons.io.FileUtils;
import org.onehippo.cm.ConfigurationService;
import org.onehippo.cm.engine.InternalConfigurationService;
import org.onehippo.cm.model.ConfigurationModel;
import org.onehippo.cms7.services.HippoServiceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.onehippo.cm.engine.autoexport.AutoExportConstants.SYSTEM_PROPERTY_AUTOEXPORT_ALLOWED;
import static org.onehippo.cm.model.Constants.PROJECT_BASEDIR_PROPERTY;

/**
 * Create a LocalHippoRepository running in its own isolated classloader. Running the repository in its own classloader
 * has the benefit that static singletons that are part of the Hippo code base are not shared between instances,
 * allowing users of this class to start multiple repositories after each other within the same JVM. Because of this
 * classloader isolation all access to the repository internals must be done using reflection. Only the JCR API is
 * shared with the test class so that test cases can use JCR without reflection.
 */
public class IsolatedRepository {

    private final static Logger log = LoggerFactory.getLogger(IsolatedRepository.class);

    private static final String dbport = System.getProperty(IsolatedRepository.class.getName() + ".dbport", "9001");
    private static final String repositoryConfig = "/org/hippoecm/repository/isolated-repository.xml";

    public static final Credentials CREDENTIALS = new SimpleCredentials("admin", "admin".toCharArray());

    private final File folder;
    private final File projectFolder;
    private final boolean autoExportEnabled;

    private URLClassLoader classLoader;
    private Object repository;
    private String repositoryPath;

    private String originalRepHome;
    private String originalRepoPath;
    private String originalProjectBaseDir;
    private String originalAutoexportAllowed;

    /**
     * Constructor that will start a repository in given folder with AutoExport disabled.
     */
    public IsolatedRepository(final File folder) {
        this.folder = folder;
        this.projectFolder = null;
        this.autoExportEnabled = false;
    }

    /**
     * Constructor that will start a repository in given folder AutoExport enabled, reading its data from the given
     * project folder.
     */
    public IsolatedRepository(final File folder, final File projectFolder) {
        this.folder = folder;
        this.projectFolder = projectFolder;
        this.autoExportEnabled = true;
    }

    public void startRepository() throws Exception {
        final File repositoryFolder = new File(folder, "repository");
        FileUtils.forceMkdir(repositoryFolder);
        repositoryPath = repositoryFolder.getAbsolutePath();

        originalRepHome = System.getProperty("rep.home", "");
        originalRepoPath = System.getProperty("repo.path", "");
        originalProjectBaseDir = System.getProperty(PROJECT_BASEDIR_PROPERTY, "");
        originalAutoexportAllowed = System.getProperty(SYSTEM_PROPERTY_AUTOEXPORT_ALLOWED, "");

        System.setProperty("rep.home", repositoryPath);
        System.setProperty("repo.path", "");
        if (autoExportEnabled) {
            System.setProperty(PROJECT_BASEDIR_PROPERTY, projectFolder.getAbsolutePath());
            System.setProperty(SYSTEM_PROPERTY_AUTOEXPORT_ALLOWED, "true");
        } else {
            System.setProperty(SYSTEM_PROPERTY_AUTOEXPORT_ALLOWED, "false");
        }

        try {
            repository = create(repositoryPath, repositoryConfig);
        } catch (Exception e) {
            restoreSystemProperties();
            throw e;
        }
    }

    private void restoreSystemProperties() {
        System.setProperty("rep.home", originalRepHome);
        System.setProperty("repo.path", originalRepoPath);
        System.setProperty(PROJECT_BASEDIR_PROPERTY, originalProjectBaseDir);
        System.setProperty(SYSTEM_PROPERTY_AUTOEXPORT_ALLOWED, originalAutoexportAllowed);
    }

    public ClassLoader getRepositoryClassLoader() {
        return classLoader;
    }

    private Object create(final String repoPath, final String repoConfig) throws Exception {
        final URLClassLoader contextClassLoader = (URLClassLoader) Thread.currentThread().getContextClassLoader();
        classLoader = new RepositoryClassLoader(contextClassLoader.getURLs(), contextClassLoader);
        Thread.currentThread().setContextClassLoader(classLoader);
        try {
            return Class.forName("org.hippoecm.repository.LocalHippoRepository", true, classLoader)
                    .getMethod("create", String.class, String.class)
                    .invoke(null, repoPath, repoConfig);
        } finally {
            Thread.currentThread().setContextClassLoader(contextClassLoader);
        }
    }

    public Session login(final Credentials credentials) {
        try {
            return (Session) repository.getClass().getMethod("login", Credentials.class).invoke(repository, credentials);
        } catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
            log.error("Failed to log in session: " + e);
        }
        return null;
    }

    public void runSingleAutoExportCycle() throws Exception {
        final URLClassLoader contextClassLoader = (URLClassLoader) Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(classLoader);
        try {
            final Class configurationServiceClass = Class.forName(ConfigurationService.class.getName(), true, classLoader);
            final Class internalConfigurationServiceClass = Class.forName(InternalConfigurationService.class.getName(), true, classLoader);

            final Object service = Class.forName(HippoServiceRegistry.class.getName(), true, classLoader)
                    .getMethod("getService", Class.class)
                    .invoke(null, configurationServiceClass);
            final Object internalService = internalConfigurationServiceClass.cast(service);
            internalConfigurationServiceClass.getMethod("runSingleAutoExportCycle").invoke(internalService);
        } finally {
            Thread.currentThread().setContextClassLoader(contextClassLoader);
        }
    }

    public ConfigurationModel getRuntimeConfigurationModel() throws Exception {
        final URLClassLoader contextClassLoader = (URLClassLoader) Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(classLoader);
        try {
            final Class configurationServiceClass = Class.forName(ConfigurationService.class.getName(), true, classLoader);

            final Object service = Class.forName(HippoServiceRegistry.class.getName(), true, classLoader)
                    .getMethod("getService", Class.class)
                    .invoke(null, configurationServiceClass);
            return (ConfigurationModel) configurationServiceClass.getMethod("getRuntimeConfigurationModel").invoke(service);
        } finally {
            Thread.currentThread().setContextClassLoader(contextClassLoader);
        }
    }

    public void stop() throws Exception {
        if (repository != null) {
            close(repository);
        }
        restoreSystemProperties();
    }

    private void close(final Object repo) {
        try {
            repo.getClass().getMethod("close").invoke(repo);
        } catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
            log.error("Failed to close repository: " + e);
        }
    }

    private static class RepositoryClassLoader extends URLClassLoader {

        private final URLClassLoader shared;

        RepositoryClassLoader(final URL[] urls, final URLClassLoader shared) {
            super(urls, null);
            this.shared = shared;
        }

        @Override
        public Class<?> loadClass(final String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith("javax.jcr") || name.startsWith("org.onehippo.cm.model")) {
                return shared.loadClass(name);
            }
            return super.loadClass(name, resolve);
        }
    }

}
