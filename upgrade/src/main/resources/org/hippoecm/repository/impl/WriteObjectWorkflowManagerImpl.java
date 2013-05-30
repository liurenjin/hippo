/*
 *  Copyright 2013 Hippo.
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
package org.hippoecm.repository.impl;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.hippoecm.repository.api.WorkflowException;
import org.hippoecm.repository.ext.WorkflowInvocation;

/**
 * Rename this file to WorkflowManagerImpl.java compile and rename to get
 * the file WriteObjectWorkflowmanagerImpl$WorkflowInvocationImpl.klass
 */
public class WriteObjectWorkflowManagerImpl {

    public static class WorkflowInvocationImpl implements WorkflowInvocation, Map {

        // Serial version Id 7.7.9
        static final long serialVersionUID = -8455388007131156961L;

        transient Map data = new HashMap();

        @Override
        public void writeExternal(ObjectOutput output) throws IOException {
            output.writeObject(data.get("category"));
            // workflowName
            output.writeObject(null);
            output.writeObject(data.get("uuid"));
            output.writeObject(data.get("className"));
            output.writeObject(data.get("methodName"));
            Class[] parameterTypes = (Class[]) data.get("parameterTypes");
            if (parameterTypes == null) {
                parameterTypes = new Class[]{};
            }
            output.writeInt(parameterTypes.length);
            for (final Class parameterType : parameterTypes) {
                output.writeObject(parameterType);
            }
            output.writeObject(data.get("arguments"));
            // interactionId
            output.writeObject(null);
            // interaction
            output.writeObject(null);
        }

        @Override
        public void readExternal(final ObjectInput in) throws IOException, ClassNotFoundException {
            //To change body of implemented methods use File | Settings | File Templates.
        }


        @Override
        public Object invoke(final Session session) throws RepositoryException, WorkflowException {
            return null;
        }

        @Override
        public Node getSubject() {
            return null;
        }

        @Override
        public void setSubject(final Node node) {
        }

        @Override
        public int size() {
            return 0;  //To change body of implemented methods use File | Settings | File Templates.
        }

        @Override
        public boolean isEmpty() {
            return false;  //To change body of implemented methods use File | Settings | File Templates.
        }

        @Override
        public boolean containsKey(final Object key) {
            return false;  //To change body of implemented methods use File | Settings | File Templates.
        }

        @Override
        public boolean containsValue(final Object value) {
            return false;  //To change body of implemented methods use File | Settings | File Templates.
        }

        @Override
        public Object get(final Object key) {
            return data.get(key);
        }

        @Override
        public Object put(final Object key, final Object value) {
            return data.put(key, value);
        }

        @Override
        public Object remove(final Object key) {
            return null;  //To change body of implemented methods use File | Settings | File Templates.
        }

        @Override
        public void putAll(final Map m) {
            //To change body of implemented methods use File | Settings | File Templates.
        }

        @Override
        public void clear() {
            //To change body of implemented methods use File | Settings | File Templates.
        }

        @Override
        public Set keySet() {
            return null;  //To change body of implemented methods use File | Settings | File Templates.
        }

        @Override
        public Collection values() {
            return null;  //To change body of implemented methods use File | Settings | File Templates.
        }

        @Override
        public Set<Entry> entrySet() {
            return null;  //To change body of implemented methods use File | Settings | File Templates.
        }
    }
}
