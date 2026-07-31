package com.github.galpiii.galpi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@EnableJpaAuditing
@SpringBootApplication
public class GalpiApplication {

	public static void main(String[] args) {
		SpringApplication.run(GalpiApplication.class, args);
	}

}
