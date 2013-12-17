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

package org.n52.geolabel.server.mapping;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.Date;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.n52.geolabel.commons.Label;
import org.n52.geolabel.server.config.GeoLabelConfig;
import org.n52.geolabel.server.config.TransformationDescriptionResources;
import org.n52.geolabel.server.mapping.description.TransformationDescription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;

/**
 * Transforms metadata XML into {@link Label} representations
 *
 */
@Singleton
public class MetadataTransformer {

    protected static final Logger log = LoggerFactory.getLogger(MetadataTransformer.class);

    public static int CACHE_MAX_LABELS = 100; // TODO make available as property
    public static int CACHE_MAX_HOURS = 48;// TODO make available as property

    /**
     * Acts as key for caching {@link Label}s based on its metadata and/or feedback source. Since
     * metadata/feedback sources are handled equally, all combinations of equal metadata/feedback source urls
     * are identical.
     *
     */
    @XmlRootElement(name = "CacheMapping")
    public static class LabelUrlKey {
        protected URL metadataUrl;
        protected URL feedbackUrl;
        protected Date cacheWriteTime;

        LabelUrlKey() {
        }

        public LabelUrlKey(URL metadataUrl, URL feedbackUrl) {
            this.metadataUrl = metadataUrl;
            this.feedbackUrl = feedbackUrl;
        }

        @XmlAttribute
        public URL getFeedbackUrl() {
            return this.feedbackUrl;
        }

        @XmlAttribute
        public URL getMetadataUrl() {
            return this.metadataUrl;
        }

        @XmlAttribute(name = "cachedAt")
        public Date getCacheWriteTime() {
            return this.cacheWriteTime;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof LabelUrlKey) {
                LabelUrlKey other = (LabelUrlKey) obj;

                if ( (this.metadataUrl != null && this.metadataUrl.equals(other.metadataUrl) || this.metadataUrl == other.metadataUrl)
                        && (this.feedbackUrl != null && this.feedbackUrl.equals(other.feedbackUrl) || this.feedbackUrl == other.feedbackUrl))
                    return true;

                if ( (this.metadataUrl != null && this.metadataUrl.equals(other.feedbackUrl) || this.metadataUrl == other.feedbackUrl)
                        && (this.feedbackUrl != null && this.feedbackUrl.equals(other.metadataUrl) || this.feedbackUrl == other.metadataUrl))
                    return true;
            }
            return super.equals(obj);
        }

        @Override
        public int hashCode() {
            int hash = 0;
            if (this.metadataUrl != null)
                hash += this.metadataUrl.hashCode();
            if (this.feedbackUrl != null)
                hash += this.feedbackUrl.hashCode();

            return hash;
        }

