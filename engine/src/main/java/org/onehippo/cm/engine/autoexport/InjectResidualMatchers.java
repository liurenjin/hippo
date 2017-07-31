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
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

import javax.jcr.RepositoryException;

import org.onehippo.cm.model.impl.ConfigurationModelImpl;
import org.onehippo.cm.model.impl.tree.DefinitionNodeImpl;
import org.onehippo.cm.model.impl.tree.DefinitionPropertyImpl;
import org.onehippo.cm.model.tree.ConfigurationItemCategory;

import static org.apache.jackrabbit.JcrConstants.JCR_PRIMARYTYPE;

class InjectResidualMatchers {

    private final List<Matcher> matchers;

    InjectResidualMatchers() {
        this(Collections.emptyList());
    }

    InjectResidualMatchers(final List<String> patterns) throws IllegalArgumentException {
        this.matchers = new ArrayList<>(patterns.size());
        for (String pattern : patterns) {
            matchers.add(new Matcher(pattern));
        }
    }

    void add(final String string) throws IllegalArgumentException {
        matchers.add(new Matcher(string));
    }

    ConfigurationItemCategory getMatch(final DefinitionNodeImpl node, final ConfigurationModelImpl currentModel)
            throws RepositoryException {
        for (final Matcher matcher : matchers) {
            if (matcher.matches(node, currentModel)) {
                return matcher.category;
            }
        }
        return null;
    }

    static class Matcher {
        final Pattern pattern;
        final String patternString;
        final String nodeType;
        final ConfigurationItemCategory category;

        Matcher(final String string) {
            final String illegalFormatMessage = "Illegal pattern: '" + string
                    + "', pattern must be formatted as 'path[nodetype]: category'; only [nodetype] is optional";
            final int bracketStart = string.indexOf('[');
            final int bracketEnd = string.indexOf(']');
            final int colon = string.lastIndexOf(": ");

            if (colon == -1) {
                throw new IllegalArgumentException(illegalFormatMessage);
            }

            try {
                category = ConfigurationItemCategory.valueOf(string.substring(colon + 2).toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(illegalFormatMessage);
            }

            if (bracketStart == -1 && bracketEnd == -1) {
                nodeType = null;
            } else if (bracketStart != -1 && bracketEnd != -1) {
                if (bracketEnd < bracketStart) {
                    throw new IllegalArgumentException(illegalFormatMessage);
                }
                final String betweenBrackets = string.substring(bracketStart + 1, bracketEnd).trim();
                if (betweenBrackets.equals("")) {
                    throw new IllegalArgumentException(illegalFormatMessage);
                }
                boolean isSns = false;
                try {
                    Integer.parseInt(betweenBrackets);
                    isSns = true;
                } catch (NumberFormatException ignored) {}

                if (isSns) {
                    nodeType = null;
                } else {
                    if (bracketEnd + 1 != colon) {
                        throw new IllegalArgumentException(illegalFormatMessage);
                    }
                    nodeType = betweenBrackets;
                }
            } else {
                throw new IllegalArgumentException(illegalFormatMessage);
            }

            patternString = nodeType == null ? string.substring(0, colon) : string.substring(0, bracketStart);
            if (patternString.trim().equals("")) {
                throw new IllegalArgumentException(illegalFormatMessage);
            }
            pattern = Pattern.compile(PatternSet.compile(patternString));
        }

        boolean matches(final DefinitionNodeImpl node, final ConfigurationModelImpl currentModel)
                throws RepositoryException {
            if (!pattern.matcher(node.getPath()).matches()) {
                return false;
            }
            if (currentModel.resolveNode(node.getJcrPath()) != null) {
                return false;
            }
            if (nodeType != null) {
                return nodeType.equals(getPrimaryType(node));
            } else {
                return true;
            }
        }

        String getPrimaryType(DefinitionNodeImpl node) {
            final DefinitionPropertyImpl property = node.getProperty(JCR_PRIMARYTYPE);
            if (property == null) {
                return null;
            }
            return property.getValue().getString();
        }
    }

}
