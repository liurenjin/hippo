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

import javax.jcr.Node;
import javax.jcr.Property;
import javax.jcr.RepositoryException;
import javax.jcr.nodetype.NodeType;
import javax.jcr.version.VersionHistory;
import javax.jcr.version.VersionManager;

import org.apache.jackrabbit.core.version.VersionHistoryRemover;
import org.hippoecm.repository.util.JcrUtils;

import static org.hippoecm.repository.HippoStdNodeType.NT_RELAXED;
import static org.hippoecm.repository.api.HippoNodeType.HIPPO_PATHS;
import static org.hippoecm.repository.api.HippoNodeType.NT_DOCUMENT;
import static org.hippoecm.repository.api.HippoNodeType.NT_HARDDOCUMENT;
import static org.hippoecm.repository.api.HippoNodeType.NT_HARDHANDLE;

public class HardDocumentUpdateVisitor extends BaseContentUpdateVisitor {

    @Override
    public boolean doUpdate(final Node node) throws RepositoryException {
        log.debug("Migrating {}", node.getPath());
        if (node.getParent().isNodeType(NT_HARDHANDLE)) {
            log.warn("Cannot run HardDocumentUpdater on {} because parent is still a hardhandle", node.getPath());
            return false;
        }
        if (!node.isNodeType(NT_HARDDOCUMENT)) {
            log.warn("Cannot run HardDocumentUpdater on {} because node is not a harddocument", node.getPath());
            return false;
        }
        if (hardDocumentIsInherited(node)) {
            log.warn("Cannot run HardDocumentUpdater on {} because harddocument is an inherited node type", node.getPath());
            return false;
        }
        try {
            final VersionHistory versionHistory = getVersionHistory(node);
            JcrUtils.ensureIsCheckedOut(node);
            removeHippoPaths(node);
            removeMixin(node, NT_HARDDOCUMENT);
            session.save();
            VersionHistoryRemover.removeVersionHistory(versionHistory);
        } finally {
            session.refresh(false);
        }
        return true;
    }

    /**
     * In case the node is not of type hippo:document the hippo:paths property has
     * no property definition after loading of the new CND. To fix this, we first
     * put the hippostd:relaxed mixin on the node so that the property has a definition.
     * Then we can remove the property.
     */
    private void removeHippoPaths(final Node node) throws RepositoryException {
        boolean removeRelaxed = false;
        if (!node.isNodeType(NT_DOCUMENT) && !node.isNodeType(NT_RELAXED)) {
            node.addMixin(NT_RELAXED);
            removeRelaxed = true;
        }
        final Property paths = JcrUtils.getPropertyIfExists(node, HIPPO_PATHS);
        if (paths != null) {
            paths.remove();
        }
        if (removeRelaxed) {
            node.removeMixin(NT_RELAXED);
        }
    }

    private VersionHistory getVersionHistory(final Node node) throws RepositoryException {
        final VersionManager versionManager = session.getWorkspace().getVersionManager();
        return versionManager.getVersionHistory(node.getPath());
    }

    private boolean hardDocumentIsInherited(Node node) throws RepositoryException {
        for (NodeType type : node.getMixinNodeTypes()) {
            if (type.getName().equals(NT_HARDDOCUMENT)) {
                return false;
            }
        }
        return true;
    }

}
