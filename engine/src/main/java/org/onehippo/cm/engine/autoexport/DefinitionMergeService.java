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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.jcr.Node;
import javax.jcr.PathNotFoundException;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import org.apache.commons.collections4.trie.PatriciaTrie;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.StopWatch;
import org.hippoecm.repository.util.NodeIterable;
import org.onehippo.cm.engine.JcrContentExporter;
import org.onehippo.cm.model.definition.Definition;
import org.onehippo.cm.model.definition.NamespaceDefinition;
import org.onehippo.cm.model.impl.ConfigurationModelImpl;
import org.onehippo.cm.model.impl.ModuleImpl;
import org.onehippo.cm.model.impl.definition.AbstractDefinitionImpl;
import org.onehippo.cm.model.impl.definition.ConfigDefinitionImpl;
import org.onehippo.cm.model.impl.definition.ContentDefinitionImpl;
import org.onehippo.cm.model.impl.definition.NamespaceDefinitionImpl;
import org.onehippo.cm.model.impl.path.JcrPath;
import org.onehippo.cm.model.impl.path.JcrPathSegment;
import org.onehippo.cm.model.impl.source.ConfigSourceImpl;
import org.onehippo.cm.model.impl.source.ContentSourceImpl;
import org.onehippo.cm.model.impl.source.SourceImpl;
import org.onehippo.cm.model.impl.tree.ConfigurationItemImpl;
import org.onehippo.cm.model.impl.tree.ConfigurationNodeImpl;
import org.onehippo.cm.model.impl.tree.ConfigurationPropertyImpl;
import org.onehippo.cm.model.impl.tree.ConfigurationTreeBuilder;
import org.onehippo.cm.model.impl.tree.DefinitionItemImpl;
import org.onehippo.cm.model.impl.tree.DefinitionNodeImpl;
import org.onehippo.cm.model.impl.tree.DefinitionPropertyImpl;
import org.onehippo.cm.model.impl.tree.ValueImpl;
import org.onehippo.cm.model.source.Source;
import org.onehippo.cm.model.source.SourceType;
import org.onehippo.cm.model.tree.DefinitionItem;
import org.onehippo.cm.model.tree.DefinitionNode;
import org.onehippo.cm.model.tree.PropertyOperation;
import org.onehippo.cm.model.util.FilePathUtils;
import org.onehippo.cm.model.util.PatternSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.util.function.Predicate.isEqual;
import static org.apache.jackrabbit.JcrConstants.JCR_PRIMARYTYPE;
import static org.onehippo.cm.engine.autoexport.AutoExportConstants.DEFAULT_MAIN_CONFIG_FILE;
import static org.onehippo.cm.model.Constants.YAML_EXT;
import static org.onehippo.cm.model.definition.DefinitionType.NAMESPACE;
import static org.onehippo.cm.model.tree.ConfigurationItemCategory.SYSTEM;
import static org.onehippo.cm.model.tree.PropertyOperation.OVERRIDE;
import static org.onehippo.cm.model.tree.PropertyOperation.REPLACE;

public class DefinitionMergeService {

    private static final Logger log = LoggerFactory.getLogger(DefinitionMergeService.class);

    private static class ModuleMapping {
        final String mvnPath;
        final Collection<String> repositoryPaths;

        final PatternSet pathPatterns;

        ModuleMapping(final String mvnPath, final Collection<String> repositoryPaths) {
            this.mvnPath = mvnPath;
            this.repositoryPaths = repositoryPaths;

            List<String> patterns = new ArrayList<>();
            for (String repositoryPath : repositoryPaths) {
                patterns.add(repositoryPath);
                patterns.add(repositoryPath.equals("/") ? "/**" : repositoryPath + "/**");
            }
            pathPatterns = new PatternSet(patterns);
        }

        boolean matchesPath(JcrPath path) {
            return pathPatterns.matches(path.toString());
        }
    }

    private ModuleMapping defaultModuleMapping;
    private final HashMap<String, ModuleMapping> moduleMappings = new HashMap<>();

    /**
     * @param autoExportConfig the current auto-export module config, which includes module:path mappings and exclusions
     */
    public DefinitionMergeService(final AutoExportConfig autoExportConfig) {
        // preprocess config mapping paths to moduleMapping objects
        // note: this is very similar to the old auto-export EventProcessor init
        for (Map.Entry<String, Collection<String>> entry : autoExportConfig.getModules().entrySet()) {
            String modulePath = entry.getKey();
            Collection<String> repositoryPaths = entry.getValue();
            ModuleMapping mapping = new ModuleMapping(modulePath, repositoryPaths);
            if (repositoryPaths.contains("/")) {
                defaultModuleMapping = mapping;
            } else {
                moduleMappings.put(mapping.mvnPath, mapping);
            }
        }
    }

    /**
     * Given a baseline B, a set of changes to that baseline in the current JCR runtime (R) R∆B expressed as a
     * ModuleImpl, and a set of destination modules Sm, produce a new version of the destination modules Sm' such that
     * B-Sm+Sm' = B+R∆B. Also, make a best effort for Sources and Definitions in Sm' to be as minimally changed compared
     * to the corresponding Sources and Definitions in Sm as possible (for stable output), and for any new Sources and
     * Definitions to follow the sorting schemes encoded in org.onehippo.cms7.autoexport.LocationMapper.
     * @param changes R∆B expressed as a Module with one ConfigSource with zero-or-more Definitions and zero-or-more ContentSources
     * @param baseline the currently stored configuration baseline B
     * @param jcrSession JCR session to be used for regenerating changed content sources
     * @return a new version of each toMerge module, if revisions are necessary
     */
    public Collection<ModuleImpl> mergeChangesToModules(final ModuleImpl changes,
                                                        final EventJournalProcessor.Changes contentChanges,
                                                        final ConfigurationModelImpl baseline,
                                                        final Session jcrSession) {
        final StopWatch stopWatch = new StopWatch();
        stopWatch.start();

        // find the modules that are configured for auto-export and also have a mvnPath indicating a source location
        final Set<String> configuredMvnPaths = new HashSet<>();
        configuredMvnPaths.addAll(moduleMappings.keySet());
        configuredMvnPaths.add(defaultModuleMapping.mvnPath);

        final HashMap<String, ModuleImpl> toExport = new HashMap<>();
        for (final ModuleImpl m : baseline.getModules()) {
            if (m.getMvnPath() != null && configuredMvnPaths.contains(m.getMvnPath())) {
                toExport.put(m.getMvnPath(), m);
            }
        }

        log.debug("Merging changes to modules: {}", toExport.values());
        log.debug("Content added: {} changed: {}", contentChanges.getAddedContent(), contentChanges.getChangedContent());

        // make sure the changes module has all the definitions nicely sorted
        changes.build();

        // handle namespaces before rebuilding, since we want any validation to happen after this
        for (final NamespaceDefinitionImpl nsd : changes.getNamespaceDefinitions()) {
            mergeNamespace(nsd, toExport, baseline);
        }

        // TODO does it make sense to auto-export webfilebundle definitions?

        // Registry for paths of nodes for which the child nodes need to be reordered
        final Set<JcrPath> reorderRegistry = new HashSet<>();

        // merge config changes
        // ConfigDefinitions are already sorted by root path
        for (final ConfigDefinitionImpl change : changes.getConfigDefinitions()) {
            // run the full and complex merge logic, recursively
            mergeConfigDefinitionNode(change.getNode(), toExport, reorderRegistry, baseline);
        }

        // merge content changes
        mergeContentDefinitions(contentChanges, toExport, reorderRegistry, jcrSession);

        final Map<JcrPath, String> contentOrderBefores = new HashMap<>();
        reorder(reorderRegistry, baseline, toExport, contentOrderBefores, jcrSession);

        exportChangedContentSources(toExport, contentOrderBefores, jcrSession);

        for (ModuleImpl module : toExport.values()) {
            module.build();
        }

        stopWatch.stop();
        log.info("Completed full auto-export merge in {}", stopWatch.toString());

        return toExport.values();
    }

    private void reorder(final Set<JcrPath> reorderRegistry,
                         final ConfigurationModelImpl currentModel,
                         final HashMap<String, ModuleImpl> toExport,
                         final Map<JcrPath, String> contentOrderBefores,
                         final Session session) {

        final List<String> sortedModules = new ArrayList<>();
        currentModel.getModulesStream().forEach((module) -> sortedModules.add(module.getName()));

        for (final JcrPath path : reorderRegistry) {
            try {
                final Node jcrNode = session.getNode(path.toString());
                boolean orderingIsRelevant = jcrNode.getPrimaryNodeType().hasOrderableChildNodes();

                final ConfigurationNodeImpl configurationNode = currentModel.resolveNode(path);
                if (configurationNode != null) {
                    orderingIsRelevant &= (configurationNode.getIgnoreReorderedChildren() == null
                            || !configurationNode.getIgnoreReorderedChildren());
                }

                if (orderingIsRelevant) {
                    reorder(path, jcrNode, configurationNode, toExport, contentOrderBefores, sortedModules);
                }
            } catch (PathNotFoundException ignore) {
                log.warn("Could not find path '{}', skipping", path.toString());
                return;
            } catch (RepositoryException e) {
                throw new IllegalStateException("Unexpected RepositoryException while reordering node " + path, e);
            }
        }
    }

