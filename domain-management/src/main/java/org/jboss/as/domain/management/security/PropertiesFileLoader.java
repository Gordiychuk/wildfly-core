/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2012, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.as.domain.management.security;

import static org.jboss.as.domain.management.logging.DomainManagementLogger.ROOT_LOGGER;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jboss.as.controller.services.path.PathEntry;
import org.jboss.as.controller.services.path.PathManager;
import org.jboss.as.controller.services.path.PathManager.Callback;
import org.jboss.as.controller.services.path.PathManager.Event;
import org.jboss.as.controller.services.path.PathManager.PathEventContext;
import org.jboss.as.domain.management.logging.DomainManagementLogger;
import org.jboss.msc.inject.Injector;
import org.jboss.msc.service.StartContext;
import org.jboss.msc.service.StartException;
import org.jboss.msc.service.StopContext;
import org.jboss.msc.value.InjectedValue;

/**
 * The base class for services depending on loading a properties file, loads the properties on
 * start up and re-loads as required where updates to the file are detected.
 *
 * @author <a href="mailto:darran.lofthouse@jboss.com">Darran Lofthouse</a>
 */
public class PropertiesFileLoader {

    private static final char[] ESCAPE_ARRAY = new char[] { '=', '\\'};
    protected static final String COMMENT_PREFIX = "#";

    /**
     * Pattern that matches :
     * <ul>
     * <li>{@code #key=value}</li>
     * <li>{@code key=value}</li>
     * </ul>
     * {@code value} must be any character except "=" and {@code key} must be any character except "#".<br/>
     * {@code group(1)} returns the key of the property.<br/>
     * {@code group(2)} returns the value of the property.
     */
    public static final Pattern PROPERTY_PATTERN = Pattern.compile("#??([^#]*)=([^=]*)");
    public static final String DISABLE_SUFFIX_KEY = "!disable";

    private final InjectedValue<PathManager> pathManager = new InjectedValue<PathManager>();

    private final String path;
    private final String relativeTo;

    protected File propertiesFile;
    private volatile long fileUpdated = -1;
    private volatile Properties properties = null;

    /*
     * State maintained during persistence.
     */
    private Properties toSave = null;
    /*
     * End of state maintained during persistence.
     */

    public PropertiesFileLoader(final String path, final String relativeTo) {
        this.path = path;
        this.relativeTo = relativeTo;
    }

    public Injector<PathManager> getPathManagerInjectorInjector() {
        return pathManager;
    }

    public void start(StartContext context) throws StartException {
        String file = path;
        if (relativeTo != null) {
            PathManager pm = pathManager.getValue();

            file = pm.resolveRelativePathEntry(file, relativeTo);
            pm.registerCallback(relativeTo, new Callback() {

                @Override
                public void pathModelEvent(PathEventContext eventContext, String name) {
                    if (eventContext.isResourceServiceRestartAllowed() == false) {
                        eventContext.reloadRequired();
                    }
                }

                @Override
                public void pathEvent(Event event, PathEntry pathEntry) {
                    // Service dependencies should trigger a stop and start.
                }
            }, Event.REMOVED, Event.UPDATED);
        }

        propertiesFile = new File(file);
        try {
            getProperties();
        } catch (IOException ioe) {
            throw DomainManagementLogger.ROOT_LOGGER.unableToLoadProperties(ioe);
        }
    }

    public void stop(StopContext context) {
        properties.clear();
        properties = null;
        propertiesFile = null;
    }

    public Properties getProperties() throws IOException {
        loadAsRequired();

        return properties;
    }

    protected void loadAsRequired() throws IOException {
        /*
         * This method does attempt to minimise the effect of race conditions, however this is not overly critical as if you
         * have users attempting to authenticate at the exact point their details are added to the file there is also a chance
         * of a race.
         */

        boolean loadRequired = properties == null || fileUpdated != propertiesFile.lastModified();

        if (loadRequired) {
            synchronized (this) {
                // Cache the value as there is still a chance of further modification.
                long fileLastModified = propertiesFile.lastModified();
                boolean loadReallyRequired = properties == null || fileUpdated != fileLastModified;
                if (loadReallyRequired) {
                    load();
                    // Update this last otherwise the check outside the synchronized block could return true before the file is
                    // set.
                    fileUpdated = fileLastModified;
                }
            }
        }
    }

    protected void load() throws IOException {
        ROOT_LOGGER.debugf("Reloading properties file '%s'", propertiesFile.getAbsolutePath());
        Properties props = new Properties();
        InputStreamReader is = new InputStreamReader(new FileInputStream(propertiesFile), StandardCharsets.UTF_8);
        try {
            props.load(is);
        } finally {
            is.close();
        }
        verifyProperties(props);
        properties = props;
    }

