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
 * Rename this file to WorkflowManagerImpl.java and compile to get
 * the file ReadObjectWorkflowManagerImpl$WorkflowInvocationImpl.klass
 */
public class ReadObjectWorkflowManagerImpl {

    public static class WorkflowInvocationImpl implements WorkflowInvocation, Map {

        // Serial version Id 7.7.8
        static final long serialVersionUID = -1089293443386933605L;

        Map objects = new HashMap();

        public void readExternal(ObjectInput input) throws IOException, ClassNotFoundException {
            objects.put("category", input.readObject());
            objects.put("uuid", input.readObject());
            objects.put("className", input.readObject());
            objects.put("methodName", input.readObject());
            int length = input.readInt();
            Class[] parameterTypes = new Class[length];
            for(int i=0; i<length; i++) {
                parameterTypes[i] = (Class) input.readObject();
            }
            objects.put("parameterTypes", parameterTypes);
            objects.put("arguments", input.readObject());
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
        public void writeExternal(final ObjectOutput out) throws IOException {
        }

        @Override
        public int size() {
            return objects.size();  //To change body of implemented methods use File | Settings | File Templates.
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
            return objects.get(key);
        }

        @Override
        public Object put(final Object key, final Object value) {
            return null;  //To change body of implemented methods use File | Settings | File Templates.
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
            return objects.keySet();
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
