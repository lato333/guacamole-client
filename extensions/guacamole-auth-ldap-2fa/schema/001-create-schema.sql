CREATE TABLE `guacamole_2fa_user` (

  `user_id`       int(11)      NOT NULL AUTO_INCREMENT,

  -- Username 
  `username`      varchar(128) NOT NULL,
  `secret_key` varchar(128)   NOT NULL,
  `verification_code` int(11),


  PRIMARY KEY (`user_id`),
  UNIQUE KEY `username` (`username`)

) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `guacamole_2fa_scratchcodes` (

  `user_id`       int(11)      NOT NULL AUTO_INCREMENT,
  `scratch_code`      int(11) NOT NULL,
  
  PRIMARY KEY (`user_id`,`scratch_code`),
  CONSTRAINT `guacamole_2fa_user_fk`
    FOREIGN KEY (`user_id`)
    REFERENCES `guacamole_2fa_user` (`user_id`) ON DELETE CASCADE
    
) ENGINE=InnoDB DEFAULT CHARSET=utf8;