
package org.n52.geolabel.server.mapping;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Map.Entry;
import java.util.Set;

import javax.inject.Inject;
import javax.xml.xpath.XPathExpressionException;

import org.codehaus.jackson.map.ObjectMapper;
import org.n52.geolabel.server.config.TransformationDescriptionResources;
import org.n52.geolabel.server.mapping.description.TransformationDescription;
import org.n52.geolabel.server.mapping.description.TransformationDescription.TransformationDescriptionWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.io.ByteStreams;
import com.google.common.io.Files;
import com.google.common.io.Resources;

public class TransformationDescriptionLoader {

    protected static final Logger log = LoggerFactory.getLogger(TransformationDescriptionLoader.class);

    private TransformationDescriptionResources resources;

    @Inject
    public TransformationDescriptionLoader(TransformationDescriptionResources resources) {
        this.resources = resources;
    }

    private TransformationDescription readTransformationDescription(InputStream input) throws IOException {
        /**
         * using JSONJAXB
         */
        // try {
        // JSONJAXBContext context = new JSONJAXBContext(JSONConfiguration.mappedJettison().build(),
        // LabelTransformationDescription.class);
        // // JSONConfiguration jsonConfiguration = context.getJSONConfiguration();
        // JSONUnmarshaller unmarshaller = context.createJSONUnmarshaller();
        //
        // Object obj = unmarshaller.unmarshalFromJSON(input, LabelTransformationDescription.class);
        // LabelTransformationDescription transformationDescription = (LabelTransformationDescription) obj;
        //
        // add(transformationDescription);
        // }
        // catch (JAXBException e) {
        // throw new IOException("Error while parsing transformation description stream", e);
        // }

        /**
         * using Jackson
         */
        ObjectMapper m = new ObjectMapper();
        TransformationDescriptionWrapper wrapper = m.readValue(input, TransformationDescriptionWrapper.class);

        return wrapper.transformationDescription;
    }

    /**
     * Reads {@link LabelTransformationDescription} from passed XML {@link File} .
     *
     */
    private TransformationDescription readTransformationDescription(File descriptionFile) throws FileNotFoundException,
            IOException {
        return readTransformationDescription(new FileInputStream(descriptionFile));
    }

    /**
     * Reads {@link TransformationDescription}s from resources based on all files in a specified folder.
     *
     */
    public void loadLocal(String folder) throws IOException {
        Enumeration<URL> descriptionResources = getClass().getClassLoader().getResources(folder);

        while (descriptionResources.hasMoreElements()) {
            URL element = descriptionResources.nextElement();
            log.debug("Loading local mapping:  {}", element);

            try {
                URI descriptionResourceURI = element.toURI();
                File descriptionResourceFile = new File(descriptionResourceURI);
                for (File descriptionFile : descriptionResourceFile.listFiles())
                    try {
                        log.debug("Loading transformation description: {}", descriptionFile);
                        // String fileExtension = Files.getFileExtension(descriptionFile.getName());
                        readTransformationDescription(descriptionFile);
                    }
                    catch (IOException e) {
                        log.error("Could not read transformation description " + descriptionFile.getName() + ".", e);
                    }
            }
            catch (URISyntaxException e) {
                log.error("Could not read transformation description.", e);
            }
        }
    }

    public Set<TransformationDescription> load() {
        Set<TransformationDescription> descriptions = new HashSet<>();

        // load from server with fallback
        Set<Entry<URL, String>> entrySet = this.resources.getTransformationDescriptionResources().entrySet();
        for (Entry<URL, String> entry : entrySet) {
            log.debug("Loading transformation description from URL {} with fallback {}",
                      entry.getKey(),
                      entry.getValue());

            TransformationDescription td = null;
            try {
                File temp = File.createTempFile("geolabel_transformationDescription_", ".json");
                ByteStreams.copy(Resources.newInputStreamSupplier(entry.getKey()), Files.newOutputStreamSupplier(temp));
                log.debug("Saved transformation description from {} to {}", entry.getKey(), temp);

                td = readTransformationDescription(temp);
                log.debug("Loaded transformation description from {} ", entry.getKey());
            }
            catch (Exception e) {
                log.warn("There was a problem loading transformation description from {}", entry.getKey(), e);
            }

            if (td == null) {
                log.debug("Using fallback {} for URL {}", entry.getValue(), entry.getKey());
                InputStream stream = getClass().getResourceAsStream(entry.getValue());

                try {
                    td = readTransformationDescription(stream);
                    log.debug("Loaded transformation description from {} ", entry.getValue());
                }
                catch (IOException e) {
                    log.error("There was a problem loading transformation description from {}", entry.getValue(), e);
                }
            }

            if (td != null) {
                descriptions.add(td);
                log.debug("Read transformation description: {}", td);
            }
            else
                log.error("Could load neither from URL nor fallback!");
        }

        // init desriptions
        for (TransformationDescription td : descriptions)
            try {
                td.initXPaths();
                log.debug("Added description: {}", td);
            }
            catch (XPathExpressionException e) {
                log.error("Error while compiling XPaths in tranformation description {}", td, e);
            }

        log.debug("Loaded {} transformation descriptions with the names {}",
                  Integer.valueOf(descriptions.size()),
                  getNames(descriptions));

        return descriptions;
    }

    private String getNames(Set<TransformationDescription> descriptions) {
        StringBuilder sb = new StringBuilder();

        for (TransformationDescription td : descriptions) {
            sb.append(td.name);
            sb.append(" ");
        }
        return sb.toString();
    }

}
