/*
 * Copyright (C) 2015 Glyptodon LLC
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.glyptodon.guacamole.auth.jdbc.connection;

import com.google.inject.Inject;
import com.google.inject.Provider;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.glyptodon.guacamole.auth.jdbc.tunnel.GuacamoleTunnelService;
import org.glyptodon.guacamole.GuacamoleException;
import org.glyptodon.guacamole.auth.jdbc.JDBCEnvironment;
import org.glyptodon.guacamole.auth.jdbc.base.ModeledGroupedDirectoryObject;
import org.glyptodon.guacamole.form.Field;
import org.glyptodon.guacamole.form.Form;
import org.glyptodon.guacamole.form.NumericField;
import org.glyptodon.guacamole.net.GuacamoleTunnel;
import org.glyptodon.guacamole.net.auth.Connection;
import org.glyptodon.guacamole.net.auth.ConnectionRecord;
import org.glyptodon.guacamole.protocol.GuacamoleClientInformation;
import org.glyptodon.guacamole.protocol.GuacamoleConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An implementation of the Connection object which is backed by a database
 * model.
 *
 * @author James Muehlner
 * @author Michael Jumper
 */
public class ModeledConnection extends ModeledGroupedDirectoryObject<ConnectionModel>
    implements Connection {

    /**
     * Logger for this class.
     */
    private static final Logger logger = LoggerFactory.getLogger(ModeledConnection.class);

    /**
     * The name of the attribute which controls the maximum number of
     * concurrent connections.
     */
    public static final String MAX_CONNECTIONS_NAME = "max-connections";

    /**
     * The name of the attribute which controls the maximum number of
     * concurrent connections per user.
     */
    public static final String MAX_CONNECTIONS_PER_USER_NAME = "max-connections-per-user";

    /**
     * All attributes related to restricting user accounts, within a logical
     * form.
     */
    public static final Form CONCURRENCY_LIMITS = new Form("concurrency", Arrays.<Field>asList(
        new NumericField(MAX_CONNECTIONS_NAME),
        new NumericField(MAX_CONNECTIONS_PER_USER_NAME)
    ));

    /**
     * All possible attributes of connection objects organized as individual,
     * logical forms.
     */
    public static final Collection<Form> ATTRIBUTES = Collections.unmodifiableCollection(Arrays.asList(
        CONCURRENCY_LIMITS
    ));

    /**
     * The environment of the Guacamole server.
     */
    @Inject
    private JDBCEnvironment environment;

    /**
     * Service for managing connections.
     */
    @Inject
    private ConnectionService connectionService;

    /**
     * Service for creating and tracking tunnels.
     */
    @Inject
    private GuacamoleTunnelService tunnelService;

    /**
     * Provider for lazy-loaded, permission-controlled configurations.
     */
    @Inject
    private Provider<ModeledGuacamoleConfiguration> configProvider;
    
    /**
     * The manually-set GuacamoleConfiguration, if any.
     */
    private GuacamoleConfiguration config = null;

    /**
     * Creates a new, empty ModeledConnection.
     */
    public ModeledConnection() {
    }

    @Override
    public String getName() {
        return getModel().getName();
    }

    @Override
    public void setName(String name) {
        getModel().setName(name);
    }

    @Override
    public GuacamoleConfiguration getConfiguration() {

        // If configuration has been manually set, return that
        if (config != null)
            return config;

        // Otherwise, return permission-controlled configuration
        ModeledGuacamoleConfiguration restrictedConfig = configProvider.get();
        restrictedConfig.init(getCurrentUser(), getModel());
        return restrictedConfig;

    }

    @Override
    public void setConfiguration(GuacamoleConfiguration config) {

        // Store manually-set configuration internally
        this.config = config;

        // Update model
        getModel().setProtocol(config.getProtocol());
        
    }

    @Override
    public List<? extends ConnectionRecord> getHistory() throws GuacamoleException {
        return connectionService.retrieveHistory(getCurrentUser(), this);
    }

    @Override
    public GuacamoleTunnel connect(GuacamoleClientInformation info) throws GuacamoleException {
        return connectionService.connect(getCurrentUser(), this, info);
    }
    
    @Override
    public void wakeOnLan() throws GuacamoleException {
        connectionService.wakeOnLan(getCurrentUser(), this);
    }

    @Override
    public int getActiveConnections() {
        return tunnelService.getActiveConnections(this).size();
    }

    @Override
    public Map<String, String> getAttributes() {

        Map<String, String> attributes = new HashMap<String, String>();

        // Set connection limit attribute
        attributes.put(MAX_CONNECTIONS_NAME, NumericField.format(getModel().getMaxConnections()));

        // Set per-user connection limit attribute
        attributes.put(MAX_CONNECTIONS_PER_USER_NAME, NumericField.format(getModel().getMaxConnectionsPerUser()));

        return attributes;
    }

    @Override
    public void setAttributes(Map<String, String> attributes) {

        // Translate connection limit attribute
        try { getModel().setMaxConnections(NumericField.parse(attributes.get(MAX_CONNECTIONS_NAME))); }
        catch (NumberFormatException e) {
            logger.warn("Not setting maximum connections: {}", e.getMessage());
            logger.debug("Unable to parse numeric attribute.", e);
        }

        // Translate per-user connection limit attribute
        try { getModel().setMaxConnectionsPerUser(NumericField.parse(attributes.get(MAX_CONNECTIONS_PER_USER_NAME))); }
        catch (NumberFormatException e) {
            logger.warn("Not setting maximum connections per user: {}", e.getMessage());
            logger.debug("Unable to parse numeric attribute.", e);
        }

    }

    /**
     * Returns the maximum number of connections that should be allowed to this
     * connection overall. If no limit applies, zero is returned.
     *
     * @return
     *     The maximum number of connections that should be allowed to this
     *     connection overall, or zero if no limit applies.
     *
     * @throws GuacamoleException
     *     If an error occurs while parsing the concurrency limit properties
     *     specified within guacamole.properties.
     */
    public int getMaxConnections() throws GuacamoleException {

        // Pull default from environment if connection limit is unset
        Integer value = getModel().getMaxConnections();
        if (value == null)
            return environment.getDefaultMaxConnections();

        // Otherwise use defined value
        return value;

    }

    /**
     * Returns the maximum number of connections that should be allowed to this
     * connection for any individual user. If no limit applies, zero is
     * returned.
     *
     * @return
     *     The maximum number of connections that should be allowed to this
     *     connection for any individual user, or zero if no limit applies.
     *
     * @throws GuacamoleException
     *     If an error occurs while parsing the concurrency limit properties
     *     specified within guacamole.properties.
     */
    public int getMaxConnectionsPerUser() throws GuacamoleException {

        // Pull default from environment if per-user connection limit is unset
        Integer value = getModel().getMaxConnectionsPerUser();
        if (value == null)
            return environment.getDefaultMaxConnectionsPerUser();

        // Otherwise use defined value
        return value;

    }

}