    private void reorder(final JcrPath path,
                         final Node jcrNode,
                         final ConfigurationNodeImpl configurationNode,
                         final HashMap<String, ModuleImpl> toExport,
                         final Map<JcrPath, String> contentOrderBefores,
                         final List<String> sortedModules) throws RepositoryException {

        log.debug("Reordering node {}", path.toString());

        final List<JcrPathSegment> expected = getExpectedOrder(jcrNode, configurationNode);
        final List<JcrPathSegment> intermediate = new LinkedList<>();

        final Holders configHolders = createConfigHolders(configurationNode, expected, toExport, sortedModules);
        final Holders contentHolders = createContentHolders(path, expected, contentOrderBefores, toExport);

        /* if parent == config
         *   if there are upstream content items
         *     atm, it is not 100% guaranteed in which order those are applied, assume ordering from jcr is
         *       correct (upstream cannot be reordered anyway)
         *   if there are only local content items
         *     we know everything that contributes to the parent
         *     any node names that are present other than that, give a warning and remove from expected
         * if parent == content
         *   if there are upstream content items
         *     atm, it is not 100% guaranteed in which order those are applied, assume ordering from jcr is
         *       correct (upstream cannot be reordered anyway)
         *   if there are only local content items
         *     we know everything that contributes to the parent
         *     any node names that are present other than that, give a warning and remove from expected
         */

        configHolders.upstream.forEach((holder) -> holder.apply(expected, intermediate));

        updateStateForUnorderedUpstream(path, expected, toExport, sortedModules, intermediate, configHolders.local);

        configHolders.local.forEach((holder) -> holder.apply(expected, intermediate));

        configHolders.cleanUp();

        contentHolders.local.forEach((holder) -> holder.apply(expected, intermediate));

        contentHolders.cleanUp();
    }

    /**
     * Inspects the names in intermediate to validate they are in the correct order. Any names that are not in the
     * correct order are removed from intermediate and a new holder is created for it. As the last step, the list of
     * holders is sorted.
     * @param path          the path to the node being sorted
     * @param expected      the expected ordering
     * @param toExport      the set of Modules being merged and eventually exported
     * @param sortedModules the list of all modeuls, sorted according to their processing order
     * @param intermediate  (in & out) the intermediate order of the sub nodes of the given path
     * @param holders       (in & out) the set of holders for the given path
     */
    private void updateStateForUnorderedUpstream(final JcrPath path,
                                                 final List<JcrPathSegment> expected,
                                                 final HashMap<String, ModuleImpl> toExport,
                                                 final List<String> sortedModules,
                                                 final List<JcrPathSegment> intermediate,
                                                 final List<OrderBeforeHolder> holders) {

        // if there are 0 or just 1 upstream items in intermediate, they are in the correct order
        if (intermediate.size() < 2) {
            return;
        }

        final List<JcrPathSegment> incorrectlyOrdered = new ArrayList<>();
        int lastCorrectIndex = expected.indexOf(intermediate.get(0));
        for (int i = 1; i < intermediate.size(); i++) {
            final JcrPathSegment current = intermediate.get(i);
            final int currentIndex = expected.indexOf(current);
            if (currentIndex > lastCorrectIndex) {
                lastCorrectIndex = currentIndex;
            } else {
                incorrectlyOrdered.add(current);
            }
        }

        intermediate.removeAll(incorrectlyOrdered);

        for (final JcrPathSegment name : incorrectlyOrdered) {
            final DefinitionNodeImpl node = getOrCreateLocalDef(path.resolve(name), toExport);
            final int moduleIndex = sortedModules.indexOf(node.getDefinition().getSource().getModule().getFullName());
            holders.add(new LocalConfigOrderBeforeHolder(moduleIndex, node, toExport));
        }

        holders.sort(Comparator.naturalOrder());
    }

    private List<JcrPathSegment> getExpectedOrder(final Node jcrNode, final ConfigurationNodeImpl configurationNode)
            throws RepositoryException {

        final List<JcrPathSegment> expectedOrder = new ArrayList<>();

        for (final Node child : new NodeIterable(jcrNode.getNodes())) {
            final JcrPathSegment segment = JcrPathSegment.get(child);
            if (configurationNode != null) {
                if (configurationNode.getChildNodeCategory(segment.forceIndex().toString()) == SYSTEM) {
                    log.info("Not including node '{}' while reordering '{}'; the node is category 'system'",
                            segment.toString(), jcrNode.getPath());
                    continue;
                }
            }
            expectedOrder.add(segment);
        }

        return expectedOrder;
    }

    abstract class OrderBeforeHolder implements Comparable {
        public void apply(final List<JcrPathSegment> expected, final List<JcrPathSegment> intermediate) {
            final JcrPathSegment myName = getDefinitionNode().getJcrName();

            if (intermediate.size() == 0) {
                intermediate.add(myName);
                return;
            }

            // start by assuming myName must be added at the end of the list
            int position = intermediate.size();
            String orderBefore = null;

            // iterate through the elements in expected from the last to the first
            while (position > 0) {
                final JcrPathSegment candidate = intermediate.get(position - 1);

                // if myName needs to be sorted after candidate, we've found the right place for myName
                if (expected.indexOf(candidate) < expected.indexOf(myName)) {
                    break;
                }

                // myName needs to be added at least before candidate
                position--;
                orderBefore = candidate.toString();
            }

            intermediate.add(position, myName);
            setOrderBefore(orderBefore);
        }
        abstract DefinitionNodeImpl getDefinitionNode();
        abstract void setOrderBefore(final String orderBefore);
        abstract void cleanUp();
    }

    class LocalConfigOrderBeforeHolder extends OrderBeforeHolder {
        protected final int moduleIndex;
        protected final DefinitionNodeImpl definitionNode;
        protected final HashMap<String, ModuleImpl> toExport;
        protected int siblingIndex;
        protected final String originalOrderBefore;
        LocalConfigOrderBeforeHolder(final int moduleIndex, final DefinitionNodeImpl definitionNode,
                                     final HashMap<String, ModuleImpl> toExport) {
            this(moduleIndex, definitionNode, toExport, true);
        }
        LocalConfigOrderBeforeHolder(final int moduleIndex, final DefinitionNodeImpl definitionNode,
                                     final HashMap<String, ModuleImpl> toExport, final boolean isLocal) {
            this.moduleIndex = moduleIndex;
            this.definitionNode = definitionNode;
            this.toExport = toExport;
            this.siblingIndex = -1;
            this.originalOrderBefore = definitionNode.getOrderBefore();

            if (isLocal) {
                definitionNode.setOrderBefore(null);
            }
        }
        @Override
        public int compareTo(final Object object) {
            if (object == null) {
                return -1;
            }
            if (object == this) {
                return 0;
            }
            if (object instanceof LocalConfigOrderBeforeHolder) {
                final LocalConfigOrderBeforeHolder other = (LocalConfigOrderBeforeHolder) object;

                int result = Integer.compare(this.getModuleIndex(), other.getModuleIndex());
                if (result != 0) {
                    return result;
                }

                result = this.getRootPath().compareTo(other.getRootPath());
                if (result != 0) {
                    return result;
                }

                return Integer.compare(this.getSiblingIndex(), other.getSiblingIndex());
            }
            return -1;
        }
        int getModuleIndex() {
            return moduleIndex;
        }
        JcrPath getRootPath() {
            return JcrPath.get(definitionNode.getDefinition().getRootPath());
        }
        int getSiblingIndex() {
            if (siblingIndex == -1 && !definitionNode.isRoot()) {
                int index = -1;
                for (final String name : definitionNode.getParent().getNodes().keySet()) {
                    index++;
                    if (definitionNode.getJcrName().equals(JcrPathSegment.get(name))) {
                        break;
                    }
                }
                siblingIndex = index;
            }
            return siblingIndex;
        }
        @Override
        DefinitionNodeImpl getDefinitionNode() {
            return definitionNode;
        }
        @Override
        void setOrderBefore(final String orderBefore) {
            definitionNode.setOrderBefore(orderBefore);
        }
        @Override
        void cleanUp() {
            if (!StringUtils.equals(definitionNode.getOrderBefore(), originalOrderBefore)) {
                definitionNode.getDefinition().getSource().markChanged();
            }
            if (definitionNode.isEmpty()) {
                removeOneDefinitionItem(definitionNode, new ArrayList<>(), toExport);
            }
        }
    }
    class UpstreamConfigOrderBeforeHolder extends LocalConfigOrderBeforeHolder {
        UpstreamConfigOrderBeforeHolder(final int moduleIndex, final DefinitionNodeImpl definitionNode,
                                        final HashMap<String, ModuleImpl> toExport) {
            super(moduleIndex, definitionNode, toExport, false);
        }
        @Override
        public void apply(final List<JcrPathSegment> expected, final List<JcrPathSegment> intermediate) {
            // todo: add logic for delayed ordering mechanism or decide to remove it
            if (intermediate.size() == 0 || "".equals(definitionNode.getOrderBefore())) {
                intermediate.add(0, definitionNode.getJcrName());
                return;
            }
            if (definitionNode.getOrderBefore() == null) {
                intermediate.add(definitionNode.getJcrName());
                return;
            }
            final int position = intermediate.indexOf(JcrPathSegment.get(definitionNode.getOrderBefore()));
            if (position == -1) {
                // if the target cannot be found, we are in a weird situation as the model should not have loaded in
                // the first place, log an error but continue
                log.error("Cannot find order-before target '{}' for node '{}' from '{}', ordering node as last",
                        definitionNode.getOrderBefore(), definitionNode.getPath(), definitionNode.getSourceLocation());
                intermediate.add(definitionNode.getJcrName());
            } else {
                intermediate.add(position, definitionNode.getJcrName());
            }
        }
        @Override
        void setOrderBefore(final String orderBefore) {
            log.error("Unexpected call to setOrderBefore with value '{}' for node '{}' from '{}'", orderBefore,
                    definitionNode.getPath(), definitionNode.getSourceLocation());
        }
        @Override
        void cleanUp() {
            // intentionally empty
        }

    }

