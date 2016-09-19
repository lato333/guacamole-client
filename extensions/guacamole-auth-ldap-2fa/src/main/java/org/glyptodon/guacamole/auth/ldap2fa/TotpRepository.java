package org.glyptodon.guacamole.auth.ldap2fa;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.glyptodon.guacamole.GuacamoleException;
import org.glyptodon.guacamole.environment.LocalEnvironment;
import org.glyptodon.guacamole.net.auth.credentials.CredentialsInfo;
import org.glyptodon.guacamole.net.auth.credentials.GuacamoleInvalidCredentialsException;
import org.glyptodon.guacamole.properties.IntegerGuacamoleProperty;
import org.glyptodon.guacamole.properties.StringGuacamoleProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TotpRepository extends LocalEnvironment  {

	public TotpRepository() throws GuacamoleException {
		super();
	}

	private final Logger logger = LoggerFactory.getLogger(TotpRepository.class);

	//  Database credentials
	static final StringGuacamoleProperty USERNAME =  new StringGuacamoleProperty() {
		@Override
		public String getName() { return "mysql-username"; }
	};

	static final StringGuacamoleProperty PASSWORD =  new StringGuacamoleProperty() {
		@Override
		public String getName() { return "mysql-password"; }
	};

	static final StringGuacamoleProperty DATABASE =  new StringGuacamoleProperty() {
		@Override
		public String getName() { return "mysql-database"; }
	};

	static final StringGuacamoleProperty HOSTNAME =  new StringGuacamoleProperty() {
		@Override
		public String getName() { return "mysql-hostname"; }
	};

	static final IntegerGuacamoleProperty PORT =  new IntegerGuacamoleProperty() {
		@Override
		public String getName() { return "mysql-port"; }
	};

	final String URL = "jdbc:mysql://" + getRequiredProperty(HOSTNAME) +":" + getRequiredProperty(PORT) + "/" + getRequiredProperty(DATABASE);
	final String MYSQL_USER = getRequiredProperty(USERNAME);
	final String MYSQL_PASSWORD = getRequiredProperty(PASSWORD);
	final String SQLQuery = "SELECT secret_key, gauth_enabled FROM guacamole_user WHERE USERNAME = ?";
	

	public String getSecretKey(String userName) throws GuacamoleInvalidCredentialsException {
		String secret = null;
		try {
			Class.forName("com.mysql.jdbc.Driver");
		} catch (ClassNotFoundException e1) {
			 throw new GuacamoleInvalidCredentialsException("Cannot load mysql-driver", CredentialsInfo.USERNAME_PASSWORD); 
		}

		try
		{

			logger.debug("Connecting to " + URL);
			
			Connection cn = DriverManager.getConnection( URL, MYSQL_USER, MYSQL_PASSWORD);
			PreparedStatement ps = cn.prepareStatement(SQLQuery);
			ps.setString(1, userName);
			ResultSet rs = ps.executeQuery();

			if(rs.getFetchSize()>1) {
				logger.error("Multiple secret keys for user " + userName);
			}
			else {
				if(rs.next()) {
					secret = rs.getString("secret_key");
				}
			}
		}
		catch(SQLException e) {
			logger.error("Cannot query the secret key for user " + userName +": " + e.getMessage());
		}

		return secret;
	}
	
	public boolean isGAuthEnabled(String userName) throws GuacamoleInvalidCredentialsException {
		boolean enabled=true;
		
		try {
			Class.forName("com.mysql.jdbc.Driver");
		} catch (ClassNotFoundException e1) {
			 throw new GuacamoleInvalidCredentialsException("Cannot load mysql-driver", CredentialsInfo.USERNAME_PASSWORD); 
		}

		try
		{

			logger.debug("Connecting to " + URL);
			
			Connection cn = DriverManager.getConnection( URL, MYSQL_USER, MYSQL_PASSWORD);
			PreparedStatement ps = cn.prepareStatement(SQLQuery);
			ps.setString(1, userName);
			ResultSet rs = ps.executeQuery();

			if(rs.getFetchSize()>1) {
				logger.error("Multiple secret keys for user " + userName);
			}
			else {
				if(rs.next()) {
					enabled = rs.getBoolean("gauth_enabled");
				}
			}
		}
		catch(SQLException e) {
			logger.error("Cannot query user " + userName +": " + e.getMessage());
		}

		return enabled;
	}

}
