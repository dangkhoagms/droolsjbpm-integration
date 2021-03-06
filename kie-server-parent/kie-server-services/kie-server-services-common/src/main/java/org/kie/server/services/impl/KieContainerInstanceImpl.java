/*
 * Copyright 2015 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
*/

package org.kie.server.services.impl;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.drools.compiler.kie.builder.impl.InternalKieScanner;
import org.drools.core.impl.InternalKieContainer;
import org.kie.api.KieServices;
import org.kie.api.builder.Message.Level;
import org.kie.api.event.kiescanner.KieScannerEventListener;
import org.kie.api.event.kiescanner.KieScannerStatusChangeEvent;
import org.kie.api.event.kiescanner.KieScannerUpdateResultsEvent;
import org.kie.server.api.marshalling.Marshaller;
import org.kie.server.api.marshalling.MarshallerFactory;
import org.kie.server.api.marshalling.MarshallingFormat;
import org.kie.server.api.model.KieContainerResource;
import org.kie.server.api.model.KieContainerStatus;
import org.kie.server.api.model.KieScannerResource;
import org.kie.server.api.model.KieScannerStatus;
import org.kie.server.api.model.ReleaseId;
import org.kie.server.services.api.KieContainerInstance;

public class KieContainerInstanceImpl implements KieContainerInstance {

    private KieContainerResource               resource;
    private InternalKieContainer               kieContainer;
    private InternalKieScanner                 scanner;
    private transient Map<MarshallingFormat, Marshaller> marshallers;

    private transient Map<String, Object> serviceContainer;

    private transient Set<Class<?>> extraClasses = new HashSet<Class<?>>();
    
    private transient KieServerScannerEventListener scannerListener;

    public KieContainerInstanceImpl(String containerId, KieContainerStatus status) {
        this(containerId, status, null);
    }

    public KieContainerInstanceImpl(String containerId, KieContainerStatus status, InternalKieContainer kieContainer) {
        this(containerId, status, kieContainer, null, null);
    }

    public KieContainerInstanceImpl(String containerId, KieContainerStatus status, InternalKieContainer kieContainer, ReleaseId releaseId, KieServerImpl kieServer) {
        super();
        this.kieContainer = kieContainer;
        this.resource = new KieContainerResource(containerId, releaseId, status);
        // set the default scanner state to DISPOSED (which is the actual default state)
        // this way we don't need to do null checks all around for the scanner resource
        this.resource.setScanner(new KieScannerResource(KieScannerStatus.DISPOSED));
        this.marshallers = new ConcurrentHashMap<MarshallingFormat, Marshaller>();
        this.serviceContainer = new ConcurrentHashMap<String, Object>();
        updateReleaseId();
        this.scannerListener = new KieServerScannerEventListener(kieServer, this);
    }

    /**
     * Maps the {@link InternalKieScanner.Status} to a scanner status used by KIE Server.
     *
     * @param status {@link InternalKieScanner.Status} to be converted
     *
     * @return {@link KieScannerStatus} which maps to the specified {@link InternalKieScanner.Status}
     */
    public static KieScannerStatus mapScannerStatus(InternalKieScanner.Status status) {
        switch (status) {
            case STARTING:
                return KieScannerStatus.CREATED;
            case RUNNING:
                return KieScannerStatus.STARTED;
            case SCANNING:
            case UPDATING:
                return KieScannerStatus.SCANNING;
            case STOPPED:
                return KieScannerStatus.STOPPED;
            case SHUTDOWN:
                return KieScannerStatus.DISPOSED;
            default:
                return KieScannerStatus.UNKNOWN;
        }
    }

    public String getContainerId() {
        return resource.getContainerId();
    }

    public void setContainerId(String containerId) {
        this.resource.setContainerId( containerId );
    }

    public InternalKieContainer getKieContainer() {
        return kieContainer;
    }

    public void setKieContainer(InternalKieContainer kieContainer) {
        this.kieContainer = kieContainer;
        updateReleaseId();
    }

    public KieContainerStatus getStatus() {
        return resource.getStatus();
    }

    public void setStatus(KieContainerStatus status) {
        this.resource.setStatus( status );
    }

    @Override
    public KieContainerResource getResource() {
        updateReleaseId();
        return resource;
    }

    public void setResource(KieContainerResource resource) {
        this.resource = resource;
    }

    public InternalKieScanner getScanner() {
        return this.scanner;
    }