    final class ContentOrderBeforeHolder extends OrderBeforeHolder {
        private final ContentDefinitionImpl contentDefinition;
        private final Map<JcrPath, String> contentOrderBefores;
        ContentOrderBeforeHolder(final ContentDefinitionImpl contentDefinition, final Map<JcrPath, String> contentOrderBefores) {
            this.contentDefinition = contentDefinition;
            this.contentOrderBefores = contentOrderBefores;
        }
        @Override
        public int compareTo(final Object object) {
            if (object == null) {
                return -1;
            }
            if (object == this) {
                return 0;
            }
            if (object instanceof ContentOrderBeforeHolder) {
                final ContentOrderBeforeHolder other = (ContentOrderBeforeHolder) object;
                return this.getContentRoot().compareTo(other.getContentRoot());
            }
            return 1;
        }
        JcrPath getContentRoot() {
            return JcrPath.get(contentDefinition.getRootPath());
        }
        @Override
        DefinitionNodeImpl getDefinitionNode() {
            return contentDefinition.getNode();
        }
        @Override
        void setOrderBefore(final String orderBefore) {
            contentOrderBefores.put(getContentRoot(), orderBefore);
        }
        @Override
        void cleanUp() {
            // Unfortunately, it is not yet possible to detect a changed order-before. Always mark the source as
            // changed, triggering a re-export.
            contentDefinition.getSource().markChanged();
        }
    }

    /**
     * Registry of definition nodes for which the parent must have its child definitions reordered.
     */
    final class ReorderParentRegistry {
        final Map<DefinitionNodeImpl, List<DefinitionNodeImpl>> registry = new HashMap<>();
        public void add(final DefinitionNodeImpl node) {
            if (node.isRoot()) {
                return; // definition root does not have a parent
            }
            registry.computeIfAbsent(node.getParent(), def -> new ArrayList<>())
                    .add(node);
        }
        public void reorder() {
            registry.forEach((parent, children) -> {
                log.debug("Reordering nodes within definition at path '{}' rooted at '{}' in file '{}'",
                        parent.getPath(), parent.getDefinition().getRootPath(), parent.getSourceLocation());
                // Reorder by ordering the children in reverse order; note that there might be more definitions in
                // parent than just the reorder children (deletes, nodes with only .meta:category, etc.)
                for (int i = children.size(); i > 1; i--) {
                    parent.orderBefore(children.get(i - 2), children.get(i - 1));
                }
                parent.getDefinition().getSource().markChanged();
            });
        }
    }

    static class Holders {
        final List<OrderBeforeHolder> upstream = new ArrayList<>();
        final List<OrderBeforeHolder> local = new ArrayList<>();
        public void cleanUp() {
            upstream.forEach(OrderBeforeHolder::cleanUp);
            local.forEach(OrderBeforeHolder::cleanUp);
        }
    }

    private Holders createConfigHolders(
            final ConfigurationNodeImpl configurationNode,
            final List<JcrPathSegment> expected,
            final HashMap<String, ModuleImpl> toExport,
            final List<String> sortedModules) {

        final Holders holders = new Holders();

        if (configurationNode == null) {
            return holders;
        }

        final Set<DefinitionNodeImpl> reorderParents = new HashSet<>();

        for (final JcrPathSegment childName : expected) {
            final ConfigurationNodeImpl childNode = configurationNode.getNode(childName);
            if (childNode == null) {
                continue;
            }
            for (final DefinitionNodeImpl childDefNode : childNode.getDefinitions()) {
                final boolean isLocal = isLocalDef(toExport).test(childDefNode);

                if (isLocal && !childDefNode.isRoot()) {
                    reorderParents.add(childDefNode.getParent());
                }

                boolean influencesOrdering = false;
                final DefinitionPropertyImpl primaryTypeProperty = childDefNode.getProperty(JCR_PRIMARYTYPE);
                if (primaryTypeProperty != null) {
                    influencesOrdering = primaryTypeProperty.getOperation() == REPLACE; // ignore OVERRIDE for ordering
                }
                if (childDefNode.getOrderBefore() != null) {
                    if (isLocal) {
                        // Remove all local order-before definitions, if needed, they will be recreated in a later step
                        childDefNode.setOrderBefore(null);
                        if (childDefNode.isEmpty()) {
                            removeOneDefinitionItem(childDefNode, new ArrayList<>(), toExport);
                        }
                    } else {
                        influencesOrdering = true;
                    }
                }

                if (influencesOrdering) {
                    final String moduleName = childDefNode.getDefinition().getSource().getModule().getFullName();
                    final int moduleIndex = sortedModules.indexOf(moduleName);
                    if (isLocal) {
                        holders.local.add(new LocalConfigOrderBeforeHolder(moduleIndex, childDefNode, toExport));
                    } else {
                        holders.upstream.add(new UpstreamConfigOrderBeforeHolder(moduleIndex, childDefNode, toExport));
                    }
                }
            }
        }

        reorderWithinParent(reorderParents, expected);

        holders.local.sort(Comparator.naturalOrder());
        holders.upstream.sort(Comparator.naturalOrder());

        return holders;
    }

    private void reorderWithinParent(final Set<DefinitionNodeImpl> parents, final List<JcrPathSegment> expected) {
        for (final DefinitionNodeImpl parent : parents) {
            log.debug("Reordering nodes within definition at path '{}' rooted at '{}' in file '{}'",
                    parent.getPath(), parent.getDefinition().getRootPath(), parent.getSourceLocation());
            parent.reorder(expected);
            parent.getDefinition().getSource().markChanged();
        }
    }

    private Holders createContentHolders(
            final JcrPath path,
            final List<JcrPathSegment> expected,
            final Map<JcrPath, String> contentOrderBefores,
            final HashMap<String, ModuleImpl> toExport) {

        final Holders holders = new Holders();
        final SortedMap<JcrPath, ContentDefinitionImpl> existingSourcesByNodePath =
                collectContentSourcesByNodePath(toExport);

        for (final JcrPathSegment childName : expected) {
            final JcrPath childPath = path.resolve(childName);
            final ContentDefinitionImpl contentDefinition = existingSourcesByNodePath.get(childPath);
            if (contentDefinition != null) {
                final ContentOrderBeforeHolder holder =
                        new ContentOrderBeforeHolder(contentDefinition, contentOrderBefores);
                if (isLocalDef(toExport).test(contentDefinition.getNode())) {
                    holders.local.add(holder);
                } else {
                    holders.upstream.add(holder);
                }
            }
        }

        holders.local.sort(Comparator.naturalOrder());
        holders.upstream.sort(Comparator.naturalOrder());

        return holders;
    }

    private void exportChangedContentSources(final HashMap<String, ModuleImpl> toExport,
                                             final Map<JcrPath, String> contentOrderBefores,
                                             final Session jcrSession) {

        final Set<JcrPath> allContentPaths = collectContentSourcesByNodePath(toExport).keySet();

        getChangedContentSourcesStream(toExport).forEach(source -> {
            final ContentDefinitionImpl def = source.getDefinition();
            final JcrPath defPath = def.getNode().getJcrPath();

            // exclude all paths that have their own sources
            final Set<String> excludedPaths = allContentPaths.stream()
                    // (but don't exclude what we're exporting!)
                    .filter(isEqual(defPath).negate())
                    .map(JcrPath::toString).collect(toImmutableSet());

            try {
                new JcrContentExporter().exportNode(
                        jcrSession.getNode(defPath.toString()), def, true, contentOrderBefores.get(defPath), excludedPaths);
            }
            catch (RepositoryException e) {
                throw new RuntimeException("Exception while regenerating changed content source file for " + defPath, e);
            }
        });
    }

