/*
 * Copyright (C) 2013 Glyptodon LLC
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

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.glyptodon.guacamole.auth.jdbc.user.AuthenticatedUser;
import org.glyptodon.guacamole.auth.jdbc.base.ModeledDirectoryObjectMapper;
import org.glyptodon.guacamole.auth.jdbc.tunnel.GuacamoleTunnelService;
import org.glyptodon.guacamole.GuacamoleClientException;
import org.glyptodon.guacamole.GuacamoleException;
import org.glyptodon.guacamole.GuacamoleSecurityException;
import org.glyptodon.guacamole.auth.jdbc.base.ModeledGroupedDirectoryObjectService;
import org.glyptodon.guacamole.auth.jdbc.permission.ConnectionPermissionMapper;
import org.glyptodon.guacamole.auth.jdbc.permission.ObjectPermissionMapper;
import org.glyptodon.guacamole.net.GuacamoleTunnel;
import org.glyptodon.guacamole.net.auth.Connection;
import org.glyptodon.guacamole.net.auth.ConnectionRecord;
import org.glyptodon.guacamole.net.auth.permission.ObjectPermission;
import org.glyptodon.guacamole.net.auth.permission.ObjectPermissionSet;
import org.glyptodon.guacamole.net.auth.permission.SystemPermission;
import org.glyptodon.guacamole.net.auth.permission.SystemPermissionSet;
import org.glyptodon.guacamole.protocol.GuacamoleClientInformation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Service which provides convenience methods for creating, retrieving, and
 * manipulating connections.
 *
 * @author Michael Jumper, James Muehlner
 */
public class ConnectionService extends ModeledGroupedDirectoryObjectService<ModeledConnection, Connection, ConnectionModel> {

    /**
     * Logger for this class.
     */
    private static final Logger logger = LoggerFactory.getLogger(ConnectionService.class);

    /**
     * Mapper for accessing connections.
     */
    @Inject
    private ConnectionMapper connectionMapper;

    /**
     * Mapper for manipulating connection permissions.
     */
    @Inject
    private ConnectionPermissionMapper connectionPermissionMapper;
    
    /**
     * Mapper for accessing connection parameters.
     */
    @Inject
    private ParameterMapper parameterMapper;

    /**
     * Mapper for accessing connection history.
     */
    @Inject
    private ConnectionRecordMapper connectionRecordMapper;

    /**
     * Provider for creating connections.
     */
    @Inject
    private Provider<ModeledConnection> connectionProvider;

    /**
     * Service for creating and tracking tunnels.
     */
    @Inject
    private GuacamoleTunnelService tunnelService;

    @Override
    protected ModeledDirectoryObjectMapper<ConnectionModel> getObjectMapper() {
        return connectionMapper;
    }

    @Override
    protected ObjectPermissionMapper getPermissionMapper() {
        return connectionPermissionMapper;
    }

    @Override
    protected ModeledConnection getObjectInstance(AuthenticatedUser currentUser,
            ConnectionModel model) {
        ModeledConnection connection = connectionProvider.get();
        connection.init(currentUser, model);
        return connection;
    }

    @Override
    protected ConnectionModel getModelInstance(AuthenticatedUser currentUser,
            final Connection object) {

        // Create new ModeledConnection backed by blank model
        ConnectionModel model = new ConnectionModel();
        ModeledConnection connection = getObjectInstance(currentUser, model);

        // Set model contents through ModeledConnection, copying the provided connection
        connection.setParentIdentifier(object.getParentIdentifier());
        connection.setName(object.getName());
        connection.setConfiguration(object.getConfiguration());
        connection.setAttributes(object.getAttributes());

        return model;
        
    }

    @Override
    protected boolean hasCreatePermission(AuthenticatedUser user)
            throws GuacamoleException {

        // Return whether user has explicit connection creation permission
        SystemPermissionSet permissionSet = user.getUser().getSystemPermissions();
        return permissionSet.hasPermission(SystemPermission.Type.CREATE_CONNECTION);

    }

    @Override
    protected ObjectPermissionSet getPermissionSet(AuthenticatedUser user)
            throws GuacamoleException {

        // Return permissions related to connections 
        return user.getUser().getConnectionPermissions();

    }

