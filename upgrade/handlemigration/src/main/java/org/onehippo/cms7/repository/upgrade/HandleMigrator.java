/*
 * Copyright 2013 Hippo B.V. (http://www.onehippo.com)
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
package org.onehippo.cms7.repository.upgrade;


import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;

import javax.jcr.NoSuchWorkspaceException;
import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.Property;
import javax.jcr.PropertyIterator;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.SimpleCredentials;
import javax.jcr.Value;
import javax.jcr.nodetype.ConstraintViolationException;
import javax.jcr.nodetype.NodeType;
import javax.jcr.nodetype.NodeTypeManager;
import javax.jcr.nodetype.PropertyDefinition;
import javax.jcr.query.Query;
import javax.jcr.query.QueryManager;
import javax.jcr.version.Version;
import javax.jcr.version.VersionHistory;
import javax.jcr.version.VersionIterator;
import javax.jcr.version.VersionManager;

import org.apache.jackrabbit.api.JackrabbitWorkspace;
import org.hippoecm.repository.HippoStdNodeType;
import org.hippoecm.repository.api.HippoNodeIterator;
import org.hippoecm.repository.api.HippoNodeType;
import org.hippoecm.repository.api.HippoVersionManager;
import org.hippoecm.repository.decorating.RepositoryDecorator;
import org.hippoecm.repository.impl.DecoratorFactoryImpl;
import org.hippoecm.repository.jackrabbit.RepositoryImpl;
import org.hippoecm.repository.util.JcrUtils;
import org.hippoecm.repository.util.NodeIterable;
import org.hippoecm.repository.util.PropertyIterable;
import org.onehippo.repository.util.JcrConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.InputSource;

/**
 * Queries for all nodes of type hippo:hardhandle and for each node found:
 * - migrates the version history:
 *   - merge version histories into one
 * - migrates the handle:
 *   - remove hippo:hardhandle and replace with mix:referenceable
 *   - remove mix:versionable from draft and live variants and replace with mix:referenceable
 *
 * Because of a bug in the caching internals of the VersionManager it is not possible at the moment to remove
 * the obsoleted versions here. (After the hardhandle and harddocument mixins have been dropped from the
 * handle and respective variants the orphaned version histories cannot be cleaned because of cached references)
 */
class HandleMigrator {

    private static final Logger log = LoggerFactory.getLogger(HandleMigrator.class);
    private static final String HANDLE_MIGRATION_WORKSPACE = "handlemigration";
    private static final String JCR_FROZEN_PRIMARY_TYPE = "jcr:frozenPrimaryType";
    private static final String JCR_FROZEN_MIXIN_TYPES = "jcr:frozenMixinTypes";
    private static final String NT_FROZEN_NODE = "nt:frozenNode";
    private static final String ATTIC_PATH = "/content/attic";

    private static final int VERSIONS_RETAIN_COUNT = Integer.getInteger("org.onehippo.cms7.migration.versions_retain_count", Integer.MAX_VALUE);

    private final Session defaultSession;
    private Session migrationSession;
    private boolean cancelled = false;

    HandleMigrator(final Session session) {
        this.defaultSession = session;
    }

    void init() throws RepositoryException {
        createMigrationWorkspaceIfNotExists();
        migrationSession = loginToMigrationWorkspace();
        defaultSession.getWorkspace().getObservationManager().setUserData(HippoNodeType.HIPPO_IGNORABLE);
    }

    void cancel() {
        cancelled = true;
    }

    void shutdown() {
        if (defaultSession != null && defaultSession.isLive()) {
            defaultSession.logout();
        }
        if (migrationSession != null && migrationSession.isLive()) {
            migrationSession.logout();
        }
    }

    void migrate() {
        log.debug("Running handle migration tool");

        final HippoNodeIterator hardHandles = (HippoNodeIterator) getHardHandles();
        final long size = hardHandles.getTotalSize();

        log.debug("{} handles to migrate", size);

        if (size > 0) {
            try {
                int count = 0;
                final long progressInterval = (size + 99) / 100;
                for (Node handle : new NodeIterable(hardHandles)) {
                    if (cancelled) {
                        break;
                    }
                    try {
                        migrate(handle);
                        count++;
                        throttle(count);
                        if (count % 100 == 0) {
                            log.info("Migrated {} handles", count);
                        }
                        if (count % progressInterval == 0) {
                            long progress = Math.round(100.0 * count / size);
                            log.info("Progress: {} %", progress);
                        }
                    } catch (RepositoryException e) {
                        log.error("Failed to migrate " + JcrUtils.getNodePathQuietly(handle), e);
                    }
                }
                log.info("Finished migrating {} handles to new model", count);
            } finally {
                if (migrationSession != null) {
                    migrationSession.logout();
                }
            }
        }

    }