    /**
     * Merge a single namespace definition into the appropriate toExport module.
     * @param nsd the definition to merge
     * @param toExport modules that may be merged into
     * @param baseline a complete ConfigurationModel baseline for context
     */
    protected void mergeNamespace(final NamespaceDefinitionImpl nsd, final HashMap<String, ModuleImpl> toExport, final ConfigurationModelImpl baseline) {
        // find the corresponding definition by namespace prefix -- only one is permitted
        final Optional<NamespaceDefinitionImpl> found = baseline.getNamespaceDefinitions().stream()
                .filter(namespaceDefinition -> namespaceDefinition.getPrefix().equals(nsd.getPrefix()))
                .findFirst();

        // clone the CndPath Value which retains a "foreign source" back-reference for use later when copying data
        final ValueImpl cndPath = nsd.getCndPath().clone();

        if (found.isPresent()) {
            // this is an update to an existing namespace def
            // find the corresponding source by path
            final Source oldSource = found.get().getSource();

            // this source will have a reference to a module in the baseline, not our clones, so lookup by mvnPath
            final ModuleImpl newModule = toExport.get(((ModuleImpl)oldSource.getModule()).getMvnPath());

            // short-circuit this loop iteration if we cannot create a valid merged definition
            if (newModule == null) {
                log.error("Cannot merge a namespace: {} that belongs to an upstream module", nsd.getPrefix());
                return;
            }

            final String oldSourcePath = oldSource.getPath();
            final SourceImpl newSource = newModule.getModifiableSources().stream()
                    .filter(SourceType.CONFIG::isOfType)
                    .filter(source -> source.getPath().equals(oldSourcePath))
                    .findFirst().get();

            log.debug("Merging namespace definition: {} to module: {} aka {} in file {}",
                    nsd.getPrefix(), newModule.getMvnPath(), newModule.getFullName(), newSource.getPath());

            final List<AbstractDefinitionImpl> defs = newSource.getModifiableDefinitions();
            for (int i = 0; i < defs.size(); i++) {
                Definition def = defs.get(i);

                // find the corresponding def within the source by type and namespace prefix
                if (def.getType().equals(NAMESPACE) && ((NamespaceDefinition)def).getPrefix().equals(nsd.getPrefix())) {
                    // replace the def with a clone of the new def
                    final NamespaceDefinitionImpl newNsd =
                            new NamespaceDefinitionImpl(newSource, nsd.getPrefix(), nsd.getURI(), cndPath);
                    defs.set(i, newNsd);
                    newSource.markChanged();
                }
            }
        }
        else {
            // this is a new namespace def -- pretend that it is a node under /hippo:namespaces for sake of file mapping
            final JcrPath incomingPath = JcrPath.get("/hippo:namespaces", nsd.getPrefix());

            // what module should we put it in?
            final ModuleImpl newModule = getModuleByAutoExportConfig(incomingPath, toExport);

            // what source should we put it in?
            final ConfigSourceImpl newSource;
            if (newModule.getNamespaceDefinitions().isEmpty()) {
                // We don't have any namespaces yet, so we can generate a new source and put it there
                newSource = createConfigSourceIfNecessary(DEFAULT_MAIN_CONFIG_FILE, newModule);
            }
            else {
                // if we have any existing namespace definitions in the destination module, we have to keep all
                // new namespaces in that same source file, due to validation rules on our model
                newSource = newModule.getNamespaceDefinitions().get(0).getSource();
            }

            log.debug("Creating new namespace definition: {} in module: {} aka {} in file {}",
                    nsd.getPrefix(), newModule.getMvnPath(), newModule.getFullName(), newSource.getPath());

            newSource.addNamespaceDefinition(nsd.getPrefix(), nsd.getURI(), cndPath);
        }
    }

    /**
     * Create a new ConfigurationModel with the updated definitions in the toExport modules.
     * @param toExport replacement modules that should override what is in the baseline
     * @param baseline the existing model upon which we'll base the new one
     * @return the new ConfigurationModel, which references Sources from the old Modules in baseline and toExport
     */
    // TODO: not used??
    protected static ConfigurationModelImpl rebuild(final HashMap<String, ModuleImpl> toExport,
                                                    final ConfigurationModelImpl baseline) {
        final StopWatch stopWatch = new StopWatch();
        stopWatch.start();

        // note: we assume that the original baseline will perform any required cleanup in close(), so we don't need
        //       to copy FileSystems etc. here
        final ConfigurationModelImpl model = new ConfigurationModelImpl();
        toExport.values().forEach(model::addModule);
        baseline.getSortedGroups().forEach(model::addGroup);
        model.build();

        stopWatch.stop();
        log.debug("Model rebuilt for auto-export merge in {}", stopWatch.toString());
        return model;
    }

    protected void mergeConfigDefinitionNode(final DefinitionNodeImpl incomingDefNode,
                                             final HashMap<String, ModuleImpl> toExport,
                                             final Set<JcrPath> reorderRegistry,
                                             final ConfigurationModelImpl model) {
        log.debug("Merging config change for path: {}", incomingDefNode.getJcrPath());

        final boolean nodeIsNew = isNewNodeDefinition(incomingDefNode);
        if (nodeIsNew) {
            createNewNode(incomingDefNode, toExport, reorderRegistry, model);
        }
        else {
            // if the incoming node is not new, we should expect its path to exist -- find it
            final JcrPath incomingDefPath = incomingDefNode.getJcrPath();
            final ConfigurationNodeImpl incomingConfigNode = model.resolveNode(incomingDefPath);

            if (incomingConfigNode == null) {
                throw new IllegalStateException("Cannot modify a node that doesn't exist in baseline: " + incomingDefPath);
            }

            log.debug("Changed path has existing definition: {}", incomingDefPath);

            // is this a delete?
            if (incomingDefNode.isDelete()) {
                // handle node delete
                final DefinitionNodeImpl deleteDef = deleteNode(incomingDefNode, incomingConfigNode, toExport, reorderRegistry);

                // incremental update of model
                new ConfigurationTreeBuilder(model.getConfigurationRootNode())
                        .markNodeAsDeletedBy(incomingConfigNode, deleteDef).pruneDeletedItems(incomingConfigNode);

                // don't bother checking any other properties or children -- they're gone
                return;
            }

            if (incomingDefNode.getOrderBefore() != null) {
                reorderRegistry.add(incomingDefNode.getJcrPath().getParent());
            }

            // handle properties, then child nodes
            for (final DefinitionPropertyImpl defProperty : incomingDefNode.getProperties().values()) {
                // handle properties on an existing node
                mergeProperty(defProperty, incomingConfigNode, toExport, reorderRegistry, model);
            }

            // any child node here may or may not be new -- do full recursion
            for (DefinitionNodeImpl childNodeDef : incomingDefNode.getNodes().values()) {
                mergeConfigDefinitionNode(childNodeDef, toExport, reorderRegistry, model);
            }
        }
    }

    /**
     * Does this DefinitionNode represent a newly-created node, or does it reference an existing node defined upstream?
     * @param defNode a DefinitionNode to test for newness
     * @return true iff this is a reference to a brand-new node, not referenced upstream yet
     */
    protected boolean isNewNodeDefinition(final DefinitionNodeImpl defNode) {
        // the node is a new node iff a jcr:primaryType is defined here and it's not an override or delete
        return !defNode.isDelete()
                && defNode.getProperty(JCR_PRIMARYTYPE) != null
                && !defNode.getProperty(JCR_PRIMARYTYPE).getOperation().equals(OVERRIDE);
    }

    /**
     * Create a new DefinitionNode in one of the toExport modules for a brand-new node, not mentioned anywhere else.
     * @param incomingDefNode the DefinitionNode from the diff that we are merging
     * @param toExport the modules to which we are merging
     * @param reorderRegistry the registry of paths to nodes which children must be resorted
     * @param model the full ConfigurationModel, which references Sources from the old Modules in baseline and toExport
     */
    protected void createNewNode(final DefinitionNodeImpl incomingDefNode, final HashMap<String, ModuleImpl> toExport,
                                 final Set<JcrPath> reorderRegistry, final ConfigurationModelImpl model) {
        // if the incoming node path is new, we should expect its parent to exist -- find it
        final JcrPath incomingPath = incomingDefNode.getJcrPath();
        final JcrPath parentPath = incomingPath.getParent();
        final ConfigurationNodeImpl existingParent = model.resolveNode(parentPath);

        if (existingParent == null) {
            throw new IllegalStateException("Cannot add a node whose parent doesn't exist in baseline: " + incomingPath);
        }

        log.debug("Changed path is newly defined: {}", incomingPath);

        // does LocationMapper think that this path should be a new context path?
        // if so, create a new ConfigDefinition rather than attempting to add to an existing one
        if (shouldPathCreateNewSource(incomingPath)) {
            // we don't care if there's an existing def -- LocationMapper is making us split to a new file
            // TODO should this take into account the modules where siblings are defined, to handle ordering properly?
            final DefinitionNodeImpl newDef = createNewDef(incomingDefNode, true, toExport, reorderRegistry);

            // update model
            new ConfigurationTreeBuilder(model.getConfigurationRootNode())
                    .push(newDef.getDefinition());
        }
        else {
            // where was the parent node mentioned?
            // is one of the existing defs for the parent in the toMerge modules? grab the last one
            // TODO if there is more than one mention in toMerge, should we prefer the def with jcr:primaryType?
            final Optional<DefinitionNodeImpl> maybeDef = getLastLocalDef(existingParent, toExport);

            // TODO should we attempt any kind of sorting on output? current behavior is append, with history-dependent output
            // TODO i.e. the sequence of changes to the repository and the timing of auto-export will produce different files
            // TODO also, definition ordering will likely not match order implied by .meta:order-before
            if (maybeDef.isPresent()) {
                // since we have a parent defNode in a valid module, use that for this new child
                final DefinitionNodeImpl parentDefNode = maybeDef.get();

                // we know that this is the only place that mentions this node, because it's new
                // -- put all descendent properties and nodes in this def
                final DefinitionNodeImpl newDefNode =
                        recursiveAdd(incomingDefNode, parentDefNode, toExport, reorderRegistry);

                // update model
                final ConfigurationTreeBuilder builder = new ConfigurationTreeBuilder(model.getConfigurationRootNode());
                final ConfigurationNodeImpl newConfigNode = builder.createChildNode(existingParent, newDefNode.getName(), newDefNode);
                builder.mergeNode(newConfigNode, newDefNode);
            }
            else {
                // there's no existing parent defNode that we can reuse, so we need a new definition
                // TODO should this take into account the modules where siblings are defined, to handle ordering properly?
                final DefinitionNodeImpl newDef = createNewDef(incomingDefNode, true, toExport, reorderRegistry);

                // update model
                new ConfigurationTreeBuilder(model.getConfigurationRootNode())
                        .push(newDef.getDefinition());
            }
        }
    }

    /**
     * Find the module within toExport that should match the given path according to the AutoExport module config.
     * @param path the path to test
     * @param toExport the set of Modules being merged here and eventually to be exported
     * @return a single Module that represents the best match for this path
     */
    protected ModuleImpl getModuleByAutoExportConfig(final JcrPath path, final HashMap<String, ModuleImpl> toExport) {
        // TODO extra logic from EventProcessor.getModuleForPath() and getModuleForNSPrefix()
        return moduleMappings.values().stream()
                .filter(mapping -> mapping.matchesPath(path))
                .map(mapping -> toExport.get(mapping.mvnPath))
                .findFirst().orElseGet(()->toExport.get(defaultModuleMapping.mvnPath));
    }