    @Override
    protected void beforeCreate(AuthenticatedUser user,
            ConnectionModel model) throws GuacamoleException {

        super.beforeCreate(user, model);
        
        // Name must not be blank
        if (model.getName() == null || model.getName().trim().isEmpty())
            throw new GuacamoleClientException("Connection names must not be blank.");

        // Do not attempt to create duplicate connections
        ConnectionModel existing = connectionMapper.selectOneByName(model.getParentIdentifier(), model.getName());
        if (existing != null)
            throw new GuacamoleClientException("The connection \"" + model.getName() + "\" already exists.");

    }

    @Override
    protected void beforeUpdate(AuthenticatedUser user,
            ConnectionModel model) throws GuacamoleException {

        super.beforeUpdate(user, model);
        
        // Name must not be blank
        if (model.getName() == null || model.getName().trim().isEmpty())
            throw new GuacamoleClientException("Connection names must not be blank.");
        
        // Check whether such a connection is already present
        ConnectionModel existing = connectionMapper.selectOneByName(model.getParentIdentifier(), model.getName());
        if (existing != null) {

            // If the specified name matches a DIFFERENT existing connection, the update cannot continue
            if (!existing.getObjectID().equals(model.getObjectID()))
                throw new GuacamoleClientException("The connection \"" + model.getName() + "\" already exists.");

        }

    }

    /**
     * Given an arbitrary Guacamole connection, produces a collection of
     * parameter model objects containing the name/value pairs of that
     * connection's parameters.
     *
     * @param connection
     *     The connection whose configuration should be used to produce the
     *     collection of parameter models.
     *
     * @return
     *     A collection of parameter models containing the name/value pairs
     *     of the given connection's parameters.
     */
    private Collection<ParameterModel> getParameterModels(ModeledConnection connection) {

        Map<String, String> parameters = connection.getConfiguration().getParameters();
        
        // Convert parameters to model objects
        Collection<ParameterModel> parameterModels = new ArrayList<ParameterModel>(parameters.size());
        for (Map.Entry<String, String> parameterEntry : parameters.entrySet()) {

            // Get parameter name and value
            String name = parameterEntry.getKey();
            String value = parameterEntry.getValue();

            // There is no need to insert empty parameters
            if (value == null || value.isEmpty())
                continue;
            
            // Produce model object from parameter
            ParameterModel model = new ParameterModel();
            model.setConnectionIdentifier(connection.getIdentifier());
            model.setName(name);
            model.setValue(value);

            // Add model to list
            parameterModels.add(model);
            
        }

        return parameterModels;

    }

    @Override
    public ModeledConnection createObject(AuthenticatedUser user, Connection object)
            throws GuacamoleException {

        // Create connection
        ModeledConnection connection = super.createObject(user, object);
        connection.setConfiguration(object.getConfiguration());

        // Insert new parameters, if any
        Collection<ParameterModel> parameterModels = getParameterModels(connection);
        if (!parameterModels.isEmpty())
            parameterMapper.insert(parameterModels);

        return connection;

    }
    
    @Override
    public void updateObject(AuthenticatedUser user, ModeledConnection object)
            throws GuacamoleException {

        // Update connection
        super.updateObject(user, object);

        // Replace existing parameters with new parameters, if any
        Collection<ParameterModel> parameterModels = getParameterModels(object);
        parameterMapper.delete(object.getIdentifier());
        if (!parameterModels.isEmpty())
            parameterMapper.insert(parameterModels);
        
    }

    /**
     * Returns the set of all identifiers for all connections within the
     * connection group having the given identifier. Only connections that the
     * user has read access to will be returned.
     * 
     * Permission to read the connection group having the given identifier is
     * NOT checked.
     *
     * @param user
     *     The user retrieving the identifiers.
     * 
     * @param identifier
     *     The identifier of the parent connection group, or null to check the
     *     root connection group.
     *
     * @return
     *     The set of all identifiers for all connections in the connection
     *     group having the given identifier that the user has read access to.
     *
     * @throws GuacamoleException
     *     If an error occurs while reading identifiers.
     */
    public Set<String> getIdentifiersWithin(AuthenticatedUser user,
            String identifier)
            throws GuacamoleException {

        // Bypass permission checks if the user is a system admin
        if (user.getUser().isAdministrator())
            return connectionMapper.selectIdentifiersWithin(identifier);

        // Otherwise only return explicitly readable identifiers
        else
            return connectionMapper.selectReadableIdentifiersWithin(user.getUser().getModel(), identifier);

    }

