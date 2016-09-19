CREATE TABLE `guacamole_user` (

  `user_id`       int(11)      NOT NULL AUTO_INCREMENT,

  -- Username 
  `username`      varchar(128) NOT NULL,
  `secret_key` varchar(128)   NOT NULL,
  `gauth_enabled` boolean NOT NULL DEFAULT 0,

  PRIMARY KEY (`user_id`),
  UNIQUE KEY `username` (`username`)

) ENGINE=InnoDB DEFAULT CHARSET=utf8;