    /**
     * Get a source within a given module to use when adding a new node definition. The source is chosen based on
     * the conventions from the AutoExport LocationMapper class, and it might in theory already exist.
     * @param path the JCR path of the new node we'll be defining
     * @param module the module where we want this definition to live
     * @return a new or existing ConfigSourceImpl
     */
    protected ConfigSourceImpl getSourceForNewConfig(final JcrPath path, final ModuleImpl module) {
        // what does LocationMapper say?
        final String sourcePath = getFilePathByLocationMapper(path);
        return createConfigSourceIfNecessary(sourcePath, module);
    }

    /**
     * Get a source within a given module to use when adding a new node definition, creating it if necessary.
     * @param sourcePath the desired module-config-root-relative path
     * @param module the module where we want this definition to live
     * @return a Source that can be used to create new definitions
     */
    protected ConfigSourceImpl createConfigSourceIfNecessary(final String sourcePath, final ModuleImpl module) {
        // does this Source already exist?
        return module.getConfigSource(sourcePath)
                // if not, add it
                .orElseGet(() -> module.addConfigSource(sourcePath));
    }

    /**
     * Does LocationMapper think that this path should be defined in a new context root path in a new file?
     * @param incomingPath the JCR node path to test
     * @return true iff this path should go in a new file, different than its parent node
     */
    protected static boolean shouldPathCreateNewSource(final JcrPath incomingPath) {
        // for the sake of creating new source files, we always want to use the minimally-indexed path
        // to avoid annoying and unnecessary "[1]" tags on filenames
        final String minimallyIndexedPath = incomingPath.toMinimallyIndexedPath().toString();
        return JcrPath.get(LocationMapper.contextNodeForPath(minimallyIndexedPath, true))
                .equals(incomingPath);
    }

    /**
     * Lookup the file path that the old AutoExport LocationMapper class would recommend for the given JCR path,
     * then adjust it to use a YAML extension instead of an XML extension.
     * @param path the JCR path for which we want to generate a new source file
     * @return a module-base-relative path with no leading slash for a potentially new yaml source file
     */
    protected String getFilePathByLocationMapper(JcrPath path) {
        String xmlFile = LocationMapper.fileForPath(path.toMinimallyIndexedPath().toString(), true);
        if (xmlFile == null) {
            return "main.yaml";
        }
        return StringUtils.removeEnd(xmlFile, ".xml") + YAML_EXT;
    }

    /**
     * Create a new ConfigDefinition to contain the contents of the given DefinitionNode, which may be copied here.
     * When copying, this will also create new definitions in new source files for descendant nodes as determined via
     * {@link #shouldPathCreateNewSource(JcrPath)}.
     * @param incomingDefNode a DefinitionNode that will be copied to form the content of the new ConfigDefinition
     * @param copyContents should the contents of the incomingDefNode be recursively copied into the new def?
     * @param toExport the set of Modules being merged here and eventually to be exported
     * @param reorderRegistry the registry of paths to nodes which children must be resorted
     */
    protected DefinitionNodeImpl createNewDef(final DefinitionNodeImpl incomingDefNode,
                                              final boolean copyContents,
                                              final HashMap<String, ModuleImpl> toExport,
                                              final Set<JcrPath> reorderRegistry) {

        final JcrPath incomingPath = incomingDefNode.getJcrPath();

        log.debug("Creating new top-level definition for path: {} ...", incomingPath);

        // create the new ConfigDefinition and add it to the source
        // we know that this is the only place that mentions this node, because it's new
        // TODO discuss with Peter; it need not be new
        // -- put all descendent properties and nodes in this def
        //... but when we create the def, make sure to walk up until we don't have an indexed node in the def root
        final DefinitionNodeImpl newRootNode = getOrCreateLocalDef(incomingPath, toExport);

        final Source source = newRootNode.getDefinition().getSource();
        log.debug("... stored in {}/hcm-config/{}", source.getModule().getName(), source.getPath());

        if (copyContents) {
            recursiveCopy(incomingDefNode, newRootNode, toExport, reorderRegistry);
        }

        return newRootNode;
    }

    /**
     * Get or create a definition in the local modules to contain data for jcrPath
     * @param path the path for which we want a definition
     * @param toExport the set of Modules being merged here and eventually to be exported
     * @return a DefinitionNodeImpl corresponding to the jcrPath, which may or may not be a root and may or not may be
     * empty
     */
    protected DefinitionNodeImpl getOrCreateLocalDef(final JcrPath path, final HashMap<String, ModuleImpl> toExport) {
        // what module should we put it in?
        final ModuleImpl destModule = getModuleByAutoExportConfig(path, toExport);

        // what source should we put it in?
        final ConfigSourceImpl destSource = getSourceForNewConfig(path, destModule);

        return destSource.getOrCreateDefinitionFor(path);
    }

    /**
     * Recursively copy the new def as a child-plus-descendants of this node.
     * This will also create new definitions in new source files for descendant nodes as determined via
     * {@link #shouldPathCreateNewSource(JcrPath)}.
     * @param from the definition we want to copy as a child of toParent
     * @param toParent the parent of the desired new definition node
     * @param toExport the set of Modules being merged here and eventually to be exported
     * @param reorderRegistry the registry of paths to nodes which children must be resorted
     * @return the newly created child node, already populated with properties and descendants
     */
    protected DefinitionNodeImpl recursiveAdd(final DefinitionNodeImpl from,
                                              final DefinitionNodeImpl toParent,
                                              final HashMap<String, ModuleImpl> toExport,
                                              final Set<JcrPath> reorderRegistry) {

        log.debug("Adding new node definition to existing definition: {}", from.getJcrPath());

        // mark source changed
        toParent.getDefinition().getSource().markChanged();

        // if order-before is set, we need to do an insert, not an add-at-end
        final DefinitionNodeImpl to;
        if (from.getOrderBefore() != null) {
            log.debug("Inserting before: {}", from.getOrderBefore());

            to = toParent.addNodeBefore(from.getName(), from.getOrderBefore());

            // if the new node does not have order-before set, we don't need to copy and keep order-before anymore
            if (to.getOrderBefore() == null) {
                from.setOrderBefore(null);
            }
            else {
                log.debug("Couldn't find node that we want to insert before");
            }
        }
        else {
            to = toParent.addNode(from.getName());
        }
        recursiveCopy(from, to, toExport, reorderRegistry);
        return to;
    }

    /**
     * Recursively copy all properties and descendants of the from-node to the to-node.
     * Creates new definitions as required by LocationMapper.
     * @param from the definition we want to copy
     * @param to the definition we are copying into
     * @param toExport the set of Modules being merged here and eventually to be exported
     * @param reorderRegistry the registry of paths to nodes which children must be resorted
     */
    protected void recursiveCopy(final DefinitionNodeImpl from, final DefinitionNodeImpl to,
                                 final HashMap<String, ModuleImpl> toExport, final Set<JcrPath> reorderRegistry) {

        // Add the 'to' path to the reorder registry, whether it is a delete, or if new content gets copied in here
        reorderRegistry.add(to.getJcrPath().getParent());

        if (from.isDelete()) {
            // delete clears everything, so there's no point continuing with other properties or recursion
            to.delete();
            return;
        }

        to.setOrderBefore(from.getOrderBefore());
        to.setIgnoreReorderedChildren(from.getIgnoreReorderedChildren());
        to.setResidualChildNodeCategory(from.getResidualChildNodeCategory());
        to.setCategory(from.getCategory());

        // copy properties using special method that migrates resources properly
        for (final DefinitionPropertyImpl fromProperty : from.getProperties().values()) {
            to.addProperty(fromProperty);
        }

        for (final DefinitionNodeImpl childNode : from.getNodes().values()) {
            // for each new childNode, we need to check if LocationMapper wants a new source file
            final JcrPath incomingPath = childNode.getJcrPath();
            if (shouldPathCreateNewSource(incomingPath)) {
                // yes, we need a new definition in a new source file
                createNewDef(childNode, true, toExport, reorderRegistry);
            } else {
                // no, just keep adding to the current destination defNode
                recursiveAdd(childNode, to, toExport, reorderRegistry);
            }
        }
    }