    /**
     * Retrieves all parameters visible to the given user and associated with
     * the connection having the given identifier. If the given user has no
     * access to such parameters, or no such connection exists, the returned
     * map will be empty.
     *
     * @param user
     *     The user retrieving connection parameters.
     *
     * @param identifier
     *     The identifier of the connection whose parameters are being
     *     retrieved.
     *
     * @return
     *     A new map of all parameter name/value pairs that the given user has
     *     access to.
     */
    public Map<String, String> retrieveParameters(AuthenticatedUser user,
            String identifier) {

        Map<String, String> parameterMap = new HashMap<String, String>();

        // Determine whether we have permission to read parameters
        boolean canRetrieveParameters;
        try {
            canRetrieveParameters = hasObjectPermission(user, identifier,
                    ObjectPermission.Type.UPDATE);
        }

        // Provide empty (but mutable) map if unable to check permissions
        catch (GuacamoleException e) {
            return parameterMap;
        }

        // Populate parameter map if we have permission to do so
        if (canRetrieveParameters) {
            for (ParameterModel parameter : parameterMapper.select(identifier))
                parameterMap.put(parameter.getName(), parameter.getValue());
        }

        return parameterMap;

    }

    /**
     * Returns a connection records object which is backed by the given model.
     *
     * @param model
     *     The model object to use to back the returned connection record
     *     object.
     *
     * @return
     *     A connection record object which is backed by the given model.
     */
    protected ConnectionRecord getObjectInstance(ConnectionRecordModel model) {
        return new ModeledConnectionRecord(model);
    }

    /**
     * Returns a list of connection records objects which are backed by the
     * models in the given list.
     *
     * @param models
     *     The model objects to use to back the connection record objects
     *     within the returned list.
     *
     * @return
     *     A list of connection record objects which are backed by the models
     *     in the given list.
     */
    protected List<ConnectionRecord> getObjectInstances(List<ConnectionRecordModel> models) {

        // Create new list of records by manually converting each model
        List<ConnectionRecord> objects = new ArrayList<ConnectionRecord>(models.size());
        for (ConnectionRecordModel model : models)
            objects.add(getObjectInstance(model));

        return objects;
 
    }

    /**
     * Retrieves the connection history of the given connection, including any
     * active connections.
     *
     * @param user
     *     The user retrieving the connection history.
     *
     * @param connection
     *     The connection whose history is being retrieved.
     *
     * @return
     *     The connection history of the given connection, including any
     *     active connections.
     *
     * @throws GuacamoleException
     *     If permission to read the connection history is denied.
     */
    public List<ConnectionRecord> retrieveHistory(AuthenticatedUser user,
            ModeledConnection connection) throws GuacamoleException {

        String identifier = connection.getIdentifier();

        // Retrieve history only if READ permission is granted
        if (hasObjectPermission(user, identifier, ObjectPermission.Type.READ)) {

            // Retrieve history
            List<ConnectionRecordModel> models = connectionRecordMapper.select(identifier);

            // Get currently-active connections
            List<ConnectionRecord> records = new ArrayList<ConnectionRecord>(tunnelService.getActiveConnections(connection));
            Collections.reverse(records);

            // Add past connections from model objects
            for (ConnectionRecordModel model : models)
                records.add(getObjectInstance(model));

            // Return converted history list
            return records;

        }

        // The user does not have permission to read the history
        throw new GuacamoleSecurityException("Permission denied.");

    }

    /**
     * Retrieves the connection history records matching the given criteria.
     * Retrieves up to <code>limit</code> connection history records matching
     * the given terms and sorted by the given predicates. Only history records
     * associated with data that the given user can read are returned.
     *
     * @param user
     *     The user retrieving the connection history.
     *
     * @param requiredContents
     *     The search terms that must be contained somewhere within each of the
     *     returned records.
     *
     * @param sortPredicates
     *     A list of predicates to sort the returned records by, in order of
     *     priority.
     *
     * @param limit
     *     The maximum number of records that should be returned.
     *
     * @return
     *     The connection history of the given connection, including any
     *     active connections.
     *
     * @throws GuacamoleException
     *     If permission to read the connection history is denied.
     */
    public List<ConnectionRecord> retrieveHistory(AuthenticatedUser user,
            Collection<ConnectionRecordSearchTerm> requiredContents,
            List<ConnectionRecordSortPredicate> sortPredicates, int limit)
            throws GuacamoleException {

        List<ConnectionRecordModel> searchResults;

        // Bypass permission checks if the user is a system admin
        if (user.getUser().isAdministrator())
            searchResults = connectionRecordMapper.search(requiredContents,
                    sortPredicates, limit);

        // Otherwise only return explicitly readable history records
        else
            searchResults = connectionRecordMapper.searchReadable(user.getUser().getModel(),
                    requiredContents, sortPredicates, limit);

        return getObjectInstances(searchResults);

    }

