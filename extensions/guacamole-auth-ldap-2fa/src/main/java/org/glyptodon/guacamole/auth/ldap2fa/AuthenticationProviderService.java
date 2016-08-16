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

package org.glyptodon.guacamole.auth.ldap2fa;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.novell.ldap.LDAPConnection;
import com.warrenstrange.googleauth.GoogleAuthenticator;
import com.warrenstrange.googleauth.GoogleAuthenticatorException;

import java.util.List;
import org.glyptodon.guacamole.auth.ldap2fa.user.AuthenticatedUser;
import org.glyptodon.guacamole.auth.ldap2fa.user.UserContext;
import org.glyptodon.guacamole.GuacamoleException;
import org.glyptodon.guacamole.auth.ldap2fa.user.UserService;
import org.glyptodon.guacamole.net.auth.Credentials;
import org.glyptodon.guacamole.net.auth.credentials.CredentialsInfo;
import org.glyptodon.guacamole.net.auth.credentials.GuacamoleInvalidCredentialsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Service providing convenience functions for the LDAP AuthenticationProvider
 * implementation.
 *
 * @author Michael Jumper
 */
public class AuthenticationProviderService {

    /**
     * Logger for this class.
     */
    private final Logger logger = LoggerFactory.getLogger(AuthenticationProviderService.class);

    /**
     * Service for creating and managing connections to LDAP servers.
     */
    @Inject
    private LDAPConnectionService ldapService;

    /**
     * Service for retrieving LDAP server configuration information.
     */
    @Inject
    private ConfigurationService confService;

    /**
     * Service for retrieving users and their corresponding LDAP DNs.
     */
    @Inject
    private UserService userService;

    /**
     * Provider for AuthenticatedUser objects.
     */
    @Inject
    private Provider<AuthenticatedUser> authenticatedUserProvider;

    /**
     * Provider for UserContext objects.
     */
    @Inject
    private Provider<UserContext> userContextProvider;

    /**
     * Determines the DN which corresponds to the user having the given
     * username. The DN will either be derived directly from the user base DN,
     * or queried from the LDAP server, depending on how LDAP authentication
     * has been configured.
     *
     * @param username
     *     The username of the user whose corresponding DN should be returned.
     *
     * @return
     *     The DN which corresponds to the user having the given username.
     *
     * @throws GuacamoleException
     *     If required properties are missing, and thus the user DN cannot be
     *     determined.
     */
    private String getUserBindDN(String username)
            throws GuacamoleException {

        // If a search DN is provided, search the LDAP directory for the DN
        // corresponding to the given username
        String searchBindDN = confService.getSearchBindDN();
        if (searchBindDN != null) {

            // Create an LDAP connection using the search account
            LDAPConnection searchConnection = ldapService.bindAs(
                searchBindDN,
                confService.getSearchBindPassword()
            );

            // Warn of failure to find
            if (searchConnection == null) {
                logger.error("Unable to bind using search DN \"{}\"", searchBindDN);
                return null;
            }

            try {

                // Retrieve all DNs associated with the given username
                List<String> userDNs = userService.getUserDNs(searchConnection, username);
                if (userDNs.isEmpty())
                    return null;

                // Warn if multiple DNs exist for the same user
                if (userDNs.size() != 1) {
                    logger.warn("Multiple DNs possible for user \"{}\": {}", username, userDNs);
                    return null;
                }

                // Return the single possible DN
                return userDNs.get(0);

            }

            // Always disconnect
            finally {
                ldapService.disconnect(searchConnection);
            }

        }

        // Otherwise, derive user DN from base DN
        return userService.deriveUserDN(username);

    }

    /**
     * Binds to the LDAP server using the provided Guacamole credentials. The
     * DN of the user is derived using the LDAP configuration properties
     * provided in guacamole.properties, as is the server hostname and port
     * information.
     *
     * @param credentials
     *     The credentials to use to bind to the LDAP server.
     *
     * @return
     *     A bound LDAP connection, or null if the connection could not be
     *     bound.
     *
     * @throws GuacamoleException
     *     If an error occurs while binding to the LDAP server.
     */
    private LDAPConnection bindAs(Credentials credentials)
        throws GuacamoleException {

        // Get username and password from credentials
        String username = credentials.getUsername();
        String password = credentials.getPassword();

        // Require username
        if (username == null || username.isEmpty()) {
            logger.debug("Anonymous bind is not currently allowed by the LDAP authentication provider.");
            return null;
        }

        // Require password, and do not allow anonymous binding
        if (password == null || password.isEmpty()) {
            logger.debug("Anonymous bind is not currently allowed by the LDAP authentication provider.");
            return null;
        }

        // Determine user DN
        String userDN = getUserBindDN(username);
        if (userDN == null) {
            logger.debug("Unable to determine DN for user \"{}\".", username);
            return null;
        }

        // Bind using user's DN
        return ldapService.bindAs(userDN, password);

    }