    /**
     * Handle a diff entry indicating that a single node should be deleted.
     * @param defNode a DefinitionNode from the diff, describing a single to-be-deleted node
     * @param configNode the ConfigurationNode corresponding to the to-be-deleted node in the current config model
     * @param toExport the set of Modules being merged here and eventually to be exported
     * @param reorderRegistry the registry of paths to nodes which children must be resorted
     */
    protected DefinitionNodeImpl deleteNode(final DefinitionNodeImpl defNode,
                                            final ConfigurationNodeImpl configNode,
                                            final HashMap<String, ModuleImpl> toExport,
                                            final Set<JcrPath> reorderRegistry) {

        log.debug("Deleting node: {}", defNode.getJcrPath());

        reorderRegistry.add(defNode.getJcrPath().getParent());

        final List<DefinitionNodeImpl> defsForConfigNode = configNode.getDefinitions();

        // if last existing node def is upstream,
        final boolean lastDefIsUpstream = !isLastDefLocal(defsForConfigNode, toExport);
        if (lastDefIsUpstream) {
            log.debug("Last def for node is upstream of export: {}", defNode.getJcrPath());

            // create new defnode w/ delete
            final DefinitionNodeImpl newDef = createNewDef(defNode, true, toExport, reorderRegistry);

            // we know that there was no local def for the node we're deleting, but there may be defs for its children
            // so for all descendants, remove all definitions and possibly sources
            removeDescendantDefinitions(configNode, new ArrayList<>(), toExport);

            return newDef;
        }
        else {
            // there are local node defs for this node
            // are there ONLY local node defs?
            final List<DefinitionNodeImpl> localDefs = getLocalDefs(defsForConfigNode, toExport);
            final boolean onlyLocalDefs = (localDefs.size() == defsForConfigNode.size());
            if (onlyLocalDefs) {
                log.debug("Only local defs for node: {}", defNode.getJcrPath());

                // since there's only local defs, we want this node to disappear from the record completely
                // i.e. "some" = "all" defs, in this case
                removeSomeDefsAndDescendants(configNode, defsForConfigNode, new ArrayList<>(), toExport);

                return defNode;
            }
            else {
                log.debug("Both local and upstream defs for node: {}", defNode.getJcrPath());

                // since there's also an upstream def, we want to collapse all local references to a single delete def
                // if exists, change one local def to delete and remove other properties and subnodes
                final DefinitionNodeImpl defToKeep = (DefinitionNodeImpl) localDefs.get(0);

                // mark chosen node as a delete
                final ConfigDefinitionImpl defToKeepDefinition = (ConfigDefinitionImpl) defToKeep.getDefinition();
                log.debug("Marking delete on node: {} from definition of: {} in source: {}",
                        defToKeep.getJcrPath(),
                        defToKeepDefinition.getNode().getJcrPath(),
                        defToKeepDefinition.getSource().getPath());
                defToKeep.delete();
                defToKeepDefinition.getSource().markChanged();

                // remove all other defs and children (but not the first one, that we are keeping)
                final List<DefinitionNodeImpl> localDefsExceptFirst = localDefs.subList(1, localDefs.size());
                removeSomeDefsAndDescendants(configNode, localDefsExceptFirst, new ArrayList<>(), toExport);

                return defToKeep;
            }
        }
    }

    /**
     * Remove some (perhaps all) definitions associated with configNode, plus all definitions associated with
     * descendants of configNode. Recurs down the ConfigurationNode tree and up the DefinitionItem tree(s).
     * Call this method to start the entire removal process, possibly preserving one or more existing definitions.
     * @param configNode the configNode whose descendants we're deleting
     * @param defsToRemove the subset of configNode.getDefinitions() that we want to delete
     * @param alreadyRemoved an accumulator for Definitions whose children we don't have to check,
     *                           because the root is already gone
     * @param toExport modules we're merging/exporting -- changes should stay inside this scope
     */
    protected void removeSomeDefsAndDescendants(final ConfigurationNodeImpl configNode,
                                                final List<? extends DefinitionItemImpl> defsToRemove,
                                                final List<AbstractDefinitionImpl> alreadyRemoved,
                                                final HashMap<String, ModuleImpl> toExport) {
        log.debug("Removing defs and children for node: {} with exceptions: {}", configNode.getJcrPath(), alreadyRemoved);

        for (final DefinitionItemImpl definitionItem : defsToRemove) {
            removeOneDefinitionItem(definitionItem, alreadyRemoved, toExport);
        }

        // we don't need to handle properties specifically, because we will remove all the nodes that contain them

        // scan downwards for child definitions, which could be rooted on the children directly
        removeDescendantDefinitions(configNode, alreadyRemoved, toExport);
    }

    /**
     * Remove child definitions from the given configNode, because configNode is being deleted.
     * This method recurs down the ConfigurationNode tree, and then recurs up the DefinitionNode tree(s) to clean up
     * any parent DefinitionNodes, Definitions, or Sources that may have been made empty.
     * @param configNode the node being deleted
     * @param alreadyRemoved an accumulator for Definitions whose children we don't have to check,
     *                           because the root is already gone
     * @param toExport modules we're merging/exporting -- changes should stay inside this scope
     */
    protected void removeDescendantDefinitions(final ConfigurationNodeImpl configNode,
                                               final List<AbstractDefinitionImpl> alreadyRemoved,
                                               final HashMap<String, ModuleImpl> toExport) {
        log.debug("Removing child defs for node: {} with exceptions: {}", configNode.getJcrPath(), alreadyRemoved);

        for (final ConfigurationNodeImpl childConfigNode : configNode.getNodes().values()) {
            for (final DefinitionNodeImpl childDefItem : childConfigNode.getDefinitions()) {
                // if child's DefinitionNode was part of a parent Definition, it may have already been removed
                // also check the definition belongs to one of autoexport modules
                final AbstractDefinitionImpl childDefinition = childDefItem.getDefinition();
                if (!alreadyRemoved.contains(childDefinition)
                        && isAutoExportModule(toExport.values(), childDefinition.getSource().getModule())) {
                    // otherwise, remove it now
                    removeOneDefinitionItem(childDefItem, alreadyRemoved, toExport);
                }
            }
            removeDescendantDefinitions(childConfigNode, alreadyRemoved, toExport);
        }
    }

    private boolean isAutoExportModule(final Collection<ModuleImpl> autoExportModules, final ModuleImpl candidate) {
        return autoExportModules.contains(candidate);
    }

    /**
     * Remove one definition item, either by removing it from its parent or (if root) removing the entire definition.
     * Recurs up the DefinitionItem tree to clean up newly-emptied items.
     * @param definitionItem the node or property to remove
     * @param alreadyRemoved an accumulator for Definitions whose children we don't have to check,
     *                           because the root is already gone
     * @param toExport modules we're merging/exporting -- changes should stay inside this scope
     */
    protected void removeOneDefinitionItem(final DefinitionItemImpl definitionItem,
                                           final List<AbstractDefinitionImpl> alreadyRemoved,
                                           final HashMap<String, ModuleImpl> toExport) {

        log.debug("Removing one def item for node: {} with exceptions: {}", definitionItem.getJcrPath(), alreadyRemoved);

        // remove the node itself
        // if this node is the root
        if (definitionItem.isRoot()) {
            // remove the definition
            final ConfigDefinitionImpl definition = (ConfigDefinitionImpl) definitionItem.getDefinition();
            removeDefinition(definition, toExport);
            alreadyRemoved.add(definition);
        }
        else {
            // otherwise, remove from parent
            removeFromParentDefinitionItem(definitionItem, alreadyRemoved, toExport);
        }

    }

    /**
     * Remove a DefinitionItem from its parent. This method assumes that you've already checked that the parent exists.
     * Recurs up the DefinitionItem tree to clean up newly-emptied items.
     * @param definitionItem the node or property to remove
     * @param alreadyRemoved an accumulator for Definitions whose children we don't have to check,
     *                           because the root is already gone
     * @param toExport modules we're merging/exporting -- changes should stay inside this scope
     */
    protected void removeFromParentDefinitionItem(final DefinitionItemImpl definitionItem,
                                                  final List<AbstractDefinitionImpl> alreadyRemoved,
                                                  final HashMap<String, ModuleImpl> toExport) {
        final ConfigDefinitionImpl definition = (ConfigDefinitionImpl) definitionItem.getDefinition();
        final SourceImpl source = definition.getSource();
        final ModuleImpl module = source.getModule();

        // check if the definition is in one of the toExport modules -- if not, we can't change it
        if (!toExport.containsValue(module)) {
            throw new IllegalStateException
                    ("Cannot change a definition from module that is not being merged: " + module.getFullName()
                            + " for node: " + definitionItem.getJcrPath());
        }
        log.debug("Removing definition item for: {} from definition of: {} in source: {}",
                definitionItem.getJcrPath(),
                definition.getNode().getJcrPath(), source.getPath());

        final DefinitionNodeImpl parentNode = definitionItem.getParent();
        if (definitionItem instanceof DefinitionNode) {
            // remove the node from its parent
            // todo: one of very few remaining uses of getModifiableNodes()
            parentNode.getModifiableNodes().remove(definitionItem.getName());

            // remove referenced resources
            removeResources((DefinitionNodeImpl) definitionItem);
        }
        else {
            // remove the property from its parent
            // todo: one of very few remaining uses of getModifiableProperties()
            parentNode.getModifiableProperties().remove(definitionItem.getName());

            // remove referenced resources
            removeResources((DefinitionPropertyImpl) definitionItem);
        }
        source.markChanged();

        // if this was the last item in the parent node ...
        if (parentNode.isEmpty()) {
            // ... remove the parent node and keep moving up
            removeOneDefinitionItem(parentNode, alreadyRemoved, toExport);
        }
    }

    /**
     * Remove an entire Definition, and if it is the last Definition in its Source, also remove the Source.
     * @param definition the definition to remove
     * @param toExport modules we're merging/exporting -- changes should stay inside this scope
     */
    protected void removeDefinition(final ConfigDefinitionImpl definition, final HashMap<String, ModuleImpl> toExport) {
        // remove the definition from its source and from its module
        final SourceImpl source = definition.getSource();
        final ModuleImpl module = source.getModule();

        // check if the definition is in one of the toExport modules -- if not, we can't change it
        if (!toExport.containsValue(module)) {
            throw new IllegalStateException
                    ("Cannot remove a definition from module that is not being merged: " + module.getFullName()
                            + " for node: " + definition.getNode().getJcrPath());
        }
        log.debug("Removing definition for node: {} from source: {}", definition.getNode().getJcrPath(), source.getPath());

        source.removeDefinition(definition);
        module.getConfigDefinitions().remove(definition);

        // remove referenced resources
        removeResources(definition.getNode());

        // if the definition was the last one from its source
        if (source.getDefinitions().size() == 0) {
            log.debug("Removing source: {}", source.getPath());

            // remove the source from its module
            module.getModifiableSources().remove(source);
            module.addConfigResourceToRemove("/" + source.getPath());
        }
    }