    /**
     * Connects to the given connection as the given user, using the given
     * client information. If the user does not have permission to read the
     * connection, permission will be denied.
     *
     * @param user
     *     The user connecting to the connection.
     *
     * @param connection
     *     The connection being connected to.
     *
     * @param info
     *     Information associated with the connecting client.
     *
     * @return
     *     A connected GuacamoleTunnel associated with a newly-established
     *     connection.
     *
     * @throws GuacamoleException
     *     If permission to connect to this connection is denied.
     */
    public GuacamoleTunnel connect(AuthenticatedUser user,
            ModeledConnection connection, GuacamoleClientInformation info)
            throws GuacamoleException {

        // Connect only if READ permission is granted
        if (hasObjectPermission(user, connection.getIdentifier(), ObjectPermission.Type.READ))
            return tunnelService.getGuacamoleTunnel(user, connection, info);

        // The user does not have permission to connect
        throw new GuacamoleSecurityException("Permission denied.");

    }

    /**
     * Sends a magic packet (WOL) to the connection as the given user.
     * If the user does not have permission to read the
     * connection, permission will be denied.
     *
     * @param user
     *     The user connecting to the connection.
     *
     * @param connection
     *     The connection being connected to.
     *
     * @throws GuacamoleException
     *     If permission to connect to this connection is denied or if packet could not be sent.
     */
    public void wakeOnLan(AuthenticatedUser user, ModeledConnection connection)
            throws GuacamoleException {

        String broadcastIPStr = null;
        String macStr = null;
        DatagramSocket socket = null;

        // Wake On LAN only if READ permission is granted
        if (!hasObjectPermission(user, connection.getIdentifier(), ObjectPermission.Type.READ)) {
            // The user does not have permission to send a WOL package
            throw new GuacamoleSecurityException("Permission denied.");
        }

        for (ParameterModel parameter : parameterMapper.select(connection.getIdentifier())) {
            if(parameter.getName().equals("mac")) {
                macStr = parameter.getValue();
            }
            else if(parameter.getName().equals("broadcast")) {
                broadcastIPStr = parameter.getValue();
            }
        }

        if(broadcastIPStr==null || macStr==null ) throw new GuacamoleException("MAC and Broadcast address have to be provided.");

        try {

            byte[] macBytes = getMacBytes(macStr);
            byte[] bytes = new byte[6 + 16 * macBytes.length];

            for (int i = 0; i < 6; i++) {
                bytes[i] = (byte) 0xff;
            }

            for (int i = 6; i < bytes.length; i += macBytes.length) {
                System.arraycopy(macBytes, 0, bytes, i, macBytes.length);
            }

            InetAddress address = InetAddress.getByName(broadcastIPStr);
            DatagramPacket packet = new DatagramPacket(bytes, bytes.length, address, 9);
            socket = new DatagramSocket();
            logger.debug("Sending WOL packet to " + macStr + " via " + broadcastIPStr);
            socket.send(packet);
        }
        catch (Exception e) {
            throw new GuacamoleException("Failed to send Wake-on-LAN packet: " + e.getMessage());
        }
        finally {
            if(socket != null) socket.close();
        }
    }
    
    private static byte[] getMacBytes(String macStr) throws IllegalArgumentException {
        byte[] bytes = new byte[6];
        String[] hex = macStr.split("(\\:|\\-)");
        if (hex.length != 6) {
            throw new IllegalArgumentException("Invalid MAC address.");
        }

        try {
            for (int i = 0; i < 6; i++) {
                bytes[i] = (byte) Integer.parseInt(hex[i], 16);
            }
        }
        catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid hex digit in MAC address.");
        }
        return bytes;
    }
}