    /**
     * Returns an AuthenticatedUser representing the user authenticated by the
     * given credentials.
     *
     * @param credentials
     *     The credentials to use for authentication.
     *
     * @return
     *     An AuthenticatedUser representing the user authenticated by the
     *     given credentials.
     *
     * @throws GuacamoleException
     *     If an error occurs while authenticating the user, or if access is
     *     denied.
     */
    public AuthenticatedUser authenticateUser(Credentials credentials)
            throws GuacamoleException {

        // Attempt bind
        LDAPConnection ldapConnection;
        try {
            ldapConnection = bindAs(credentials);
        }
        catch (GuacamoleException e) {
            logger.error("Cannot bind with LDAP server: {}", e.getMessage());
            logger.debug("Error binding with LDAP server.", e);
            ldapConnection = null;
        }
  
        // If bind fails, permission to login is denied
        if (ldapConnection == null)
            throw new GuacamoleInvalidCredentialsException("Permission denied.", CredentialsInfo.USERNAME_PASSWORD);
     
        // 2FA  
        GoogleAuthenticator gAuth = new GoogleAuthenticator();
        TotpRepository tp = new TotpRepository();
        String storedSecret = tp.getSecretKey(credentials.getUsername());
        String receivedSecret = credentials.getSecret();
        Integer receivedSecretI;
        
        if(storedSecret==null || receivedSecret==null) 
        	throw new GuacamoleInvalidCredentialsException("At least one secret is not available. 2 factor authentication not possible.", CredentialsInfo.USERNAME_PASSWORD);
        
        
        try {
        	receivedSecretI = Integer.parseInt(receivedSecret);
        } catch (NumberFormatException e) {
        	throw new GuacamoleInvalidCredentialsException("Token is not a number.", CredentialsInfo.USERNAME_PASSWORD);
        }

        boolean isCodeValid = false;
        
        try {
        	isCodeValid =  gAuth.authorize(storedSecret, receivedSecretI);
        }
        catch(GoogleAuthenticatorException e) {
        	isCodeValid = false;
        }
      
        if (!isCodeValid) 
        	throw new GuacamoleInvalidCredentialsException("At least one secret is not available. 2 factor authentication not possible.", CredentialsInfo.USERNAME_PASSWORD);
        
        try {

            // Return AuthenticatedUser if bind succeeds
            AuthenticatedUser authenticatedUser = authenticatedUserProvider.get();
            authenticatedUser.init(credentials);
            return authenticatedUser;

        }

        // Always disconnect
        finally {
            ldapService.disconnect(ldapConnection);
        }

    }

    /**
     * Returns a UserContext object initialized with data accessible to the
     * given AuthenticatedUser.
     *
     * @param authenticatedUser
     *     The AuthenticatedUser to retrieve data for.
     *
     * @return
     *     A UserContext object initialized with data accessible to the given
     *     AuthenticatedUser.
     *
     * @throws GuacamoleException
     *     If the UserContext cannot be created due to an error.
     */
    public UserContext getUserContext(org.glyptodon.guacamole.net.auth.AuthenticatedUser authenticatedUser)
            throws GuacamoleException {

        // Bind using credentials associated with AuthenticatedUser
        Credentials credentials = authenticatedUser.getCredentials();
        LDAPConnection ldapConnection = bindAs(credentials);
        if (ldapConnection == null)
            return null;

        try {

            // Build user context by querying LDAP
            UserContext userContext = userContextProvider.get();
            userContext.init(authenticatedUser, ldapConnection);
            return userContext;

        }

        // Always disconnect
        finally {
            ldapService.disconnect(ldapConnection);
        }

    }

}