    public void createScanner() {
        this.scanner = (InternalKieScanner) KieServices.Factory.get().newKieScanner(kieContainer);
        // we also need to update the underlaying scanner resource to avoid inconsistency
        KieScannerStatus status = KieContainerInstanceImpl.mapScannerStatus(scanner.getStatus());
        long pollingInterval = scanner.getPollingInterval();
        resource.setScanner(new KieScannerResource(status, pollingInterval));
    }

    public void startScanner(long pollingInterval) {
        if (this.scanner == null) {
            throw new IllegalStateException("Can not start non-existing (null) scanner!");
        }
        this.scanner.start(pollingInterval);
        this.scanner.addListener(scannerListener);
        this.getResource().setScanner(new KieScannerResource(KieScannerStatus.STARTED, pollingInterval));
    }

    public void scanNow() {
        if (this.scanner == null) {
            throw new IllegalStateException("Can not run (scanNow) non-existing (null) scanner!");
        }
        this.scanner.scanNow();
    }

    public void stopScanner() {
        if (this.scanner == null) {
            throw new IllegalStateException("Can not stop non-existing (null) scanner!");
        }
        this.scanner.stop();  
        this.scanner.removeListener(scannerListener);
        this.getResource().getScanner().setStatus(KieScannerStatus.STOPPED);
    }

    public void disposeScanner() {
        if (this.scanner == null) {
            throw new IllegalStateException("Can not dispose non-existing (null) scanner!");
        }
        this.scanner.shutdown();
        this.scanner = null;
        this.getResource().setScanner(new KieScannerResource(KieScannerStatus.DISPOSED));
    }

    public Marshaller getMarshaller(MarshallingFormat format) {
        synchronized ( marshallers ) {
            Marshaller marshaller = marshallers.get( format );
            if ( marshaller == null ) {
                marshaller = MarshallerFactory.getMarshaller( getExtraClasses(), format, this.kieContainer.getClassLoader() );
                this.marshallers.put( format, marshaller );
            }
            return marshaller;
        }
    }

    public void disposeMarshallers() {
        synchronized ( marshallers ) {
            for ( Marshaller marshaller : this.marshallers.values() ) {
                marshaller.dispose();
            }
            this.marshallers.clear();
        }
    }

    @Override
    public void addService(Object service) {
        if (service == null) {
            return;
        }
        if (serviceContainer.containsKey(service.getClass().getName())) {
            throw new IllegalStateException("Service " + service.getClass().getName() + " already exists");
        }
        serviceContainer.put(service.getClass().getName(), service);
    }

    @Override
    public boolean addExtraClasses(Set<Class<?>> extraJaxbClassList) {
        return this.extraClasses.addAll( extraJaxbClassList );
    }

    @Override
    public void clearExtraClasses() {
        this.extraClasses.clear();
    }

    @Override
    public Set<Class<?>> getExtraClasses() {
        return this.extraClasses;
    }

    @Override
    public <T> T getService(Class<T> serviceType) {
        return (T) this.serviceContainer.get(serviceType.getName());
    }

    @Override
    public <T> T removeService(Class<T> serviceType) {
        return (T) this.serviceContainer.remove(serviceType.getName());
    }

    @Override
    public String toString() {
        return resource.toString();
    }

    protected boolean updateReleaseId() {
        ReleaseId oldReleaseId = this.resource.getReleaseId();
        ReleaseId oldResolvedReleaseId = this.resource.getResolvedReleaseId();
        if ( kieContainer != null ) {
            this.resource.setReleaseId( new ReleaseId( kieContainer.getContainerReleaseId() ) );
            this.resource.setResolvedReleaseId( new ReleaseId( kieContainer.getReleaseId() ) );
        }

        if (releaseIdUpdated(oldReleaseId, this.resource.getReleaseId())
                || releaseIdUpdated(oldResolvedReleaseId, this.resource.getResolvedReleaseId())) {
            disposeMarshallers();
            return true;
        }
        
        return false;
    }

    /**
     * Checks whether the releaseId was updated (i.e. the old one is different from the new one).
     *
     * @param oldReleaseId old ReleaseId
     * @param newReleaseId new releaseId
     * @return true if the second (new) releaseId is different and thus was updated; otherwise false
     */
    private boolean releaseIdUpdated(ReleaseId oldReleaseId, ReleaseId newReleaseId) {
        if (oldReleaseId == null && newReleaseId == null) {
            return false;
        }
        if (oldReleaseId == null && newReleaseId != null) {
            return true;
        }
        // now both releaseIds are non-null, so it is safe to call equals()
        return !oldReleaseId.equals(newReleaseId);
    }

}
