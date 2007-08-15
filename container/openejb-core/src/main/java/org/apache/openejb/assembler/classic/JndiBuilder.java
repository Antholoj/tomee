/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.openejb.assembler.classic;

import javax.naming.Context;
import javax.naming.NamingException;
import javax.naming.Reference;
import javax.naming.NameAlreadyBoundException;
import javax.jms.MessageListener;

import org.apache.openejb.DeploymentInfo;
import org.apache.openejb.InterfaceType;
import org.apache.openejb.util.LogCategory;
import org.apache.openejb.util.Logger;
import org.apache.openejb.loader.SystemInstance;
import org.apache.openejb.core.CoreDeploymentInfo;
import org.apache.openejb.core.ivm.naming.BusinessLocalReference;
import org.apache.openejb.core.ivm.naming.BusinessRemoteReference;
import org.apache.openejb.core.ivm.naming.ObjectReference;
import org.apache.openejb.core.ivm.naming.IntraVmJndiReference;
import org.codehaus.swizzle.stream.StringTemplate;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.util.Comparator;


/**
 * @version $Rev$ $Date$
 */
public class JndiBuilder {

    public static final Logger logger = Logger.getInstance(LogCategory.OPENEJB_STARTUP, JndiBuilder.class.getPackage().getName());

    private JndiNameStrategy strategy = new LegacyAddedSuffixStrategy();
    private final Context context;