    /**
     * Merge an incoming property change into toExport modules.
     * @param defProperty the incoming property change
     * @param configNode the ConfigurationNode representing the parent node of defProperty
     * @param toExport modules we're merging/exporting -- changes should stay inside this scope
     * @param reorderRegistry the registry of paths to nodes which children must be resorted
     * @param model the full ConfigurationModel, which references Sources from the old Modules in baseline and toExport
     */
    protected void mergeProperty(final DefinitionPropertyImpl defProperty,
                                 final ConfigurationNodeImpl configNode,
                                 final HashMap<String, ModuleImpl> toExport,
                                 final Set<JcrPath> reorderRegistry,
                                 final ConfigurationModelImpl model) {

        log.debug("Merging property: {} with operation: {}", defProperty.getJcrPath(), defProperty.getOperation());

        final ConfigurationPropertyImpl configProperty = configNode.getProperty(defProperty.getName());

        switch (defProperty.getOperation()) {
            case REPLACE:
            case ADD:
            case OVERRIDE:
                mergePropertyThatShouldExist(defProperty, configNode, configProperty, toExport, reorderRegistry, model);
                break;
            default:
                // case DELETE:
                deleteProperty(defProperty, configNode, configProperty, toExport, reorderRegistry, model);
                break;
        }
    }

    protected void mergePropertyThatShouldExist(final DefinitionPropertyImpl defProperty,
                                                final ConfigurationNodeImpl configNode,
                                                final ConfigurationPropertyImpl configProperty,
                                                final HashMap<String, ModuleImpl> toExport,
                                                final Set<JcrPath> reorderRegistry,
                                                final ConfigurationModelImpl model) {
        final boolean propertyExists = (configProperty != null);
        if (propertyExists) {
            // this is an existing property being replaced
            log.debug(".. which already exists", defProperty.getJcrPath());

            // is there a local def for this specific property?
            final Optional<DefinitionPropertyImpl> maybeLocalPropertyDef = getLastLocalDef(configProperty, toExport);
            if (maybeLocalPropertyDef.isPresent()) {
                // yes, there's a local def for the specific property
                final DefinitionPropertyImpl localPropDef = maybeLocalPropertyDef.get();

                log.debug(".. and already has a local property def in: {} from source: {}",
                        localPropDef.getDefinition().getNode().getJcrPath(),
                        localPropDef.getDefinition().getSource().getPath());

                final List<DefinitionPropertyImpl> defsForConfigProperty = configProperty.getDefinitions();

                // cases:
                // 1. local is replace and only def, diff is override => replace
                if (localPropDef.getOperation() == REPLACE
                        && defProperty.getOperation() == PropertyOperation.OVERRIDE
                        && defsForConfigProperty.size() == 1) {
                    defProperty.setOperation(REPLACE);
                }
                // 2. local is replace and not only def, diff is override => override (do nothing)

                if (localPropDef.getOperation() == PropertyOperation.OVERRIDE) {
                    // 3. local is override, diff is replace => override
                    if (defProperty.getOperation() == REPLACE) {
                        defProperty.setOperation(PropertyOperation.OVERRIDE);
                    }

                    if (defProperty.getOperation() == PropertyOperation.OVERRIDE) {
                        final DefinitionPropertyImpl nextUpDefProperty = (DefinitionPropertyImpl)
                                defsForConfigProperty.get(defsForConfigProperty.size() - 2);
                        final boolean diffMatchesNextUp =
                                defProperty.getType() == nextUpDefProperty.getType()
                                        && defProperty.getValueType() == nextUpDefProperty.getValueType();
                        if (diffMatchesNextUp) {
                            // 4. local is override, diff is override, upstream is same as diff => replace
                            defProperty.setOperation(REPLACE);
                        }
                        // 5. local is override, diff is override, upstream is still different => override (do nothing)
                    }
                }

                // 6. local is add, diff is replace => replace (do nothing)
                // 7. local is add, diff is override => override (do nothing)
                // 8. local is add, diff is add => add (do nothing)
                // 9. local is replace, diff is replace => replace (do nothing)
                // 10. local is replace, diff is add => replace (updateFrom handles)
                // 11. local is override, diff is add => override (updateFrom handles)

                // change local def to reflect new state
                localPropDef.updateFrom(defProperty);
                localPropDef.getDefinition().getSource().markChanged();

                // update the model incrementally, since a new local def should be available for other props
                final ConfigurationTreeBuilder builder =
                        new ConfigurationTreeBuilder(model.getConfigurationRootNode());

                // build the property back up from scratch using all of the definitions
                configNode.removeProperty(defProperty.getName());
                for (final DefinitionPropertyImpl def : defsForConfigProperty) {
                    builder.mergeProperty(configNode, def);
                }
            }
            else {
                // no, there's no local def for the specific property
                log.debug("... but has no local def yet");

                addLocalProperty(defProperty, configNode, toExport, reorderRegistry, model);
            }
        }
        else {
            // this is a totally new property
            // note: this is effectively unreachable for case: OVERRIDE
            log.debug(".. which is totally new", defProperty.getJcrPath());

            addLocalProperty(defProperty, configNode, toExport, reorderRegistry, model);
        }
    }

    protected void deleteProperty(final DefinitionPropertyImpl defProperty,
                                  final ConfigurationNodeImpl configNode,
                                  final ConfigurationPropertyImpl configProperty,
                                  final HashMap<String, ModuleImpl> toExport,
                                  final Set<JcrPath> reorderRegistry,
                                  final ConfigurationModelImpl model) {
        final boolean propertyExists = (configProperty != null);
        if (!propertyExists) {
            throw new IllegalArgumentException("Cannot delete a property that doesn't exist in config model!");
        }

        final List<DefinitionPropertyImpl> defsForConfigProperty = configProperty.getDefinitions();
        final boolean lastDefIsUpstream = !isLastDefLocal(defsForConfigProperty, toExport);

        // add local property
        if (lastDefIsUpstream) {
            addLocalProperty(defProperty, configNode, toExport, reorderRegistry, model);
        }
        else {
            final List<DefinitionPropertyImpl> localDefs = getLocalDefs(defsForConfigProperty, toExport);
            final boolean onlyLocalDefs = (localDefs.size() == defsForConfigProperty.size());

            // remove all but the first local def
            final DefinitionPropertyImpl firstLocalDef = localDefs.get(0);
            firstLocalDef.getDefinition().getSource().markChanged();

            for (final DefinitionPropertyImpl localDef : localDefs.subList(1, localDefs.size())) {
                removeFromParentDefinitionItem(localDef, new ArrayList<>(), toExport);
            }

            // clear the property in the model
            configNode.removeProperty(defProperty.getName());

            if (onlyLocalDefs) {
                // if the first local def is the only def left, remove that, too
                removeFromParentDefinitionItem(firstLocalDef, new ArrayList<>(), toExport);
            }
            else {
                // otherwise, replace first local def with delete
                firstLocalDef.updateFrom(defProperty);
            }
        }
    }

    /**
     * Add a local definition of a given property, either by adding to an existing definition for the
     * containing node, or by creating a new definition for the containing node and adding to that.
     * @param defProperty the property to add
     * @param configNode the ConfigurationNode for the containing node
     * @param toExport modules we're merging/exporting -- changes should stay inside this scope
     * @param reorderRegistry the registry of paths to nodes which children must be resorted
     * @param model the full ConfigurationModel, which references Sources from the old Modules in baseline and toExport
     */
    protected void addLocalProperty(final DefinitionPropertyImpl defProperty,
                                    final ConfigurationNodeImpl configNode,
                                    final HashMap<String, ModuleImpl> toExport,
                                    final Set<JcrPath> reorderRegistry,
                                    final ConfigurationModelImpl model) {
        // is there a local def for the parent node, where I can put this property?
        final Optional<DefinitionNodeImpl> maybeLocalNodeDef = getLastLocalDef(configNode, toExport);
        if (maybeLocalNodeDef.isPresent()) {
            // yes, there's a local def for parent node -- add the property
            final DefinitionNodeImpl definitionNode = maybeLocalNodeDef.get();

            log.debug("Adding new local property: {} in existing def: {} from source: {}",
                    defProperty.getJcrPath(),
                    definitionNode.getDefinition().getNode().getJcrPath(),
                    definitionNode.getDefinition().getSource().getPath());

            final DefinitionPropertyImpl newProperty = definitionNode.addProperty(defProperty);
            definitionNode.getDefinition().getSource().markChanged();

            // update the model incrementally, since a new local def should be available for other props
            new ConfigurationTreeBuilder(model.getConfigurationRootNode())
                    .mergeProperty(configNode, newProperty).pruneDeletedItems(configNode);
        }
        else {
            // no, there's no local def for parent node
            // create a new local definition with this property
            final DefinitionNodeImpl newDefNode =
                    createNewDef(defProperty.getParent(), false, toExport, reorderRegistry);

            log.debug("Adding new local def for property: {} in source: {}", defProperty.getJcrPath(),
                    newDefNode.getDefinition().getSource().getPath());

            newDefNode.addProperty(defProperty);

            // update the model incrementally, since a new local def should be available for other props
            new ConfigurationTreeBuilder(model.getConfigurationRootNode())
                    .push(newDefNode.getDefinition()).pruneDeletedItems(configNode);
        }
    }

