/**
 * Copyright 2013 52°North Initiative for Geospatial Open Source Software GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.n52.geolabel.component;

import java.util.HashSet;
import java.util.Set;

import javax.faces.bean.ManagedBean;
import javax.faces.bean.RequestScoped;

@ManagedBean
@RequestScoped
public class GeoLabelResourcesBean {

    private boolean resourcesAdded = false;
    private boolean geoLabelServiceFailed = false;
    private Set<String> failedEndpoints = new HashSet<>();

    public boolean isResourcesAdded() {
        return this.resourcesAdded;
    }

    public void setResourcesAdded(boolean resourcesAdded) {
        this.resourcesAdded = resourcesAdded;
    }

    public boolean isGeoLabelServiceFailed() {
        return this.geoLabelServiceFailed;
    }

    public void setGeoLabelServiceFailed(boolean geoLabelServiceFailed) {
        this.geoLabelServiceFailed = geoLabelServiceFailed;
        if ( !geoLabelServiceFailed)
            this.failedEndpoints.clear();
    }

    public void setGeoLabelServiceFailed(String endpoint) {
        this.failedEndpoints.add(endpoint);
        this.geoLabelServiceFailed = true;
    }

    public boolean isGeoLabelServiceFailed(String endpoint) {
        return this.failedEndpoints.contains(endpoint);
    }
}