    private NodeIterator getHardHandles() {
        try {
            final QueryManager queryManager = defaultSession.getWorkspace().getQueryManager();
            final Query query = queryManager.createQuery("SELECT * FROM hippo:hardhandle ORDER BY jcr:name", Query.SQL);
            return query.execute().getNodes();
        } catch (RepositoryException e) {
            log.error("Failed to query for handles to migrate", e);
        }
        return null;
    }

    private void throttle(final int count) {
        if (count % 10 == 0) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException ignore) {
            }
        } else {
            try {
                Thread.sleep(5);
            } catch (InterruptedException ignore) {
            }
        }
    }

    void migrate(final Node handle) throws RepositoryException {
        log.debug("Migrating {}", handle.getPath());
        try {
            final List<Version> versions = getVersions(handle);
            if (!versions.isEmpty()) {
                migrateVersionHistory(handle, versions);
            }
            migrateHandle(handle);
        } finally {
            defaultSession.refresh(false);
            migrationSession.refresh(false);
        }
    }

    private void migrateHandle(final Node handle) throws RepositoryException {
        log.debug("Migrating handle {}", handle.getPath());
        for (Node variant : new NodeIterable(handle.getNodes(handle.getName()))) {
            if (!HippoStdNodeType.UNPUBLISHED.equals(JcrUtils.getStringProperty(variant, HippoStdNodeType.HIPPOSTD_STATE, null))) {
                removeMixin(variant, HippoNodeType.NT_HARDDOCUMENT);
            }
        }
        removeMixin(handle, HippoNodeType.NT_HARDHANDLE);
        defaultSession.save();
    }

    private void removeMixin(final Node node, final String mixin) throws RepositoryException {
        if (node.isNodeType(mixin)) {
            JcrUtils.ensureIsCheckedOut(node, false);
            final List<Reference> references = removeReferences(node);
            try {
                node.removeMixin(mixin);
                node.addMixin(JcrConstants.MIX_REFERENCEABLE);
            } finally {
                restoreReferences(references);
            }
        }
    }

    private void restoreReferences(final List<Reference> references) throws RepositoryException {
        for (Reference reference : references) {
            Node node = reference.getNode();
            String property = reference.getPropertyName();
            if (reference.getValue() != null) {
                node.setProperty(property, reference.getValue());
            } else {
                node.setProperty(property, reference.getValues());
            }
        }
    }

    private List<Reference> removeReferences(final Node handle) throws RepositoryException {
        final List<Reference> references = new LinkedList<>();
        for (Property property : new PropertyIterable(handle.getReferences())) {
            final Node node = property.getParent();
            JcrUtils.ensureIsCheckedOut(node, true);
            final String propertyName = property.getName();
            if (!HippoNodeType.HIPPO_RELATED.equals(propertyName)) {
                references.add(new Reference(property));
            }
            property.remove();
        }
        return references;
    }

    private void migrateVersionHistory(final Node handle, final List<Version> versions) throws RepositoryException {
        log.debug("Migrating version history of {}", handle.getPath());
        try {
            final Node tmp  = createTemporaryNode(versions);
            replayHistory(tmp, versions);
            setPreview(handle, tmp.getIdentifier());
        } finally {
            removeTemporaryNode();
        }
    }

    private void setPreview(final Node handle, final String identifier) throws RepositoryException {
        JcrUtils.ensureIsCheckedOut(handle, false);
        final String docPath = handle.getPath() + "/" + handle.getName();
        defaultSession.getWorkspace().clone(HANDLE_MIGRATION_WORKSPACE, "/tmp", docPath, true);
        final Node newPreview = defaultSession.getNodeByIdentifier(identifier);
        boolean deleted = handle.getPath().startsWith(ATTIC_PATH);
        if (deleted) {
            clear(newPreview);
            newPreview.setPrimaryType(HippoNodeType.NT_DELETED);
            defaultSession.save();
        } else {
            final Node oldPreview = getOldPreview(handle, identifier);
            if (oldPreview != null) {
                copy(oldPreview, newPreview);
                oldPreview.remove();
                defaultSession.save();
            }
        }
    }

    private Node getOldPreview(final Node handle, final String identifier) throws RepositoryException {
        for (Node document : new NodeIterable(handle.getNodes(handle.getName()))) {
            final String state = JcrUtils.getStringProperty(document, HippoStdNodeType.HIPPOSTD_STATE, null);
            if (HippoStdNodeType.UNPUBLISHED.equals(state) && !document.getIdentifier().equals(identifier)) {
                return document;
            }
        }
        return null;
    }

    private void replayHistory(final Node tmp, final List<Version> versions) throws RepositoryException {
        final HippoVersionManager versionManager = (HippoVersionManager)
                migrationSession.getWorkspace().getVersionManager();
        int count = 0;
        while (versions.size() > VERSIONS_RETAIN_COUNT) {
            versions.remove(0);
        }
        for (Version version : versions) {
            clear(tmp);
            copy(version.getFrozenNode(), tmp);
            tmp.setProperty(HippoStdNodeType.HIPPOSTD_STATE, HippoStdNodeType.UNPUBLISHED);
            migrationSession.save();
            versionManager.checkin("/tmp", version.getCreated());
            versionManager.checkout("/tmp");
            count++;
        }
        log.debug("Replayed {} versions", count);
    }

    private Node createTemporaryNode(final List<Version> versions) throws RepositoryException {
        final Version latest = versions.get(versions.size() - 1);
        final String primaryType = latest.getFrozenNode().getProperty(JCR_FROZEN_PRIMARY_TYPE).getString();
        final Node tmp = migrationSession.getRootNode().addNode("tmp", primaryType);
        tmp.addMixin(JcrConstants.MIX_VERSIONABLE);
        return tmp;
    }

    private void removeTemporaryNode() {
        try {
            while (migrationSession.nodeExists("/tmp")) {
                migrationSession.getNode("/tmp").remove();
            }
            migrationSession.save();
        } catch (RepositoryException e) {
            log.error("Error while removing temporary node", e);
        }
    }

    private void clear(final Node srcNode) throws RepositoryException {
        for (Property property : new PropertyIterable(srcNode.getProperties())) {
            if (!property.getDefinition().isProtected()) {
                property.remove();
            }
        }
        for (Node node : new NodeIterable(srcNode.getNodes())) {
            if (!node.getDefinition().isProtected()) {
                node.remove();
            }
        }
        for (NodeType nodeType : srcNode.getMixinNodeTypes()) {
            if (!nodeType.getName().equals(JcrConstants.MIX_VERSIONABLE)) {
                srcNode.removeMixin(nodeType.getName());
            }
        }
    }

    private void copy(final Node srcNode, final Node destNode) throws RepositoryException {
        final NodeTypeManager nodeTypeManager = srcNode.getSession().getWorkspace().getNodeTypeManager();

        final List<String> mixins = getMixinTypesToCopy(srcNode);
        NodeType[] nodeTypes = new NodeType[mixins.size() + 1];
        nodeTypes[0] = destNode.getPrimaryNodeType();
        int i = 1;
        for (String mixinType : mixins) {
            destNode.addMixin(mixinType);
            nodeTypes[i++] = nodeTypeManager.getNodeType(mixinType);
        }

        for (PropertyIterator iter = srcNode.getProperties(); iter.hasNext();) {
            Property prop = iter.nextProperty();
            if (prop.getName().startsWith("jcr:frozen") || prop.getName().startsWith("jcr:uuid") ||
                    prop.getName().equals(HippoNodeType.HIPPO_RELATED) ||
                    prop.getName().equals(HippoNodeType.HIPPO_COMPUTE) ||
                    prop.getName().equals(HippoNodeType.HIPPO_PATHS)) {
                continue;
            }
            if (destNode.hasProperty(prop.getName())) {
                continue;
            }

            if (prop.getDefinition().isMultiple()) {
                boolean isProtected = true;
                for (NodeType nodeType : nodeTypes) {
                    if (nodeType.canSetProperty(prop.getName(), prop.getValues())) {
                        isProtected = false;
                        break;
                    }
                }
                for (NodeType nodeType : nodeTypes) {
                    PropertyDefinition[] propDefs = nodeType.getPropertyDefinitions();
                    for (PropertyDefinition propDef : propDefs) {
                        if (propDef.getName().equals(prop.getName()) && propDef.isProtected())
                            isProtected = true;
                    }
                }
                if (!isProtected) {
                    destNode.setProperty(prop.getName(), prop.getValues(), prop.getType());
                }
            } else {
                boolean isProtected = true;
                for (NodeType nodeType : nodeTypes) {
                    if (nodeType.canSetProperty(prop.getName(), prop.getValue())) {
                        isProtected = false;
                        break;
                    }
                }
                for (NodeType nodeType : nodeTypes) {
                    PropertyDefinition[] propDefs = nodeType.getPropertyDefinitions();
                    for (PropertyDefinition propDef : propDefs) {
                        if (propDef.getName().equals(prop.getName()) && propDef.isProtected())
                            isProtected = true;
                    }
                }
                if (!isProtected) {
                    destNode.setProperty(prop.getName(), prop.getValue());
                }
            }
        }

        for (Node srcChild : new NodeIterable(srcNode.getNodes())) {
            Node destChild = null;
            final String relPath = srcChild.getName() + '[' + srcChild.getIndex() + ']';
            if (destNode.hasNode(relPath)) {
                destChild = destNode.getNode(relPath);
            } else {
                final String primaryNodeType = getPrimaryTypeToCopy(srcChild);
                try {
                    destChild = destNode.addNode(srcChild.getName(), primaryNodeType);
                } catch (ConstraintViolationException e) {
                    log.debug("Cannot add child {} of type {} to {}", new String[] { srcChild.getName(), primaryNodeType, destNode.getPath() });
                }
            }
            if (destChild != null) {
                copy(srcChild, destChild);
            }
        }

    }

    private List<String> getMixinTypesToCopy(final Node node) throws RepositoryException {
        final List<String> result = new ArrayList<String>();
        if (node.isNodeType(NT_FROZEN_NODE)) {
            if (node.hasProperty(JCR_FROZEN_MIXIN_TYPES)) {
                for (Value value : node.getProperty(JCR_FROZEN_MIXIN_TYPES).getValues()) {
                    if (value.getString().equals(HippoNodeType.NT_HARDDOCUMENT)) {
                        continue;
                    }
                    result.add(value.getString());
                }
            }
        } else {
            for (NodeType nodeType : node.getMixinNodeTypes()) {
                result.add(nodeType.getName());
            }
        }
        return result;
    }

    private String getPrimaryTypeToCopy(final Node node) throws RepositoryException {
        if (node.isNodeType(NT_FROZEN_NODE)) {
            return node.getProperty(JCR_FROZEN_PRIMARY_TYPE).getString();
        } else {
            return node.getPrimaryNodeType().getName();
        }
    }

    private List<Version> getVersions(final Node handle) throws RepositoryException {
        final List<Version> versions = new ArrayList<Version>();

        final VersionManager versionManager = defaultSession.getWorkspace().getVersionManager();
        final VersionHistory handleHistory = versionManager.getVersionHistory(handle.getPath());
        final VersionIterator allHandleVersions = handleHistory.getAllVersions();
        while (allHandleVersions.hasNext()) {
            final Version handleVersion = allHandleVersions.nextVersion();
            if (!handleVersion.getName().equals("jcr:rootVersion")) {
                final NodeIterator nodes = handleVersion.getNode("jcr:frozenNode").getNodes();
                while (nodes.hasNext()) {
                    final Node node = nodes.nextNode();
                    if (node.isNodeType("nt:versionedChild")) {
                        final String reference = node.getProperty("jcr:childVersionHistory").getString();
                        final VersionHistory documentHistory = (VersionHistory)
                                defaultSession.getNodeByIdentifier(reference);
                        final VersionIterator allDocumentVersions = documentHistory.getAllVersions();
                        while (allDocumentVersions.hasNext()) {
                            final Version documentVersion = allDocumentVersions.nextVersion();
                            if (!documentVersion.getName().equals("jcr:rootVersion")) {
                                versions.add(documentVersion);
                            }
                        }
                    }
                }
            }
        }

        Collections.sort(versions, new Comparator<Version>() {
            @Override
            public int compare(final Version v1, final Version v2) {
                try {
                    final Long t1 = v1.getCreated().getTimeInMillis();
                    final Long t2 = v2.getCreated().getTimeInMillis();
                    return t1.compareTo(t2);
                } catch (RepositoryException e) {
                    throw new RuntimeException(e);
                }
            }
        });
        return versions;
    }

    private void createMigrationWorkspaceIfNotExists() throws RepositoryException {
        RepositoryImpl repositoryImpl = (RepositoryImpl) RepositoryDecorator.unwrap(defaultSession.getRepository());
        try {
            repositoryImpl.getRootSession(HANDLE_MIGRATION_WORKSPACE);
        } catch (NoSuchWorkspaceException e) {
            final InputSource is = new InputSource(getClass().getResourceAsStream("handlemigration-workspace.xml"));
            ((JackrabbitWorkspace)repositoryImpl.getRootSession(null).getWorkspace()).createWorkspace(HANDLE_MIGRATION_WORKSPACE, is);
        }
    }

    private Session loginToMigrationWorkspace() throws RepositoryException {
        RepositoryImpl repositoryImpl = (RepositoryImpl) RepositoryDecorator.unwrap(defaultSession.getRepository());
        final SimpleCredentials credentials = new SimpleCredentials("system", new char[]{});
        return DecoratorFactoryImpl.getSessionDecorator(repositoryImpl.getRootSession(HANDLE_MIGRATION_WORKSPACE).impersonate(credentials), credentials);
    }

    static class Reference {
        private final Node node;
        private final String propertyName;
        private final Value value;
        private final Value[] values;

        Reference(Property property) throws RepositoryException {
            this.node = property.getParent();
            this.propertyName = property.getName();
            if (property.isMultiple()) {
                this.value = property.getValue();
                this.values = null;
            } else {
                this.value = null;
                this.values = property.getValues();
            }
        }

        Node getNode() {
            return node;
        }

        String getPropertyName() {
            return propertyName;
        }

        Value getValue() {
            return value;
        }

        Value[] getValues() {
            return values;
        }
    }

}