    /**
     * Saves changes in properties file. It reads the property file into memory, modifies it and saves it back to the file.
     *
     * @throws IOException
     */
    public synchronized void persistProperties() throws IOException {
        beginPersistence();

        // Read the properties file into memory
        // Shouldn't be so bad - it's a small file
        List<String> content = readFile(propertiesFile);

        BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(propertiesFile), StandardCharsets.UTF_8));

        try {
            for (String line : content) {
                String trimmed = line.trim();
                if (trimmed.length() == 0) {
                    bw.newLine();
                } else {
                    Matcher matcher = PROPERTY_PATTERN.matcher(trimmed);
                    if (matcher.matches()) {
                        final String key = cleanKey(matcher.group(1));
                        if (toSave.containsKey(key) || toSave.containsKey(key + DISABLE_SUFFIX_KEY)) {
                            writeProperty(bw, key, matcher.group(2));
                            toSave.remove(key);
                            toSave.remove(key + DISABLE_SUFFIX_KEY);
                        } else {
                            write(bw, line, true);
                        }
                    } else {
                        write(bw, line, true);
                    }
                }
            }

            endPersistence(bw);
        } finally {
            safeClose(bw);
        }
    }

    protected String cleanKey(final String key) {
        char[] keyChars = key.toCharArray();
        char[] cleaned = new char[keyChars.length];
        int keyPos = 0;
        int cleanedPos = 0;

        while (keyPos < keyChars.length) {
            if (keyChars[keyPos++] == '\\') {
                char current = keyChars[keyPos];
                switch (current) {
                    case 't':
                        cleaned[cleanedPos++] = '\t';
                        break;
                    case 'r':
                        cleaned[cleanedPos++] = '\r';
                        break;
                    case 'n':
                        cleaned[cleanedPos++] = '\n';
                        break;
                    case 'f':
                        cleaned[cleanedPos++] = '\f';
                        break;
                    default:
                        cleaned[cleanedPos++] = current;
                }

                keyPos++;
            } else {
                cleaned[cleanedPos++] = keyChars[keyPos - 1];
            }
        }

        return new String(cleaned, 0, cleanedPos);
    }

    protected List<String> readFile(File file) throws IOException {
        FileReader fileReader = new FileReader(file);
        BufferedReader bufferedFileReader = new BufferedReader(fileReader);
        List<String> content = new ArrayList<String>();
        try {
            String line;
            while ((line = bufferedFileReader.readLine()) != null) {
                addLineContent(bufferedFileReader, content, line);
            }
        } finally {
            safeClose(bufferedFileReader);
            safeClose(fileReader);
        }
        return content;
    }

    /**
     * Add the line to the content
     *
     * @param bufferedFileReader The file reader
     * @param content            The content of the file
     * @param line               The current read line
     * @throws IOException
     */
    protected void addLineContent(BufferedReader bufferedFileReader, List<String> content, String line) throws IOException {
        content.add(line);
    }

    /**
     * Method called to indicate the start of persisting the properties.
     *
     * @throws IOException
     */
    protected void beginPersistence() throws IOException {
        toSave = (Properties) properties.clone();
    }

    protected void write(final BufferedWriter writer, final String line, final boolean newLine) throws IOException {
        writer.append(line);
        if (newLine) {
            writer.newLine();
        }
    }

    /**
     * Method called to indicate persisting the properties file is now complete.
     *
     * @throws IOException
     */
    protected void endPersistence(final BufferedWriter writer) throws IOException {
        // Append any additional users to the end of the file.
        for (Object currentKey : toSave.keySet()) {
            String key = (String) currentKey;
            if (!key.contains(DISABLE_SUFFIX_KEY)) {
                writeProperty(writer, key, null);
            }
        }

        toSave = null;
    }

    private void writeProperty(BufferedWriter writer, String key, String currentValue) throws IOException {
        String escapedKey = escapeString(key, ESCAPE_ARRAY);
        final String value = getValue(key, currentValue);
        final String newLine;
        if (Boolean.valueOf(toSave.getProperty(key + DISABLE_SUFFIX_KEY))) {
            // Commented property
            newLine = "#" + escapedKey + "=" + value;
        } else {
            newLine = escapedKey + "=" + value;
        }
        write(writer, newLine, true);
    }

    /**
     * Get the value of the property.<br/>
     * If the value to save is null, return the previous value (enable/disable mode).
     *
     * @param key The key of the property
     * @param previousValue The previous value
     * @return The value of the property
     */
    private String getValue(String key, String previousValue) {
        final String value;
        final String valueUpdated = toSave.getProperty(key);
        if (valueUpdated == null) {
            value = previousValue;
        } else {
            value = valueUpdated;
        }
        return value;
    }

    public static String escapeString(String name, char[] escapeArray) {
        Arrays.sort(escapeArray);
        for(int i = 0; i < name.length(); ++i) {
            char ch = name.charAt(i);
            if (Arrays.binarySearch(escapeArray, ch) >= 0) {
                StringBuilder builder = new StringBuilder();
                builder.append(name, 0, i);
                builder.append('\\').append(ch);
                for(int j = i + 1; j < name.length(); ++j) {
                    ch = name.charAt(j);
                    if (Arrays.binarySearch(escapeArray, ch) >= 0) {
                        builder.append('\\');
                    }
                    builder.append(ch);
                }
                return builder.toString();
            }
        }
        return name;
    }

    protected void safeClose(final Closeable c) {
        try {
            c.close();
        } catch (IOException ignored) {
        }
    }

    /**
     * Provides the base class with an opportunity to verify the contents of the properties before they are used.
     *
     * @param properties - The Properties instance to verify.
     */
    protected void verifyProperties(Properties properties) throws IOException {
    };

}
