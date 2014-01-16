/*
 * Copyright 2012-2013 Hippo B.V. (http://www.onehippo.com)
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *  http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.hippoecm.repository.concurrent.action;

import java.util.Random;

import javax.jcr.Node;

public class RenameAssetAction extends AbstractAssetActionsWorkflowAction {

    private final Random random = new Random(System.currentTimeMillis());

    public RenameAssetAction(final ActionContext context) {
        super(context);
    }

    @Override
    protected String getWorkflowMethodName() {
        return "move";
    }

    @Override
    protected Node doExecute(Node node) throws Exception {
        Node parent = node.getParent().getParent();
        String newName = node.getName();
        do {
            newName += "." + random.nextInt(10);
        } while (parent.hasNode(newName));
        getWorkflow(node).rename(newName);
        return parent.getNode(newName).getNode(newName);
    }
}