    public JndiBuilder(Context context) {
        this.context = context;

        String strategyClass = SystemInstance.get().getProperty("openejb.jndiname.strategy.class", LegacyAddedSuffixStrategy.class.getName());
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        try {
            strategy = (JndiNameStrategy) classLoader.loadClass(strategyClass).newInstance();
        } catch (InstantiationException e) {
            throw new IllegalStateException("Could not instantiate JndiNameStrategy: "+strategyClass, e);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Could not access JndiNameStrategy: "+strategyClass, e);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Could not load JndiNameStrategy: "+strategyClass, e);
        } catch (Throwable t){
            throw new IllegalStateException("Could not create JndiNameStrategy: "+strategyClass, t);
        }
    }

    public void build(EjbJarInfo ejbJar, HashMap<String, DeploymentInfo> deployments) {
        for (EnterpriseBeanInfo beanInfo : ejbJar.enterpriseBeans) {
            DeploymentInfo deploymentInfo = deployments.get(beanInfo.ejbDeploymentId);
            bind(deploymentInfo, beanInfo);
        }
    }

    public static interface JndiNameStrategy {

        public static enum Interface {

            REMOTE_HOME(InterfaceType.EJB_HOME, "RemoteHome", "home", ""),
            LOCAL_HOME(InterfaceType.EJB_LOCAL_HOME, "LocalHome", "local-home", "Local"),
            BUSINESS_LOCAL(InterfaceType.BUSINESS_LOCAL, "Local", "business-local", "BusinessLocal"),
            BUSINESS_REMOTE(InterfaceType.BUSINESS_REMOTE, "Remote", "business-remote", "BusinessRemote"),
            SERVICE_ENDPOINT(InterfaceType.SERVICE_ENDPOINT, "Endpoint", "service-endpoint", "ServiceEndpoint");

            private final InterfaceType type;
            private final String annotatedName;
            private final String xmlName;
            private final String xmlNameCc;
            private final String openejbLegacy;

            Interface(InterfaceType type, String annotatedName, String xmlName, String openejbLegacy) {
                this.type = type;
                this.annotatedName = annotatedName;
                this.xmlName = xmlName;
                this.xmlNameCc = camelCase(xmlName);
                this.openejbLegacy = openejbLegacy;
            }

            private String camelCase(String string){
                StringBuilder sb = new StringBuilder();
                String[] strings = string.split("-");
                for (String s : strings) {
                    int l = sb.length();
                    sb.append(s);
                    sb.setCharAt(l, Character.toUpperCase(sb.charAt(l)));
                }
                return sb.toString();
            }


            public InterfaceType getType() {
                return type;
            }

            public String getAnnotatedName() {
                return annotatedName;
            }

            public String getXmlName() {
                return xmlName;
            }

            public String getXmlNameCc() {
                return xmlNameCc;
            }

            public String getOpenejbLegacy() {
                return openejbLegacy;
            }

        }

        public String getName(DeploymentInfo deploymentInfo, Class interfce, Interface type);
    }

    // TODO: put these into the classpath and get them with xbean-finder
    public static class TemplatedStrategy implements JndiNameStrategy {
        private org.codehaus.swizzle.stream.StringTemplate template;

        public TemplatedStrategy() {
            String format = SystemInstance.get().getProperty("openejb.jndiname.format", "{deploymentId}/{interfaceClass.simpleName}");
            this.template = new StringTemplate(format);
        }


        public String getName(DeploymentInfo deploymentInfo, Class interfce, Interface type) {
            Map<String,String> contextData = new HashMap<String,String>();
            contextData.put("moduleId", deploymentInfo.getModuleID());
            contextData.put("ejbType", deploymentInfo.getComponentType().name());
            contextData.put("ejbClass", deploymentInfo.getBeanClass().getName());
            contextData.put("ejbClass.simpleName", deploymentInfo.getBeanClass().getSimpleName());
            contextData.put("ejbName", deploymentInfo.getEjbName());
            contextData.put("deploymentId", deploymentInfo.getDeploymentID().toString());
            contextData.put("interfaceType", type.annotatedName);
            contextData.put("interfaceType.xmlName", type.getXmlName());
            contextData.put("interfaceType.xmlNameCc", type.getXmlNameCc());
            contextData.put("interfaceType.openejbLegacyName", type.getOpenejbLegacy());
            contextData.put("interfaceClass", interfce.getName());
            contextData.put("interfaceClass.simpleName", interfce.getSimpleName());
            return template.apply(contextData);
        }
    }

    public static class LegacyAddedSuffixStrategy implements JndiNameStrategy {

        public String getName(DeploymentInfo deploymentInfo, Class interfce, Interface type) {
            String id = deploymentInfo.getDeploymentID() + "";
            if (id.charAt(0) == '/') {
                id = id.substring(1);
            }

            switch (type) {
                case REMOTE_HOME:
                    return id;
                case LOCAL_HOME:
                    return id + "Local";
                case BUSINESS_LOCAL:
                    return id + "BusinessLocal";
                case BUSINESS_REMOTE:
                    return id + "BusinessRemote";
            }
            return id;
        }
    }

    public static class AddedSuffixStrategy implements JndiNameStrategy {

        public String getName(DeploymentInfo deploymentInfo, Class interfce, Interface type) {
            String id = deploymentInfo.getDeploymentID() + "";
            if (id.charAt(0) == '/') {
                id = id.substring(1);
            }

            switch (type) {
                case REMOTE_HOME:
                    return id + "Remote";
                case LOCAL_HOME:
                    return id + "Local";
                case BUSINESS_LOCAL:
                    return id + "BusinessLocal";
                case BUSINESS_REMOTE:
                    return id + "BusinessRemote";
            }
            return id;
        }
    }


    public static class CommonPrefixStrategy implements JndiNameStrategy {

        public String getName(DeploymentInfo deploymentInfo, Class interfce, Interface type) {
            String id = deploymentInfo.getDeploymentID() + "";
            if (id.charAt(0) == '/') {
                id = id.substring(1);
            }

            switch (type) {
                case REMOTE_HOME:
                    return "component/remote/" + id;
                case LOCAL_HOME:
                    return "component/local/" + id;
                case BUSINESS_REMOTE:
                    return "business/remote/" + id;
                case BUSINESS_LOCAL:
                    return "business/local/" + id;
            }
            return id;
        }
    }

    public static class InterfaceSimpleNameStrategy implements JndiNameStrategy {

        public String getName(DeploymentInfo deploymentInfo, Class interfce, Interface type) {
            return interfce.getSimpleName();
        }
    }

    public JndiNameStrategy getStrategy() {
        return strategy;
    }

    public void bind(DeploymentInfo deploymentInfo, EnterpriseBeanInfo beanInfo) {
        JndiNameStrategy strategy = getStrategy();
        CoreDeploymentInfo deployment = (CoreDeploymentInfo) deploymentInfo;

        Bindings bindings = new Bindings();
        deployment.set(Bindings.class, bindings);

        Object id = deployment.getDeploymentID();
        try {
            Class homeInterface = deployment.getHomeInterface();
            if (homeInterface != null) {

                String name = "openejb/ejb/" + strategy.getName(deployment, deploymentInfo.getHomeInterface(), JndiNameStrategy.Interface.REMOTE_HOME);
                ObjectReference ref = new ObjectReference(deployment.getEJBHome());
                bind(name, ref, bindings, beanInfo);

                name = "openejb/Deployment/" + deployment.getDeploymentID() + "/" + deployment.getRemoteInterface().getName();
                bind(name, ref, bindings, beanInfo);
            }
        } catch (NamingException e) {
            throw new RuntimeException("Unable to bind home interface for deployment " + id, e);
        }

        try {
            Class localHomeInterface = deployment.getLocalHomeInterface();
            if (localHomeInterface != null) {

                String name = "openejb/ejb/" + strategy.getName(deployment, deploymentInfo.getLocalHomeInterface(), JndiNameStrategy.Interface.LOCAL_HOME);
                ObjectReference ref = new ObjectReference(deployment.getEJBLocalHome());
                bind(name, ref, bindings, beanInfo);

                name = "openejb/Deployment/" + deployment.getDeploymentID() + "/" + deployment.getLocalInterface().getName();
                bind(name, ref, bindings, beanInfo);
            }
        } catch (NamingException e) {
            throw new RuntimeException("Unable to bind local interface for deployment " + id, e);
        }

        try {
            List<Class> localInterfaces = deployment.getBusinessLocalInterfaces();
            Class beanClass = deployment.getBeanClass();

            for (Class interfce : deployment.getBusinessLocalInterfaces()) {

                List<Class> interfaces = ProxyInterfaceResolver.getInterfaces(beanClass, interfce, localInterfaces);
                DeploymentInfo.BusinessLocalHome home = deployment.getBusinessLocalHome(interfaces);
                BusinessLocalReference ref = new BusinessLocalReference(home);

                String internalName = "openejb/Deployment/" + deployment.getDeploymentID() + "/" + interfce.getName();
                bind(internalName, ref, bindings, beanInfo);

                try {
                    String externalName = "openejb/ejb/" + strategy.getName(deployment, interfce, JndiNameStrategy.Interface.BUSINESS_LOCAL);
                    bind(externalName, ref, bindings, beanInfo);
                } catch (NamingException dontCareJustYet) {
                }
            }
        } catch (NamingException e) {
            throw new RuntimeException("Unable to bind business local interface for deployment " + id, e);
        }

        try {

            List<Class> remoteInterfaces = deployment.getBusinessRemoteInterfaces();
            Class beanClass = deployment.getBeanClass();

            for (Class interfce : deployment.getBusinessRemoteInterfaces()) {

                List<Class> interfaces = ProxyInterfaceResolver.getInterfaces(beanClass, interfce, remoteInterfaces);
                DeploymentInfo.BusinessRemoteHome home = deployment.getBusinessRemoteHome(interfaces);
                BusinessRemoteReference ref = new BusinessRemoteReference(home);

                String internalName = "openejb/Deployment/" + deployment.getDeploymentID() + "/" + interfce.getName();
                bind(internalName, ref, bindings, beanInfo);

                try {
                    String externalName = "openejb/ejb/" + strategy.getName(deployment, interfce, JndiNameStrategy.Interface.BUSINESS_REMOTE);
                    bind(externalName, ref, bindings, beanInfo);
                } catch (NamingException dontCareJustYet) {
                }
            }
        } catch (NamingException e) {
            throw new RuntimeException("Unable to bind business remote deployment in jndi.", e);
        }

        try {
            if (MessageListener.class.equals(deployment.getMdbInterface())) {
                String name = "openejb/ejb/" + deployment.getDeploymentID().toString();

                String destinationId = deployment.getDestinationId();
                String jndiName = "java:openejb/Resource/" + destinationId;
                Reference reference = new IntraVmJndiReference(jndiName);

                bind(name, reference, bindings, beanInfo);
            }
        } catch (NamingException e) {
            throw new RuntimeException("Unable to bind mdb destination in jndi.", e);
        }
    }


    private void bind(String name, Reference ref, Bindings bindings, EnterpriseBeanInfo beanInfo) throws NamingException {

        if (name.startsWith("openejb/ejb/")) {

            String externalName = name.replaceFirst("openejb/ejb/", "");

            if (beanInfo.jndiNames.contains(externalName)){
                logger.debug("Duplicate: Jndi(name=" + externalName +")");
                return;
            }

            beanInfo.jndiNames.add(externalName);
            logger.info("Jndi(name=" + externalName +")");
        }

        try {
            context.bind(name, ref);
            bindings.add(name);
        } catch (NameAlreadyBoundException e) {
            logger.error("Jndi name could not be bound; it may be taken by another ejb.  Jndi(name=" + name +")");
            throw e;
        }
    }

    private static List<Class> asList(Class interfce) {
        List<Class> list = new ArrayList<Class>();
        list.add(interfce);
        return list;
    }

    protected static final class Bindings {
        private final List<String> bindings = new ArrayList<String>();

        public List<String> getBindings() {
            return bindings;
        }

        public boolean add(String o) {
            return bindings.add(o);
        }
    }

    public static class RemoteInterfaceComparator implements Comparator<Class> {

        public int compare(java.lang.Class a, java.lang.Class b) {
            boolean aIsRmote = java.rmi.Remote.class.isAssignableFrom(a);
            boolean bIsRmote = java.rmi.Remote.class.isAssignableFrom(b);

            if (aIsRmote == bIsRmote) return 0;
            return (aIsRmote)? 1: -1;
        }
    }
}