        @Override
        public String toString() {
            StringBuilder builder = new StringBuilder();
            builder.append("LabelUrlKey [");
            if (this.metadataUrl != null) {
                builder.append("metadataUrl=");
                builder.append(this.metadataUrl);
                builder.append(", ");
            }
            if (this.feedbackUrl != null) {
                builder.append("feedbackUrl=");
                builder.append(this.feedbackUrl);
                builder.append(", ");
            }
            if (this.cacheWriteTime != null) {
                builder.append("cacheWriteTime=");
                builder.append(this.cacheWriteTime);
            }
            builder.append("]");
            return builder.toString();
        }

    }

    public class RemoteTransformationDescription {

        protected URL online;

        protected String fallbackFile;

        protected RemoteTransformationDescription description;

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + ( (this.fallbackFile == null) ? 0 : this.fallbackFile.hashCode());
            result = prime * result + ( (this.online == null) ? 0 : this.online.hashCode());
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            RemoteTransformationDescription other = (RemoteTransformationDescription) obj;
            if (this.fallbackFile == null) {
                if (other.fallbackFile != null)
                    return false;
            }
            else if ( !this.fallbackFile.equals(other.fallbackFile))
                return false;
            if (this.online == null) {
                if (other.online != null)
                    return false;
            }
            else if ( !this.online.equals(other.online))
                return false;
            return true;
        }

    }

    private LoadingCache<LabelUrlKey, Label> labelUrlCache = CacheBuilder.newBuilder().maximumSize(CACHE_MAX_LABELS).expireAfterWrite(CACHE_MAX_HOURS,
                                                                                                                                      TimeUnit.HOURS).build(new CacheLoader<LabelUrlKey, Label>() {
        @Override
        public Label load(LabelUrlKey key) throws Exception {
            key.cacheWriteTime = new Date();
            log.info("Generating new GEO label for cache from urls {}", key);

            Label label = new Label();

            if (key.feedbackUrl != null)
                label = updateGeoLabel(key.feedbackUrl, label);
            if (key.metadataUrl != null)
                label = updateGeoLabel(key.metadataUrl, label);

            return label;
        }
    });

    private Set<TransformationDescription> transformationDescriptions;

    private TransformationDescriptionLoader loader;

    @Inject
    public MetadataTransformer(TransformationDescriptionResources resources) {
        this.loader = new TransformationDescriptionLoader(resources);
        log.debug("NEW {}", this);
    }

    /**
     * Reads the supplied metadata XML stream and applies all loaded transformation descriptions to update a
     * {@link Label} instance
     *
     * @param xmlInputStream
     * @param label
     * @return
     * @throws IOException
     *         If reading XML stream or initial loading of transformation descriptions fails
     */
    public Label updateGeoLabel(InputStream xmlInputStream, Label label) throws IOException {
        if (this.transformationDescriptions == null)
            readTransformationDescriptions();

        Document doc;
        try {
            // Read supplied metadata xml
            DocumentBuilderFactory domFactory = DocumentBuilderFactory.newInstance();
            domFactory.setNamespaceAware(true);
            DocumentBuilder builder = domFactory.newDocumentBuilder();
            doc = builder.parse(xmlInputStream);

            log.debug("Loaded metadata XML: {}, first child name: {}", doc, doc.getFirstChild().getNodeName());
        }
        catch (Exception e) {
            throw new IOException("Could not parse supplied metadata xml", e);
        }

        for (TransformationDescription transformer : this.transformationDescriptions)
            transformer.updateGeoLabel(label, doc);

        return label;
    }

    private void readTransformationDescriptions() {
        this.transformationDescriptions = this.loader.load();
    }

    /**
     * See {@link MetadataTransformer#updateGeoLabel(InputStream, Label)}.Loads metadata stream from
     * {@link URL}.
     *
     * @param metadataUrl
     * @param label
     * @return
     * @throws IOException
     */
    public Label updateGeoLabel(URL metadataUrl, Label label) throws IOException {
        try {
            URLConnection con = metadataUrl.openConnection();
            con.setConnectTimeout(GeoLabelConfig.CONNECT_TIMEOUT);
            con.setReadTimeout(GeoLabelConfig.READ_TIMEOUT);
            InputStream is = con.getInputStream();

            return updateGeoLabel(is, label);
        }
        catch (Exception e) {
            log.debug("Could not update label with URL {}", metadataUrl, e);
            label.setError(e);
            return label;
        }
    }

    /**
     * Returns new {@link Label} instance from supplied metadata XML stream. See
     * {@link MetadataTransformer#updateGeoLabel(InputStream, Label)}
     *
     * @param xml
     * @return
     * @throws IOException
     */
    public Label createGeoLabel(InputStream xml) throws IOException {
        return updateGeoLabel(xml, new Label());
    }

    /**
     * Returns new {@link Label} instance from supplied metadata XML {@link URL} reference. See
     * {@link MetadataTransformer#updateGeoLabel(InputStream, Label)}
     *
     * @param metadataUrl
     * @return
     * @throws IOException
     */
    public Label createGeoLabel(URL metadataUrl) throws IOException {
        return updateGeoLabel(metadataUrl, new Label());
    }

    /**
     * Returns a {@link Label} from metadata and/or feedback {@link URL}. Results are cached.
     *
     * @param metadataURL
     * @param feedbackURL
     * @return
     * @throws IOException
     */
    public Label getLabel(URL metadataURL, URL feedbackURL) throws IOException {
        log.debug("Creating label for metadata {} and feedback {}", metadataURL, feedbackURL);

        try {
            LabelUrlKey labelUrlKey = new LabelUrlKey(metadataURL, feedbackURL);
            return this.labelUrlCache.get(labelUrlKey);
        }
        catch (ExecutionException e) {
            throw new IOException(e.getCause());
        }
    }

    public Set<LabelUrlKey> getCacheContent() {
        return this.labelUrlCache.asMap().keySet();
    }

    public Set<RemoteTransformationDescription> getMappings() {
        // TODO Auto-generated method stub
        return null;
    }
}