    protected <C extends ConfigurationItemImpl<D>, D extends DefinitionItemImpl>
        Optional<D> getLastLocalDef(final C item, final HashMap<String, ModuleImpl> toExport) {
        final List<D> existingDefs = item.getDefinitions();
        return Lists.reverse(existingDefs).stream()
                .filter(isLocalDef(toExport))
                .findFirst();
    }

    protected <D extends DefinitionItemImpl> List<D> getLocalDefs(final List<D> defsForNode,
                                                final HashMap<String, ModuleImpl> toExport) {
        return defsForNode.stream()
                .filter(isLocalDef(toExport)).collect(Collectors.toList());
    }

    protected boolean isLastDefLocal(final List<? extends DefinitionItem> definitionItems,
                                     final HashMap<String, ModuleImpl> toExport) {
        return isLocalDef(toExport).test(definitionItems.get(definitionItems.size()-1));
    }

    protected Predicate<DefinitionItem> isLocalDef(final HashMap<String, ModuleImpl> toExport) {
        return def -> toExport.containsKey(getMvnPathFromDefinitionItem(def));
    }

    protected static String getMvnPathFromDefinitionItem(final DefinitionItem item) {
        return ((ModuleImpl)item.getDefinition().getSource().getModule()).getMvnPath();
    }

    protected void mergeContentDefinitions(final EventJournalProcessor.Changes contentChanges,
                                           final HashMap<String, ModuleImpl> toExport,
                                           final Set<JcrPath> reorderRegistry,
                                           final Session jcrSession) {

        // set of content change paths in lexical order, so that shorter common sub-paths come first
        // use a PATRICIA Trie, which stores strings efficiently when there are common prefixes
        final Set<String> contentChangesByPath = Collections.newSetFromMap(new PatriciaTrie<>());
        contentChangesByPath.addAll(contentChanges.getAddedContent().getPaths());
        contentChangesByPath.addAll(contentChanges.getChangedContent().getPaths());

        // set of existing sources in reverse lexical order, so that longer paths come first
        // note: we can use an ordinary TreeMap here, because we don't expect as many sources as raw paths
        final SortedMap<JcrPath, ContentDefinitionImpl> existingSourcesByNodePath =
                collectContentSourcesByNodePath(toExport);

        // process deletes, including resource removal
        for (final String deletePath : contentChanges.getDeletedContent()) {
            // if a delete path is -above- a content root path, we need to delete one or more entire sources
            final Set<JcrPath> toRemoveByNodePath = new HashSet<>();
            for (final JcrPath sourceNodePath : existingSourcesByNodePath.keySet()) {
                if (sourceNodePath.startsWith(deletePath)) {
                    final ContentDefinitionImpl contentDef = existingSourcesByNodePath.get(sourceNodePath);
                    final SourceImpl source = contentDef.getSource();
                    final ModuleImpl module = source.getModule();

                    // mark all referenced resources for delete
                    removeResources(contentDef.getNode());

                    // remove the source from its module
                    module.getModifiableSources().remove(source);
                    module.addContentResourceToRemove("/" + source.getPath());
                    toRemoveByNodePath.add(contentDef.getNode().getJcrPath());

                    // mark the parent as needing reordering
                    reorderRegistry.add(sourceNodePath.getParent());
                }
            }
            // if a delete path is -below- one of the sources that remains, treat it as a change
            for (final JcrPath sourceNodePath : Sets.difference(existingSourcesByNodePath.keySet(), toRemoveByNodePath)) {
                if (deletePath.startsWith(sourceNodePath.toMinimallyIndexedPath().toString())) {
                    contentChangesByPath.add(deletePath);
                }
            }
        }

        for (final String changePath : contentChangesByPath) {
            // is there an existing source for this exact path? if so, use that
            final JcrPath changeNodePath = JcrPath.get(changePath);
            if (existingSourcesByNodePath.containsKey(changeNodePath)) {
                // mark it changed for later re-export, and then we're done with this path
                existingSourcesByNodePath.get(changeNodePath).getSource().markChanged();
                continue;
            }

            // there was no exactly-matching source, so we need to decide whether to reuse or create new
            // if LocationMapper tells us we should have a new source file...
            if (shouldPathCreateNewSource(changeNodePath)) {
                // create a new source file
                existingSourcesByNodePath.put(changeNodePath, createNewContentSource(changeNodePath, toExport));

                // REPO-1715 We have a potential for a race condition where child nodes can be accidentally
                //           exported to source files for an ancestor node before we process the add events
                //           for the child nodes. To clean up this state, we also need to re-export any
                //           source on the direct ancestor path for the change path.
                for (ContentDefinitionImpl def : existingSourcesByNodePath.values()) {
                    if (changeNodePath.startsWith(def.getNode().getJcrPath())) {
                        def.getSource().markChanged();
                    }
                }
            }
            else {
                // check if there's an existing file that is an ancestor of the changed path
                // find the source with the longest matching substring of the changed path
                final Optional<ContentDefinitionImpl> maybeDef = existingSourcesByNodePath.entrySet().stream()
                        .filter(e -> changeNodePath.startsWith(e.getKey()))
                        .map(Map.Entry::getValue)
                        .findFirst();
                if (maybeDef.isPresent()) {
                    // there's an existing matching source, so just mark it changed for later re-export
                    maybeDef.get().getSource().markChanged();
                }
                else {
                    // otherwise, create a new source file
                    // REPO-1715 We don't have to walk up the tree in this case, since we know there's
                    //           no source on an ancestor path that might have picked up these changes.
                    existingSourcesByNodePath.put(changeNodePath, createNewContentSource(changeNodePath, toExport));
                }
            }
        }

        // we've added new defs, so we need to update the modules to reflect that
        for (ModuleImpl module : toExport.values()) {
            module.build();
        }

        getChangedContentSourcesStream(toExport).forEach(source ->
                reorderRegistry.add(source.getDefinition().getNode().getJcrPath().getParent())
        );
    }

    private Stream<ContentSourceImpl> getChangedContentSourcesStream(final Map<String, ModuleImpl> toExport) {
        return toExport.values().stream()
                .flatMap(m -> m.getContentSources().stream())
                .filter(SourceImpl::hasChangedSinceLoad);
    }

    /**
     * Helper to collect all content sources of given modules by root path in reverse lexical order of root paths.
     * @param toExport modules in whose sources we're interested
     */
    protected SortedMap<JcrPath, ContentDefinitionImpl> collectContentSourcesByNodePath(final HashMap<String, ModuleImpl> toExport) {
        final Function<ContentDefinitionImpl, JcrPath> cdPath = cd -> cd.getNode().getJcrPath();
        final BinaryOperator<ContentDefinitionImpl> pickOne = (l, r) -> l;
        final Supplier<TreeMap<JcrPath, ContentDefinitionImpl>> reverseTreeMapper =
                () -> new TreeMap<>(Comparator.reverseOrder());
        return toExport.values().stream()
                .flatMap(m -> Lists.reverse(m.getContentDefinitions()).stream())
                .collect(Collectors.toMap(cdPath, Function.identity(), pickOne, reverseTreeMapper));
    }

    /**
     * Recursively find all resource paths in this node or descendants, then tell the containing module to remove
     * the resources at those paths.
     */
    protected void removeResources(final DefinitionNodeImpl node) {
        // find resource values
        for (final DefinitionPropertyImpl dp : node.getProperties().values()) {
            removeResources(dp);
        }

        // recursively visit child definition nodes
        for (final DefinitionNodeImpl childNode : node.getNodes().values()) {
            removeResources(childNode);
        }
    }

    /**
     * Find all resource paths in this property, then tell the containing module to remove
     * the resources at those paths.
     */
    protected void removeResources(final DefinitionPropertyImpl dp) {
        switch (dp.getType()) {
            case SINGLE:
                removeResourceIfNecessary(dp.getValue());
                break;
            case SET:
            case LIST:
                for (final ValueImpl value : dp.getValues()) {
                    removeResourceIfNecessary(value);
                }
                break;
        }
    }

    /**
     * If the given value represents a resource, tell the containing module to remove the resource at the path
     * represented by value.getString().
     */
    protected void removeResourceIfNecessary(final ValueImpl value) {
        if (value.isResource()) {
            final SourceImpl source = value.getDefinition().getSource();
            final String resourcePath = source.toModulePath(value.getString());

            if (source.getType() == SourceType.CONFIG) {
                source.getModule().addConfigResourceToRemove(resourcePath);
            }
            else {
                source.getModule().addContentResourceToRemove(resourcePath);
            }
        }
    }

    /**
     * Create a new ContentSourceImpl within one of the toExport modules to store content for the provided contentPath.
     * @param changePath the path whose content we want to store in the new source
     * @param toExport the set of modules that are being exported, which may contain the new source
     */
    protected ContentDefinitionImpl createNewContentSource(final JcrPath changePath, final HashMap<String, ModuleImpl> toExport) {
        // there's no existing source, so we need to create one
        final ModuleImpl module = getModuleByAutoExportConfig(changePath, toExport);
        final String sourcePath = getFilePathByLocationMapper(changePath);

        // TODO should we export the changePath into this new source, or the LocationMapper contextPath?
        // TODO ... we want the source root def to match the node expected from the source file name, right?

        final Predicate<String> sourceExists = s ->
            module.getModifiableSources().stream()
                .filter(SourceType.CONTENT::isOfType)
                .anyMatch(source -> source.getPath().equals(s));

        // if there's already a source with this path, generate a unique name
        final String uniqueSourcePath =
                FilePathUtils.generateUniquePath(sourcePath, sourceExists, 0);

        // create a new source and content definition with change path
        return module.addContentSource(uniqueSourcePath).addContentDefinition(changePath);
    }

}
